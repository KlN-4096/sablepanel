package com.klnon.sablepanel.panel.preview.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.preview.protocol.Spm2Mesh;
import com.klnon.sablepanel.panel.preview.protocol.Spm2Record;

import java.util.List;

/** Complete, immutable structure data shared by protocol and model stages. */
public final class PreviewStructure {
    public record PaletteEntry(String id, String state, String en, String zh,
                                int color, int lightEmission, long count) {
    }

    /**
     * 一段连续体素外加一个绕轴旋转 —— 目前只有轴承上的 Create contraption 用得上。
     * <p>
     * 体素本身存装配姿态,当前角度不在这里施加:整数网格表示不了任意角度(硬取整会让方块
     * 互相重叠并打出空洞),所以角度作为组属性透传给前端,由前端逐实例乘一个绕轴矩阵。
     *
     * @param first 组内第一个体素在体素表里的下标(组内连续)
     * @param pivotX 旋转轴过的点,与体素同一坐标空间(即已经减掉 origin)
     * @param axis   {@code "x"}/{@code "y"}/{@code "z"},轴承朝向所在的轴
     * @param angle  角度,度
     */
    public record Group(int first, int count, int pivotX, int pivotY, int pivotZ, String axis, float angle) {
    }

    private final List<PaletteEntry> palette;
    /** 体素直接以线格式记录保存:此前另有一个同形的 Voxel 类型,每次编码都要整表转换一遍。 */
    private final List<Spm2Record> voxels;
    private final byte[] shellBitmap;
    private final int width;
    private final int height;
    private final int depth;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int plotX;
    private final int plotZ;
    private final String biome;
    private final List<Group> groups;

    public PreviewStructure(List<PaletteEntry> palette, List<Spm2Record> voxels, byte[] shellBitmap,
                            int originX, int originY, int originZ, int plotX, int plotZ, String biome,
                            List<Group> groups) {
        this.palette = List.copyOf(palette);
        this.groups = List.copyOf(groups);
        this.voxels = List.copyOf(voxels);
        this.shellBitmap = shellBitmap.clone();
        if (this.shellBitmap.length != (this.voxels.size() + 7) / 8) {
            throw new IllegalArgumentException("shell bitmap length does not match voxel count");
        }
        // 尺寸一趟算完:此前 width/height/depth 各自全量扫一遍体素表
        int maxX = 0, maxY = 0, maxZ = 0;
        for (Spm2Record voxel : this.voxels) {
            maxX = Math.max(maxX, voxel.x());
            maxY = Math.max(maxY, voxel.y());
            maxZ = Math.max(maxZ, voxel.z());
        }
        this.width = this.voxels.isEmpty() ? 0 : maxX + 1;
        this.height = this.voxels.isEmpty() ? 0 : maxY + 1;
        this.depth = this.voxels.isEmpty() ? 0 : maxZ + 1;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.plotX = plotX;
        this.plotZ = plotZ;
        this.biome = biome == null ? "" : biome;
    }

    public List<PaletteEntry> palette() {
        return this.palette;
    }

    public List<Spm2Record> voxels() {
        return this.voxels;
    }

    public byte[] shellBitmap() {
        return this.shellBitmap.clone();
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int depth() {
        return this.depth;
    }

    public Spm2Mesh toSpm2(JsonObject additions) {
        JsonObject metadata = metadataJson();
        if (additions != null) {
            for (var entry : additions.entrySet()) metadata.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return new Spm2Mesh(metadata.toString(), this.voxels, this.shellBitmap);
    }

    private JsonObject metadataJson() {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("origin_x", this.originX);
        metadata.addProperty("origin_y", this.originY);
        metadata.addProperty("origin_z", this.originZ);
        metadata.addProperty("plot_x", this.plotX);
        metadata.addProperty("plot_z", this.plotZ);
        metadata.addProperty("biome", this.biome);
        JsonObject biomeColors = new JsonObject();
        int[] colors = biomeColors(this.biome);
        biomeColors.addProperty("grass", colors[0]);
        biomeColors.addProperty("foliage", colors[1]);
        biomeColors.addProperty("water", colors[2]);
        metadata.add("biome_colors", biomeColors);
        metadata.addProperty("width", width());
        metadata.addProperty("height", height());
        metadata.addProperty("depth", depth());
        metadata.addProperty("voxel_count", this.voxels.size());
        if (!this.groups.isEmpty()) {
            JsonArray groups = new JsonArray();
            for (Group group : this.groups) {
                JsonObject value = new JsonObject();
                value.addProperty("first", group.first());
                value.addProperty("count", group.count());
                JsonArray pivot = new JsonArray();
                pivot.add(group.pivotX()); pivot.add(group.pivotY()); pivot.add(group.pivotZ());
                value.add("pivot", pivot);
                value.addProperty("axis", group.axis());
                value.addProperty("angle", group.angle());
                groups.add(value);
            }
            metadata.add("groups", groups);
        }

        JsonArray states = new JsonArray();
        for (PaletteEntry entry : this.palette) {
            JsonObject state = new JsonObject();
            state.addProperty("id", entry.id());
            state.addProperty("state", entry.state());
            state.addProperty("en", entry.en());
            state.addProperty("zh", entry.zh());
            state.addProperty("color", entry.color());
            state.addProperty("light_emission", entry.lightEmission());
            state.addProperty("count", entry.count());
            states.add(state);
        }
        metadata.add("states", states);
        return metadata;
    }

    private static int[] biomeColors(String biome) {
        return switch (biome) {
            case "minecraft:swamp" -> new int[]{0x6A7039, 0x6A7039, 0x617B64};
            case "minecraft:dark_forest" -> new int[]{0x507A32, 0x507A32, 0x3F76E4};
            case "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands" ->
                    new int[]{0x9E814D, 0x9E814D, 0x3F76E4};
            case "minecraft:desert", "minecraft:savanna", "minecraft:savanna_plateau" ->
                    new int[]{0xBFB755, 0xBFB755, 0x3F76E4};
            default -> new int[]{0x7FB238, 0x59C93C, 0x3F76E4};
        };
    }
}
