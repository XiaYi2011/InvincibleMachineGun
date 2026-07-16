package com.codigohasta.addon.modules;

import com.codigohasta.addon.AddonTemplate;
import com.codigohasta.addon.mixin.InventoryAccessor;
import com.codigohasta.addon.utils.leaveshack.InventoryUtil;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

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
        .defaultValue(true).visible(() -> autoSwitch.get()).build());

    // 传送设置
    public enum Mode { Vanilla, Paper }
    private final Setting<Mode> mode = sgTP.add(new EnumSetting.Builder<Mode>()
        .name("兼容模式").defaultValue(Mode.Paper).build());
    private final Setting<Double> maxRange = sgTP.add(new DoubleSetting.Builder()
        .name("最大范围").defaultValue(49.0).min(1).sliderMax(99).build());
    private final Setting<Boolean> goUp = sgTP.add(new BoolSetting.Builder()
        .name("V-Clip").defaultValue(true).visible(() -> mode.get() == Mode.Paper).build());
    private final Setting<Boolean> returnPos = sgTP.add(new BoolSetting.Builder()
        .name("攻击后回传").defaultValue(true).build());
    private final Setting<Boolean> offsetFix = sgTP.add(new BoolSetting.Builder()
        .name("偏移同步").description("发送微小偏移包防止拉回").defaultValue(true).build());
    private final Setting<Boolean> antiLag = sgTP.add(new BoolSetting.Builder()
        .name("反拉回")
        .description("被服务器回弹时自动传送回目标位置")
        .defaultValue(true)
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

    // 内部变量
    private final List<Entity> targets = new ArrayList<>();
    private final List<Vec3d> renderPathNodes = new ArrayList<>();
    private Entity currentTarget;
    private int originalSlot = -1;
    private int silentSwapSlot = -1;
    private int silentSwapPrevSlot = -1;
    private long nextAttackTime = 0;

    // 反拉回目标位置
    private Vec3d antiLagTarget = null;

    public TpAura() {
        super(AddonTemplate.CATEGORY, "如来神掌", "从天而降的掌法。抄袭了裤子条纹的tp。娱乐功能");
    }

    @Override
    public void onActivate() {
        originalSlot = -1;
        silentSwapSlot = -1;
        silentSwapPrevSlot = -1;
        nextAttackTime = System.currentTimeMillis();
        renderPathNodes.clear();
        antiLagTarget = null;
    }

    @Override
    public void onDeactivate() {
        if (silentSwapSlot != -1 && mc.player != null) swapBackWeapon();
        if (originalSlot != -1 && autoSwitch.get() && !silentSwap.get() && mc.player != null) {
            ((InventoryAccessor) mc.player.getInventory()).setSelectedSlot(originalSlot);
            originalSlot = -1;
        }
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
            return;
        }
        currentTarget = targets.get(0);

        executeTrouserAttack(currentTarget);
        swapBackWeapon();

        nextAttackTime = System.currentTimeMillis() + attackDelayMs.get();
    }

    private void executeTrouserAttack(Entity target) {
        Vec3d startPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
        double reach = maxRange.get();

        Vec3d finalPos = !invalid(targetPos) ? targetPos : findNearestPos(targetPos);
        if (finalPos == null) return;

        Vec3d highStart = startPos.add(0, reach, 0);
        Vec3d highTarget = finalPos.add(0, reach, 0);

        renderPathNodes.clear();
        renderPathNodes.add(startPos);
        if (mode.get() == Mode.Paper && goUp.get()) {
            renderPathNodes.add(highStart);
            renderPathNodes.add(highTarget);
        }
        renderPathNodes.add(finalPos);

        if (mode.get() == Mode.Paper) {
            Vec3d currentServerPos = startPos;

            // 攻击路径
            if (goUp.get()) {
                paperTP(currentServerPos, highStart);
                currentServerPos = highStart;
                paperTP(currentServerPos, highTarget);
                currentServerPos = highTarget;
            }
            paperTP(currentServerPos, finalPos);
            currentServerPos = finalPos;

            if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
            mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

            // 回传
            if (returnPos.get()) {
                if (goUp.get()) {
                    paperTP(currentServerPos, highTarget);
                    currentServerPos = highTarget;
                    paperTP(currentServerPos, highStart);
                    currentServerPos = highStart;
                }
                paperTP(currentServerPos, startPos);
                currentServerPos = startPos;

                if (offsetFix.get()) {
                    Vec3d offset = getOffset(startPos);
                    paperTP(currentServerPos, offset);
                    antiLagTarget = offset;
                } else {
                    antiLagTarget = startPos;
                }
            } else {
                if (offsetFix.get()) {
                    Vec3d offset = getOffset(finalPos);
                    paperTP(currentServerPos, offset);
                    antiLagTarget = offset;
                } else {
                    antiLagTarget = finalPos;
                }
            }
        } else {
            // Vanilla 模式
            int spam = 4;
            for (int i = 0; i < spam; i++) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, mc.player.horizontalCollision));
            }
            if (goUp.get()) {
                sendMove(highStart);
                sendMove(highTarget);
            }
            sendMove(finalPos);
            if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
            mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

            if (returnPos.get()) {
                if (goUp.get()) {
                    sendMove(highTarget);
                    sendMove(highStart);
                }
                sendMove(startPos);
                Vec3d finalPosClient = offsetFix.get() ? getOffset(startPos) : startPos;
                if (offsetFix.get()) sendMove(finalPosClient);
                antiLagTarget = finalPosClient;
            } else {
                Vec3d finalPosClient = offsetFix.get() ? getOffset(finalPos) : finalPos;
                if (offsetFix.get()) sendMove(finalPosClient);
                antiLagTarget = finalPosClient;
            }
        }
    }

    /**
     * Paper模式传送，完全复刻旧版 ICTP 的防回弹逻辑。
     */
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

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || !antiLag.get() || antiLagTarget == null) return;

        if (event.packet instanceof PlayerPositionLookS2CPacket packet) {
            Vec3d serverPos = packet.change().position();
            double dist = serverPos.distanceTo(antiLagTarget);
            if (dist > maxRange.get() || dist < 0.01) return;

            event.cancel();
            mc.getNetworkHandler().sendPacket(new TeleportConfirmC2SPacket(packet.teleportId()));

            // 重新传送到反拉回目标位置（仅发包）
            paperTP(serverPos, antiLagTarget);
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
                Vec3d n2 = renderPathNodes.get(i+1);
                event.renderer.line(n1.x, n1.y + 1, n1.z, n2.x, n2.y + 1, n2.z, pathColor.get());
                event.renderer.box(new Box(n1.x - 0.2, n1.y, n1.z - 0.2, n1.x + 0.2, n1.y + 2, n1.z + 0.2), pathColor.get(), pathColor.get(), ShapeMode.Lines, 0);
            }
        }
    }

    private Vec3d getOffset(Vec3d base) {
        double dx = 0.05, dy = 0.01;
        List<Vec3d> offsets = Arrays.asList(base.add(dx, dy, 0), base.add(-dx, dy, 0), base.add(0, dy, dx), base.add(0, dy, -dx));
        Collections.shuffle(offsets);
        for (Vec3d pos : offsets) if (!invalid(pos)) return pos;
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

    private Vec3d findNearestPos(Vec3d desired) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Vec3d test = desired.add(dx, dy, dz);
                    if (!invalid(test)) return test;
                }
            }
        }
        return null;
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

    @Override
    public String getInfoString() {
        return currentTarget != null ? EntityUtils.getName(currentTarget) : null;
    }
}