package com.klnon.sablepanel.panel.preview.resources;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModResourceStackTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    @Test
    void laterLayersOverrideAndClosureFollowsModelsAndTextures() throws Exception {
        Path vanilla = archive(Map.of(
                "assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}",
                "assets/minecraft/models/block/stone.json", "{\"textures\":{\"all\":\"minecraft:block/stone\"}}",
                "assets/minecraft/textures/block/stone.png", "vanilla"));
        Path mod = archive(Map.of(
                "assets/minecraft/models/block/stone.json", "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"test:block/polished\"}}",
                "assets/minecraft/models/block/cube_all.json", "{\"elements\":[]}",
                "assets/test/textures/block/polished.png", "mod-texture"));

        try (ModResourceStack stack = new ModResourceStack(vanilla, List.of(mod))) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of(
                    "assets/minecraft/blockstates/stone.json"));

            assertArrayEquals(PNG, stack.read("assets/test/textures/block/polished.png"));
            assertTrue(bundle.entries().stream().anyMatch(entry ->
                    entry.path().equals("assets/test/textures/block/polished.png") && entry.layer().equals("mod-0")));
            assertTrue(bundle.entries().stream().anyMatch(entry ->
                    entry.path().equals("assets/minecraft/models/block/cube_all.json")));
            assertFalse(bundle.shards().isEmpty());
            assertTrue(bundle.shards().stream().allMatch(shard -> shard.bytes().length <= ModResourceStack.MAX_SHARD_BYTES));
            assertTrue(bundle.manifestJson().contains("\"fingerprint\""));
        }
    }

    @Test
    void indexAndBundleAreDeterministicAndRejectUnsafePaths() throws Exception {
        Path vanilla = archive(Map.of("assets/minecraft/blockstates/air.json", "{}"));
        try (ModResourceStack first = new ModResourceStack(vanilla, List.of());
             ModResourceStack second = new ModResourceStack(vanilla, List.of())) {
            assertEquals(first.fingerprint(), second.fingerprint());
            assertEquals(first.closure(Set.of("assets/minecraft/blockstates/air.json")).manifestJson(),
                    second.closure(Set.of("assets/minecraft/blockstates/air.json")).manifestJson());
            assertThrows(IllegalArgumentException.class, () -> first.read("../secrets.txt"));
            assertThrows(IllegalArgumentException.class, () -> first.closure(Set.of("https://example.test/file")));
        }
    }

    @Test
    void objClosureIncludesReferencedMtlAndDiffuseTexture() throws Exception {
        Path vanilla = archive(Map.of(
                "assets/test/models/block/part.obj", "mtllib part.mtl\nv 0 0 0\n",
                "assets/test/models/block/part.mtl", "newmtl body\nmap_Kd ../textures/block/body.png\nnewmtl named\nmap_Kd -clamp on test:block/named\n",
                "assets/test/models/textures/block/body.png", "texture",
                "assets/test/textures/block/named.png", "named-texture"));
        try (ModResourceStack stack = new ModResourceStack(vanilla, List.of())) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of("assets/test/models/block/part.obj"));
            assertTrue(bundle.entries().stream().anyMatch(entry -> entry.path().endsWith("part.mtl")));
            assertTrue(bundle.entries().stream().anyMatch(entry -> entry.path().endsWith("body.png")));
            assertTrue(bundle.entries().stream().anyMatch(entry -> entry.path().equals("assets/test/textures/block/named.png")));
        }
    }

    @Test
    void invalidPngAndObjLimitsAreExcludedFromTheClosure() throws Exception {
        byte[] oversizedPng = PNG.clone();
        ByteBuffer.wrap(oversizedPng).putInt(16, 4097);
        String tooManyFaces = "v 0 0 0\n" + "f 1 1 1\n".repeat(50_001);
        String tooManyMaterials = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "newmtl m" + index).collect(java.util.stream.Collectors.joining("\n"));
        Path archive = archiveBytes(Map.of(
                "assets/test/textures/block/large.png", oversizedPng,
                "assets/test/models/block/faces.obj", tooManyFaces.getBytes(StandardCharsets.UTF_8),
                "assets/test/models/block/materials.mtl", tooManyMaterials.getBytes(StandardCharsets.UTF_8),
                "assets/test/models/block/nan.obj", "v NaN 0 0\n".getBytes(StandardCharsets.UTF_8)));
        try (ModResourceStack stack = new ModResourceStack(archive, List.of())) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of(
                    "assets/test/textures/block/large.png", "assets/test/models/block/faces.obj",
                    "assets/test/models/block/materials.mtl", "assets/test/models/block/nan.obj"));

            assertTrue(bundle.entries().isEmpty());
            assertEquals(4, bundle.failures().size());
        }
    }

    /**
     * 打不开的模组层只跳过,不能让整份闭包失败。
     * <p>
     * 真机上抓到的:开发运行下 {@code ModList.getModFiles()} 会给出一批
     * 通过了 {@code Files.isRegularFile} 却无法作为 ZipFile 打开的路径(实测 180 个模组里 88 个),
     * 此前任意一个这样的层都会让整份闭包抛异常 —— 结果是所有预览永久停在低保真,
     * 而服务端一行日志都没有。
     */
    @Test
    void unreadableLayerIsSkippedInsteadOfFailingTheWholeClosure() throws Exception {
        Path vanilla = archive(Map.of(
                "assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}",
                "assets/minecraft/models/block/stone.json", "{\"textures\":{\"all\":\"minecraft:block/stone\"}}",
                "assets/minecraft/textures/block/stone.png", "vanilla"));
        Path notAZip = Files.createTempFile("preview-bogus", ".jar");
        Files.writeString(notAZip, "这不是 zip");

        try (ModResourceStack stack = new ModResourceStack(vanilla, List.of(notAZip))) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of(
                    "assets/minecraft/blockstates/stone.json"));

            assertFalse(bundle.entries().isEmpty(), "坏层之外的资源必须照常进入闭包");
            assertTrue(bundle.entries().stream().anyMatch(entry ->
                    entry.path().equals("assets/minecraft/textures/block/stone.png")));
            assertArrayEquals(PNG, stack.read("assets/minecraft/textures/block/stone.png"));
        }
    }

    /**
     * jar-in-jar 打包的模组没有独立的磁盘路径,加载器给回的是已挂载文件系统里的一个
     * {@code assets} 目录。这类层此前被 {@code Files.isRegularFile} 挡掉、当 zip 打必然
     * FileNotFoundException —— 实盘 269 个模组文件里 88 个是这样,它们的 blockstate
     * 全部缺失,预览只能退回纯色。目录层必须与归档层等价:同样能索引、能读、能进闭包、
     * 能被后面的层覆盖。
     */
    @Test
    void mountedAssetDirectoriesAreIndexedLikeArchives() throws Exception {
        Path vanilla = archive(Map.of(
                "assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"sim:block/sail\"}}}",
                "assets/sim/models/block/sail.json", "{\"textures\":{\"all\":\"sim:block/vanilla_loses\"}}"));
        Path nested = Files.createTempDirectory("preview-jij").resolve("assets");
        write(nested, "sim/models/block/sail.json", "{\"textures\":{\"all\":\"sim:block/sail\"}}");
        write(nested, "sim/textures/block/sail.png", null);

        try (ModResourceStack stack = new ModResourceStack(vanilla, List.of(nested))) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of("assets/minecraft/blockstates/stone.json"));

            assertArrayEquals(PNG, stack.read("assets/sim/textures/block/sail.png"),
                    "目录层的资源必须能按 assets/ 前缀取回");
            assertTrue(bundle.entries().stream().anyMatch(entry ->
                    entry.path().equals("assets/sim/textures/block/sail.png") && entry.layer().equals("mod-0")),
                    "目录层的纹理必须进闭包");
            assertTrue(bundle.missing().isEmpty(),
                    "目录层的模型必须覆盖掉原版层那份,否则闭包会去找不存在的 vanilla_loses");
        }
    }

    @Test
    void itemAssemblyClosureIncludesSiblingStaticVariants() throws Exception {
        Path archive = archive(Map.of(
                "assets/test/blockstates/propeller.json",
                "{\"variants\":{\"\":{\"model\":\"test:block/propeller/block\"}}}",
                "assets/test/models/item/propeller.json",
                "{\"parent\":\"test:block/propeller/item\"}",
                "assets/test/models/block/propeller/item.json",
                "{\"textures\":{\"all\":\"test:block/propeller\"},\"elements\":[]}",
                "assets/test/models/block/propeller/block.json",
                "{\"textures\":{\"all\":\"test:block/propeller\"},\"elements\":[]}",
                "assets/test/models/block/propeller/rotor.json",
                "{\"textures\":{\"all\":\"test:block/propeller\"},\"elements\":[]}",
                "assets/test/models/block/propeller/rotor_reversed.json",
                "{\"textures\":{\"all\":\"test:block/propeller\"},\"elements\":[]}",
                "assets/test/models/block/propeller/nested/hidden.json", "{\"elements\":[]}",
                "assets/test/models/block/propeller/nested/deep/buried.json", "{\"elements\":[]}",
                "assets/test/textures/block/propeller.png", "texture"));

        try (ModResourceStack stack = new ModResourceStack(archive, List.of())) {
            ModResourceStack.Bundle bundle = stack.closure(Set.of(
                    "assets/test/blockstates/propeller.json"));
            Set<String> paths = bundle.entries().stream().map(ModResourceStack.Entry::path)
                    .collect(java.util.stream.Collectors.toSet());

            assertTrue(paths.contains("assets/test/models/block/propeller/rotor.json"),
                    "完整物品模型所在目录的静态部件必须进入资源闭包");
            assertTrue(paths.contains("assets/test/models/block/propeller/rotor_reversed.json"),
                    "同拓扑状态变体必须与物品模型一起可供保守推导");
            assertTrue(paths.contains("assets/test/models/block/propeller/nested/hidden.json"),
                    "一层子目录的部件模型(管道 connection/rim 形态)必须进入闭包");
            assertFalse(paths.contains("assets/test/models/block/propeller/nested/deep/buried.json"),
                    "只放宽一层子目录，不能递归吞入任意模型树");
        }
    }

    @Test
    void itemAssemblySiblingScanHasADeterministicCountLimit() throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("assets/test/blockstates/machine.json",
                "{\"variants\":{\"\":{\"model\":\"test:block/machine/part69\"}}}");
        entries.put("assets/test/models/item/machine.json",
                "{\"parent\":\"test:block/machine/item\"}");
        entries.put("assets/test/models/block/machine/item.json", "{\"elements\":[]}");
        for (int index = 0; index < 70; index++) {
            entries.put("assets/test/models/block/machine/part%02d.json".formatted(index),
                    "{\"elements\":[]}");
        }

        try (ModResourceStack stack = new ModResourceStack(archive(entries), List.of())) {
            Set<String> paths = stack.closure(Set.of("assets/test/blockstates/machine.json"))
                    .entries().stream().map(ModResourceStack.Entry::path)
                    .collect(java.util.stream.Collectors.toSet());

            assertTrue(paths.contains("assets/test/models/block/machine/part63.json"));
            assertFalse(paths.contains("assets/test/models/block/machine/part64.json"),
                    "静态组装 sibling 扫描必须有固定上限");
            assertTrue(paths.contains("assets/test/models/block/machine/part69.json"),
                    "blockstate 正式引用不能被可选 sibling 上限截掉");
        }
    }

    @Test
    void itemAssemblySiblingScanHasADirectByteLimit() throws Exception {
        String largeModel = "{\"elements\":[]}" + " ".repeat(1_100_000);
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("assets/test/blockstates/large_machine.json",
                "{\"variants\":{\"\":{\"model\":\"test:block/large_machine/base\"}}}");
        entries.put("assets/test/models/item/large_machine.json",
                "{\"parent\":\"test:block/large_machine/item\"}");
        entries.put("assets/test/models/block/large_machine/item.json", "{\"elements\":[]}");
        entries.put("assets/test/models/block/large_machine/a.json", largeModel);
        entries.put("assets/test/models/block/large_machine/b.json", largeModel);
        entries.put("assets/test/models/block/large_machine/base.json", "{\"elements\":[]}");

        try (ModResourceStack stack = new ModResourceStack(archive(entries), List.of())) {
            Set<String> paths = stack.closure(Set.of("assets/test/blockstates/large_machine.json"))
                    .entries().stream().map(ModResourceStack.Entry::path)
                    .collect(java.util.stream.Collectors.toSet());

            assertTrue(paths.contains("assets/test/models/block/large_machine/a.json"));
            assertFalse(paths.contains("assets/test/models/block/large_machine/b.json"),
                    "可选 sibling 的 JSON 总量必须受独立字节预算约束");
            assertTrue(paths.contains("assets/test/models/block/large_machine/base.json"),
                    "正式 blockstate 引用不受可选 sibling 字节预算影响");
        }
    }

    @Test
    void oversizedAssemblySiblingDoesNotHideLaterSmallCandidates() throws Exception {
        String oversized = "{\"elements\":[]}" + " ".repeat(2_100_000);
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("assets/test/blockstates/machine.json",
                "{\"variants\":{\"\":{\"model\":\"test:block/machine/base\"}}}");
        entries.put("assets/test/models/item/machine.json",
                "{\"parent\":\"test:block/machine/item\"}");
        entries.put("assets/test/models/block/machine/item.json", "{\"elements\":[]}");
        entries.put("assets/test/models/block/machine/a_oversized.json", oversized);
        entries.put("assets/test/models/block/machine/b_small.json", "{\"elements\":[]}");
        entries.put("assets/test/models/block/machine/base.json", "{\"elements\":[]}");

        try (ModResourceStack stack = new ModResourceStack(archive(entries), List.of())) {
            Set<String> paths = stack.closure(Set.of("assets/test/blockstates/machine.json"))
                    .entries().stream().map(ModResourceStack.Entry::path)
                    .collect(java.util.stream.Collectors.toSet());

            assertFalse(paths.contains("assets/test/models/block/machine/a_oversized.json"));
            assertTrue(paths.contains("assets/test/models/block/machine/b_small.json"),
                    "单个过大的可选 sibling 只能跳过自身，不能截断后续合法候选");
        }
    }

    @Test
    void optionalAssemblyResourcesCannotCrowdRequiredClosure() throws Exception {
        String blockstate = "{\"variants\":{\"\":{\"model\":\"test:block/machine\"}}}";
        String model = "{\"textures\":{\"all\":\"test:block/machine\"},\"elements\":[]}";
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("assets/test/blockstates/machine.json", blockstate.getBytes(StandardCharsets.UTF_8));
        entries.put("assets/test/models/block/machine.json", model.getBytes(StandardCharsets.UTF_8));
        entries.put("assets/test/textures/block/machine.png", PNG);
        entries.put("assets/test/models/item/machine.json",
                ("{\"parent\":\"test:block/machine/item\"}" + " ".repeat(256)).getBytes(StandardCharsets.UTF_8));
        entries.put("assets/test/models/block/machine/item.json", "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8));
        Path archive = archiveBytes(entries);
        long requiredBytes = blockstate.getBytes(StandardCharsets.UTF_8).length
                + model.getBytes(StandardCharsets.UTF_8).length + PNG.length;

        try (ModResourceStack stack = new ModResourceStack(
                List.of(new ModResourceStack.Layer("test", archive)), requiredBytes)) {
            Set<String> paths = stack.closure(Set.of("assets/test/blockstates/machine.json"))
                    .entries().stream().map(ModResourceStack.Entry::path)
                    .collect(java.util.stream.Collectors.toSet());

            assertTrue(paths.contains("assets/test/blockstates/machine.json"));
            assertTrue(paths.contains("assets/test/models/block/machine.json"));
            assertTrue(paths.contains("assets/test/textures/block/machine.png"));
            assertFalse(paths.contains("assets/test/models/item/machine.json"),
                    "总预算不足时必须先舍弃可选组装资源，不能丢正式引用闭包");
        }
    }

    @Test
    void mountedDirectoryFingerprintChangesWhenSameSizeContentChangesWithSameTimestamp() throws Exception {
        Path assets = Files.createTempDirectory("preview-fingerprint").resolve("assets");
        Path model = assets.resolve("test/models/block/value.json");
        long timestamp = 1_700_000_000_000L;
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{\"value\":1}");
        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(timestamp));
        String first;
        try (ModResourceStack stack = new ModResourceStack(
                List.of(new ModResourceStack.Layer("mounted", assets)))) {
            first = stack.fingerprint();
        }

        Files.writeString(model, "{\"value\":2}");
        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(timestamp));
        try (ModResourceStack stack = new ModResourceStack(
                List.of(new ModResourceStack.Layer("mounted", assets)))) {
            assertNotEquals(first, stack.fingerprint(),
                    "目录层同路径、同大小、同时间戳的内容更新后不能继续复用旧资源闭包");
        }
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, value == null ? PNG : value.getBytes(StandardCharsets.UTF_8));
    }

    private static Path archive(Map<String, String> entries) throws Exception {
        Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
        entries.forEach((path, value) -> bytes.put(path,
                path.endsWith(".png") ? PNG : value.getBytes(StandardCharsets.UTF_8)));
        return archiveBytes(bytes);
    }

    private static Path archiveBytes(Map<String, byte[]> entries) throws Exception {
        Path file = Files.createTempFile("preview-resources", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }
}
