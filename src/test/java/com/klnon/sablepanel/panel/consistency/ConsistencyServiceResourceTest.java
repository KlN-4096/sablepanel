package com.klnon.sablepanel.panel.consistency;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistencyServiceResourceTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path root;

    @Test
    void danglingPointerCollectionStopsAtThePublishedIssueLimitPlusSentinel() throws Exception {
        Path directory = Files.createDirectories(this.root.resolve("sublevels"));
        writePointerFile(directory.resolve("r.0.0.slvlr"), 10_002);

        Method scan = ConsistencyService.class.getDeclaredMethod(
                "danglingPointers", Map.class, Set.class, List.class);
        scan.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> issues = (List<?>) scan.invoke(null, Map.of(DIMENSION, directory), Set.of(), new ArrayList<>());

        assertEquals(10_001, issues.size(),
                "响应只发布 10,000 条时,不能先把全部异常物化后才截断");
    }

    @Test
    void restoredPayloadIsSkippedByFinalPointerCheck() throws Exception {
        Path directory = Files.createDirectories(this.root.resolve("sublevels"));
        writePointerFile(directory.resolve("r.0.0.slvlr"), 1);
        Method scan = ConsistencyService.class.getDeclaredMethod(
                "danglingPointers", Map.class, Set.class, List.class);
        scan.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> issues = (List<?>) scan.invoke(null,
                Map.of(DIMENSION, directory), Set.of(), new ArrayList<>());
        assertEquals(1, issues.size());

        ByteBuffer header = ByteBuffer.allocate(4096);
        header.putInt(0, 1); // autosave 在最终提交前重新占用了 storage=0,index=0
        Files.write(directory.resolve("r.0.0.0.slvls"), header.array());
        Method verify = ConsistencyService.class.getDeclaredMethod(
                "stillDangling", Map.class, List.class, Set.class);
        verify.setAccessible(true);
        Set<String> skipped = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        List<?> remaining = (List<?>) verify.invoke(null,
                Map.of(DIMENSION, directory), issues, skipped);

        assertTrue(remaining.isEmpty(), "payload 已恢复时不能再删除对应 holding 指针");
        assertEquals(1, skipped.size());
        assertTrue(skipped.iterator().next().startsWith("pointer:"));
    }

    @Test
    void finalPointerCheckRejectsTooManyStorageFiles() throws Exception {
        Path directory = Files.createDirectories(this.root.resolve("sublevels"));
        int[] pointers = IntStream.range(0, 65).map(storage -> storage << 16).toArray();
        writePointerFile(directory.resolve("r.0.0.slvlr"), pointers);
        Method scan = ConsistencyService.class.getDeclaredMethod(
                "danglingPointers", Map.class, Set.class, List.class);
        scan.setAccessible(true);
        List<?> issues = (List<?>) scan.invoke(null,
                Map.of(DIMENSION, directory), Set.of(), new ArrayList<>());
        Method bound = ConsistencyService.class.getDeclaredMethod("requirePointerRepairBounded", List.class);
        bound.setAccessible(true);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> bound.invoke(null, issues));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(error.getCause().getMessage().contains("分批选择"));
    }

    @Test
    void metadataPersistenceSavesOnlyChangedDimensions() {
        List<String> saved = new ArrayList<>();

        ConsistencyService.saveChangedMetadata(Set.of(DIMENSION), Map.of(
                DIMENSION, () -> saved.add(DIMENSION),
                "minecraft:the_nether", () -> saved.add("minecraft:the_nether")));

        assertEquals(List.of(DIMENSION), saved);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> ConsistencyService.saveChangedMetadata(Set.of(DIMENSION),
                        Map.of(DIMENSION, () -> { throw new RuntimeException("disk failure"); })));
        assertEquals("disk failure", failure.getMessage());
    }

    private static void writePointerFile(Path file, int count) throws Exception {
        writePointerFile(file, IntStream.range(0, count).toArray());
    }

    private static void writePointerFile(Path file, int[] pointers) throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pointers", pointers);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, new DataOutputStream(encoded));
        byte[] payload = encoded.toByteArray();

        int sectorSize = 128;
        int headerSectors = 4096 / sectorSize;
        int sectors = Math.max(1, (5 + payload.length + sectorSize - 1) / sectorSize);
        assertTrue(sectors <= 255, "测试记录必须能装进一个 span");
        ByteBuffer contents = ByteBuffer.allocate((headerSectors + sectors) * sectorSize);
        contents.putInt(0, (headerSectors << 8) | sectors);
        contents.position(4096);
        contents.putInt(payload.length + 1);
        contents.put((byte) 0);
        contents.put(payload);
        Files.write(file, contents.array());
    }
}
