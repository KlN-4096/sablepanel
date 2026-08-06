package com.klnon.sablepanel.panel.service;

import com.klnon.sablepanel.panel.data.DiskScanner;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次扫盘会话:dims 与 meta 必须成对产生、成对使用。从前"取维度目录+扫条目元数据"
 * 两行三件套在 service 层逐字重复 13 处,过期时还容易只重扫一半。
 */
record ScanSession(Map<String, Path> dims, Map<UUID, List<DiskScanner.EntryMeta>> meta) {

    static ScanSession strict(MinecraftServer server, List<String> warnings) throws IOException {
        Map<String, Path> dims = DiskScanner.sublevelDirsStrict(server);
        return new ScanSession(dims, DiskScanner.scanEntryMetaStrict(dims, warnings));
    }

    /** 先失效缓存再扫:验收/回滚路径必须看见最新盘面 */
    static ScanSession fresh(MinecraftServer server, List<String> warnings) throws IOException {
        DiskScanner.invalidateCache();
        return strict(server, warnings);
    }

    List<DiskScanner.EntryMeta> entriesOf(UUID uuid) {
        return this.meta.getOrDefault(uuid, List.of());
    }
}
