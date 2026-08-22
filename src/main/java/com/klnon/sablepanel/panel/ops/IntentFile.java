package com.klnon.sablepanel.panel.ops;

import com.google.gson.Gson;
import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.storage.AtomicIo;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 意图集合的落盘({@code config/sablepanel/<name>}):Pause/Freeze/Force/Physics 服务共用,
 * 此前各手抄一份。读写失败只记日志绝不抛 —— 起服路径上宁可丢意图也不能拦启动。
 */
final class IntentFile {
    private final String name;

    IntentFile(String name) {
        this.name = name;
    }

    private Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sablepanel").resolve(this.name);
    }

    /** 文件缺失/损坏返回空表;条目里混进 null 时只丢那一条。 */
    List<String> load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return List.of();
            String[] arr = new Gson().fromJson(Files.readString(f), String[].class);
            return arr == null ? List.of() : Arrays.stream(arr).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: loading {} failed", this.name, e);
            return List.of();
        }
    }

    /** UUID 意图集读入:解析不了的条目静默丢弃;非空时按 {@code what} 记一条恢复日志。 */
    Set<UUID> loadUuids(String what) {
        Set<UUID> out = new HashSet<>();
        for (String s : load()) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!out.isEmpty()) {
            SablePanel.LOGGER.info("sablepanel: {} {} restored from disk", out.size(), what);
        }
        return out;
    }

    /** 快照与写盘同锁:并发 persist 时后进锁者重取最新集合,先写的新状态不会被旧快照盖掉。 */
    synchronized void saveUuids(Collection<UUID> values) {
        save(values.stream().map(UUID::toString).toList());
    }

    synchronized void save(Collection<String> values) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            AtomicIo.writeString(f, new Gson().toJson(values.stream().sorted().toList()));
        } catch (Exception e) {
            SablePanel.LOGGER.warn("sablepanel: saving {} failed", this.name, e);
        }
    }
}
