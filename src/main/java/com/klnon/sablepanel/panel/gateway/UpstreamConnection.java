package com.klnon.sablepanel.panel.gateway;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.client.ClientPanelConfig;
import com.klnon.sablepanel.panel.transport.CertificatePinException;
import com.klnon.sablepanel.panel.transport.PanelEndpoint;
import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.klnon.sablepanel.panel.transport.PanelNet;
import com.klnon.sablepanel.panel.transport.PanelTcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 网关的上游 TLS 连接:建立/断线重连/证书指纹确认/事件订阅令牌,两种形态共用 ——
 * 服务端模式目标固定(本机 API 口),由重连循环维持;客户端模式目标由页面输入,
 * 指纹按端点记忆在 {@link ClientPanelConfig} 里。互斥都在本实例上,网络等待不进锁。
 */
final class UpstreamConnection implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamConnection.class);
    private static final int RECONNECT_SECONDS = 2;
    private static final long RECONNECT_LOG_INTERVAL_MS = 60_000L;

    private final boolean clientMode;
    private final PanelEndpoint fixedEndpoint;
    private final String fixedFingerprint;
    private final ClientPanelConfig clientConfig;
    private final Consumer<PanelEvent> publisher;
    /** SSE 桥由网关持有,这里只在换令牌/重连恢复/断开时读一眼当前实例 */
    private final Supplier<PanelEventStreams> streams;
    private volatile PanelTcpClient upstream;
    private volatile PanelEndpoint endpoint;
    private volatile PanelEndpoint pendingEndpoint;
    private volatile String pendingFingerprint;
    private volatile ScheduledExecutorService reconnectExecutor;
    private volatile boolean closed = true;
    private volatile long lastReconnectLogMs;
    private volatile String eventToken;
    private final AtomicBoolean reconnectPending = new AtomicBoolean();

    UpstreamConnection(boolean clientMode, PanelEndpoint fixedEndpoint, String fixedFingerprint,
                       ClientPanelConfig clientConfig, Consumer<PanelEvent> publisher,
                       Supplier<PanelEventStreams> streams) {
        this.clientMode = clientMode;
        this.fixedEndpoint = fixedEndpoint;
        this.fixedFingerprint = fixedFingerprint;
        this.clientConfig = clientConfig;
        this.publisher = publisher;
        this.streams = streams;
    }

    /** 网关启动时打开:服务端模式立即尝试首连并启动重连循环;客户端模式等页面指令 */
    void open() {
        synchronized (this) {
            this.closed = false;
            this.reconnectPending.set(false);
        }
        if (this.clientMode) return;
        try {
            connect(this.fixedEndpoint, this.fixedFingerprint);
        } catch (Exception error) {
            logReconnectFailure(error);
        }
        startReconnectLoop();
    }

    boolean isConnected() {
        PanelTcpClient current = this.upstream;
        return current != null && current.isActive();
    }

    /** 客户端模式页面预填的地址:已连上用当前端点,否则用上次成功的 */
    String displayAddress() {
        PanelEndpoint current = this.endpoint;
        return current != null ? current.toString() : this.clientConfig.lastAddress;
    }

    /** 活跃的上游连接;没有就顺手推一次重连(仅服务端模式生效)并返回 null */
    PanelTcpClient active() {
        PanelTcpClient current = this.upstream;
        if (current != null && current.isActive()) return current;
        requestReconnect();
        return null;
    }

    /** 订阅成功后的记账。短锁:只比对上游身份并交换 eventToken,不做任何网络等待 */
    synchronized PanelEventStreams adoptEventToken(PanelTcpClient client, String token) {
        if (client != this.upstream || !client.isActive()) return null;
        PanelEventStreams current = this.streams.get();
        if (current == null) return null;
        String previousToken = this.eventToken;
        if (previousToken != null && !previousToken.equals(token)) current.closeStreams();
        this.eventToken = token;
        return current;
    }

    /**
     * 客户端模式:按页面请求连接集群。返回 null 表示成功;否则为应回给页面的失败响应
     * (409 冲突 / 指纹确认要求,HTTP 形状由网关原样转发)。
     */
    synchronized PanelResponse connectRequested(String address, String accepted) throws Exception {
        PanelEndpoint requested = PanelEndpoint.parse(address, 25581);
        if (this.upstream != null && this.upstream.isActive() && !requested.equals(this.endpoint)) {
            return PanelResponse.error(409, "请先断开当前集群");
        }
        closeUpstream();
        String key = requested.toString();
        if (!accepted.isBlank()) {
            if (!requested.equals(this.pendingEndpoint)
                    || this.pendingFingerprint == null
                    || !accepted.equalsIgnoreCase(this.pendingFingerprint)) {
                return PanelResponse.error(409, "指纹确认已失效,请重新连接");
            }
        }
        String expected = accepted.isBlank() ? this.clientConfig.certificatePins.get(key) : accepted;
        try {
            connect(requested, expected);
            if (!accepted.isBlank()) this.clientConfig.certificatePins.put(key, accepted);
            this.clientConfig.lastAddress = key;
            this.clientConfig.save();
            clearPendingFingerprint();
            return null;
        } catch (CertificatePinException pin) {
            this.pendingEndpoint = requested;
            this.pendingFingerprint = pin.fingerprint();
            JsonObject response = new JsonObject();
            response.addProperty("error", pin.changed() ? "certificate_changed" : "certificate_confirmation_required");
            response.addProperty("fingerprint", pin.fingerprint());
            return new PanelResponse(409, "application/json",
                    response.toString().getBytes(StandardCharsets.UTF_8), false);
        }
    }

    /** 客户端模式:断开当前集群并清掉待确认指纹 */
    synchronized void disconnectRequested() {
        closeUpstream();
        clearPendingFingerprint();
    }

    private void connect(PanelEndpoint endpoint, String fingerprint) throws Exception {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw connectForFingerprint(endpoint);
        }
        PanelTcpClient connected = PanelTcpClient.connectManager(endpoint, fingerprint, this.publisher);
        PanelTcpClient previous;
        synchronized (this) {
            if (this.closed) {
                connected.close();
                throw new IllegalStateException("网页网关已关闭");
            }
            previous = this.upstream;
            this.upstream = connected;
            this.endpoint = endpoint;
        }
        if (previous != null && previous != connected) previous.close();
        restoreEventSubscription(connected);
    }

    private CertificatePinException connectForFingerprint(PanelEndpoint endpoint) throws Exception {
        try {
            PanelTcpClient.connectManager(endpoint, "").close();
            throw new IllegalStateException("未取得服务器证书");
        } catch (CertificatePinException pin) {
            return pin;
        }
    }

    @Override
    public void close() {
        PanelTcpClient currentUpstream;
        ScheduledExecutorService currentReconnect;
        synchronized (this) {
            this.closed = true;
            currentUpstream = this.upstream;
            currentReconnect = this.reconnectExecutor;
            this.upstream = null;
            this.endpoint = null;
            this.reconnectExecutor = null;
            this.eventToken = null;
        }
        PanelNet.shutdown(currentReconnect);
        if (currentUpstream != null) currentUpstream.close();
    }

    private void closeUpstream() {
        PanelTcpClient current;
        synchronized (this) {
            current = this.upstream;
            this.upstream = null;
            this.endpoint = null;
            this.eventToken = null;
        }
        if (current != null) current.close();
        PanelEventStreams streams = this.streams.get();
        if (streams != null) streams.closeStreams();
    }

    private void startReconnectLoop() {
        ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(
                PanelNet.daemonThreads("sablepanel-web-reconnect"));
        synchronized (this) {
            if (this.closed) {
                reconnect.shutdownNow();
                return;
            }
            this.reconnectExecutor = reconnect;
        }
        reconnect.scheduleWithFixedDelay(this::ensureServerUpstream,
                RECONNECT_SECONDS, RECONNECT_SECONDS, TimeUnit.SECONDS);
    }

    private void requestReconnect() {
        if (this.clientMode || this.closed) return;
        ScheduledExecutorService reconnect = this.reconnectExecutor;
        if (reconnect == null || reconnect.isShutdown()) return;
        if (!this.reconnectPending.compareAndSet(false, true)) return;
        try {
            reconnect.execute(() -> {
                try {
                    ensureServerUpstream();
                } finally {
                    this.reconnectPending.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            this.reconnectPending.set(false);
        }
    }

    private void ensureServerUpstream() {
        if (this.clientMode || this.closed) return;
        PanelTcpClient current = this.upstream;
        if (current != null && current.isActive()) return;

        PanelTcpClient connected;
        try {
            connected = PanelTcpClient.connectManager(this.fixedEndpoint, this.fixedFingerprint, this.publisher);
        } catch (Exception error) {
            logReconnectFailure(error);
            return;
        }

        PanelTcpClient stale = null;
        boolean keep;
        synchronized (this) {
            keep = !this.closed && !this.clientMode
                    && (this.upstream == null || !this.upstream.isActive());
            if (keep) {
                stale = this.upstream;
                this.upstream = connected;
                this.endpoint = this.fixedEndpoint;
                this.lastReconnectLogMs = 0;
            }
        }
        if (!keep) {
            connected.close();
            return;
        }
        if (stale != null) stale.close();
        restoreEventSubscription(connected);
        LOGGER.info("sablepanel: server web gateway reconnected to {}", this.fixedEndpoint);
    }

    /** 只读 volatile 字段,不拿实例锁:10 秒的订阅等待不能挡住 connect/close */
    private void restoreEventSubscription(PanelTcpClient client) {
        String token = this.eventToken;
        if (token == null || token.isBlank()) return;
        try {
            PanelResponse response = client.subscribeEvents(token).get(10, TimeUnit.SECONDS);
            if (response.status() == 200) {
                PanelEventStreams streams = this.streams.get();
                if (streams != null) streams.resync();
            }
        } catch (Exception error) {
            LOGGER.warn("sablepanel: event subscription restore failed: {}", messageOf(error));
        }
    }

    private void logReconnectFailure(Throwable error) {
        long now = System.currentTimeMillis();
        if (now - this.lastReconnectLogMs < RECONNECT_LOG_INTERVAL_MS) return;
        this.lastReconnectLogMs = now;
        LOGGER.warn("sablepanel: server web gateway upstream unavailable: {}", messageOf(error));
    }

    private void clearPendingFingerprint() {
        this.pendingEndpoint = null;
        this.pendingFingerprint = null;
    }
}
