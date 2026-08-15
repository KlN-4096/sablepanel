package com.klnon.sablepanel.panel.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 世界存档与回收站共用的压缩体/解压后 NBT 硬上限。 */
public final class BoundedNbtIo {
    public static final long MAX_COMPRESSED_BYTES = 64L << 20;
    private static final long MAX_ACCOUNTED_BYTES = 128L << 20;

    private BoundedNbtIo() {
    }

    public static CompoundTag readCompressed(Path file) throws IOException {
        requireCompressedSize(file);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            return readCompressed(input, file.toString());
        }
    }

    public static CompoundTag readCompressed(byte[] payload) throws IOException {
        if (payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("压缩 NBT 条目超过 64 MiB: " + payload.length + " 字节");
        }
        return readCompressed(new ByteArrayInputStream(payload), "存储槽位");
    }

    public static void requireCompressedSize(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_COMPRESSED_BYTES) {
            throw new IOException("压缩 NBT 文件超过 64 MiB: " + file + " (" + size + " 字节)");
        }
    }

    private static CompoundTag readCompressed(InputStream input, String source) throws IOException {
        try {
            return NbtIo.readCompressed(input, NbtAccounter.create(MAX_ACCOUNTED_BYTES));
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("NBT 解压超过 128 MiB 或格式无效: " + source, error);
        }
    }
}
