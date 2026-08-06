package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.panel.data.DiskScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static void writePointerFile(Path file, int count) throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("pointers", IntStream.range(0, count).toArray());
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
