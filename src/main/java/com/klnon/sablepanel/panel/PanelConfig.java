package com.klnon.sablepanel.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.klnon.sablepanel.SablePanel;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/** 面板配置:config/sablepanel-server.json,首次启动自动生成 */
public final class PanelConfig {
    /**
     * 默认访问 token。HOST 会在 PEER 通过回环数据端口注册时下发当前值，
     * 因此同机实例无需预先手工对齐。服主可在面板"维护"卡片里修改，
     * 改动会串行落盘并同步给当前集群成员。
     */
    public static final String DEFAULT_TOKEN = "sablepanel";
    public static final int DEFAULT_RECYCLE_MAX_FILES = 500;
    public static final int MAX_RECYCLE_FILES = 1_000_000;

    public boolean enabled = true;
    public boolean webEnabled = true;
    public String webBind = "0.0.0.0";
    public int webPort = 25580;
    public String apiBind = "0.0.0.0";
    public int apiPort = 25581;
    public volatile String token = DEFAULT_TOKEN;
    /** 回收站中实际 NBT 备份文件的硬上限；超出时按删除日期清理最早的完整依赖组。 */
    public int recycleMaxFiles = DEFAULT_RECYCLE_MAX_FILES;

    /**
     * 本服在面板里的显示名,留空则取服务端目录名。
     * 同一台机器上多个服务端使用相同 apiPort 时，先绑定数据端口的实例成为 HOST，
     * 后启动的实例从 127.0.0.1 注册为 PEER；HOST 按 webPort 托管可选网页。
     */
    public String serverName = "";

    /**
     * "推荐删除"的保护阈值:组命中任一条即不推荐(宁可漏推荐,不可误删玩家资产)。
     * 想更激进地清理就调小,想更保守就调大。
     */
    public int protectBlocks = 20;
    /** 方块种类数 ≥ 此值 → 保护(残骸几乎都是单一种类,建造物种类多) */
    public int protectBlockTypes = 4;
    /** 方块实体数 ≥ 此值 → 保护(残骸最多带 1 个,机械/家具成组出现) */
    public int protectBlockEntities = 3;

    /**
     * "虚空中/极高空"筛选的高度阈值,判据是**整个包围盒**都越过了阈值。
     * <p>
     * 用绝对高度而不是维度建筑上限:航空服的飞艇本来就飞得高,按建筑上限(实测有存档
     * 被模组改到 480)筛会把正常游玩的飞艇也捞进来。1000 以上基本只有 bug 甩出去的体。
     * 各服情况不同,所以放出来给服主调。
     */
    public int voidBelowY = -64;
    /** 体底高于此值 → 极高空(玩家正常手段到不了) */
    public int skyAboveY = 1000;

    /** 集群内的唯一标识 = 显示名;留空时退回服务端目录名(通常就是实例名) */
    public String serverId() {
        if (this.serverName != null && !this.serverName.isBlank()) {
            return this.serverName.trim();
        }
        try {
            Path dir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path name = dir.getFileName();
            if (name != null && !name.toString().isBlank()) return name.toString();
        } catch (Exception ignored) {
        }
        return "server";
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sablepanel-server.json");
    }

    /** 回写配置(前端改 token 后调用)。只写服务端自己的 config 目录,不碰外部路径。 */
    public synchronized void save() throws java.io.IOException {
        Path f = file();
        Files.createDirectories(f.getParent());
        com.klnon.sablepanel.panel.storage.AtomicIo.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(this));
    }

    public static PanelConfig load() {
        Path file = file();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            PanelConfig cfg = Files.isRegularFile(file) ? readExisting(file, gson) : null;
            return cfg != null ? cfg : writeFresh(file, gson);
        } catch (Exception e) {
            SablePanel.LOGGER.error("sablepanel: failed to load panel config, panel disabled", e);
            PanelConfig cfg = new PanelConfig();
            cfg.enabled = false;
            return cfg;
        }
    }

    private static PanelConfig readExisting(Path file, Gson gson) throws java.io.IOException {
        String raw = Files.readString(file);
        var source = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        PanelConfig cfg = gson.fromJson(source, PanelConfig.class);
        if (cfg == null) return null;
        cfg.clampInvalid();
        // 旧版本的配置文件缺新字段(会取默认值),补写回去,服主才看得见能调什么
        String full = gson.toJson(cfg);
        if (!full.equals(raw)) com.klnon.sablepanel.panel.storage.AtomicIo.writeString(file, full);
        return cfg;
    }

    private static PanelConfig writeFresh(Path file, Gson gson) throws java.io.IOException {
        PanelConfig cfg = new PanelConfig();
        Files.createDirectories(file.getParent());
        com.klnon.sablepanel.panel.storage.AtomicIo.writeString(file, gson.toJson(cfg));
        SablePanel.LOGGER.info("sablepanel: generated panel config {}", file);
        return cfg;
    }

    /** 非法值就地钳回默认:一个写坏的字段不该拖垮整个面板 */
    private void clampInvalid() {
        if (this.token == null || this.token.isBlank()) this.token = DEFAULT_TOKEN;
        if (this.webBind == null || this.webBind.isBlank()) this.webBind = "0.0.0.0";
        if (this.apiBind == null || this.apiBind.isBlank()) this.apiBind = "0.0.0.0";
        if (this.webPort < 1 || this.webPort > 65535) this.webPort = 25580;
        if (this.apiPort < 1 || this.apiPort > 65535) this.apiPort = 25581;
        if (this.recycleMaxFiles < 1 || this.recycleMaxFiles > MAX_RECYCLE_FILES) {
            this.recycleMaxFiles = DEFAULT_RECYCLE_MAX_FILES;
        }
        // 上下阈值反了会让两个筛选同时命中所有体,直接退回默认
        if (this.voidBelowY >= this.skyAboveY) {
            this.voidBelowY = -64;
            this.skyAboveY = 1000;
        }
    }
}
