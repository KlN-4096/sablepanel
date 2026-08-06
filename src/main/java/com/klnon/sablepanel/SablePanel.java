package com.klnon.sablepanel;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.bodies.PanelRuntime;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.gateway.PanelWebGateway;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import com.klnon.sablepanel.panel.audit.EventLog;
import com.klnon.sablepanel.panel.audit.PanelObserver;
import com.klnon.sablepanel.panel.metrics.PhysicsTimer;

@Mod(SablePanel.MOD_ID)
public class SablePanel {
    public static final String MOD_ID = "sablepanel";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final PanelRuntime panelRuntime = new PanelRuntime();

    private long tickStartNanos;

    public SablePanel() {
        // 票种注册必须早于世界读档,否则存档里的常驻票 byName 查不到会被静默丢弃
        com.klnon.sablepanel.panel.ops.ForceLoadService.init();
        if (FMLEnvironment.dist == Dist.CLIENT) startClientGateway();
        NeoForge.EVENT_BUS.addListener(this::onContainerReady);
        NeoForge.EVENT_BUS.addListener(this::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(this::onPostPhysics);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("SablePanel instrumentation loaded");
    }

    /** 客户端模式的空白面板网关。mod 构造器一次性调用,shutdown hook 持引用,不需要静态守卫 */
    private static void startClientGateway() {
        PanelWebGateway gateway = PanelWebGateway.client(ClientPanelConfig.load());
        try {
            gateway.start();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close, "sablepanel-client-stop"));
        } catch (Exception error) {
            gateway.close();
            LOGGER.warn("sablepanel: client web gateway startup failed", error);
        }
    }

    private void onServerTickPre(ServerTickEvent.Pre event) {
        if (this.panelRuntime.isRunning()) {
            this.tickStartNanos = System.nanoTime();
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            this.panelRuntime.start(event.getServer());
        } catch (Throwable t) {
            LOGGER.error("sablepanel: panel startup failed", t);
        }
    }

    private void onContainerReady(ForgeSableSubLevelContainerReadyEvent event) {
        try {
            if (!(event.getContainer() instanceof ServerSubLevelContainer container)) {
                return;
            }
            String dim = container.getLevel().dimension().location().toString();
            container.addObserver(new PanelObserver(dim, this.panelRuntime::requestRuntimeRefresh));

            JsonObject o = new JsonObject();
            o.addProperty("ev", "container_ready");
            o.addProperty("dim", dim);
            o.addProperty("occupancy", container.getOccupancy().cardinality());
            o.addProperty("loaded", container.getLoadedCount());
            EventLog.write(o);
        } catch (Throwable t) {
            LOGGER.warn("sablepanel: container_ready hook failed", t);
        }
    }

    private void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        PhysicsTimer.begin(event.getPhysicsSystem());
    }

    private void onPostPhysics(ForgeSablePostPhysicsTickEvent event) {
        PhysicsTimer.end(event.getPhysicsSystem());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        long durationNanos = this.panelRuntime.isRunning() && this.tickStartNanos != 0
                ? System.nanoTime() - this.tickStartNanos : 0;
        this.tickStartNanos = 0;
        this.panelRuntime.onServerTick(event.getServer(), durationNanos);
    }

    // ServerStopped(而非 Stopping):sable 在停服晚期才逐体 UNLOADED,writer 必须活到那之后
    private void onServerStopped(ServerStoppedEvent event) {
        this.panelRuntime.close();
        this.tickStartNanos = 0;
        PhysicsTimer.reset();
        EventLog.close();
    }
}
