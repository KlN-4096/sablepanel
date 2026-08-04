package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块 id -> 中/英显示名。
 * 英文:服务端 Language(NeoForge 已注入全部 mod 的 en_us);
 * 中文:mod jar 内的 assets/&lt;ns&gt;/lang/zh_cn.json + 打包的原版 zh_cn 方块表(服务端没有原版中文资源)。
 * 全部懒加载一次,之后纯内存查询,线程安全。
 */
public final class BlockNames {
    private static volatile Map<String, String> zhByDescId;
    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();

    private BlockNames() {
    }

    /** @return [en, zh](zh 缺失时回退 en) */
    public static String[] of(String blockId) {
        return CACHE.computeIfAbsent(blockId, BlockNames::resolve);
    }

    private static String[] resolve(String blockId) {
        String descId = null;
        try {
            ResourceLocation rl = ResourceLocation.parse(blockId);
            Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
            if (b != net.minecraft.world.level.block.Blocks.AIR || rl.getPath().equals("air")) {
                descId = b.getDescriptionId();
            }
        } catch (Throwable ignored) {
        }
        if (descId == null) {
            // 未注册的方块(理论上不会发生):用 path 兜底
            String pretty = prettify(blockId);
            return new String[]{pretty, pretty};
        }
        String en = Language.getInstance().getOrDefault(descId, prettify(blockId));
        String zh = zhMap().getOrDefault(descId, en);
        return new String[]{en, zh};
    }

    private static String prettify(String id) {
        int i = id.indexOf(':');
        String path = i >= 0 ? id.substring(i + 1) : id;
        return path.replace('_', ' ');
    }

    private static Map<String, String> zhMap() {
        Map<String, String> m = zhByDescId;
        if (m != null) return m;
        synchronized (BlockNames.class) {
            if (zhByDescId != null) return zhByDescId;
            Map<String, String> map = new HashMap<>();
            // 1) 打包的原版方块中文表
            try (InputStream in = BlockNames.class.getResourceAsStream("/web/zh_cn_blocks.json")) {
                if (in != null) mergeLang(map, in);
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: bundled zh block names load failed", t);
            }
            // 2) 各 mod jar 的 zh_cn.json(只取 block. 前缀)
            try {
                for (var fileInfo : ModList.get().getModFiles()) {
                    for (var mod : fileInfo.getMods()) {
                        try {
                            Path p = fileInfo.getFile().findResource("assets", mod.getModId(), "lang", "zh_cn.json");
                            if (p != null && Files.isRegularFile(p)) {
                                try (InputStream in = Files.newInputStream(p)) {
                                    mergeLang(map, in);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: mod zh lang scan failed", t);
            }
            SablePanel.LOGGER.info("sablepanel: zh block name table loaded, {} keys", map.size());
            zhByDescId = map;
            return map;
        }
    }

    private static void mergeLang(Map<String, String> map, InputStream in) {
        JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String k : o.keySet()) {
            if (k.startsWith("block.")) {
                try {
                    map.putIfAbsent(k, o.get(k).getAsString());
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
