package com.klnon.sablepanel.panel.data;

import com.klnon.sablepanel.panel.PanelConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.SablePanel;
import net.minecraft.core.registries.BuiltInRegistries;
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
        String[] hit = CACHE.get(blockId);
        if (hit != null) return hit;
        String descId = descriptionId(blockId);
        if (descId == null) {
            // 未注册的方块:id 直接来自 NBT 的方块调色板,长度和取值都没有上限,
            // 缓存它等于给一张静态 Map 开了个无界入口 —— 一份构造过或损坏的存档就能把
            // 65,535 字符的键连同两份副本永久钉在堆里。注册表里的那些天然有上界(注册表大小)
            String pretty = prettify(blockId);
            return new String[]{pretty, pretty};
        }
        String en = Language.getInstance().getOrDefault(descId, prettify(blockId));
        String[] names = {en, zhMap().getOrDefault(descId, en)};
        CACHE.put(blockId, names);
        return names;
    }

    /**
     * 注册表里查不到就返回 null。
     * <p>
     * 必须用 {@code getOptional}:{@code BuiltInRegistries.BLOCK} 是 DefaultedMappedRegistry,
     * 它的 {@code get()} 查不到时返回默认值 {@code minecraft:air} 而不是 null
     * (21.1.233 的 {@code DefaultedMappedRegistry:57})。所以"返回值不是 AIR、或者 path 就是 air"
     * 这种判法漏掉一整类:{@code 任意命名空间:air} 全都会被当成已注册进缓存,而命名空间同样
     * 来自 NBT,照样无界。同一个类把 {@code getOptional} 重写成直接读真实映射(同文件 62 行),
     * 用它就不需要 AIR 特判了。
     */
    private static String descriptionId(String blockId) {
        try {
            return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockId))
                    .map(Block::getDescriptionId).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 缓存条目数,只给测试判"未注册的 id 不进缓存" */
    static int cachedCount() {
        return CACHE.size();
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
