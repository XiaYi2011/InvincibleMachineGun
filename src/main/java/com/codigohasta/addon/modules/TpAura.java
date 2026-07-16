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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
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

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TpAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("攻击机制");
    private final SettingGroup sgTP = settings.createGroup("传送");
    private final SettingGroup sgTargeting = settings.createGroup("目标");
    private final SettingGroup sgWhitelist = settings.createGroup("白名单");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    private final Setting<Integer> attackDelayMs = sgTiming.add(new IntSetting.Builder()
        .name("额外延迟(ms)")
        .description("两次攻击的最小间隔。服务器 TPS 低时建议调高，避免移动包预算超限被回弹")
        .defaultValue(50).min(0).sliderMax(5000).build());

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切武器").defaultValue(true).build());
    private final Setting<Boolean> requireMace = sgGeneral.add(new BoolSetting.Builder()
        .name("仅手持重锤").defaultValue(false).build());
    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("挥手").defaultValue(true).build());
    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("静默切换").defaultValue(true).visible(autoSwitch::get).build());

    public enum Mode { Vanilla, Paper }
    private final Setting<Mode> mode = sgTP.add(new EnumSetting.Builder<Mode>()
        .name("兼容模式").defaultValue(Mode.Paper).build());
    private final Setting<Double> maxRange = sgTP.add(new DoubleSetting.Builder()
        .name("最大攻击范围").defaultValue(49.0).min(1).sliderMax(99).build());
    private final Setting<Boolean> goUp = sgTP.add(new BoolSetting.Builder()
        .name("V-Clip").defaultValue(true).build());
    private final Setting<Boolean> smartVClip = sgTP.add(new BoolSetting.Builder()
        .name("智能V-Clip")
        .description("先尝试直接传送，若不可达再启用V-Clip抬升")
        .defaultValue(false)
        .visible(goUp::get)
        .build());
    private final Setting<Double> vClipHeight = sgTP.add(new DoubleSetting.Builder()
        .name("V-Clip 高度").defaultValue(22.0).min(1).sliderMax(100).visible(goUp::get).build());
    private final Setting<Boolean> returnPos = sgTP.add(new BoolSetting.Builder()
        .name("攻击后回传").defaultValue(true).build());
    private final Setting<Boolean> noThroughWalls = sgTP.add(new BoolSetting.Builder()
        .name("不穿墙")
        .description("关闭V-Clip时若路径穿墙则放弃攻击，防止返回时卡墙")
        .defaultValue(false)
        .visible(() -> !goUp.get() && returnPos.get())
        .build());
    private final Setting<Boolean> offsetFix = sgTP.add(new BoolSetting.Builder()
        .name("偏移同步").description("发送微小偏移包防止拉回").defaultValue(true).build());
    private final Setting<Boolean> antiLag = sgTP.add(new BoolSetting.Builder()
        .name("反拉回").defaultValue(true).build());
    private final Setting<Integer> maxAntiLagRetries = sgTP.add(new IntSetting.Builder()
        .name("每秒最多拉回次数").defaultValue(10).min(1).max(20).build());
    private final Setting<Double> maxSingleTpDist = sgTP.add(new DoubleSetting.Builder()
        .name("最大单次传送距离").defaultValue(0.0).min(0).sliderMax(100).build());

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

    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("显示路径").defaultValue(true).build());
    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("轨迹颜色").defaultValue(new SettingColor(255, 0, 0, 100)).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("目标颜色").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<Boolean> renderIntermediateNodes = sgRender.add(new BoolSetting.Builder()
        .name("渲染中间点").defaultValue(true).visible(() -> maxSingleTpDist.get() > 0).build());
    private final Setting<Integer> renderTimeMs = sgRender.add(new IntSetting.Builder()
        .name("路径滞留(ms)").description("攻击结束后路径继续显示的时间，期间渐隐")
        .defaultValue(1200).min(100).sliderMax(5000).build());

    // -------------------- 运行状态 --------------------
    private final List<Entity> targets = new ArrayList<>();
    private final List<Vec3d> renderPathNodes = new ArrayList<>();
    private int renderAttackIdx = -1;
    private long renderPathExpire = 0;
    private Entity renderTarget = null;
    private Entity currentTarget;
    private int originalSlot = -1;
    private int silentSwapSlot = -1;
    private int silentSwapPrevSlot = -1;
    private long nextAttackTime = 0;
    private Vec3d expectedPos = null;

    private int antiLagRetries = 0;
    private long lastAntiLagTime = 0;

    /** 与服务器一致的 moved-wrongly 阈值（spigot.yml moved-wrongly-threshold 默认 0.0625） */
    private static final double MOVED_WRONGLY_THRESHOLD = 0.0625D;
    /** 单段传送硬上限 200 格 */
    private static final double MAX_LEG_DIST_SQR = 40000.0D;
    /**
     * Paper 移动包预算：deltaPackets > max(allowedPlayerTicks, 5) 会被惩罚按 1 包计。
     * 空包会把 allowedPlayerTicks 重置为 20，每个带位移的包再 -1，
     * 因此需满足 垫包数 + 2 * 位置包数 <= 20（含 1 余量给客户端原生移动包）。
     */
    private static final int PAPER_BUDGET = 20;
    /** 纯 Vanilla 没有 allowedPlayerTicks，硬上限 5 包/tick */
    private static final int VANILLA_BUDGET = 5;
    /** 累计距离容差：补偿服务器 tick 起点位置与本地 startPos 之间的漂移 */
    private static final double DIST_TOLERANCE = 3.0;

    public TpAura() {
        super(AddonTemplate.CATEGORY, "如来神掌", "从天而降的掌法。智能V-Clip，不穿墙选项。");
    }

    @Override
    public void onActivate() {
        originalSlot = -1;
        silentSwapSlot = -1;
        silentSwapPrevSlot = -1;
        nextAttackTime = System.currentTimeMillis();
        renderPathNodes.clear();
        renderAttackIdx = -1;
        renderTarget = null;
        renderPathExpire = 0;
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
        currentTarget = null;
        renderTarget = null;
        renderPathNodes.clear();
        renderAttackIdx = -1;
    }

    // -------------------- 武器切换（未改动） --------------------
    private int findWeaponInventorySlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 45; i++) {
            String name = mc.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if (name.contains("sword") || name.contains("mace") || name.contains("axe")) {
                return i < 9 ? i + 36 : i;
            }
        }
        return -1;
    }

    private boolean checkAndSwapWeapon() {
        if (mc.player == null) return false;
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
        if (mc.player == null || silentSwapSlot == -1) return;
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

    // -------------------- 主循环 --------------------
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (System.currentTimeMillis() < nextAttackTime) {
            swapBackWeapon();
            return;
        }

        if (autoSwitch.get() && !checkAndSwapWeapon()) return;

        targets.clear();
        TargetUtils.getList(targets, this::entityCheck, SortPriority.LowestDistance, 20);

        if (targets.isEmpty()) {
            currentTarget = null;
            swapBackWeapon();
            return;
        }

        Entity attacked = null;
        for (Entity t : targets) {
            currentTarget = t;
            if (executeTrouserAttack(t)) {
                attacked = t;
                break;
            }
        }

        if (attacked == null) currentTarget = null;

        swapBackWeapon();
        nextAttackTime = System.currentTimeMillis() + attackDelayMs.get();
    }

    // -------------------- 攻击编排：先算路、先校验、后发包 --------------------
    private boolean executeTrouserAttack(Entity target) {
        if (mc.player == null || mc.world == null) return false;
        // 服务器对 sleeping 玩家 movedDist > 1 直接回传，跳过
        if (mc.player.isSleeping()) return false;

        Vec3d startPos = mc.player.getEntityPos();
        Vec3d targetCenter = target.getBoundingBox().getCenter();
        Vec3d finalPos = findNearestLegalToTarget(targetCenter, 6.0);
        if (finalPos == null) return false;

        List<Vec3d> route = new ArrayList<>();
        route.add(startPos);

        if (mode.get() == Mode.Paper) {
            boolean useVClip = goUp.get();
            if (useVClip && smartVClip.get()
                && isWholeTpValid(startPos, finalPos)
                && !(noThroughWalls.get() && isPathObstructed(startPos, finalPos))) {
                useVClip = false;
            }

            if (!useVClip) {
                if (!isWholeTpValid(startPos, finalPos)) return false;
                if (noThroughWalls.get() && isPathObstructed(startPos, finalPos)) return false;
                route.add(finalPos);
                appendReturn(route, startPos, finalPos);
            } else {
                double vh = vClipHeight.get();
                Vec3d highStart = startPos.add(0, vh, 0);
                if (isObstructed(highStart) || isObstructed(finalPos)) return false;
                Vec3d highTarget = findSafeHighTarget(highStart, finalPos, vh);
                if (highTarget == null) return false;
                route.add(highStart);
                route.add(highTarget);
                route.add(finalPos);
                if (returnPos.get()) {
                    route.add(highTarget);
                    route.add(highStart);
                    route.add(startPos);
                    if (offsetFix.get()) {
                        Vec3d off = getOffset(startPos);
                        if (off != null) route.add(off);
                    }
                } else if (offsetFix.get()) {
                    Vec3d off = getOffset(finalPos);
                    if (off != null) route.add(off);
                }
            }
        } else {
            // Vanilla 模式：同样的组路方式（其线性检查使长距离基本不可行，预算检查会直接放弃）
            double vh = vClipHeight.get();
            if (goUp.get()) {
                Vec3d highStart = startPos.add(0, vh, 0);
                Vec3d highTarget = finalPos.add(0, vh, 0);
                route.add(highStart);
                route.add(highTarget);
                route.add(finalPos);
                if (returnPos.get()) {
                    route.add(highTarget);
                    route.add(highStart);
                    route.add(startPos);
                    if (offsetFix.get()) {
                        Vec3d off = getOffset(startPos);
                        if (off != null) route.add(off);
                    }
                } else if (offsetFix.get()) {
                    Vec3d off = getOffset(finalPos);
                    if (off != null) route.add(off);
                }
            } else {
                route.add(finalPos);
                appendReturn(route, startPos, finalPos);
            }
        }

        // 可选：按最大单段距离细分（渲染节点与实际发包节点始终一致）
        if (maxSingleTpDist.get() > 0) route = subdivideRoute(route, maxSingleTpDist.get());

        int attackIdx = indexOfPos(route, finalPos);
        if (attackIdx < 1) return false;

        // 校验 + 预算 + 发包（任何一步失败都不会发出半个包，杜绝脱同步）
        if (!executeRoute(route, attackIdx, target)) return false;

        // 缓存本次成功路径用于渲染（失败时保留上一次路径渐隐，不再闪烁）
        renderPathNodes.clear();
        renderPathNodes.addAll(route);
        renderAttackIdx = attackIdx;
        renderTarget = target;
        renderPathExpire = System.currentTimeMillis() + renderTimeMs.get();
        return true;
    }

    private void appendReturn(List<Vec3d> route, Vec3d startPos, Vec3d finalPos) {
        if (returnPos.get()) {
            route.add(startPos);
            if (offsetFix.get()) {
                Vec3d off = getOffset(startPos);
                if (off != null) route.add(off);
            }
        } else if (offsetFix.get()) {
            Vec3d off = getOffset(finalPos);
            if (off != null) route.add(off);
        }
    }

    private List<Vec3d> subdivideRoute(List<Vec3d> route, double maxDist) {
        List<Vec3d> out = new ArrayList<>();
        out.add(route.get(0));
        for (int i = 0; i < route.size() - 1; i++) {
            Vec3d a = route.get(i), b = route.get(i + 1);
            double dist = a.distanceTo(b);
            if (dist > maxDist) {
                int segs = (int) Math.ceil(dist / maxDist);
                Vec3d dir = b.subtract(a).normalize();
                double segLen = dist / segs;
                for (int s = 1; s <= segs; s++) {
                    out.add(s == segs ? b : a.add(dir.multiply(segLen * s)));
                }
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private int indexOfPos(List<Vec3d> route, Vec3d pos) {
        for (int i = 1; i < route.size(); i++) {
            if (route.get(i).squaredDistanceTo(pos) < 1.0E-6) return i;
        }
        return -1;
    }

    // -------------------- 传送执行器 --------------------
    /**
     * 校验全部路段 -> 检查移动包预算 -> 垫包 -> 逐节点位置包（在 attackIdx 后插入攻击包）。
     * 任何一步不满足都返回 false 且一个包都不发，保证客户端/服务器永不脱同步。
     */
    private boolean executeRoute(List<Vec3d> route, int attackIdx, @Nullable Entity target) {
        if (mc.player == null || mc.world == null || route.size() < 2) return false;

        // 1) 发包前校验每一段（修复旧版"先传送后校验"的中途 abort 脱同步）
        for (int i = 0; i < route.size() - 1; i++) {
            if (!isWholeTpValid(route.get(i), route.get(i + 1))) return false;
        }

        boolean paper = mode.get() == Mode.Paper;
        int posPackets = route.size() - 1;
        int padding = paper ? paperPadding(route) : vanillaPadding(route);

        // 2) 预算检查：不够就整次放弃
        if (paper) {
            if (padding + 2 * posPackets > PAPER_BUDGET) return false;
        } else {
            if (padding + posPackets > VANILLA_BUDGET) return false;
        }

        // 3) 发包
        if (mc.player.isSneaking()) sendUnsneak();
        for (int i = 0; i < padding; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }
        for (int i = 1; i < route.size(); i++) {
            Vec3d n = route.get(i);
            if (paper) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(n.x, n.y, n.z, true, mc.player.horizontalCollision));
            } else {
                sendMove(n);
            }
            if (i == attackIdx && target != null) {
                if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
                mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
            }
        }

        // 4) 同步客户端状态
        expectedPos = route.get(route.size() - 1);
        if (!returnPos.get()) {
            mc.player.setPosition(expectedPos.x, expectedPos.y, expectedPos.z);
            mc.player.setVelocity(Vec3d.ZERO);
        }
        return true;
    }

    /**
     * Paper 垫包数：服务器按"距 tick 起点的累计距离 <= 10 * 本 tick 移动包数"检查。
     * 第 k 个位置包发出时已有 padding + k 个包，因此对每个节点 k 需满足
     * cumDist_k <= 10 * (padding + k)，取所有 k 的最大值。
     */
    private int paperPadding(List<Vec3d> route) {
        int padding = 0;
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + DIST_TOLERANCE;
            padding = Math.max(padding, (int) Math.ceil(cum / 10.0 - k));
        }
        return Math.max(padding, 0);
    }

    /** 纯 Vanilla 为线性检查：movedDist - expectedDist > 100 * deltaPackets */
    private int vanillaPadding(List<Vec3d> route) {
        int padding = 0;
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + DIST_TOLERANCE;
            padding = Math.max(padding, (int) Math.ceil(cum * cum / 100.0 - k));
        }
        return Math.max(padding, 0);
    }

    private void sendUnsneak() {
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

    private void sendMove(Vec3d pos) {
        if (mc.player == null) return;
        PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, false, false);
        ((IPlayerMoveC2SPacket) packet).meteor$setTag(1337);
        mc.player.networkHandler.sendPacket(packet);
    }

    private Vec3d findSafeHighTarget(Vec3d highStart, Vec3d finalPos, double baseHeight) {
        Vec3d original = finalPos.add(0, baseHeight, 0);
        if (isObstructed(original)) return null;
        if (isWholeTpValid(highStart, original) && isWholeTpValid(original, finalPos))
            return original;

        for (int offset = 1; offset <= 5; offset++) {
            for (int sign : new int[]{1, -1}) {
                Vec3d candidate = finalPos.add(0, baseHeight + offset * sign, 0);
                if (isObstructed(candidate)) continue;
                if (isWholeTpValid(highStart, candidate) && isWholeTpValid(candidate, finalPos))
                    return candidate;
            }
        }
        return null;
    }

    private Vec3d findNearestLegalToTarget(Vec3d targetCenter, double radius) {
        if (mc.player == null) return null;
        double bestDistSq = Double.MAX_VALUE;
        Vec3d bestPos = null;

        int intRadius = (int) Math.ceil(radius);
        for (int dx = -intRadius; dx <= intRadius; dx++) {
            for (int dy = -intRadius; dy <= intRadius; dy++) {
                for (int dz = -intRadius; dz <= intRadius; dz++) {
                    if (dx*dx + dy*dy + dz*dz > radius*radius) continue;
                    Vec3d candidate = targetCenter.add(dx + 0.5, dy, dz + 0.5);
                    if (!invalid(candidate)) {
                        double distSq = targetCenter.squaredDistanceTo(candidate);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestPos = candidate;
                        }
                    }
                }
            }
        }
        if (bestPos != null) return bestPos;

        for (double dx = -radius; dx <= radius; dx += 0.5) {
            for (double dy = -radius; dy <= radius; dy += 0.5) {
                for (double dz = -radius; dz <= radius; dz += 0.5) {
                    if (dx*dx + dy*dy + dz*dz > radius*radius) continue;
                    Vec3d candidate = targetCenter.add(dx, dy, dz);
                    if (!invalid(candidate)) {
                        double distSq = targetCenter.squaredDistanceTo(candidate);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestPos = candidate;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    // -------------------- 服务器移动检测的本地复刻 --------------------
    private boolean isWholeTpValid(Vec3d startPos, Vec3d endPos) {
        if (mc.player == null || mc.world == null) return false;
        return startPos.squaredDistanceTo(endPos) < MAX_LEG_DIST_SQR &&
               isChunkLoaded(endPos) &&
               !isWrongMove(startPos, endPos) &&
               !isObstructed(endPos);
    }

    private boolean isWrongMove(Vec3d startPos, Vec3d endPos) {
        return getSquaredMovementDelta(startPos, endPos) > MOVED_WRONGLY_THRESHOLD;
    }

    private double getSquaredMovementDelta(Vec3d startPos, Vec3d endPos) {
        if (mc.player == null || mc.world == null) return 0;
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
        // 与服务器一致的 Y 弱检测：该条件恒真，Y 残差永远被清零（V-Clip 原理）
        if (d7 > -0.5D || d7 < 0.5D) d7 = 0.0D;
        return d6 * d6 + d7 * d7 + d8 * d8;
    }

    private Vec3d move(Vec3d startPos, Vec3d movement) {
        Vec3d vec3d = adjustMovementForCollisions(startPos, movement);
        if (vec3d.lengthSquared() > 1.0E-7) return startPos.add(vec3d);
        return startPos;
    }

    private Vec3d adjustMovementForCollisions(Vec3d startPos, Vec3d movement) {
        if (mc.player == null || mc.world == null) return Vec3d.ZERO;
        Box box = mc.player.getBoundingBox().offset(mc.player.getEntityPos().negate()).offset(startPos);
        List<VoxelShape> list = mc.world.getEntityCollisions(mc.player, box.stretch(movement));
        Vec3d vec3d = movement.lengthSquared() == 0.0 ? movement : adjustMovementForCollisions(mc.player, movement, box, mc.world, list);
        boolean bl = movement.x != vec3d.x;
        boolean bl2 = movement.y != vec3d.y;
        boolean bl3 = movement.z != vec3d.z;
        // 修复：台阶高度用玩家真实属性（默认 0.6），旧版写死 1.0 导致本地误判合法 -> moved wrongly 回弹。
        // 服务器端模拟时玩家 onGround 恒为 true（我们所有位置包都带 onGround=true）。
        double stepHeight = mc.player.getAttributeValue(EntityAttributes.STEP_HEIGHT);
        boolean bl4 = true;
        if (bl4 && (bl || bl3)) {
            Vec3d vec3d2 = adjustMovementForCollisions(mc.player, new Vec3d(movement.x, stepHeight, movement.z), box, mc.world, list);
            Vec3d vec3d3 = adjustMovementForCollisions(mc.player, new Vec3d(0.0, stepHeight, 0.0), box.stretch(movement.x, 0.0, movement.z), mc.world, list);
            if (vec3d3.y < stepHeight) {
                Vec3d vec3d4 = adjustMovementForCollisions(mc.player, new Vec3d(movement.x, 0.0, movement.z), box.offset(vec3d3), mc.world, list).add(vec3d3);
                if (vec3d4.horizontalLengthSquared() > vec3d2.horizontalLengthSquared()) vec3d2 = vec3d4;
            }
            if (vec3d2.horizontalLengthSquared() > vec3d.horizontalLengthSquared())
                return vec3d2.add(adjustMovementForCollisions(mc.player, new Vec3d(0.0, -vec3d2.y + movement.y, 0.0), box.offset(vec3d2), mc.world, list));
        }
        return vec3d;
    }

    private Vec3d adjustMovementForCollisions(@Nullable Entity entity, Vec3d movement, Box entityBoundingBox, World world, List<VoxelShape> collisions) {
        ImmutableList.Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(collisions.size() + 1);
        if (!collisions.isEmpty()) builder.addAll(collisions);
        WorldBorder worldBorder = world.getWorldBorder();
        boolean bl = entity != null && worldBorder.canCollide(entity, entityBoundingBox.stretch(movement));
        if (bl) builder.add(worldBorder.asVoxelShape());
        builder.addAll(world.getBlockCollisions(entity, entityBoundingBox.stretch(movement)));
        return adjustMovementForCollisions(movement, entityBoundingBox, builder.build());
    }

    private Vec3d adjustMovementForCollisions(Vec3d movement, Box entityBoundingBox, List<VoxelShape> collisions) {
        if (collisions.isEmpty()) return movement;
        double d = movement.x, e = movement.y, f = movement.z;
        if (e != 0.0) {
            e = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, entityBoundingBox, collisions, e);
            if (e != 0.0) entityBoundingBox = entityBoundingBox.offset(0.0, e, 0.0);
        }
        boolean bl = Math.abs(d) < Math.abs(f);
        if (bl && f != 0.0) {
            f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
            if (f != 0.0) entityBoundingBox = entityBoundingBox.offset(0.0, 0.0, f);
        }
        if (d != 0.0) {
            d = VoxelShapes.calculateMaxOffset(Direction.Axis.X, entityBoundingBox, collisions, d);
            if (!bl && d != 0.0) entityBoundingBox = entityBoundingBox.offset(d, 0.0, 0.0);
        }
        if (!bl && f != 0.0) f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
        return new Vec3d(d, e, f);
    }

    private static double clampHorizontal(double d) { return MathHelper.clamp(d, -3.0E7D, 3.0E7D); }
    private static double clampVertical(double d) { return MathHelper.clamp(d, -2.0E7D, 2.0E7D); }

    private boolean isChunkLoaded(Vec3d pos) {
        return mc.world != null && mc.world.isChunkLoaded((int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4);
    }

    private boolean isObstructed(Vec3d pos) {
        if (mc.player == null || mc.world == null) return true;
        // 修复：未加载区块 getBlockCollisions 返回空，会误判不阻塞，
        // 而服务器 hasChunkAt 会静默丢弃该包 -> 脱同步回弹
        if (!isChunkLoaded(pos)) return true;
        Box box = mc.player.getBoundingBox().offset(mc.player.getEntityPos().negate()).offset(pos);
        box = box.expand(-0.0001, -0.0001, -0.0001);
        for (VoxelShape v : mc.world.getBlockCollisions(mc.player, box)) return true;
        return false;
    }

    private boolean isPathObstructed(Vec3d from, Vec3d to) {
        if (mc.player == null || mc.world == null) return true;
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) return false;
        double step = 0.1;
        int steps = (int) Math.ceil(dist / step);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3d point = from.add(dx * t, dy * t, dz * t);
            if (isObstructed(point)) return true;
        }
        return false;
    }

    // -------------------- 反拉回 --------------------
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || !antiLag.get() || expectedPos == null) return;
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;
        if (System.currentTimeMillis() - lastAntiLagTime > 1000) antiLagRetries = 0;

        Vec3d serverPos = packet.change().position();
        double dist = serverPos.distanceTo(expectedPos);
        // 服务器已认可我们的位置 / 与攻击无关的传送（重生、指令等）：放行
        if (dist < 0.01 || dist > maxRange.get() + vClipHeight.get() + 10) {
            expectedPos = null;
            return;
        }
        // 放弃抵抗：放行（不取消事件），让客户端接受回弹
        if (antiLagRetries >= maxAntiLagRetries.get()) {
            expectedPos = null;
            return;
        }
        // 修复：先确认能合法回传再取消事件；回传本身也要满足 moved-wrongly 与预算
        if (!isWholeTpValid(serverPos, expectedPos)) {
            expectedPos = null;
            return;
        }
        List<Vec3d> route = List.of(serverPos, expectedPos);
        int padding = mode.get() == Mode.Paper ? paperPadding(route) : vanillaPadding(route);
        if (padding + 2 > (mode.get() == Mode.Paper ? PAPER_BUDGET : VANILLA_BUDGET)) {
            expectedPos = null;
            return;
        }

        event.cancel();
        mc.player.networkHandler.sendPacket(new TeleportConfirmC2SPacket(packet.teleportId()));
        for (int i = 0; i < padding; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(expectedPos.x, expectedPos.y, expectedPos.z, true, mc.player.horizontalCollision));
        antiLagRetries++;
        lastAntiLagTime = System.currentTimeMillis();
    }

    // -------------------- 渲染 --------------------
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (currentTarget != null) {
            event.renderer.box(currentTarget.getBoundingBox(), targetColor.get(), targetColor.get(), ShapeMode.Lines, 0);
        }
        if (!renderPath.get() || renderPathNodes.size() < 2) return;

        long now = System.currentTimeMillis();
        if (now > renderPathExpire) return;

        // 最后 300ms 渐隐
        long remain = renderPathExpire - now;
        float fade = remain < 300 ? Math.max(0f, remain / 300f) : 1f;

        Color lineColor = faded(pathColor.get(), fade);
        for (int i = 0; i < renderPathNodes.size() - 1; i++) {
            Vec3d n1 = renderPathNodes.get(i);
            Vec3d n2 = renderPathNodes.get(i + 1);
            event.renderer.line(n1.x, n1.y, n1.z, n2.x, n2.y, n2.z, lineColor);
        }

        // 攻击点标记（人形框，比 0.25 小盒子直观）
        if (renderAttackIdx >= 0 && renderAttackIdx < renderPathNodes.size()) {
            Vec3d hit = renderPathNodes.get(renderAttackIdx);
            Color c = faded(targetColor.get(), fade);
            event.renderer.box(hit.x - 0.3, hit.y, hit.z - 0.3, hit.x + 0.3, hit.y + 1.8, hit.z + 0.3, c, c, ShapeMode.Both, 0);
        }

        // 中间节点仅在做单段距离细分时才有意义，且数量可控
        if (renderIntermediateNodes.get() && maxSingleTpDist.get() > 0) {
            Color nodeColor = faded(pathColor.get(), fade);
            for (Vec3d node : renderPathNodes) {
                event.renderer.box(
                    node.x - 0.125, node.y - 0.125, node.z - 0.125,
                    node.x + 0.125, node.y + 0.125, node.z + 0.125,
                    nodeColor, nodeColor, ShapeMode.Both, 0
                );
            }
        }

        // 攻击点到当前目标的连线
        if (renderTarget != null && !renderTarget.isRemoved() && renderAttackIdx >= 0 && renderAttackIdx < renderPathNodes.size()) {
            Vec3d hit = renderPathNodes.get(renderAttackIdx);
            Vec3d tc = renderTarget.getBoundingBox().getCenter();
            event.renderer.line(hit.x, hit.y, hit.z, tc.x, tc.y, tc.z, lineColor);
        }
    }

    private static Color faded(SettingColor c, float f) {
        return new Color(c.r, c.g, c.b, (int) Math.round(c.a * f));
    }

    // -------------------- 工具 --------------------
    private @Nullable Vec3d getOffset(Vec3d base) {
        if (mc.player == null || mc.world == null) return null;
        double dx = 0.05, dy = 0.01;
        List<Vec3d> offsets = new ArrayList<>(Arrays.asList(
            base.add(dx, dy, 0), base.add(-dx, dy, 0), base.add(0, dy, dx), base.add(0, dy, -dx)
        ));
        Collections.shuffle(offsets);
        for (Vec3d pos : offsets) if (!invalid(pos) && !isObstructed(pos)) return pos;
        // 修复：旧版兜底点可能阻塞 -> 目的地碰撞回弹；找不到安全点就放弃 offset（非必需）
        return null;
    }

    private boolean invalid(Vec3d pos) {
        if (mc.world == null || mc.player == null) return true;
        // 修复：ClientWorld.getChunk 对未加载区块返回 EmptyChunk 而非 null，旧检查形同虚设
        if (!isChunkLoaded(pos)) return true;
        BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        Box box = mc.player.getBoundingBox().offset(pos.subtract(mc.player.getEntityPos()));
        for (BlockPos bPos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = mc.world.getBlockState(bPos);
            if (!state.getCollisionShape(mc.world, bPos).isEmpty() || state.isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    private boolean entityCheck(Entity entity) {
        if (mc.player == null) return false;
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