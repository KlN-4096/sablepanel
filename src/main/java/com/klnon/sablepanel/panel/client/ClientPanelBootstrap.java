package com.klnon.sablepanel.panel.client;

import com.klnon.sablepanel.SablePanel;
import com.klnon.sablepanel.panel.web.PanelWebGateway;

public final class ClientPanelBootstrap {
    private static volatile PanelWebGateway gateway;

    private ClientPanelBootstrap() {
    }

    public static synchronized void start() {
        if (gateway != null) return;
        ClientPanelConfig config = ClientPanelConfig.load();
        PanelWebGateway next = PanelWebGateway.client(config);
        try {
            next.start();
            gateway = next;
            Runtime.getRuntime().addShutdownHook(new Thread(ClientPanelBootstrap::stop, "sablepanel-client-stop"));
        } catch (Exception error) {
            next.close();
            SablePanel.LOGGER.warn("sablepanel: client web gateway startup failed", error);
        }
    }

    public static synchronized void stop() {
        if (gateway != null) gateway.close();
        gateway = null;
    }
}
