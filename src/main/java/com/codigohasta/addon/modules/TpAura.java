package com.codigohasta.addon.modules;

import com.codigohasta.addon.AddonTemplate;
import com.codigohasta.addon.mixin.InventoryAccessor;
import com.google.common.collect.ImmutableList;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TpAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("攻击冷却");
    private final SettingGroup sgTP = settings.createGroup("传送");
    private final SettingGroup sgAdvanced = settings.createGroup("高级设置");
    private final SettingGroup sgTargeting = settings.createGroup("目标");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // -------------------- 通用 --------------------

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("挥手")
        .description("攻击时摆动手臂。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("转头")
        .description("攻击前将视角转向目标。击退方向由服务器在处理攻击包那一刻记录的你的视角决定，不转头会把目标打向错误方向。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> silentRotate = sgGeneral.add(new BoolSetting.Builder()
        .name("静默转头")
        .description("只在攻击前的那一个位置包里附带转头，客户端视角完全不受影响；关闭后整个攻击期间视角都会跟随目标（KillAura 式）。")
        .defaultValue(true)
        .visible(rotate::get)
        .build());

    // 武器选择（完整复刻 KillAura）

    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("持有武器时攻击")
        .description("只有手中拿着指定物品时才会攻击。")
        .defaultValue(AttackItems.Weapons)
        .build());

    private final Setting<List<Item>> weapons = sgGeneral.add(new ItemListSetting.Builder()
        .name("武器类型")
        .description("允许用来攻击的武器种类（选择了钻石剑则任意剑均可）。")
        .defaultValue(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.TRIDENT)
        .filter(WEAPON_FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build());

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切换")
        .description("攻击时自动切换到合适的武器。")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("切回")
        .description("不再攻击时切回之前的物品栏格子。")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build());

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("盾牌模式")
        .description("目标举盾时怎么办：忽略=不攻击举盾的目标；破盾=自动切换斧头破盾（需开启自动切换）；正常=照常攻击。")
        .defaultValue(ShieldMode.None)
        .build());

    // -------------------- 攻击冷却（完整复刻 KillAura Timing） --------------------

    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("服务器卡顿时暂停")
        .description("服务器严重掉刻时暂停攻击。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("使用物品时暂停")
        .description("正在使用物品（吃饭、拉弓等）时不攻击。")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("水晶时暂停")
        .description("CrystalAura 放置水晶时不攻击。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS同步")
        .description("让攻击冷却与服务器 TPS 同步。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("自定义冷却")
        .description("使用自定义攻击间隔代替原版攻击冷却。")
        .defaultValue(false)
        .build());

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("攻击间隔")
        .description("两次攻击之间间隔多少 tick。")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build());

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("切换延迟")
        .description("切换物品栏格子后等待多少 tick 再攻击。")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build());

    // -------------------- 传送 --------------------

    public enum Mode { Vanilla, Paper }
    private final Setting<Mode> mode = sgTP.add(new EnumSetting.Builder<Mode>()
        .name("兼容模式")
        .description("目标服务器的移动限制模型。")
        .defaultValue(Mode.Paper)
        .build());

    private final Setting<Double> range = sgTP.add(new DoubleSetting.Builder()
        .name("范围")
        .description("可以攻击到的最远距离（同时用于目标筛选）。")
        .defaultValue(100)
        .min(1)
        .max(200)
        .build());

    private final Setting<TpMode> tpMode = sgTP.add(new EnumSetting.Builder<TpMode>()
        .name("传送模式")
        .description("直线：直接传送到目标旁；v-clip：按固定高度抬升；智能v-clip：自动搜索可达的抬升高度。")
        .defaultValue(TpMode.VClip)
        .build());

    private final Setting<Boolean> noThroughWalls = sgTP.add(new BoolSetting.Builder()
        .name("不穿墙")
        .description("直线模式下若路径穿墙则放弃攻击，防止返回时卡墙。")
        .defaultValue(true)
        .visible(() -> tpMode.get() == TpMode.Direct)
        .build());

    private final Setting<Double> vClipHeight = sgTP.add(new DoubleSetting.Builder()
        .name("v-clip高度")
        .description("v-clip 模式向上抬升的固定高度。")
        .defaultValue(22.0)
        .sliderMin(1)
        .sliderMax(64)
        .visible(() -> tpMode.get() == TpMode.VClip)
        .build());

    private final Setting<Integer> smartMaxAttempts = sgTP.add(new IntSetting.Builder()
        .name("v-clip搜索最大尝试次数")
        .description("智能 v-clip 从 max(玩家Y, 目标Y) 开始逐格向上搜索可达路径的最大尝试次数。")
        .defaultValue(50)
        .min(1)
        .sliderMax(100)
        .visible(() -> tpMode.get() == TpMode.SmartVClip)
        .build());

    private final Setting<Boolean> returnPos = sgTP.add(new BoolSetting.Builder()
        .name("攻击后回传")
        .description("攻击后沿相反路径传送回原位。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> antiLag = sgTP.add(new BoolSetting.Builder()
        .name("反拉回")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxAntiLagRetries = sgTP.add(new IntSetting.Builder()
        .name("每秒最多拉回次数")
        .defaultValue(20)
        .min(1)
        .sliderMax(20)
        .visible(antiLag::get)
        .build());

    private final Setting<Boolean> offsetFix = sgTP.add(new BoolSetting.Builder()
        .name("随机偏移")
        .description("发送微小偏移包防止拉回。")
        .defaultValue(true)
        .build());

    private final Setting<Double> maxSingleTpDist = sgTP.add(new DoubleSetting.Builder()
        .name("最大单次传送距离")
        .description("大于 0 时把单段传送细分为不超过该距离的多段。")
        .defaultValue(0.0)
        .min(0)
        .sliderMax(16)
        .build());

    // -------------------- 高级设置（Paper 移动限制严格程度，默认值即服务器当前值） --------------------

    private final Setting<Double> movedWronglyThreshold = sgAdvanced.add(new DoubleSetting.Builder()
        .name("moved-wrongly阈值")
        .description("本地模拟服务器 moved-wrongly 判定的阈值（spigot.yml moved-wrongly-threshold 默认 0.0625）。调大=校验更宽松、更可能被回弹；调小=更严格。")
        .defaultValue(0.0625)
        .min(0)
        .sliderMax(0.25)
        .build());

    private final Setting<Double> maxLegDist = sgAdvanced.add(new DoubleSetting.Builder()
        .name("单段传送距离上限")
        .description("单个传送包允许的最大距离（服务器硬限制 200 格）。")
        .defaultValue(200.0)
        .min(10)
        .sliderMax(400)
        .build());

    private final Setting<Double> distTolerance = sgAdvanced.add(new DoubleSetting.Builder()
        .name("累计距离容差")
        .description("补偿服务器 tick 起点位置与本地 startPos 之间的漂移（默认 3 格）。")
        .defaultValue(3.0)
        .min(0)
        .sliderMax(10)
        .build());

    private final Setting<Integer> paperBudget = sgAdvanced.add(new IntSetting.Builder()
        .name("Paper移动包预算")
        .description("Paper 每 tick 的移动包预算（默认 20）：垫包数 + 2×位置包数 不能超过该值。")
        .defaultValue(20)
        .min(2)
        .sliderMax(40)
        .visible(() -> mode.get() == Mode.Paper)
        .build());

    private final Setting<Double> paperPacketDist = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Paper每包距离")
        .description("Paper 按“距 tick 起点的累计距离 ≤ 每包距离 × 本 tick 移动包数”检查（默认 10 格/包）。")
        .defaultValue(10.0)
        .min(1)
        .sliderMax(20)
        .visible(() -> mode.get() == Mode.Paper)
        .build());

    // -------------------- 目标（完整复刻 KillAura Targeting，另加 Y 轴与受伤间隔过滤） --------------------

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("目标实体")
        .description("要攻击的实体。")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER)
        .build());

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("优先级")
        .description("范围内多个目标的排序方式。")
        .defaultValue(SortPriority.ClosestAngle)
        .build());

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("候选目标数")
        .description("每 tick 按优先级取前 N 个目标作为候选，攻击其中第一个路径可行的（传送光环每次只攻击一个目标）。")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 20)
        .build());

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("隔墙范围")
        .description("看不见目标时仍可攻击它的最大距离。KillAura 默认为 3.5，传送光环默认给大以免限制穿墙攻击。")
        .defaultValue(100)
        .min(0)
        .sliderMax(200)
        .build());

    private final Setting<Boolean> yFilter = sgTargeting.add(new BoolSetting.Builder()
        .name("Y轴过滤")
        .description("只攻击指定 Y 范围内的目标。")
        .defaultValue(true)
        .build());

    private final Setting<Double> minY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最小Y")
        .defaultValue(-64)
        .min(-2032)
        .max(2032)
        .visible(yFilter::get)
        .build());

    private final Setting<Double> maxY = sgTargeting.add(new DoubleSetting.Builder()
        .name("最大Y")
        .defaultValue(320)
        .min(-2032)
        .max(2032)
        .visible(yFilter::get)
        .build());

    private final Setting<Boolean> hurtFilter = sgTargeting.add(new BoolSetting.Builder()
        .name("受伤间隔过滤")
        .description("目标还在受伤冷却（受击无敌帧）中时跳过该目标，避免空刀。")
        .defaultValue(true)
        .build());

    private final Setting<EntityAge> passiveMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("被动生物年龄")
        .description("要攻击的被动生物（动物、村民）的年龄。")
        .defaultValue(EntityAge.Adult)
        .build());

    private final Setting<EntityAge> hostileMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("敌对生物年龄")
        .description("要攻击的敌对生物（僵尸、猪灵、疣猪兽、僵尸疣猪兽）的年龄。")
        .defaultValue(EntityAge.Both)
        .build());

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略命名")
        .description("不攻击被命名的生物。")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> ignorePassive = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略被动")
        .description("只攻击正在以你为目标的被动敌对生物（末影人、猪灵、狼等）。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("忽略驯服")
        .description("不攻击你驯服的生物。")
        .defaultValue(false)
        .build());

    // -------------------- 渲染 --------------------

    private final Setting<Boolean> renderPath = sgRender.add(new BoolSetting.Builder()
        .name("显示路径")
        .defaultValue(true)
        .build());
    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("轨迹颜色")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("目标颜色")
        .defaultValue(new SettingColor(0, 0, 0, 200))
        .build());
    private final Setting<Boolean> renderIntermediateNodes = sgRender.add(new BoolSetting.Builder()
        .name("渲染中间点")
        .defaultValue(true)
        .visible(() -> maxSingleTpDist.get() > 0)
        .build());
    private final Setting<Integer> renderTimeMs = sgRender.add(new IntSetting.Builder()
        .name("路径滞留(ms)")
        .description("攻击结束后路径继续显示的时间，期间渐隐。")
        .defaultValue(1200)
        .min(100)
        .sliderMax(5000)
        .build());

    // -------------------- 运行状态 --------------------
    private final List<Entity> targets = new ArrayList<>();
    private final List<Vec3d> renderPathNodes = new ArrayList<>();
    private int renderAttackIdx = -1;
    private long renderPathExpire = 0;
    private Entity renderTarget = null;
    private Entity currentTarget;

    // 武器切换状态（KillAura 语义）
    private int previousSlot = -1;
    private boolean swapped = false;

    // 攻击冷却状态（KillAura 语义）
    private int hitTimer = 0;
    private int switchTimer = 0;
    /** 自上一次攻击以来的 tick 数。本地复刻 PlayerEntity.ticksSinceLastAttack——我们直接发包绕过了客户端攻击流程，原版计数器不会被重置。 */
    private int ticksSinceAttack = 0;

    private Vec3d expectedPos = null;
    private int antiLagRetries = 0;
    private long lastAntiLagTime = 0;

    /** 纯 Vanilla 没有 allowedPlayerTicks，硬上限 5 包/tick */
    private static final int VANILLA_BUDGET = 5;
    /** 武器列表过滤器（与 KillAura 一致；矛是 1.21.11 新武器） */
    private static final ArrayList<Item> WEAPON_FILTER = new ArrayList<>(List.of(
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL,
        Items.DIAMOND_HOE, Items.MACE, Items.DIAMOND_SPEAR, Items.TRIDENT));

    public TpAura() {
        super(AddonTemplate.CATEGORY, "如来神掌", "从天而降的掌法。直线/v-clip/智能v-clip 三种传送模式，KillAura 式目标、武器与冷却。");
    }

    @Override
    public void onActivate() {
        previousSlot = -1;
        swapped = false;
        hitTimer = 0;
        switchTimer = 0;
        ticksSinceAttack = 10000; // 启用时视为冷却已满，第一刀可以立即出手
        targets.clear();
        currentTarget = null;
        renderPathNodes.clear();
        renderAttackIdx = -1;
        renderTarget = null;
        renderPathExpire = 0;
        expectedPos = null;
        antiLagRetries = 0;
    }

    @Override
    public void onDeactivate() {
        stopAttacking();
        expectedPos = null;
        currentTarget = null;
        renderTarget = null;
        renderPathNodes.clear();
        renderAttackIdx = -1;
        targets.clear();
    }

    // -------------------- 主循环 --------------------
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (ticksSinceAttack < 10000) ticksSinceAttack++;

        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameMode.SPECTATOR) {
            currentTarget = null;
            stopAttacking();
            return;
        }
        if (pauseOnUse.get() && (mc.interactionManager.isBreakingBlock() || mc.player.isUsingItem())) {
            currentTarget = null;
            stopAttacking();
            return;
        }
        if (pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1f) {
            currentTarget = null;
            stopAttacking();
            return;
        }
        if (pauseOnCA.get() && Modules.get().get(CrystalAura.class).isActive() && Modules.get().get(CrystalAura.class).kaTimer > 0) {
            currentTarget = null;
            stopAttacking();
            return;
        }

        targets.clear();
        TargetUtils.getList(targets, this::entityCheck, priority.get(), maxTargets.get());

        if (targets.isEmpty()) {
            currentTarget = null;
            stopAttacking();
            return;
        }

        Entity primary = targets.getFirst();

        // 武器选择（KillAura 逻辑：自动切换 + 举盾换斧）
        if (autoSwitch.get()) {
            FindItemResult weaponResult = new FindItemResult(selectedSlot(), -1);
            if (attackWhenHolding.get() == AttackItems.Weapons) weaponResult = InvUtils.find(this::acceptableWeapon, 0, 8);

            if (shouldShieldBreak()) {
                FindItemResult axeResult = InvUtils.find(itemStack -> itemStack.getItem() instanceof AxeItem, 0, 8);
                if (axeResult.found()) weaponResult = axeResult;
            }

            if (!swapped) {
                previousSlot = selectedSlot();
                swapped = true;
            }

            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!acceptableWeapon(mc.player.getMainHandStack())) {
            currentTarget = null;
            stopAttacking();
            return;
        }

        // 非静默转头：整个攻击期间视角跟随目标（KillAura RotationMode.Always 方式）。
        // 静默转头不在这里处理——它只被塞进攻击前的那个位置包里（见 executeRoute）。
        if (rotate.get() && !silentRotate.get()) {
            Rotations.rotate(Rotations.getYaw(primary), Rotations.getPitch(primary, Target.Body));
        }

        if (!delayCheck()) return;

        // 按优先级依次尝试候选目标，攻击第一个路径可行的
        boolean attacked = false;
        for (Entity t : targets) {
            currentTarget = t;
            if (executeTpAttack(t)) {
                attacked = true;
                hitTimer = 0;
                ticksSinceAttack = 0; // 复刻 attack() 重置攻击冷却
                break;
            }
        }

        if (!attacked) currentTarget = null;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    private void stopAttacking() {
        if (!swapped) return;
        swapped = false;
        if (swapBack.get()) InvUtils.swap(previousSlot, false);
        previousSlot = -1;
    }

    // -------------------- 武器选择（完整复刻 KillAura） --------------------
    private boolean shouldShieldBreak() {
        for (Entity target : targets) {
            if (target instanceof PlayerEntity player && player.isBlocking() && shieldMode.get() == ShieldMode.Break) {
                return true;
            }
        }
        return false;
    }

    private boolean acceptableWeapon(ItemStack stack) {
        if (shouldShieldBreak()) return stack.getItem() instanceof AxeItem;
        if (attackWhenHolding.get() == AttackItems.All) return true;

        if (weapons.get().contains(Items.DIAMOND_SWORD) && stack.isIn(ItemTags.SWORDS)) return true;
        if (weapons.get().contains(Items.DIAMOND_AXE) && stack.isIn(ItemTags.AXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_PICKAXE) && stack.isIn(ItemTags.PICKAXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_SHOVEL) && stack.isIn(ItemTags.SHOVELS)) return true;
        if (weapons.get().contains(Items.DIAMOND_HOE) && stack.isIn(ItemTags.HOES)) return true;
        if (weapons.get().contains(Items.MACE) && stack.getItem() instanceof MaceItem) return true;
        if (weapons.get().contains(Items.DIAMOND_SPEAR) && stack.isIn(ItemTags.SPEARS)) return true;
        return weapons.get().contains(Items.TRIDENT) && stack.getItem() instanceof TridentItem;
    }

    private int selectedSlot() {
        return ((InventoryAccessor) mc.player.getInventory()).getSelectedSlot();
    }

    // -------------------- 攻击冷却（完整复刻 KillAura 的 delayCheck） --------------------
    private boolean delayCheck() {
        if (switchTimer > 0) {
            switchTimer--;
            return false;
        }

        float delay = customDelay.get() ? hitDelay.get() : 0.5f;
        if (tpsSync.get()) delay /= (TickRate.INSTANCE.getTickRate() / 20);

        if (customDelay.get()) {
            if (hitTimer < delay) {
                hitTimer++;
                return false;
            } else return true;
        } else return getAttackCooldownProgress(delay) >= 1;
    }

    /**
     * 复刻 PlayerEntity.getAttackCooldownProgress：MathHelper.clamp((ticksSinceLastAttack + baseTime) / getAttackCooldownPeriod(), 0, 1)，
     * 其中 getAttackCooldownPeriod() = 1 / 攻击速度 * 20。使用本地计数器（见 ticksSinceAttack 注释）。
     */
    private float getAttackCooldownProgress(float baseTime) {
        float period = (float) (1.0D / mc.player.getAttributeValue(EntityAttributes.ATTACK_SPEED) * 20.0D);
        return MathHelper.clamp((ticksSinceAttack + baseTime) / period, 0.0F, 1.0F);
    }

    // -------------------- 攻击编排：先算路、先校验、后发包 --------------------
    private boolean executeTpAttack(Entity target) {
        if (mc.player == null || mc.world == null) return false;
        // 服务器对 sleeping 玩家 movedDist > 1 直接回传，跳过
        if (mc.player.isSleeping()) return false;

        Vec3d startPos = mc.player.getEntityPos();
        Vec3d targetCenter = target.getBoundingBox().getCenter();
        Vec3d finalPos = findNearestLegalToTarget(targetCenter, 6.0);
        if (finalPos == null) return false;

        List<Vec3d> route = new ArrayList<>();
        route.add(startPos);
        Vec3d attackPos = finalPos;

        switch (tpMode.get()) {
            case Direct -> {
                // 直线模式：保持原有逻辑
                if (!isWholeTpValid(startPos, finalPos)) return false;
                if (noThroughWalls.get() && isPathObstructed(startPos, finalPos)) return false;
                route.add(finalPos);
                appendReturn(route, startPos, finalPos);
            }
            case VClip -> {
                // v-clip 模式：保持原有逻辑（固定抬升高度）
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
            case SmartVClip -> {
                // 智能 v-clip：先判断能否直线传送，可以就用直线
                if (isWholeTpValid(startPos, finalPos)) {
                    route.add(finalPos);
                    appendReturn(route, startPos, finalPos);
                } else {
                    attackPos = buildSmartRoute(route, startPos, finalPos, target);
                    if (attackPos == null) return false;
                }
            }
        }

        // 可选：按最大单段距离细分（渲染节点与实际发包节点始终一致）
        if (maxSingleTpDist.get() > 0) route = subdivideRoute(route, maxSingleTpDist.get());

        int attackIdx = indexOfPos(route, attackPos);
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

    /**
     * 智能 v-clip 寻路。用 A(玩家)、B(A 正上方)、C(攻击点正上方)、D(攻击点) 表示 4 个端点，
     * 完整路径为 A→B→C→D→攻击→C→B→A。
     *
     * 搜索规则（无需 v-clip 高度设置）：
     *  - 抬升高度从 max(玩家Y, 目标Y) 开始，作为 v-clip 的绝对 y 坐标；
     *  - 检测整条路径是否可达，不可达就 y+1 再试，直到找到或超出最大尝试次数；
     *  - 若 C→目标 的距离小于 6（包括重合），服务器本就能接受该攻击，不需要点 D，直接从 C 攻击；
     *  - 若上升高度为 0（B 与 A 重合），跳过 B，避免原地传送；
     *  - 回传仍保持完全相反的路径。
     *
     * @return 攻击发生的位置（D 或 C），找不到可达路径返回 null
     */
    private @Nullable Vec3d buildSmartRoute(List<Vec3d> route, Vec3d A, Vec3d D, Entity target) {
        Vec3d targetPos = target.getEntityPos();
        double yBase = Math.max(A.y, targetPos.y);
        int attempts = smartMaxAttempts.get();

        for (int i = 0; i < attempts; i++) {
            double y = yBase + i;
            Vec3d B = new Vec3d(A.x, y, A.z);
            Vec3d C = new Vec3d(D.x, y, D.z);

            if (isObstructed(B) || isObstructed(C)) continue;

            boolean rise = y - A.y > 1.0E-6;
            if (rise) {
                // A→B 是垂直 v-clip（Y 弱检测恒过），B→C 是水平段（受 moved-wrongly 约束）
                if (!isWholeTpValid(A, B) || !isWholeTpValid(B, C)) continue;
            } else {
                // 上升高度为 0：B 与 A 重合，跳过 B 直接走 A→C，避免原地传送
                if (!isWholeTpValid(A, C)) continue;
            }

            // 服务器攻击判定为 distanceToSqr < 36（6 格）：C 距目标小于 6 时不需要再降到 D
            boolean needD = C.squaredDistanceTo(targetPos) >= 36.0;
            if (needD && C.squaredDistanceTo(D) > 1.0E-6 && !isWholeTpValid(C, D)) continue;

            // 组去程路径
            if (rise) route.add(B);
            route.add(C);
            Vec3d attackPos = C;
            if (needD && C.squaredDistanceTo(D) > 1.0E-6) {
                route.add(D);
                attackPos = D;
            }

            // 回传：完全相反的路径
            if (returnPos.get()) {
                for (int j = route.size() - 2; j >= 0; j--) route.add(route.get(j));
                if (offsetFix.get()) {
                    Vec3d off = getOffset(A);
                    if (off != null) route.add(off);
                }
            } else if (offsetFix.get()) {
                Vec3d off = getOffset(attackPos);
                if (off != null) route.add(off);
            }
            return attackPos;
        }
        return null;
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
            if (padding + 2 * posPackets > paperBudget.get()) return false;
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
            boolean attackNode = i == attackIdx && target != null;

            if (attackNode && rotate.get()) {
                // 转头：把“从攻击点看向目标”的朝向塞进攻击前的这同一个位置包。
                // 服务器按收包顺序处理，处理攻击包时用的就是这个 yaw —— 击退方向因此正确。
                // 静默转头只发这一个包；非静默另由 onTick 里的 Rotations.rotate 持续跟随。
                float[] rot = getAttackRotation(n, target);
                if (paper) {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(n.x, n.y, n.z, rot[0], rot[1], true, mc.player.horizontalCollision));
                } else {
                    sendMoveLook(n, rot[0], rot[1]);
                }
            } else if (paper) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(n.x, n.y, n.z, true, mc.player.horizontalCollision));
            } else {
                sendMove(n);
            }

            if (attackNode) {
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
     * 计算从攻击位置看向目标身体中心的 yaw/pitch。
     * 不能用 Rotations.getYaw(target)——那是从玩家真实位置算的，而服务器认为我们在攻击点上，
     * 用错起点会让击退方向完全跑偏。
     */
    private float[] getAttackRotation(Vec3d from, Entity target) {
        double tx = target.getX();
        double ty = target.getY() + target.getHeight() / 2.0;
        double tz = target.getZ();

        double dx = tx - from.x;
        double dy = ty - (from.y + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = tz - from.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, distXZ)));
        return new float[]{yaw, pitch};
    }

    /**
     * Paper 垫包数：服务器按"距 tick 起点的累计距离 <= 每包距离 * 本 tick 移动包数"检查。
     * 第 k 个位置包发出时已有 padding + k 个包，因此对每个节点 k 需满足
     * cumDist_k <= 每包距离 * (padding + k)，取所有 k 的最大值。
     */
    private int paperPadding(List<Vec3d> route) {
        int padding = 0;
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + distTolerance.get();
            padding = Math.max(padding, (int) Math.ceil(cum / paperPacketDist.get() - k));
        }
        return Math.max(padding, 0);
    }

    /** 纯 Vanilla 为线性检查：movedDist - expectedDist > 100 * deltaPackets */
    private int vanillaPadding(List<Vec3d> route) {
        int padding = 0;
        for (int k = 1; k < route.size(); k++) {
            double cum = route.get(0).distanceTo(route.get(k)) + distTolerance.get();
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

    private void sendMoveLook(Vec3d pos, float yaw, float pitch) {
        if (mc.player == null) return;
        PlayerMoveC2SPacket packet = new PlayerMoveC2SPacket.Full(pos.x, pos.y, pos.z, yaw, pitch, false, false);
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
        return startPos.squaredDistanceTo(endPos) < maxLegDist.get() * maxLegDist.get() &&
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
        if (dist < 0.01 || dist > range.get() + vClipHeight.get() + smartMaxAttempts.get() + 10) {
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
        if (padding + 2 > (mode.get() == Mode.Paper ? paperBudget.get() : VANILLA_BUDGET)) {
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
        Box box = mc.player.getBoundingBox().offset(pos.subtract(mc.player.getEntityPos()));
        for (BlockPos bPos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = mc.world.getBlockState(bPos);
            if (!state.getCollisionShape(mc.world, bPos).isEmpty() || state.isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    // -------------------- 目标筛选（完整复刻 KillAura，另加 Y 轴与受伤间隔过滤） --------------------
    private boolean entityCheck(Entity entity) {
        if (mc.player == null) return false;
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDead()) || !entity.isAlive()) return false;

        Box hitbox = entity.getBoundingBox();
        if (!PlayerUtils.isWithin(
            MathHelper.clamp(mc.player.getX(), hitbox.minX, hitbox.maxX),
            MathHelper.clamp(mc.player.getY(), hitbox.minY, hitbox.maxY),
            MathHelper.clamp(mc.player.getZ(), hitbox.minZ, hitbox.maxZ),
            range.get()
        )) return false;

        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;

        // Y轴过滤（默认开启）
        if (yFilter.get()) {
            double y = entity.getY();
            if (y < minY.get() || y > maxY.get()) return false;
        }
        // 受伤间隔过滤（默认开启）：目标还在受伤冷却（hurtTime > 0）时跳过，避免空刀
        if (hurtFilter.get() && entity instanceof LivingEntity living && living.hurtTime > 0) return false;

        if (!PlayerUtils.canSeeEntity(entity) && !PlayerUtils.isWithin(entity, wallsRange.get())) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwner() != null
                && tameable.getOwner().equals(mc.player)
            ) return false;
        }
        if (ignorePassive.get()) {
            if (entity instanceof EndermanEntity enderman && !enderman.isAngry()) return false;
            if ((entity instanceof PiglinEntity || entity instanceof ZombifiedPiglinEntity || entity instanceof WolfEntity) && !((MobEntity) entity).isAttacking()) return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && player.isBlocking()) return false;
            if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            // 有幼年变种的敌对生物（僵尸、猪灵、疣猪兽、僵尸疣猪兽）
            if (entity instanceof ZombieEntity || entity instanceof PiglinEntity
                || entity instanceof HoglinEntity || entity instanceof ZoglinEntity) {
                return switch (hostileMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
            // 有幼年变种的被动生物（动物、村民）
            if (entity instanceof PassiveEntity && (!(entity instanceof FrogEntity || entity instanceof ParrotEntity))) {
                return switch (passiveMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
        }
        return true;
    }

    @Override
    public String getInfoString() {
        return currentTarget != null ? EntityUtils.getName(currentTarget) : null;
    }

    // -------------------- 枚举 --------------------
    public enum TpMode {
        Direct("直线模式"),
        VClip("v-clip模式"),
        SmartVClip("智能v-clip模式");

        private final String title;
        TpMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum AttackItems {
        Weapons("武器"),
        All("任意物品");

        private final String title;
        AttackItems(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum ShieldMode {
        Ignore("忽略"),
        Break("破盾"),
        None("正常");

        private final String title;
        ShieldMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum EntityAge {
        Baby("幼年"),
        Adult("成年"),
        Both("全部");

        private final String title;
        EntityAge(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
}