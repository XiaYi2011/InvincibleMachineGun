package com.codigohasta.addon.modules;

import com.codigohasta.addon.AddonTemplate;
import com.codigohasta.addon.mixin.InventoryAccessor;
import com.codigohasta.addon.utils.leaveshack.InventoryUtil;
import com.google.common.collect.ImmutableList;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class TpAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("攻击机制");
    private final SettingGroup sgTP = settings.createGroup("传送");
    private final SettingGroup sgTargeting = settings.createGroup("目标");
    private final SettingGroup sgWhitelist = settings.createGroup("白名单");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // 攻击延迟（毫秒）
    private final Setting<Integer> attackDelayMs = sgTiming.add(new IntSetting.Builder()
        .name("额外延迟(ms)")
        .description("每次攻击后的冷却时间，单位毫秒")
        .defaultValue(0)
        .min(0)
        .sliderMax(5000)
        .build());

    // 武器设置
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切武器").defaultValue(true).build());
    private final Setting<Boolean> requireMace = sgGeneral.add(new BoolSetting.Builder()
        .name("仅手持重锤").defaultValue(false).build());
    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("挥手").defaultValue(true).build());
    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("静默切换").description("使用数据包切换武器（无动画、无声音）")
        .defaultValue(true).visible(autoSwitch::get).build());

    // 传送设置
    public enum Mode { Vanilla, Paper }
    private final Setting<Mode> mode = sgTP.add(new EnumSetting.Builder<Mode>()
        .name("兼容模式").defaultValue(Mode.Paper).build());
    private final Setting<Double> maxRange = sgTP.add(new DoubleSetting.Builder()
        .name("最大攻击范围")
        .description("能攻击到的最远距离")
        .defaultValue(49.0)
        .min(1)
        .sliderMax(99)
        .build());
    private final Setting<Boolean> goUp = sgTP.add(new BoolSetting.Builder()
        .name("V-Clip")
        .description("启用 V-Clip 上升再下降的攻击路径")
        .defaultValue(true)
        .build());
    private final Setting<Double> vClipHeight = sgTP.add(new DoubleSetting.Builder()
        .name("V-Clip 高度")
        .description("上升的高度")
        .defaultValue(22.0)
        .min(1)
        .sliderMax(100)
        .visible(goUp::get)
        .build());
    private final Setting<Boolean> returnPos = sgTP.add(new BoolSetting.Builder()
        .name("攻击后回传").defaultValue(true).build());
    private final Setting<Boolean> offsetFix = sgTP.add(new BoolSetting.Builder()
        .name("偏移同步").description("发送微小偏移包防止拉回").defaultValue(true).build());
    private final Setting<Boolean> antiLag = sgTP.add(new BoolSetting.Builder()
        .name("反拉回")
        .description("被服务器回弹时自动传送回目标位置")
        .defaultValue(true)
        .build());
    private final Setting<Double> maxSingleTpDist = sgTP.add(new DoubleSetting.Builder()
        .name("最大单次传送距离")
        .description("单次传送超过此距离将拆分为多段，0 为不限制")
        .defaultValue(0.0)
        .min(0)
        .sliderMax(100)
        .build());

    // 目标设置
    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("目标实体").defaultValue(Collections.singleton(EntityType.PLAYER)).build());
    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略好友").defaultValue(false).build());
    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略命名").defaultValue(true).build());
    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略驯服").defaultValue(false).build());
    private final Setting<Boolean> enableYFilter = sgTargeting.add(new BoolSetting.Builder()
        .name("启用Y轴过滤").defaultValue(false).build());
    private final Setting<Double> minY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最小Y").defaultValue(-64).min(-2032).max(2032).visible(enableYFilter::get).build());
    private final Setting<Double> maxY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最大Y").defaultValue(320).min(-2032).max(2032).visible(enableYFilter::get).build());

    public enum ListMode { Whitelist, Blacklist, Off }
    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("名单模式").defaultValue(ListMode.Off).build());
    private final Setting<String> playerList = sgWhitelist.add(new StringSetting.Builder()
        .name("玩家列表").defaultValue("").build());

    // 渲染
    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("显示路径").defaultValue(true).build());
    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("轨迹颜色").defaultValue(new SettingColor(255, 0, 0, 100)).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("目标颜色").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<Boolean> renderIntermediateNodes = sgRender.add(new BoolSetting.Builder()
        .name("渲染中间点")
        .description("当单次传送距离受限时，是否渲染分段传送的中间点")
        .defaultValue(true)
        .visible(() -> maxSingleTpDist.get() > 0)
        .build());

    // 内部变量
    private final List<Entity> targets = new ArrayList<>();
    private final List<Vec3d> renderPathNodes = new ArrayList<>();
    private Entity currentTarget;
    private int originalSlot = -1;
    private int silentSwapSlot = -1;
    private int silentSwapPrevSlot = -1;
    private long nextAttackTime = 0;
    private Vec3d expectedPos = null;

    // 反拉回重试控制
    private int antiLagRetries = 0;
    private long lastAntiLagTime = 0;

    public TpAura() {
        super(AddonTemplate.CATEGORY, "如来神掌", "从天而降的掌法。路径碰撞检测，单次传送距离限制");
    }

    @Override
    public void onActivate() {
        originalSlot = -1;
        silentSwapSlot = -1;
        silentSwapPrevSlot = -1;
        nextAttackTime = System.currentTimeMillis();
        renderPathNodes.clear();
        expectedPos = null;
        antiLagRetries = 0;
    }

    @Override
    public void onDeactivate() {
        if (silentSwapSlot != -1 && mc.player != null) swapBackWeapon();
        if (originalSlot != -1 && autoSwitch.get() && !silentSwap.get() && mc.player != null) {
            ((InventoryAccessor) mc.player.getInventory()).setSelectedSlot(originalSlot);
            originalSlot = -1;
        }
        expectedPos = null;
    }

    private int findWeaponInventorySlot() {
        for (int i = 0; i < 45; i++) {
            String name = mc.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if (name.contains("sword") || name.contains("mace") || name.contains("axe")) {
                return i < 9 ? i + 36 : i;
            }
        }
        return -1;
    }

    private boolean checkAndSwapWeapon() {
        String itemMain = mc.player.getMainHandStack().getItem().toString().toLowerCase();
        boolean isWeapon = itemMain.contains("sword") || itemMain.contains("mace") || itemMain.contains("axe");
        if (isWeapon && !(requireMace.get() && !itemMain.contains("mace"))) return true;

        if (silentSwap.get()) {
            int slot = findWeaponInventorySlot();
            if (slot != -1) {
                silentSwapSlot = slot;
                silentSwapPrevSlot = ((InventoryAccessor) mc.player.getInventory()).getSelectedSlot();
                if (slot >= 36) {
                    InventoryUtil.switchToSlot(slot - 36);
                } else {
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.SWAP, mc.player);
                    InventoryUtil.switchToSlot(0);
                }
                return true;
            }
        } else {
            FindItemResult weapon = InvUtils.find(s -> {
                String name = s.getItem().toString().toLowerCase();
                return name.contains("sword") || name.contains("mace") || name.contains("axe");
            }, 0, 8);
            if (weapon.found()) {
                if (originalSlot == -1) originalSlot = ((InventoryAccessor) mc.player.getInventory()).getSelectedSlot();
                InvUtils.swap(weapon.slot(), false);
                return true;
            }
        }
        return false;
    }

    private void swapBackWeapon() {
        if (silentSwapSlot == -1) return;
        if (silentSwapSlot >= 36) {
            InventoryUtil.switchToSlot(silentSwapPrevSlot);
        } else {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, silentSwapSlot, 0, SlotActionType.SWAP, mc.player);
            InventoryUtil.switchToSlot(silentSwapPrevSlot);
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
        silentSwapSlot = -1;
        silentSwapPrevSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (System.currentTimeMillis() < nextAttackTime) {
            swapBackWeapon();
            return;
        }

        if (autoSwitch.get() && !checkAndSwapWeapon()) return;

        targets.clear();
        TargetUtils.getList(targets, this::entityCheck, SortPriority.LowestDistance, 1);
        if (targets.isEmpty()) {
            currentTarget = null;
            swapBackWeapon();
            expectedPos = null;
            return;
        }
        currentTarget = targets.get(0);

        executeTrouserAttack(currentTarget);
        swapBackWeapon();

        nextAttackTime = System.currentTimeMillis() + attackDelayMs.get();
    }

    // ========== 新的核心攻击逻辑 ==========
    private void executeTrouserAttack(Entity target) {
        Vec3d startPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());

        // 在目标半径6格球体内寻找最靠近玩家的合法站立点
        Vec3d finalPos = findClosestValidPosInSphere(targetPos, startPos, 6.0);
        if (finalPos == null) return;

        if (mode.get() == Mode.Paper) {
            paperAttack(target, startPos, finalPos);
        } else {
            vanillaAttack(target, startPos, finalPos);
        }
    }

    private void paperAttack(Entity target, Vec3d startPos, Vec3d finalPos) {
        List<Vec3d> path = new ArrayList<>();
        path.add(startPos);

        if (goUp.get()) {
            double vh = vClipHeight.get();
            Vec3d highStart = startPos.add(0, vh, 0);
            Vec3d highTarget = finalPos.add(0, vh, 0);
            path.add(highStart);
            path.add(highTarget);
            path.add(finalPos);
        } else {
            path.add(finalPos);
        }

        if (returnPos.get()) {
            if (goUp.get()) {
                path.add(finalPos.add(0, vClipHeight.get(), 0));
                path.add(startPos.add(0, vClipHeight.get(), 0));
            }
            path.add(startPos);
            if (offsetFix.get()) path.add(getOffset(startPos));
        } else {
            if (offsetFix.get()) path.add(getOffset(finalPos));
        }

        // 预检：务必使用当前实际位置 startPos 作为起点
        Vec3d serverPos = startPos;
        for (int i = 1; i < path.size(); i++) {
            if (isWrongMove(serverPos, path.get(i))) {
                return;
            }
            serverPos = path.get(i);
        }

        // 执行传送
        serverPos = startPos;
        expectedPos = serverPos;
        renderPathNodes.clear();
        renderPathNodes.add(serverPos);

        for (int i = 1; i < path.size(); i++) {
            Vec3d next = path.get(i);
            doPaperTP(expectedPos, next);
            expectedPos = next;
            renderPathNodes.add(next);
        }

        if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

        if (!returnPos.get()) {
            mc.player.setPosition(expectedPos.x, expectedPos.y, expectedPos.z);
        }
    }

    private void vanillaAttack(Entity target, Vec3d startPos, Vec3d finalPos) {
        List<Vec3d> path = new ArrayList<>();
        if (goUp.get()) {
            double vh = vClipHeight.get();
            path.add(startPos.add(0, vh, 0));
            path.add(finalPos.add(0, vh, 0));
        }
        path.add(finalPos);

        renderPathNodes.clear();
        renderPathNodes.add(startPos);
        renderPathNodes.addAll(path);

        int spam = 4;
        for (int i = 0; i < spam; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, mc.player.horizontalCollision));
        }

        for (Vec3d p : path) {
            sendMove(p);
        }

        if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

        if (returnPos.get()) {
            Collections.reverse(path);
            for (Vec3d p : path) sendMove(p);
            sendMove(startPos);
            Vec3d finalPosClient = offsetFix.get() ? getOffset(startPos) : startPos;
            if (offsetFix.get()) sendMove(finalPosClient);
            expectedPos = finalPosClient;
        } else {
            Vec3d finalPosClient = offsetFix.get() ? getOffset(finalPos) : finalPos;
            if (offsetFix.get()) sendMove(finalPosClient);
            expectedPos = finalPosClient;
            mc.player.setPosition(expectedPos.x, expectedPos.y, expectedPos.z);
        }
    }

    // ========== 传送方法 ==========
    private void doPaperTP(Vec3d from, Vec3d to) {
        double maxDist = maxSingleTpDist.get();
        if (maxDist <= 0) {
            paperTP(from, to);
            return;
        }
        double dist = from.distanceTo(to);
        if (dist <= maxDist) {
            paperTP(from, to);
            return;
        }
        int segments = (int) Math.ceil(dist / maxDist);
        Vec3d direction = to.subtract(from).normalize();
        double segLen = dist / segments;
        Vec3d current = from;
        for (int i = 0; i < segments; i++) {
            Vec3d next = current.add(direction.multiply(segLen));
            if (i == segments - 1) next = to;
            paperTP(current, next);
            current = next;
        }
    }

    private void paperTP(Vec3d from, Vec3d to) {
        if (mc.player.isSneaking()) {
            PlayerInput lastInput = mc.player.getLastPlayerInput();
            PlayerInput input = new PlayerInput(
                lastInput.forward(),
                lastInput.backward(),
                lastInput.left(),
                lastInput.right(),
                lastInput.jump(),
                false,
                lastInput.sprint()
            );
            mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(input));
        }

        double distance = from.distanceTo(to);
        int packetsRequired = (int) Math.ceil(distance / 10);
        for (int i = 0; i < packetsRequired - 1; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(to.x, to.y, to.z, true, mc.player.horizontalCollision));
    }

    private void sendMove(Vec3d pos) {
        PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, false, false);
        ((IPlayerMoveC2SPacket) packet).meteor$setTag(1337);
        mc.player.networkHandler.sendPacket(packet);
    }

    // ========== 服务器模拟 ==========
    private static final double MOVED_WRONGLY_THRESHOLD = 0.0625D;

    private boolean isWrongMove(Vec3d startPos, Vec3d endPos) {
        return getSquaredMovementDelta(startPos, endPos) > MOVED_WRONGLY_THRESHOLD;
    }

    private double getSquaredMovementDelta(Vec3d startPos, Vec3d endPos) {
        double d0 = clampHorizontal(endPos.getX());
        double d1 = clampVertical(endPos.getY());
        double d2 = clampHorizontal(endPos.getZ());

        double d6 = d0 - startPos.getX();
        double d7 = d1 - startPos.getY();
        double d8 = d2 - startPos.getZ();

        Vec3d movedPos = move(startPos, new Vec3d(d6, d7, d8));

        d6 = d0 - movedPos.x;
        d7 = d1 - movedPos.y;
        d8 = d2 - movedPos.z;

        if (d7 > -0.5D || d7 < 0.5D) {
            d7 = 0.0D;
        }
        return d6 * d6 + d7 * d7 + d8 * d8;
    }

    private Vec3d move(Vec3d startPos, Vec3d movement) {
        Vec3d vec3d = adjustMovementForCollisions(startPos, movement);
        if (vec3d.lengthSquared() > 1.0E-7) {
            return startPos.add(vec3d);
        }
        return startPos;
    }

    private Vec3d adjustMovementForCollisions(Vec3d startPos, Vec3d movement) {
        Box box = mc.player.getBoundingBox().offset(startPos.subtract(mc.player.getEntityPos()));
        final float stepHeight = 1;
        List<VoxelShape> list = mc.world.getEntityCollisions(mc.player, box.stretch(movement));
        Vec3d vec3d = movement.lengthSquared() == 0.0 ? movement : adjustMovementForCollisions(mc.player, movement, box, mc.world, list);

        boolean bl = movement.x != vec3d.x;
        boolean bl2 = movement.y != vec3d.y;
        boolean bl3 = movement.z != vec3d.z;
        boolean bl4 = mc.player.isOnGround() || bl2 && movement.y < 0.0;
        if (bl4 && (bl || bl3)) {
            Vec3d vec3d2 = adjustMovementForCollisions(mc.player, new Vec3d(movement.x, stepHeight, movement.z), box, mc.world, list);
            Vec3d vec3d3 = adjustMovementForCollisions(
                mc.player, new Vec3d(0.0, stepHeight, 0.0), box.stretch(movement.x, 0.0, movement.z), mc.world, list
            );
            if (vec3d3.y < (double)stepHeight) {
                Vec3d vec3d4 = adjustMovementForCollisions(mc.player, new Vec3d(movement.x, 0.0, movement.z), box.offset(vec3d3), mc.world, list).add(vec3d3);
                if (vec3d4.horizontalLengthSquared() > vec3d2.horizontalLengthSquared()) {
                    vec3d2 = vec3d4;
                }
            }

            if (vec3d2.horizontalLengthSquared() > vec3d.horizontalLengthSquared()) {
                return vec3d2.add(adjustMovementForCollisions(mc.player, new Vec3d(0.0, -vec3d2.y + movement.y, 0.0), box.offset(vec3d2), mc.world, list));
            }
        }

        return vec3d;
    }

    private Vec3d adjustMovementForCollisions(@Nullable Entity entity, Vec3d movement, Box entityBoundingBox, World world, List<VoxelShape> collisions) {
        ImmutableList.Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(collisions.size() + 1);
        if (!collisions.isEmpty()) {
            builder.addAll(collisions);
        }

        WorldBorder worldBorder = world.getWorldBorder();
        boolean bl = entity != null && worldBorder.canCollide(entity, entityBoundingBox.stretch(movement));
        if (bl) {
            builder.add(worldBorder.asVoxelShape());
        }

        builder.addAll(world.getBlockCollisions(entity, entityBoundingBox.stretch(movement)));
        return adjustMovementForCollisions(movement, entityBoundingBox, builder.build());
    }

    private Vec3d adjustMovementForCollisions(Vec3d movement, Box entityBoundingBox, List<VoxelShape> collisions) {
        if (collisions.isEmpty()) {
            return movement;
        } else {
            double d = movement.x;
            double e = movement.y;
            double f = movement.z;
            if (e != 0.0) {
                e = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, entityBoundingBox, collisions, e);
                if (e != 0.0) {
                    entityBoundingBox = entityBoundingBox.offset(0.0, e, 0.0);
                }
            }

            boolean bl = Math.abs(d) < Math.abs(f);
            if (bl && f != 0.0) {
                f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
                if (f != 0.0) {
                    entityBoundingBox = entityBoundingBox.offset(0.0, 0.0, f);
                }
            }

            if (d != 0.0) {
                d = VoxelShapes.calculateMaxOffset(Direction.Axis.X, entityBoundingBox, collisions, d);
                if (!bl && d != 0.0) {
                    entityBoundingBox = entityBoundingBox.offset(d, 0.0, 0.0);
                }
            }

            if (!bl && f != 0.0) {
                f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
            }

            return new Vec3d(d, e, f);
        }
    }

    private static double clampHorizontal(double d) {
        return MathHelper.clamp(d, -3.0E7D, 3.0E7D);
    }

    private static double clampVertical(double d) {
        return MathHelper.clamp(d, -2.0E7D, 2.0E7D);
    }

    // ========== 合法性检查 ==========
    private boolean isObstructed(Vec3d pos) {
        Box box = mc.player.getBoundingBox().offset(pos.subtract(mc.player.getEntityPos()));
        box = box.expand(-0.0001, -0.0001, -0.0001);
        for (VoxelShape v : mc.world.getBlockCollisions(mc.player, box)) {
            return true;
        }
        return false;
    }

    // ========== 目标点搜索（优化版） ==========
    private Vec3d findClosestValidPosInSphere(Vec3d targetPos, Vec3d playerPos, double radius) {
        BlockPos center = BlockPos.ofFloored(targetPos.x, targetPos.y, targetPos.z);
        // 快速拒绝：如果玩家与目标中心距离超过16格，则半径6格内任意点离玩家都超过10格，不可能合法
        if (playerPos.squaredDistanceTo(Vec3d.ofCenter(center)) > 16 * 16) {
            return null;
        }

        int r = (int) Math.ceil(radius);
        Vec3d best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.add(dx, dy, dz);
                    Vec3d stand = Vec3d.ofBottomCenter(bp).add(0, 1, 0);
                    if (stand.distanceTo(targetPos) > radius) continue;

                    double distSq = playerPos.squaredDistanceTo(stand);
                    // 距离玩家太远必然不合法，提前跳过
                    if (distSq >= 100.0000000000001) continue;

                    // 内联合法性判断，避免重复计算距离
                    if (!isObstructed(stand) && !isWrongMove(playerPos, stand)) {
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = stand;
                        }
                    }
                }
            }
        }
        return best;
    }

    // ========== 辅助方法 ==========
    private Vec3d getOffset(Vec3d base) {
        double dx = 0.05, dy = 0.01;
        List<Vec3d> offsets = Arrays.asList(
            base.add(dx, dy, 0),
            base.add(-dx, dy, 0),
            base.add(0, dy, dx),
            base.add(0, dy, -dx)
        );
        Collections.shuffle(offsets);
        for (Vec3d pos : offsets) {
            if (!invalid(pos)) return pos;
        }
        return base.add(0, dy, 0);
    }

    private boolean invalid(Vec3d pos) {
        if (mc.world == null) return true;
        BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        if (mc.world.getChunk(bp.getX() >> 4, bp.getZ() >> 4) == null) return true;
        Box box = mc.player.getBoundingBox().offset(pos.subtract(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
        for (BlockPos bPos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = mc.world.getBlockState(bPos);
            if (!state.getCollisionShape(mc.world, bPos).isEmpty() || state.isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    private boolean entityCheck(Entity entity) {
        if (!(entity instanceof LivingEntity) || !entity.isAlive() || entity == mc.player) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (mc.player.distanceTo(entity) > maxRange.get()) return false;

        if (enableYFilter.get()) {
            double y = entity.getY();
            if (y < minY.get() || y > maxY.get()) return false;
        }

        if (ignoreFriends.get() && entity instanceof PlayerEntity p && Friends.get().isFriend(p)) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get() && entity instanceof TameableEntity tameable && tameable.isTamed()) return false;

        if (entity instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (!Friends.get().shouldAttack(p)) return false;
            String name = p.getName().getString();
            List<String> list = Arrays.stream(playerList.get().split(",")).map(String::trim).collect(Collectors.toList());
            if (listMode.get() == ListMode.Whitelist && !list.contains(name)) return false;
            if (listMode.get() == ListMode.Blacklist && list.contains(name)) return false;
        }
        return true;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || !antiLag.get() || expectedPos == null) return;
        if (event.packet instanceof PlayerPositionLookS2CPacket packet) {
            if (System.currentTimeMillis() - lastAntiLagTime > 1000) antiLagRetries = 0;

            Vec3d serverPos = packet.change().position();
            double dist = serverPos.distanceTo(expectedPos);
            if (dist > maxRange.get() || dist < 0.01) return;

            if (antiLagRetries < 3) {
                event.cancel();
                mc.getNetworkHandler().sendPacket(new TeleportConfirmC2SPacket(packet.teleportId()));
                doPaperTP(serverPos, expectedPos);
                antiLagRetries++;
                lastAntiLagTime = System.currentTimeMillis();
            } else {
                expectedPos = null;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (currentTarget != null) {
            event.renderer.box(currentTarget.getBoundingBox(), targetColor.get(), targetColor.get(), ShapeMode.Lines, 0);
        }
        if (renderPath.get() && !renderPathNodes.isEmpty()) {
            for (int i = 0; i < renderPathNodes.size() - 1; i++) {
                Vec3d n1 = renderPathNodes.get(i);
                Vec3d n2 = renderPathNodes.get(i + 1);
                event.renderer.line(n1.x, n1.y + 1, n1.z, n2.x, n2.y + 1, n2.z, pathColor.get());
                event.renderer.box(new Box(n1.x - 0.2, n1.y, n1.z - 0.2, n1.x + 0.2, n1.y + 2, n1.z + 0.2), pathColor.get(), pathColor.get(), ShapeMode.Lines, 0);
            }
        }
    }

    @Override
    public String getInfoString() {
        return currentTarget != null ? EntityUtils.getName(currentTarget) : null;
    }
}