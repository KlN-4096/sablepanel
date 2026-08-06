package com.klnon.sablepanel.panel.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 临时文件+原子换名的唯一实现。写崩(停电/进程被杀)只会留下 .tmp,目标文件要么是旧的
 * 完整内容要么是新的完整内容 —— 配置/状态类 JSON 半份落盘等于下次启动整个面板报废。
 */
public final class AtomicIo {
    private AtomicIo() {
    }

    public static void writeString(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        move(temporary, target);
    }

    /** 文件系统不支持原子移动时退化为普通替换(仍是先写全再换名) */
    public static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
