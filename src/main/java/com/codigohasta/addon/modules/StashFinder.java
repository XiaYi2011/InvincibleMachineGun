/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.text.RunnableClickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.item.Items;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.util.*;

public class StashFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("坐标过滤");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final List<Block> DEFAULT_SUPPORT_BLOCK_BLACKLIST = List.of(
        Blocks.OXIDIZED_COPPER,
        Blocks.OXIDIZED_CUT_COPPER,
        Blocks.TUFF_BRICKS,
        Blocks.WAXED_COPPER_BLOCK,
        Blocks.WAXED_OXIDIZED_COPPER,
        Blocks.WAXED_OXIDIZED_CUT_COPPER,
        Blocks.BARREL,
        Blocks.WAXED_COPPER_BULB
    );

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("要搜索的存储方块（仅箱子有效）")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("每个箱子单独记录，此设置已无效")
        .defaultValue(1)
        .visible(() -> false)   // 隐藏，避免混淆
        .build()
    );

    private final Setting<List<Block>> blacklistedBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blacklisted-support-blocks")
        .description("忽略放置在这些方块上的容器")
        .defaultValue(DEFAULT_SUPPORT_BLOCK_BLACKLIST)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("发现新箱子时发送通知")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("通知模式")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    // 坐标过滤
    private final Setting<Double> targetX = sgFilter.add(new DoubleSetting.Builder()
        .name("target-x")
        .description("要筛选的 X 坐标，0 且范围为 0 时忽略此轴")
        .defaultValue(0.0)
        .build()
    );
    private final Setting<Double> targetY = sgFilter.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("要筛选的 Y 坐标，0 且范围为 0 时忽略此轴")
        .defaultValue(0.0)
        .build()
    );
    private final Setting<Double> targetZ = sgFilter.add(new DoubleSetting.Builder()
        .name("target-z")
        .description("要筛选的 Z 坐标，0 且范围为 0 时忽略此轴")
        .defaultValue(0.0)
        .build()
    );
    private final Setting<Double> filterRange = sgFilter.add(new DoubleSetting.Builder()
        .name("filter-range")
        .description("对已启用的坐标轴应用此半径，0 表示精确匹配")
        .defaultValue(0.0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> renderTracer = sgRender.add(new BoolSetting.Builder()
        .name("render-tracer")
        .description("渲染到箱子的追踪线")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> traceColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("追踪线颜色")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Integer> traceArrivalDistance = sgRender.add(new IntSetting.Builder()
        .name("tracer-hide-at-distance")
        .description("当距离小于该值时隐藏追踪线")
        .defaultValue(16)
        .min(1)
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Integer> traceMaxDistance = sgRender.add(new IntSetting.Builder()
        .name("tracer-max-distance")
        .description("当距离大于该值时隐藏追踪线")
        .defaultValue(2000)
        .min(10)
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Boolean> renderChunkColumn = sgRender.add(new BoolSetting.Builder()
        .name("render-chunk-column")
        .description("在箱子位置渲染垂直光柱")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> traceColumnColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-column-color")
        .description("光柱颜色")
        .defaultValue(new SettingColor(255, 215, 0, 100))
        .visible(renderChunkColumn::get)
        .build()
    );

    private final Setting<Keybind> clearTracesBind = sgRender.add(new KeybindSetting.Builder()
        .name("clear-traces-bind")
        .description("清除所有追踪线的快捷键")
        .defaultValue(Keybind.none())
        .build()
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 追踪点：键为箱子方块坐标，值为追踪线目标点（中心X,Z + 当前玩家眼睛Y）
    private final Map<BlockPos, Vec3d> tracerPositions = new HashMap<>();
    public List<ChestEntry> chests = new ArrayList<>();

    public StashFinder() {
        super(Categories.World, "stash-finder", "搜索已加载区块中的箱子，记录坐标并提供追踪与导航。");
    }

    @Override
    public void onActivate() {
        load();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (clearTracesBind.get().isPressed()) {
            tracerPositions.clear();
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        List<Block> blockBlacklist = blacklistedBlocks.get();
        boolean dirty = false;

        double tx = targetX.get();
        double ty = targetY.get();
        double tz = targetZ.get();
        double range = filterRange.get();

        boolean filterX = (tx != 0.0 || range != 0.0);
        boolean filterY = (ty != 0.0 || range != 0.0);
        boolean filterZ = (tz != 0.0 || range != 0.0);

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (!(blockEntity instanceof ChestBlockEntity)) continue;
            if (!storageBlocks.get().contains(blockEntity.getType())) continue;

            if (!blockBlacklist.isEmpty()) {
                BlockPos below = blockEntity.getPos().down();
                if (blockBlacklist.contains(event.chunk().getBlockState(below).getBlock())) continue;
            }

            BlockPos pos = blockEntity.getPos();

            // 坐标过滤
            if (filterX && Math.abs(pos.getX() - tx) > range) continue;
            if (filterY && Math.abs(pos.getY() - ty) > range) continue;
            if (filterZ && Math.abs(pos.getZ() - tz) > range) continue;

            // 创建新条目（去重基于方块坐标）
            ChestEntry entry = new ChestEntry(pos);
            ChestEntry old = null;
            int index = chests.indexOf(entry);
            if (index < 0) {
                chests.add(entry);
                dirty = true;
            } else {
                old = chests.set(index, entry);
            }

            // 更新追踪点
            if (renderTracer.get()) {
                double eyeY = mc.player != null ? mc.player.getEyeY() : 0.0;
                tracerPositions.put(pos, new Vec3d(pos.getX() + 0.5, eyeY, pos.getZ() + 0.5));
            }

            // 通知（仅当条目是新的或内容有变化时）
            if (sendNotifications.get() && (old == null || !entry.equals(old))) {
                switch (notificationMode.get()) {
                    case Chat -> sendChatNotification(entry);
                    case Toast -> {
                        MeteorToast toast = new MeteorToast.Builder(title).icon(Items.CHEST).text("发现箱子！").build();
                        mc.getToastManager().add(toast);
                    }
                    case Both -> {
                        sendChatNotification(entry);
                        MeteorToast toast = new MeteorToast.Builder(title).icon(Items.CHEST).text("发现箱子！").build();
                        mc.getToastManager().add(toast);
                    }
                }
            }
        }

        // 只在整个区块处理完后保存一次
        if (dirty) {
            saveJson();
            saveCsv();
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        // 按坐标排序（方便浏览）
        chests.sort(Comparator.comparingInt((ChestEntry e) -> e.x)
            .thenComparingInt(e -> e.y)
            .thenComparingInt(e -> e.z));

        WVerticalList list = theme.verticalList();

        WHorizontalList hl = theme.horizontalList();
        WButton clear = hl.add(theme.button("清除所有记录")).widget();
        WButton resetTracers = hl.add(theme.button("重置追踪线")).widget();
        list.add(hl);

        WTable table = new WTable();
        if (!chests.isEmpty()) list.add(table);

        clear.action = () -> {
            chests.clear();
            table.clear();
            tracerPositions.clear();
        };

        resetTracers.action = () -> {
            table.clear();
            tracerPositions.clear();
            fillTable(theme, table);
        };

        fillTable(theme, table);
        return list;
    }

    private void fillTable(GuiTheme theme, WTable table) {
        for (ChestEntry entry : chests) {
            BlockPos pos = entry.getPos();
            table.add(theme.label("箱子: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())).padRight(10);

            WCheckbox visible = table.add(theme.checkbox(tracerPositions.containsKey(pos))).widget();
            visible.action = () -> {
                if (visible.checked) {
                    double eyeY = mc.player != null ? mc.player.getEyeY() : 0.0;
                    tracerPositions.put(pos, new Vec3d(pos.getX() + 0.5, eyeY, pos.getZ() + 0.5));
                } else {
                    tracerPositions.remove(pos);
                }
            };

            WButton open = table.add(theme.button("详情")).widget();
            open.action = () -> mc.setScreen(new ChunkScreen(theme, entry));

            WButton gotoBtn = table.add(theme.button("导航")).widget();
            gotoBtn.action = () -> PathManagers.get().moveTo(pos, true);

            WMinus delete = table.add(theme.minus()).widget();
            delete.action = () -> {
                if (chests.remove(entry)) {
                    tracerPositions.remove(pos);
                    table.clear();
                    fillTable(theme, table);
                    saveJson();
                    saveCsv();
                }
            };

            table.row();
        }
    }

    private void load() {
        boolean loaded = false;
        File file = getJsonFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                chests = GSON.fromJson(reader, new TypeToken<List<ChestEntry>>() {}.getType());
                if (chests == null) chests = new ArrayList<>();
                loaded = true;
            } catch (Exception e) {
                MeteorClient.LOG.error("加载箱子 JSON 失败", e);
                chests = new ArrayList<>();
            }
        }

        if (!loaded) {
            file = getCsvFile();
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String header = reader.readLine(); // 跳过表头
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(",");
                        if (parts.length >= 3) {
                            try {
                                int x = Integer.parseInt(parts[0].trim());
                                int y = Integer.parseInt(parts[1].trim());
                                int z = Integer.parseInt(parts[2].trim());
                                chests.add(new ChestEntry(x, y, z));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } catch (Exception e) {
                    MeteorClient.LOG.error("加载箱子 CSV 失败", e);
                }
            }
        }
    }

    private void saveCsv() {
        try {
            File file = getCsvFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(file)) {
                writer.write("X,Y,Z\n");
                for (ChestEntry entry : chests) {
                    writer.write(entry.x + "," + entry.y + "," + entry.z + "\n");
                }
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("保存箱子 CSV 出错", e);
        }
    }

    private void saveJson() {
        try {
            File file = getJsonFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(file)) {
                GSON.toJson(chests, writer);
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("保存箱子 JSON 出错", e);
        }
    }

    private File getJsonFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "stashes"), Utils.getFileWorldName()), "chests.json");
    }

    private File getCsvFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "stashes"), Utils.getFileWorldName()), "chests.csv");
    }

    @Override
    public String getInfoString() {
        return String.valueOf(chests.size());
    }

    private void sendChatNotification(ChestEntry entry) {
        BlockPos pos = entry.getPos();
        MutableText coords = Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
            .setStyle(Style.EMPTY
                .withColor(Formatting.WHITE)
                .withFormatting(Formatting.UNDERLINE)
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("导航到箱子")))
                .withClickEvent(new RunnableClickEvent(() -> PathManagers.get().moveTo(pos, true))));

        MutableText message = Text.literal("发现箱子 ")
            .formatted(Formatting.GRAY)
            .append(Text.literal("[").formatted(Formatting.GRAY))
            .append(coords)
            .append(Text.literal("]").formatted(Formatting.GRAY));

        ChatUtils.sendMsg(message);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (tracerPositions.isEmpty() || mc.player == null) return;

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        // 移除已到达的追踪点
        tracerPositions.entrySet().removeIf(entry -> {
            Vec3d pos = entry.getValue();
            double horizontalDist = Math.hypot(pos.x - playerX, pos.z - playerZ);
            return horizontalDist <= traceArrivalDistance.get();
        });

        if (!renderTracer.get() && !renderChunkColumn.get()) return;

        for (Vec3d pos : tracerPositions.values()) {
            double horizontalDist = Math.hypot(pos.x - playerX, pos.z - playerZ);
            if (horizontalDist > traceMaxDistance.get()) continue;

            if (renderTracer.get()) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.x, mc.player.getEyeY(), pos.z, traceColor.get()
                );
            }

            if (renderChunkColumn.get()) {
                double x1 = pos.x - 0.5;
                double x2 = pos.x + 0.5;
                double z1 = pos.z - 0.5;
                double z2 = pos.z + 0.5;

                int bottomY = mc.world.getBottomY();
                int topY = bottomY + mc.world.getDimension().height();

                event.renderer.line(x1, bottomY, z1, x1, topY, z1, traceColumnColor.get());
                event.renderer.line(x1, bottomY, z2, x1, topY, z2, traceColumnColor.get());
                event.renderer.line(x2, bottomY, z1, x2, topY, z1, traceColumnColor.get());
                event.renderer.line(x2, bottomY, z2, x2, topY, z2, traceColumnColor.get());
            }
        }
    }

    public enum Mode { Chat, Toast, Both }

    // 新箱子记录类
    public static class ChestEntry {
        public int x, y, z;   // 方块坐标，可被 Gson 直接序列化

        public ChestEntry(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public ChestEntry(BlockPos pos) {
            this(pos.getX(), pos.getY(), pos.getZ());
        }

        public BlockPos getPos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChestEntry that = (ChestEntry) o;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }

    private static class ChunkScreen extends WindowScreen {
        private final ChestEntry entry;

        public ChunkScreen(GuiTheme theme, ChestEntry entry) {
            super(theme, "箱子详情");
            this.entry = entry;
        }

        @Override
        public void initWidgets() {
            WTable t = add(theme.table()).expandX().widget();
            t.add(theme.label("坐标: " + entry.x + ", " + entry.y + ", " + entry.z));
            t.row();
            // 可以添加更多信息（如距离等）这里保持简洁
        }
    }
}