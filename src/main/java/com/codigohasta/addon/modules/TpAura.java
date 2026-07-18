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
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
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
    private final SettingGroup sgAdvanced = settings.createGroup("高级");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // ---- 攻击机制 ----
    private final Setting<Integer> attackDelayMs = sgTiming.add(new IntSetting.Builder()
        .name("额外延迟(ms)").defaultValue(50).min(0).sliderMax(5000).build());

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切武器").defaultValue(false).build());
    private final Setting<Boolean> requireMace = sgGeneral.add(new BoolSetting.Builder()
        .name("仅手持重锤").defaultValue(false).build());
    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("挥手").defaultValue(true).build());
    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("静默切换").defaultValue(false).visible(autoSwitch::get).build());

    // ---- 转头（新增） ----
    public enum RotationMode {
        None,
        OnHit,
        Always
    }
    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("转头模式")
        .description("攻击时是否旋转视角以获得正确击退方向")
        .defaultValue(RotationMode.OnHit)
        .build());
    private final Setting<Boolean> silentRotate = sgGeneral.add(new BoolSetting.Builder()
        .name("静默转头")
        .description("仅在攻击那一刻旋转，攻击后立即恢复原视角")
        .defaultValue(true)
        .visible(() -> rotation.get() != RotationMode.None)
        .build());

    // ---- 传送 ----
    public enum TpMode {
        Straight,
        VClip,
        SmartVClip
    }
    private final Setting<TpMode> tpMode = sgTP.add(new EnumSetting.Builder<TpMode>()
        .name("传送模式")
        .description("选择路径生成策略")
        .defaultValue(TpMode.SmartVClip)
        .build());

    private final Setting<Double> maxRange = sgTP.add(new DoubleSetting.Builder()
        .name("最大攻击范围").defaultValue(100).min(1).max(200).build());

    private final Setting<Boolean> returnPos = sgTP.add(new BoolSetting.Builder()
        .name("攻击后回传").defaultValue(true).build());

    // 直线模式专用：不穿墙
    private final Setting<Boolean> noThroughWalls = sgTP.add(new BoolSetting.Builder()
        .name("不穿墙")
        .description("直线模式下若路径穿墙则放弃攻击")
        .defaultValue(true)
        .visible(() -> tpMode.get() == TpMode.Straight && returnPos.get())
        .build());

    // V-Clip 模式专用：V-Clip 高度
    private final Setting<Double> vClipHeight = sgTP.add(new DoubleSetting.Builder()
        .name("V-Clip 高度")
        .defaultValue(22.0).sliderMin(1).sliderMax(64)
        .visible(() -> tpMode.get() == TpMode.VClip)
        .build());

    // 智能 V-Clip 专用：最大搜索尝试次数
    private final Setting<Integer> smartVClipMaxAttempts = sgTP.add(new IntSetting.Builder()
        .name("V-Clip 搜索最大尝试次数")
        .defaultValue(50).min(1).sliderMax(100)
        .visible(() -> tpMode.get() == TpMode.SmartVClip)
        .build());

    private final Setting<Boolean> offsetFix = sgTP.add(new BoolSetting.Builder()
        .name("随机偏移").defaultValue(true).build());
    private final Setting<Boolean> antiLag = sgTP.add(new BoolSetting.Builder()
        .name("反拉回").defaultValue(true).build());
    private final Setting<Integer> maxAntiLagRetries = sgTP.add(new IntSetting.Builder()
        .name("每秒最多拉回次数").defaultValue(20).min(1).sliderMax(20).build());
    private final Setting<Double> maxSingleTpDist = sgTP.add(new DoubleSetting.Builder()
        .name("最大单次传送距离").defaultValue(0.0).min(0).sliderMax(16).build());

    // ---- 目标 ----
    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("目标实体").defaultValue(Collections.singleton(EntityType.PLAYER)).build());
    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略好友").defaultValue(false).build());
    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略命名").defaultValue(false).build());
    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略驯服").defaultValue(false).build());
    private final Setting<Boolean> enableYFilter = sgTargeting.add(new BoolSetting.Builder()
        .name("启用Y轴过滤").defaultValue(false).build());
    private final Setting<Double> minY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最小Y").defaultValue(-64).min(-2032).max(2032).visible(enableYFilter::get).build());
    private final Setting<Double> maxY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最大Y").defaultValue(320).min(-2032).max(2032).visible(enableYFilter::get).build());
    // 新增：受伤间隔过滤
    private final Setting<Boolean> ignoreHurtResistant = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略受伤无敌")
        .description("跳过仍处于受伤无敌帧的目标")
        .defaultValue(true)
        .build());

    // ---- 白名单 ----
    public enum ListMode { Whitelist, Blacklist, Off }
    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("名单模式").defaultValue(ListMode.Off).build());
    private final Setting<String> playerList = sgWhitelist.add(new StringSetting.Builder()
        .name("玩家列表").defaultValue("").build());

    // ---- 高级（可调 Paper 移动限制） ----
    private final Setting<Double> movedWronglyThreshold = sgAdvanced.add(new DoubleSetting.Builder()
        .name("moved-wrongly 阈值")
        .description("服务器 moved-wrongly 检查的平方距离阈值 (默认0.0625)")
        .defaultValue(0.0625D).min(0.0D).sliderMax(1.0D).build());
    private final Setting<Integer> paperBudget = sgAdvanced.add(new IntSetting.Builder()
        .name("Paper 移动包预算")
        .description("Paper 每 tick 允许的最大移动包相关计数 (默认20)")
        .defaultValue(20).min(1).sliderMax(100).build());
    private final Setting<Integer> vanillaBudget = sgAdvanced.add(new IntSetting.Builder()
        .name("Vanilla 移动包预算")
        .description("纯 Vanilla 每 tick 最大位置包数量 (默认5)")
        .defaultValue(5).min(1).sliderMax(20).build());
    private final Setting<Double> distTolerance = sgAdvanced.add(new DoubleSetting.Builder()
        .name("距离容差")
        .description("补偿服务器 tick 起点位置漂移的累计距离容差 (默认3.0)")
        .defaultValue(3.0D).min(0.0D).sliderMax(10.0D).build());

    // ---- 渲染 ----
    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("显示路径").defaultValue(true).build());
    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("轨迹颜色").defaultValue(new SettingColor(0, 0, 0, 100)).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("目标颜色").defaultValue(new SettingColor(0, 0, 0, 200)).build());
    private final Setting<Boolean> renderIntermediateNodes = sgRender.add(new BoolSetting.Builder()
        .name("渲染中间点").defaultValue(true).visible(() -> maxSingleTpDist.get() > 0).build());
    private final Setting<Integer> renderTimeMs = sgRender.add(new IntSetting.Builder()
        .name("路径滞留(ms)").defaultValue(1200).min(100).sliderMax(5000).build());

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

    /** 单段传送硬上限 200 格 */
    private static final double MAX_LEG_DIST_SQR = 40000.0D;

    public TpAura() {
        super(AddonTemplate.CATEGORY, "如来神掌", "By-Rgxaa");
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

    // -------------------- 武器切换 --------------------
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

    // -------------------- 攻击编排 --------------------
    private boolean executeTrouserAttack(Entity target) {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isSleeping()) return false;

        Vec3d startPos = mc.player.getEntityPos();
        Vec3d targetCenter = target.getBoundingBox().getCenter();
        Vec3d finalPos = findNearestLegalToTarget(targetCenter, 6.0);
        if (finalPos == null) return false;

        List<Vec3d> route = new ArrayList<>();
        TpMode mode = tpMode.get();

        if (mode == TpMode.Straight) {
            // 直线模式
            if (!isWholeTpValid(startPos, finalPos)) return false;
            if (noThroughWalls.get() && isPathObstructed(startPos, finalPos)) return false;
            route.add(startPos);
            route.add(finalPos);
            appendReturn(route, startPos, finalPos);
        } else if (mode == TpMode.VClip) {
            // 固定 V-Clip 高度
            double vh = vClipHeight.get();
            Vec3d highStart = startPos.add(0, vh, 0);
            Vec3d highTarget = finalPos.add(0, vh, 0);
            if (isObstructed(highStart) || isObstructed(highTarget)) return false;
            if (!isWholeTpValid(startPos, highStart) || !isWholeTpValid(highStart, highTarget) || !isWholeTpValid(highTarget, finalPos))
                return false;
            route.add(startPos);
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
            // 智能 V-Clip
            // 1. 先尝试直线
            if (isWholeTpValid(startPos, finalPos)
                && !(noThroughWalls.get() && isPathObstructed(startPos, finalPos))) {
                route.add(startPos);
                route.add(finalPos);
                appendReturn(route, startPos, finalPos);
            } else {
                // 2. 智能寻找 V-Clip 高度
                double playerY = startPos.getY();
                double targetY = finalPos.getY();
                double baseY = Math.max(playerY, targetY);
                int maxAttempts = smartVClipMaxAttempts.get();
                boolean found = false;
                for (int attempt = 0; attempt < maxAttempts; attempt++) {
                    double h = baseY + attempt;
                    Vec3d B = new Vec3d(startPos.getX(), h, startPos.getZ());
                    Vec3d C = new Vec3d(finalPos.getX(), h, finalPos.getZ());

                    // 避免原地传送：上升高度为0时B与startPos相同，可以省略B
                    boolean skipB = (h == playerY && B.distanceTo(startPos) < 0.001);

                    if (!skipB && isObstructed(B)) continue;
                    if (isObstructed(C)) continue;

                    if (!skipB && !isWholeTpValid(startPos, B)) continue;
                    if (!isWholeTpValid(skipB ? startPos : B, C)) continue;
                    if (!isWholeTpValid(C, finalPos)) continue;

                    // 找到合法路径
                    route.add(startPos);
                    if (!skipB) route.add(B);
                    route.add(C);
                    // 如果C到finalPos距离很近（<6），C本身就可以作为攻击点，不需要再单独加finalPos，但我们已经有了finalPos，所以还是加入
                    route.add(finalPos);

                    if (returnPos.get()) {
                        route.add(C);
                        if (!skipB) route.add(B);
                        route.add(startPos);
                        if (offsetFix.get()) {
                            Vec3d off = getOffset(startPos);
                            if (off != null) route.add(off);
                        }
                    } else if (offsetFix.get()) {
                        Vec3d off = getOffset(finalPos);
                        if (off != null) route.add(off);
                    }
                    found = true;
                    break;
                }
                if (!found) return false;
            }
        }

        // 按最大单段距离细分
        if (maxSingleTpDist.get() > 0) route = subdivideRoute(route, maxSingleTpDist.get());

        int attackIdx = indexOfPos(route, finalPos);
        if (attackIdx < 1) return false;

        if (!executeRoute(route, attackIdx, target)) return false;

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

    // -------------------- 传送执行器（含转头） --------------------
    private boolean executeRoute(List<Vec3d> route, int attackIdx, @Nullable Entity target) {
        if (mc.player == null || mc.world == null || route.size() < 2) return false;

        // 校验全部路段
        for (int i = 0; i < route.size() - 1; i++) {
            if (!isWholeTpValid(route.get(i), route.get(i + 1))) return false;
        }

        boolean paper = mode.get() == Mode.Paper; // 注意：需要保留兼容模式设置，但原有 Mode 枚举还在，这里假定 Mode 枚举已经移除或整合，原代码中有 Mode 设置，我们保留但不再使用，改用 tpMode。但 executeRoute 仍需要区分 Paper/Vanilla 发包方式，需要保留 mode 设置。用户要求中保留了“兼容模式”吗？原代码中有 mode 设置 (Vanilla/Paper)，但新要求并未提及移除。我们应保留 mode 设置，但在 executeRoute 中使用。所以需要把 Mode 设置留下。但需求只说了 Paper 模式下的修改，没有说去掉 Vanilla。所以保留 mode 设置，放在 sgTP 中。修改代码时保留原有 mode 设置。

        // 预算计算
        int posPackets = route.size() - 1;
        int padding = paper ? paperPadding(route) : vanillaPadding(route);

        if (paper) {
            if (padding + 2 * posPackets > paperBudget.get()) return false;
        } else {
            if (padding + posPackets > vanillaBudget.get()) return false;
        }

        if (mc.player.isSneaking()) sendUnsneak();

        // 如果开启了转头且不是 None
        boolean doRotate = rotation.get() != RotationMode.None;
        float prevYaw = mc.player.getYaw();
        float prevPitch = mc.player.getPitch();
        double rotYaw = prevYaw, rotPitch = prevPitch;
        if (doRotate && target != null) {
            rotYaw = Rotations.getYaw(target);
            rotPitch = Rotations.getPitch(target, Target.Body);
            if (rotation.get() == RotationMode.Always) {
                // Always 模式：预先旋转
                Rotations.rotate(rotYaw, rotPitch);
            }
        }

        // 垫包
        for (int i = 0; i < padding; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }

        // 逐节点位置包
        for (int i = 1; i < route.size(); i++) {
            Vec3d n = route.get(i);
            if (paper) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(n.x, n.y, n.z, true, mc.player.horizontalCollision));
            } else {
                sendMove(n);
            }

            // 攻击点处理
            if (i == attackIdx && target != null) {
                // 如果需要转头（OnHit 或 Always 但未预转，这里再保证）
                if (doRotate) {
                    Rotations.rotate(rotYaw, rotPitch); // 确保朝向正确（Always 已转过，但不影响）
                }
                if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
                mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

                // 静默转头：攻击后恢复原视角
                if (doRotate && silentRotate.get() && rotation.get() != RotationMode.Always) {
                    Rotations.rotate(prevYaw, prevPitch);
                }
            }
        }

        // Always 模式下攻击后如果 silentRotate 也需要恢复（Already handled? 但 Always 模式在攻击前已经转了，攻击后需要恢复）
        if (doRotate && silentRotate.get() && rotation.get() == RotationMode.Always) {
            Rotations.rotate(prevYaw, prevPitch);
        }

        // 同步客户端状态
        expectedPos = route.get(route.size() - 1);
        if (!returnPos.get()) {
            mc.player.setPosition(expectedPos.x, expectedPos.y, expectedPos.z);
            mc.player.setVelocity(Vec3d.ZERO);
        }
        return true;
    }

    // ---- 垫包计算（使用高级设置） ----
    private int paperPadding(List<Vec3d> route) {
        int padding = 0;
        double tol = distTolerance.get();
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + tol;
            padding = Math.max(padding, (int) Math.ceil(cum / 10.0 - k));
        }
        return Math.max(padding, 0);
    }

    private int vanillaPadding(List<Vec3d> route) {
        int padding = 0;
        double tol = distTolerance.get();
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + tol;
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

    // ---- 智能 V-Clip 辅助（已整合到 executeTrouserAttack） ----
    // 移除原来的 findSafeHighTarget，逻辑在智能模式中直接实现

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

    // -------------------- 服务器移动检测复刻（使用可调阈值） --------------------
    private boolean isWholeTpValid(Vec3d startPos, Vec3d endPos) {
        if (mc.player == null || mc.world == null) return false;
        return startPos.squaredDistanceTo(endPos) < MAX_LEG_DIST_SQR &&
               isChunkLoaded(endPos) &&
               !isWrongMove(startPos, endPos) &&
               !isObstructed(endPos);
    }

    private boolean isWrongMove(Vec3d startPos, Vec3d endPos) {
        return getSquaredMovementDelta(startPos, endPos) > movedWronglyThreshold.get();
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

    // -------------------- 反拉回（使用可调阈值） --------------------
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || !antiLag.get() || expectedPos == null) return;
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;
        if (System.currentTimeMillis() - lastAntiLagTime > 1000) antiLagRetries = 0;

        Vec3d serverPos = packet.change().position();
        double dist = serverPos.distanceTo(expectedPos);
        if (dist < 0.01 || dist > maxRange.get() + (tpMode.get() == TpMode.VClip ? vClipHeight.get() : 50) + 10) {
            expectedPos = null;
            return;
        }
        if (antiLagRetries >= maxAntiLagRetries.get()) {
            expectedPos = null;
            return;
        }
        if (!isWholeTpValid(serverPos, expectedPos)) {
            expectedPos = null;
            return;
        }
        List<Vec3d> route = List.of(serverPos, expectedPos);
        int padding = mode.get() == Mode.Paper ? paperPadding(route) : vanillaPadding(route);
        int budget = mode.get() == Mode.Paper ? paperBudget.get() : vanillaBudget.get();
        if (padding + 2 > budget) {
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

        long remain = renderPathExpire - now;
        float fade = remain < 300 ? Math.max(0f, remain / 300f) : 1f;

        Color lineColor = faded(pathColor.get(), fade);
        for (int i = 0; i < renderPathNodes.size() - 1; i++) {
            Vec3d n1 = renderPathNodes.get(i);
            Vec3d n2 = renderPathNodes.get(i + 1);
            event.renderer.line(n1.x, n1.y, n1.z, n2.x, n2.y, n2.z, lineColor);
        }

        if (renderAttackIdx >= 0 && renderAttackIdx < renderPathNodes.size()) {
            Vec3d hit = renderPathNodes.get(renderAttackIdx);
            Color c = faded(targetColor.get(), fade);
            event.renderer.box(hit.x - 0.3, hit.y, hit.z - 0.3, hit.x + 0.3, hit.y + 1.8, hit.z + 0.3, c, c, ShapeMode.Both, 0);
        }

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
        return null;
    }

    private boolean invalid(Vec3d pos) {
        if (mc.world == null || mc.player == null) return true;
        if (!isChunkLoaded(pos)) return true;
        BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        Box box = mc.player.getBoundingBox().offset(pos.subtract(mc.player.getEntityPos()));
        for (BlockPos bPos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = mc.world.getBlockState(bPos);
            if (!state.getCollisionShape(mc.world, bPos).isEmpty() || state.isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    // -------------------- 目标过滤（重写自 KillAura 逻辑 + 受伤间隔） --------------------
    private boolean entityCheck(Entity entity) {
        if (mc.player == null) return false;
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!(entity instanceof LivingEntity) || !entity.isAlive() || ((LivingEntity) entity).isDead()) return false;

        // 实体类型白名单
        if (!entities.get().contains(entity.getType())) return false;

        // 距离检查
        if (mc.player.distanceTo(entity) > maxRange.get()) return false;

        // Y 轴过滤
        if (enableYFilter.get()) {
            double y = entity.getY();
            if (y < minY.get() || y > maxY.get()) return false;
        }

        // 受伤间隔过滤
        if (ignoreHurtResistant.get() && ((LivingEntity) entity).hurtTime > 0) return false;

        // 忽略命名
        if (ignoreNamed.get() && entity.hasCustomName()) return false;

        // 忽略驯服
        if (ignoreTamed.get() && entity instanceof TameableEntity tameable && tameable.isTamed()) return false;

        // 玩家特殊处理
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
            if (!Friends.get().shouldAttack(player)) return false;

            // 名单模式
            String name = player.getName().getString();
            List<String> list = Arrays.stream(playerList.get().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (listMode.get() == ListMode.Whitelist && !list.contains(name)) return false;
            if (listMode.get() == ListMode.Blacklist && list.contains(name)) return false;
        }

        return true;
    }

    @Override
    public String getInfoString() {
        return currentTarget != null ? EntityUtils.getName(currentTarget) : null;
    }

    // 保留兼容模式设置（Paper/Vanilla）
    public enum Mode { Vanilla, Paper }
    private final Setting<Mode> mode = sgTP.add(new EnumSetting.Builder<Mode>()
        .name("兼容模式").defaultValue(Mode.Paper).build());
}