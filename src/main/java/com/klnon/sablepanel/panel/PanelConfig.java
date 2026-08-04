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

    public boolean enabled = true;
    /** 旧字段，仅用于读取 0.11.0 及更早配置。 */
    @Deprecated
    public transient String bind = "0.0.0.0";
    /** 旧字段，仅用于读取 0.11.0 及更早配置。 */
    @Deprecated
    public transient int port = 25580;
    public boolean webEnabled = true;
    public String webBind = "0.0.0.0";
    public int webPort = 25580;
    public String apiBind = "0.0.0.0";
    public int apiPort = 25581;
    public volatile String token = DEFAULT_TOKEN;
    /** 回收站中实际 NBT 备份文件的硬上限；超出时按删除日期清理最早的完整依赖组。 */
    public int recycleMaxFiles = 500;
    /** 每秒性能历史保留天数；历史文件位于 config/sablepanel/stats。 */
    public int statsRetentionDays = 30;

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
        Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(this));
    }

    public static PanelConfig load() {
        Path file = file();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            PanelConfig cfg = null;
            if (Files.isRegularFile(file)) {
                String raw = Files.readString(file);
                var source = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
                cfg = gson.fromJson(source, PanelConfig.class);
                if (cfg != null) {
                    if (!source.has("webBind") && source.has("bind")) cfg.webBind = source.get("bind").getAsString();
                    if (!source.has("webPort") && source.has("port")) cfg.webPort = source.get("port").getAsInt();
                    if (cfg.token == null || cfg.token.isBlank()) cfg.token = DEFAULT_TOKEN;
                    if (cfg.webBind == null || cfg.webBind.isBlank()) cfg.webBind = "0.0.0.0";
                    if (cfg.apiBind == null || cfg.apiBind.isBlank()) cfg.apiBind = "0.0.0.0";
                    if (cfg.webPort < 1 || cfg.webPort > 65535) cfg.webPort = 25580;
                    if (cfg.apiPort < 1 || cfg.apiPort > 65535) cfg.apiPort = 25581;
                    if (cfg.recycleMaxFiles < 1) cfg.recycleMaxFiles = 500;
                    if (cfg.statsRetentionDays < 1) cfg.statsRetentionDays = 30;
                    // 旧版本的配置文件缺新字段(会取默认值),补写回去,服主才看得见能调什么
                    String full = gson.toJson(cfg);
                    if (!full.equals(raw)) Files.writeString(file, full);
                }
            }
            if (cfg == null) {
                cfg = new PanelConfig();
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(cfg));
                SablePanel.LOGGER.info("sablepanel: generated panel config {}", file);
            }
            return cfg;
        } catch (Exception e) {
            SablePanel.LOGGER.error("sablepanel: failed to load panel config, panel disabled", e);
            PanelConfig cfg = new PanelConfig();
            cfg.enabled = false;
            return cfg;
        }
    }
}
