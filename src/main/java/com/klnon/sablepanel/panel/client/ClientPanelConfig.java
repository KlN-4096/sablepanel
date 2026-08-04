package com.klnon.sablepanel.panel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class ClientPanelConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientPanelConfig.class);
    private transient Path configFile;
    public int webPort = 25580;
    public String lastAddress = "";
    public Map<String, String> certificatePins = new HashMap<>();

    public static ClientPanelConfig load() {
        return load(file());
    }

    static ClientPanelConfig load(Path file) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            ClientPanelConfig config = Files.isRegularFile(file)
                    ? gson.fromJson(Files.readString(file), ClientPanelConfig.class) : new ClientPanelConfig();
            if (config == null) config = new ClientPanelConfig();
            config.configFile = file;
            if (config.webPort < 1 || config.webPort > 65535) config.webPort = 25580;
            if (config.lastAddress == null) config.lastAddress = "";
            if (config.certificatePins == null) config.certificatePins = new HashMap<>();
            config.save();
            return config;
        } catch (Exception error) {
            LOGGER.warn("sablepanel: loading client panel config failed", error);
            ClientPanelConfig config = new ClientPanelConfig();
            config.configFile = file;
            return config;
        }
    }

    public synchronized void save() throws java.io.IOException {
        Path file = this.configFile != null ? this.configFile : file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(this));
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sablepanel-client.json");
    }
}
