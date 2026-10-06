package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.*;

/**
 * Viết lại từ đầu (không có mã nguồn gốc). CHƯA BIÊN DỊCH.
 * Nạp .schem (Sponge v2/v3) và .nbt (vanilla structure), xây từ dưới lên,
 * tự đi bằng Baritone khi block ngoài tầm.
 * KHÔNG có Easy Place theo hướng block, KHÔNG đọc Litematica.
 */
public class AutoBuild extends Module {
    public enum SneakMode { Auto, Always, Never }

    private record BuildTask(BlockPos pos, BlockState state) {}

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> schematicPath = sg.add(new StringSetting.Builder()
        .name("schematic-file").description("Đường dẫn đầy đủ tới file .schem hoặc .nbt.")
        .defaultValue("").build());
    private final Setting<Integer> maxPerTick = sg.add(new IntSetting.Builder()
        .name("max-placements-per-tick").defaultValue(4).min(1).sliderMax(10).build());
    private final Setting<Integer> minDelayMs = sg.add(new IntSetting.Builder()
        .name("min-delay-ms").defaultValue(100).min(0).sliderMax(1000).build());
    private final Setting<Integer> maxDelayMs = sg.add(new IntSetting.Builder()
        .name("max-delay-ms").defaultValue(500).min(0).sliderMax(2000).build());
    private final Setting<Double> curvePower = sg.add(new DoubleSetting.Builder()
        .name("delay-curve-power").description("Lớn hơn = thiên về delay ngắn.")
        .defaultValue(2.0).min(0.1).sliderMax(5).build());
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").defaultValue(4.5).min(1).sliderMax(6).build());
    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").description("Gửi packet xoay đầu trước khi đặt.").defaultValue(true).build());
    private final Setting<Boolean> autoMove = sg.add(new BoolSetting.Builder()
        .name("auto-move").description("Tự đi tới block tiếp theo bằng Baritone.").defaultValue(true).build());
    private final Setting<SneakMode> sneakMode = sg.add(new EnumSetting.Builder<SneakMode>()
        .name("sneak-mode").description("Auto hiện tại = không sneak.").defaultValue(SneakMode.Auto).build());
    private final Setting<Integer> originX = sg.add(new IntSetting.Builder()
        .name("origin-x").defaultValue(0).noSlider().build());
    private final Setting<Integer> originY = sg.add(new IntSetting.Builder()
        .name("origin-y").defaultValue(64).noSlider().build());
    private final Setting<Integer> originZ = sg.add(new IntSetting.Builder()
        .name("origin-z").defaultValue(0).noSlider().build());
    private final Setting<Integer> resortTicks = sg.add(new IntSetting.Builder()
        .name("resort-interval-ticks").defaultValue(40).min(5).sliderMax(200).build());

    private final List<BuildTask> queue = new ArrayList<>();
    private volatile List<BuildTask> loaded;
    private final Random random = new Random();
    private long nextPlaceAt;
    private int tickCounter;

    public AutoBuild() {
        super(Categories.World, "auto-build", "Tự động xây theo schematic (.schem, .nbt).");
    }

    // ------------------------------------------------------------ vòng đời

    @Override
    public void onActivate() {
        queue.clear();
        loaded = null;
        File f = new File(schematicPath.get().trim().replace("\"", ""));
        if (!f.isFile()) { error("Không tìm thấy file schematic: " + f); toggle(); return; }
        BlockPos origin = new BlockPos(originX.get(), originY.get(), originZ.get());
        Thread t = new Thread(() -> {
            try {
                loaded = load(f, origin);
            } catch (Throwable e) {
                mc.execute(() -> { error("Lỗi đọc schematic: " + e); toggle(); });
            }
        }, "AutoBuild-loader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        loaded = null;
        try { AutoBuildHelper.stopMoving(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        List<BuildTask> l = loaded;
        if (l != null) {
            loaded = null;
            queue.clear();
            queue.addAll(l);
            sort();
            info("Đã nạp %d block.", queue.size());
        }
        if (queue.isEmpty()) return;
        if (++tickCounter % resortTicks.get() == 0) sort();

        long now = System.currentTimeMillis();
        if (now < nextPlaceAt) return;

        int placed = 0;
        BlockPos firstOut = null;
        Iterator<BuildTask> it = queue.iterator();
        while (it.hasNext() && placed < maxPerTick.get()) {
            BuildTask t = it.next();
            BlockState cur = mc.world.getBlockState(t.pos());
            if (cur.getBlock() == t.state().getBlock()) { it.remove(); continue; }
            if (!cur.isReplaceable()) { it.remove(); continue; }
            if (!AutoBuildHelper.inReach(t.pos(), range.get())) {
                if (firstOut == null) firstOut = t.pos();
                continue;
            }
            if (!selectItem(t.state().getBlock())) continue;
            boolean sneak = sneakMode.get() == SneakMode.Always;
            if (AutoBuildHelper.placeBlock(t.pos(), rotate.get(), sneak)) placed++;
        }

        if (firstOut == null) {
            if (AutoBuildHelper.isMoving()) AutoBuildHelper.stopMoving();
        } else if (autoMove.get() && placed == 0) {
            AutoBuildHelper.moveNear(firstOut, Math.max(1, range.get() - 1));
        }

        if (placed > 0) {
            double r = Math.pow(random.nextDouble(), curvePower.get());
            int lo = Math.min(minDelayMs.get(), maxDelayMs.get());
            int hi = Math.max(minDelayMs.get(), maxDelayMs.get());
            nextPlaceAt = now + lo + (long) ((hi - lo) * r);
        }
        if (queue.isEmpty()) { info("Xây xong."); toggle(); }
    }

    /** Thấp -> cao (nền trước, mái sau), cùng tầng thì gần người trước. */
    private void sort() {
        BlockPos p = mc.player.getBlockPos();
        queue.sort(Comparator.comparingInt((BuildTask t) -> t.pos().getY())
            .thenComparingDouble(t -> t.pos().getSquaredDistance(p)));
    }

    private boolean selectItem(Block block) {
        Item item = block.asItem();
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) {
                if (inv.getSelectedSlot() != i) inv.setSelectedSlot(i); // 1.21.5+
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ đọc schematic

    private static List<BuildTask> load(File f, BlockPos origin) throws Exception {
        NbtCompound root = NbtIo.readCompressed(f.toPath(), NbtSizeTracker.ofUnlimitedBytes());
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".schem")) return readSponge(root, origin);
        if (n.endsWith(".nbt")) return readStructure(root, origin);
        throw new IllegalArgumentException("Chỉ hỗ trợ .schem và .nbt");
    }

    private static List<BuildTask> readSponge(NbtCompound root, BlockPos origin) {
        NbtCompound s = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        int w = s.getInt("Width", 0), h = s.getInt("Height", 0), l = s.getInt("Length", 0);
        NbtCompound palNbt;
        byte[] data;
        if (s.contains("Blocks")) { // Sponge v3
            NbtCompound b = s.getCompoundOrEmpty("Blocks");
            palNbt = b.getCompoundOrEmpty("Palette");
            data = b.getByteArray("Data").orElse(new byte[0]);
        } else { // Sponge v2
            palNbt = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(new byte[0]);
        }
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : palNbt.getKeys()) {
            BlockState st = parseState(key);
            if (st != null) palette.put(palNbt.getInt(key, 0), st);
        }
        List<BuildTask> out = new ArrayList<>();
        int idx = 0, i = 0;
        while (i < data.length) {
            int v = 0, shift = 0, b;
            do {
                b = data[i++];
                v |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
            idx++;
            BlockState st = palette.get(v);
            if (st == null || st.isAir()) continue;
            out.add(new BuildTask(origin.add(x, y, z), st));
        }
        return out;
    }

    private static List<BuildTask> readStructure(NbtCompound root, BlockPos origin) {
        NbtList pal = root.getListOrEmpty("palette");
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            NbtCompound e = pal.getCompoundOrEmpty(i);
            StringBuilder sb = new StringBuilder(e.getString("Name", "minecraft:air"));
            NbtCompound props = e.getCompoundOrEmpty("Properties");
            if (!props.getKeys().isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (String k : props.getKeys()) {
                    if (!first) sb.append(',');
                    sb.append(k).append('=').append(props.getString(k, ""));
                    first = false;
                }
                sb.append(']');
            }
            states.add(parseState(sb.toString()));
        }
        List<BuildTask> out = new ArrayList<>();
        NbtList blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound b = blocks.getCompoundOrEmpty(i);
            NbtList p = b.getListOrEmpty("pos");
            int si = b.getInt("state", -1);
            if (si < 0 || si >= states.size()) continue;
            BlockState st = states.get(si);
            if (st == null || st.isAir()) continue;
            out.add(new BuildTask(origin.add(p.getInt(0, 0), p.getInt(1, 0), p.getInt(2, 0)), st));
        }
        return out;
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" -> BlockState */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState parseState(String s) {
        String name = s, props = "";
        int br = s.indexOf('[');
        if (br >= 0) { name = s.substring(0, br); props = s.substring(br + 1, s.length() - 1); }
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        Block block = Registries.BLOCK.get(id);
        BlockState st = block.getDefaultState();
        if (!props.isEmpty()) {
            for (String kv : props.split(",")) {
                String[] a = kv.split("=");
                if (a.length != 2) continue;
                Property p = block.getStateManager().getProperty(a[0]);
                if (p == null) continue;
                Optional o = p.parse(a[1]);
                if (o.isPresent()) st = st.with(p, (Comparable) o.get());
            }
        }
        return st;
    }
}
