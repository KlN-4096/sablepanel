package com.klnon.sablepanel.panel.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
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

    /**
     * 文件系统不支持原子移动时退化为普通替换(仍是先写全再换名)。
     * Windows 上杀毒/索引器会瞬时占住刚写完的文件或目录,rename 抛 AccessDenied
     * (2026-08-22 实机:回收站 .pending 目录转正被咬,整个组件级联进 recovery_required);
     * 同卷 rename 无中间态,短退避重试安全。
     */
    public static void move(Path source, Path target) throws IOException {
        AccessDeniedException denied = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException error) {
                denied = error;
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    error.addSuppressed(interrupted);
                    throw error;
                }
            }
        }
        throw denied;
    }
}
