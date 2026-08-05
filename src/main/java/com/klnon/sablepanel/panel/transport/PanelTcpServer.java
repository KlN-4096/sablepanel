package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PanelTcpServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelTcpServer.class);
    private static final int CALLBACK_THREADS = 4;
    private static final int CALLBACK_QUEUE_CAPACITY = 64;
    private static final int FORWARD_THREADS = 4;
    private static final int FORWARD_QUEUE_CAPACITY = 64;
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 4;
    private static final int DEFAULT_READER_IDLE_SECONDS = 45;
    private static final int DEFAULT_WRITER_IDLE_SECONDS = 20;
    private static final int DEFAULT_PONG_TIMEOUT_SECONDS = 10;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final AtomicLong CALLBACK_THREAD_IDS = new AtomicLong();
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workers = new NioEventLoopGroup(2);
    private final ExecutorService callbacks = new ThreadPoolExecutor(
            CALLBACK_THREADS, CALLBACK_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CALLBACK_QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable,
                        "sablepanel-tcp-server-callback-" + CALLBACK_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    /**
     * 跨服转发专用池(bulkhead)。转发要在 PEER 上同步等最多 30 秒,和本地请求共用回调线程时,
     * 4 个卡住的 PEER 请求就能让完全无关连接的本地请求一起排队 —— 那正是面板整体 503 的形状。
     */
    private final ExecutorService forwards = new ThreadPoolExecutor(
            FORWARD_THREADS, FORWARD_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(FORWARD_QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable,
                        "sablepanel-tcp-server-forward-" + CALLBACK_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, ConnectionHandler> peers = new ConcurrentHashMap<>();
    private final Set<ConnectionHandler> connections = ConcurrentHashMap.newKeySet();
    private final Function<PanelRequest, PanelResponse> managerRequests;
    private final Supplier<String> token;
    private final String selfId;
    private final int readerIdleSeconds;
    private final int writerIdleSeconds;
    private final int pongTimeoutSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BiConsumer<String, PanelEvent> peerEvents = (id, event) -> { };
    private volatile Channel channel;

    public PanelTcpServer(String selfId, Function<PanelRequest, PanelResponse> managerRequests,
                          Supplier<String> token) {
        this(selfId, managerRequests, token, DEFAULT_READER_IDLE_SECONDS,
                DEFAULT_WRITER_IDLE_SECONDS, DEFAULT_PONG_TIMEOUT_SECONDS);
    }

    PanelTcpServer(String selfId, Function<PanelRequest, PanelResponse> managerRequests,
                   Supplier<String> token, int readerIdleSeconds, int writerIdleSeconds,
                   int pongTimeoutSeconds) {
        this.selfId = selfId;
        this.managerRequests = managerRequests;
        this.token = token;
        this.readerIdleSeconds = readerIdleSeconds;
        this.writerIdleSeconds = writerIdleSeconds;
        this.pongTimeoutSeconds = pongTimeoutSeconds;
    }

    public void start(String bind, int port, SslContext ssl) throws InterruptedException {
        if (this.closed.get()) throw new IllegalStateException("TCP 服务器已关闭");
        ServerBootstrap bootstrap = new ServerBootstrap().group(this.boss, this.workers)
                .channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                        channel.pipeline().addLast(new IdleStateHandler(
                                readerIdleSeconds, writerIdleSeconds, 0));
                        channel.pipeline().addLast(new PanelFrameDecoder(false));
                        channel.pipeline().addLast(new LengthFieldPrepender(PanelWire.LENGTH_FIELD_BYTES));
                        channel.pipeline().addLast(new PanelFrameCodec());
                        channel.pipeline().addLast(new ConnectionHandler());
                    }
                });
        ChannelFuture future = bootstrap.bind(bind, port).sync();
        this.channel = future.channel();
    }

    public void setPeerEvents(BiConsumer<String, PanelEvent> peerEvents) {
        if (this.channel != null) throw new IllegalStateException("事件处理器必须在启动前设置");
        this.peerEvents = peerEvents != null ? peerEvents : (id, event) -> { };
    }

    public void publishEvent(PanelEvent event) {
        if (!isActive()) return;
        this.connections.forEach(connection -> connection.sendEvent(event));
    }

    public void revokeEventSubscriptions() {
        this.connections.forEach(ConnectionHandler::revokeEvents);
    }

    public Set<String> peerIds() {
        return new TreeSet<>(this.peers.keySet());
    }

    public int port() {
        if (this.channel == null) return -1;
        return ((InetSocketAddress) this.channel.localAddress()).getPort();
    }

    public boolean isActive() {
        Channel current = this.channel;
        return !this.closed.get() && current != null && current.isActive();
    }

    public CompletableFuture<PanelResponse> requestPeer(String id, PanelRequest request) {
        ConnectionHandler peer = this.peers.get(id);
        if (peer == null) return CompletableFuture.completedFuture(PanelResponse.error(404, "服务器不在线"));
        return peer.request(request);
    }

    public CompletableFuture<PanelResponse> updatePeerToken(String id, String next) {
        ConnectionHandler peer = this.peers.get(id);
        if (peer == null) return CompletableFuture.completedFuture(PanelResponse.error(404, "服务器不在线"));
        return peer.updateToken(next);
    }

    public void disconnectPeer(String id) {
        ConnectionHandler peer = this.peers.remove(id);
        if (peer != null) peer.close();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        Channel current = this.channel;
        this.channel = null;
        if (current != null) current.close().awaitUninterruptibly();
        this.connections.forEach(ConnectionHandler::close);
        this.connections.clear();
        this.peers.clear();
        shutdownCallbacks();
        this.workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        this.boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private void shutdownCallbacks() {
        shutdownPool(this.callbacks);
        shutdownPool(this.forwards);
    }

    private static void shutdownPool(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class ConnectionHandler extends SimpleChannelInboundHandler<PanelFrame> {
        private final Map<Long, CompletableFuture<PanelResponse>> pending = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger forwardInFlight = new AtomicInteger();
        private final AtomicReference<PanelEvent> pendingEvent = new AtomicReference<>();
        private final AtomicBoolean eventWriteScheduled = new AtomicBoolean();
        private volatile ChannelHandlerContext context;
        private volatile String peerId;
        private volatile String subscribedToken;
        private volatile boolean inactive;
        private long awaitingPongId;

        @Override
        public void handlerAdded(ChannelHandlerContext context) {
            this.context = context;
            connections.add(this);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, PanelFrame frame) {
            try {
                switch (frame.type()) {
                    case PanelFrame.PEER_REGISTER -> registerPeer(context, frame);
                    case PanelFrame.REQUEST -> submitRequest(context, frame);
                    case PanelFrame.RESPONSE, PanelFrame.ERROR -> {
                        CompletableFuture<PanelResponse> future = this.pending.remove(frame.requestId());
                        if (future != null) future.complete(PanelWire.response(frame));
                    }
                    case PanelFrame.PING -> context.writeAndFlush(
                            new PanelFrame(PanelFrame.PONG, frame.requestId(), new JsonObject(), new byte[0]));
                    case PanelFrame.PONG -> acceptPong(frame.requestId());
                    case PanelFrame.EVENT_SUBSCRIBE -> subscribeEvents(context, frame);
                    case PanelFrame.EVENT -> receivePeerEvent(context, frame);
                    default -> context.close();
                }
            } catch (Exception error) {
                context.close();
            }
        }

        private void subscribeEvents(ChannelHandlerContext context, PanelFrame frame) {
            if (this.peerId != null || frame.body().length != 0 || !frame.meta().has("token")) {
                context.close();
                return;
            }
            String candidate = frame.meta().get("token").getAsString();
            if (!eventTokenValid(candidate)) {
                sendResponse(context, frame.requestId(), PanelResponse.error(401, "token 无效"));
                return;
            }
            this.subscribedToken = candidate;
            sendResponse(context, frame.requestId(), PanelResponse.json(200, "{\"ok\":true}", false));
        }

        private void receivePeerEvent(ChannelHandlerContext context, PanelFrame frame) {
            String source = this.peerId;
            if (source == null) {
                context.close();
                return;
            }
            peerEvents.accept(source, PanelWire.event(frame).fromServer(source));
        }

        private void submitRequest(ChannelHandlerContext context, PanelFrame frame) {
            // meta 解析在事件循环上也无所谓:请求帧不压缩,这里只读一个字符串
            String target = frame.meta().has("target") ? frame.meta().get("target").getAsString() : "";
            boolean forwarded = !target.isEmpty() && !target.equals(selfId);
            // 在途配额也要分开算,否则同一条网关连接上 4 个慢转发会把自己的本地请求也顶成 503
            boolean accepted = submitCallback(forwarded ? this.forwardInFlight : this.inFlight,
                    forwarded ? forwards : callbacks, () -> {
                PanelRequest request;
                try {
                    request = PanelWire.request(frame);
                } catch (Exception error) {
                    sendResponse(context, frame.requestId(), PanelResponse.error(400, messageOf(error)));
                    return;
                }
                try {
                    PanelResponse response = managerRequests.apply(request);
                    sendResponse(context, frame.requestId(), response != null
                            ? response : PanelResponse.error(500, "请求处理未返回响应"));
                } catch (Exception error) {
                    LOGGER.warn("sablepanel: TCP request handler failed {}", request.path(), error);
                    sendResponse(context, frame.requestId(), PanelResponse.error(500, messageOf(error)));
                }
            });
            if (!accepted) {
                sendResponse(context, frame.requestId(), PanelResponse.error(503, "服务器繁忙,请稍后重试"));
            }
        }

        private boolean submitCallback(AtomicInteger slots, ExecutorService pool, Runnable task) {
            if (closed.get() || this.inactive || this.context == null || !this.context.channel().isActive()) {
                return false;
            }
            if (slots.incrementAndGet() > MAX_IN_FLIGHT_PER_CONNECTION) {
                slots.decrementAndGet();
                return false;
            }
            try {
                pool.execute(() -> {
                    try {
                        if (!this.inactive) task.run();
                    } finally {
                        slots.decrementAndGet();
                    }
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                slots.decrementAndGet();
                return false;
            }
        }

        private void registerPeer(ChannelHandlerContext context, PanelFrame frame) {
            InetSocketAddress remote = (InetSocketAddress) context.channel().remoteAddress();
            if (!remote.getAddress().isLoopbackAddress()) {
                context.close();
                return;
            }
            String id = frame.meta().get("id").getAsString();
            if (id.equals(selfId)) {
                context.close();
                return;
            }
            this.peerId = id;
            revokeEvents();
            ConnectionHandler previous = peers.put(id, this);
            if (previous != null && previous != this) previous.close();
            JsonObject meta = new JsonObject();
            meta.addProperty("token", token.get());
            context.writeAndFlush(new PanelFrame(PanelFrame.PEER_REGISTERED, 0, meta, new byte[0]));
            LOGGER.info("sablepanel: peer {} joined the TLS cluster", id);
        }

        CompletableFuture<PanelResponse> request(PanelRequest request) {
            if (this.context == null || this.inactive || !this.context.channel().isActive()) {
                return CompletableFuture.failedFuture(new IllegalStateException("PEER 已断开"));
            }
            PanelFrame frame;
            try {
                frame = PanelWire.request(this.ids.incrementAndGet(), request);
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
            long id = frame.requestId();
            CompletableFuture<PanelResponse> future = new CompletableFuture<>();
            this.pending.put(id, future);
            this.context.writeAndFlush(frame).addListener(write -> {
                if (!write.isSuccess()) completePendingExceptionally(id, write.cause());
            });
            scheduleTimeout(id);
            return future;
        }

        CompletableFuture<PanelResponse> updateToken(String next) {
            if (this.context == null || this.inactive || !this.context.channel().isActive()) {
                return CompletableFuture.failedFuture(new IllegalStateException("PEER 已断开"));
            }
            long id = this.ids.incrementAndGet();
            CompletableFuture<PanelResponse> future = new CompletableFuture<>();
            this.pending.put(id, future);
            JsonObject meta = new JsonObject();
            meta.addProperty("token", next);
            this.context.writeAndFlush(new PanelFrame(PanelFrame.TOKEN_UPDATE, id, meta, new byte[0]))
                    .addListener(write -> {
                        if (!write.isSuccess()) completePendingExceptionally(id, write.cause());
                    });
            scheduleTimeout(id);
            return future;
        }

        private void completePendingExceptionally(long id, Throwable error) {
            CompletableFuture<PanelResponse> future = this.pending.remove(id);
            if (future != null) future.completeExceptionally(error);
        }

        private void scheduleTimeout(long id) {
            this.context.executor().schedule(() -> {
                CompletableFuture<PanelResponse> future = this.pending.remove(id);
                if (future != null) future.completeExceptionally(new java.util.concurrent.TimeoutException("PEER 请求超时"));
            }, 30, TimeUnit.SECONDS);
        }

        private void sendResponse(ChannelHandlerContext context, long id, PanelResponse response) {
            try {
                context.writeAndFlush(PanelWire.response(id, response));
            } catch (Exception error) {
                context.close();
            }
        }

        private void sendEvent(PanelEvent event) {
            if (this.peerId != null || this.inactive) return;
            String subscribed = this.subscribedToken;
            if (!eventTokenValid(subscribed)) {
                revokeEvents();
                return;
            }
            this.pendingEvent.set(event);
            scheduleEventWrite();
        }

        private void scheduleEventWrite() {
            ChannelHandlerContext current = this.context;
            if (current == null || !current.channel().isActive() || !current.channel().isWritable()) return;
            if (this.eventWriteScheduled.compareAndSet(false, true)) {
                current.executor().execute(this::flushEvent);
            }
        }

        private void flushEvent() {
            ChannelHandlerContext current = this.context;
            if (current == null || this.inactive || !current.channel().isActive()
                    || !eventTokenValid(this.subscribedToken)) {
                revokeEvents();
                return;
            }
            if (!current.channel().isWritable()) {
                this.eventWriteScheduled.set(false);
                return;
            }
            PanelEvent event = this.pendingEvent.getAndSet(null);
            if (event != null) {
                current.writeAndFlush(PanelWire.event(event)).addListener(write -> {
                    if (!write.isSuccess()) current.close();
                });
            }
            this.eventWriteScheduled.set(false);
            if (this.pendingEvent.get() != null) scheduleEventWrite();
        }

        private void revokeEvents() {
            this.subscribedToken = null;
            this.pendingEvent.set(null);
            this.eventWriteScheduled.set(false);
        }

        private boolean eventTokenValid(String candidate) {
            if (candidate == null || candidate.length() > 64) return false;
            String expected = token.get();
            return expected != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
            if (event instanceof IdleStateEvent) {
                sendPingOrClose(context);
                return;
            }
            super.userEventTriggered(context, event);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
            if (context.channel().isWritable() && this.pendingEvent.get() != null) scheduleEventWrite();
            super.channelWritabilityChanged(context);
        }

        private void sendPingOrClose(ChannelHandlerContext context) {
            if (this.awaitingPongId != 0) {
                context.close();
                return;
            }
            long pingId = this.ids.incrementAndGet();
            this.awaitingPongId = pingId;
            context.writeAndFlush(new PanelFrame(PanelFrame.PING, pingId, new JsonObject(), new byte[0]))
                    .addListener(write -> {
                        if (!write.isSuccess()) context.close();
                    });
            context.executor().schedule(() -> {
                if (this.awaitingPongId == pingId && context.channel().isActive()) context.close();
            }, pongTimeoutSeconds, TimeUnit.SECONDS);
        }

        private void acceptPong(long id) {
            if (this.awaitingPongId == id) this.awaitingPongId = 0;
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            this.inactive = true;
            this.awaitingPongId = 0;
            revokeEvents();
            connections.remove(this);
            if (this.peerId != null) peers.remove(this.peerId, this);
            this.pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("PEER 已断开")));
            this.pending.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }

        void close() {
            this.inactive = true;
            if (this.context != null) this.context.close();
        }
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
