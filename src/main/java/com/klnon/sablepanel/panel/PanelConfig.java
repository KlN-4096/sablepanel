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
     * 默认访问 token。故意是**固定值**:同机多个服务端开箱即用地落进同一个面板集群
     * (同 port + 同 token = 同集群),不需要手工对齐配置。
     * 服主可在面板"维护"卡片里直接改,改动会同步给集群内所有成员。
     */
    public static final String DEFAULT_TOKEN = "sablepanel";

    public boolean enabled = true;
    public String bind = "0.0.0.0";
    public int port = 25580;
    public String token = DEFAULT_TOKEN;
    /** 回收站中实际 NBT 备份文件的硬上限；超出时按删除日期清理最早的完整依赖组。 */
    public int recycleMaxFiles = 500;

    /**
     * 本服在面板里的显示名,留空则取服务端目录名。
     * 同一台机器上多个服务端装本 mod 时:端口与 token 相同者视为同一集群,
     * 先启动的抢到端口当 HOST 开网页,后启动的自动挂进去,在页面顶栏切换。
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
                cfg = gson.fromJson(raw, PanelConfig.class);
                if (cfg != null) {
                    if (cfg.token == null || cfg.token.isBlank()) cfg.token = DEFAULT_TOKEN;
                    if (cfg.recycleMaxFiles < 1) cfg.recycleMaxFiles = 500;
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
