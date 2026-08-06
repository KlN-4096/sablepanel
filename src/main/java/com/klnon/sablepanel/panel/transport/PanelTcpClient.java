package com.klnon.sablepanel.panel.transport;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public final class PanelTcpClient implements AutoCloseable {
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 4;
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final int PONG_TIMEOUT_SECONDS = 10;
    private final NioEventLoopGroup group = new NioEventLoopGroup(1, PanelNet.daemonThreads("sablepanel-tcp-client"));
    private final ExecutorService callbacks = PanelNet.boundedPool("sablepanel-tcp-client-callback", 2, 32);
    private final ExecutorService controlCallbacks = PanelNet.boundedPool("sablepanel-tcp-client-control", 1, 16);
    private final PanelNet.Pending pending = new PanelNet.Pending();
    private final AtomicInteger pendingRequests = new AtomicInteger();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Function<PanelRequest, PanelResponse> incomingRequests;
    private final Function<String, PanelResponse> tokenUpdates;
    private final Consumer<PanelEvent> events;
    private final String peerId;
    private volatile Channel channel;

    private PanelTcpClient(Function<PanelRequest, PanelResponse> incomingRequests,
                           Function<String, PanelResponse> tokenUpdates, Consumer<PanelEvent> events,
                           String peerId) {
        this.incomingRequests = incomingRequests;
        this.tokenUpdates = tokenUpdates;
        this.events = events;
        this.peerId = peerId;
    }

    public static PanelTcpClient connectManager(PanelEndpoint endpoint, String expectedFingerprint)
            throws Exception {
        return connectManager(endpoint, expectedFingerprint, null);
    }

    public static PanelTcpClient connectManager(PanelEndpoint endpoint, String expectedFingerprint,
                                                Consumer<PanelEvent> events) throws Exception {
        PinnedTrustManager trust = new PinnedTrustManager(expectedFingerprint);
        SslContext ssl = SslContextBuilder.forClient().trustManager(trust)
                .protocols("TLSv1.3", "TLSv1.2").build();
        PanelTcpClient client = new PanelTcpClient(null, null, events, null);
        try {
            client.connect(endpoint, ssl);
            return client;
        } catch (Exception error) {
            client.close();
            if (trust.candidate() != null) {
                throw new CertificatePinException(trust.candidate(),
                        expectedFingerprint != null && !expectedFingerprint.isBlank(), error);
            }
            throw error;
        }
    }

    public static PanelTcpClient connectPeer(PanelEndpoint endpoint, String peerId,
                                              Function<PanelRequest, PanelResponse> requests,
                                              Function<String, PanelResponse> tokenUpdates) throws Exception {
        SslContext ssl = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                .protocols("TLSv1.3", "TLSv1.2").build();
        PanelTcpClient client = new PanelTcpClient(requests, tokenUpdates, null, peerId);
        try {
            client.connect(endpoint, ssl);
            return client;
        } catch (Exception error) {
            client.close();
            throw error;
        }
    }

    private void connect(PanelEndpoint endpoint, SslContext ssl) throws Exception {
        if (this.closed.get()) throw new IllegalStateException("TCP 客户端已关闭");
        Bootstrap bootstrap = new Bootstrap().group(this.group).channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, PanelWire.CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(ssl.newHandler(channel.alloc(), endpoint.host(), endpoint.port()));
                        channel.pipeline().addLast(new IdleStateHandler(45, 20, 0));
                        channel.pipeline().addLast(new PanelFrameDecoder(true));
                        channel.pipeline().addLast(new LengthFieldPrepender(PanelWire.LENGTH_FIELD_BYTES));
                        channel.pipeline().addLast(new PanelFrameCodec());
                        channel.pipeline().addLast(new ClientHandler());
                    }
        });
        this.channel = bootstrap.connect(endpoint.host(), endpoint.port()).sync().channel();
        this.ready.get(PanelWire.HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public CompletableFuture<PanelResponse> request(PanelRequest request) {
        if (!isActive()) return CompletableFuture.failedFuture(new IllegalStateException("未连接到服务器"));
        PanelFrame frame;
        try {
            frame = PanelWire.request(this.requestIds.incrementAndGet(), request);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
        return sendTracked(frame);
    }

    public CompletableFuture<PanelResponse> subscribeEvents(String token) {
        if (this.peerId != null || this.events == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("当前连接不能订阅事件"));
        }
        return sendTracked(PanelWire.eventSubscribe(this.requestIds.incrementAndGet(), token));
    }

    public void publishEvent(PanelEvent event) {
        if (this.peerId == null) throw new IllegalStateException("只有 PEER 可以上报事件");
        Channel current = this.channel;
        if (isActive()) current.writeAndFlush(PanelWire.event(event));
    }

    private CompletableFuture<PanelResponse> sendTracked(PanelFrame frame) {
        Channel current = this.channel;
        if (!isActive() || current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("未连接到服务器"));
        }
        if (this.pendingRequests.incrementAndGet() > MAX_PENDING_REQUESTS) {
            this.pendingRequests.decrementAndGet();
            return CompletableFuture.failedFuture(new RejectedExecutionException("待处理请求过多"));
        }
        CompletableFuture<PanelResponse> result =
                this.pending.track(current, frame, PanelNet.REQUEST_TIMEOUT_SECONDS, "面板请求超时");
        result.whenComplete((response, error) -> this.pendingRequests.decrementAndGet());
        return result;
    }

    public boolean isActive() {
        return !this.closed.get() && this.channel != null && this.channel.isActive()
                && this.ready.isDone() && !this.ready.isCompletedExceptionally();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        Channel current = this.channel;
        this.channel = null;
        if (current != null) current.close().awaitUninterruptibly();
        this.pending.failAll("连接已关闭");
        PanelNet.shutdown(this.controlCallbacks);
        PanelNet.shutdown(this.callbacks);
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<PanelFrame> {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final PanelNet.Heartbeat heartbeat = new PanelNet.Heartbeat(requestIds, PONG_TIMEOUT_SECONDS);
        private volatile boolean inactive;

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
            if (event instanceof SslHandshakeCompletionEvent handshake) {
                if (!handshake.isSuccess()) {
                    ready.completeExceptionally(handshake.cause());
                    context.close();
                    return;
                }
                if (peerId != null) {
                    JsonObject meta = new JsonObject();
                    meta.addProperty("id", peerId);
                    context.writeAndFlush(new PanelFrame(PanelFrame.PEER_REGISTER, 0, meta, new byte[0]));
                } else {
                    ready.complete(null);
                }
                return;
            }
            if (event instanceof IdleStateEvent) {
                this.heartbeat.pingOrClose(context);
                return;
            }
            super.userEventTriggered(context, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, PanelFrame frame) {
            try {
                switch (frame.type()) {
                    case PanelFrame.RESPONSE -> pending.complete(frame);
                    case PanelFrame.REQUEST -> handleIncoming(context, frame, incomingRequests);
                    case PanelFrame.TOKEN_UPDATE -> handleTokenUpdate(context, frame);
                    case PanelFrame.PING -> context.writeAndFlush(
                            new PanelFrame(PanelFrame.PONG, frame.requestId(), new JsonObject(), new byte[0]));
                    case PanelFrame.PONG -> this.heartbeat.pong(frame.requestId());
                    case PanelFrame.PEER_REGISTERED -> handlePeerRegistered(context, frame);
                    case PanelFrame.EVENT -> handleEvent(context, frame);
                    default -> context.close();
                }
            } catch (Exception error) {
                pending.fail(frame.requestId(), error);
                if (!ready.isDone()) ready.completeExceptionally(error);
                context.close();
            }
        }

        private void handleEvent(ChannelHandlerContext context, PanelFrame frame) {
            if (events == null || peerId != null) {
                context.close();
                return;
            }
            events.accept(PanelWire.event(frame));
        }

        private void handlePeerRegistered(ChannelHandlerContext context, PanelFrame frame) {
            if (tokenUpdates == null || !frame.meta().has("token")) {
                ready.completeExceptionally(new IllegalStateException("注册响应缺少 token"));
                context.close();
                return;
            }
            String authoritative = frame.meta().get("token").getAsString();
            if (!submitControl(() -> {
                try {
                    PanelResponse response = tokenUpdates.apply(authoritative);
                    if (response == null || response.status() >= 400) {
                        ready.completeExceptionally(new IllegalStateException("集群 token 采纳失败"));
                        context.close();
                    } else {
                        ready.complete(null);
                    }
                } catch (Exception error) {
                    ready.completeExceptionally(error);
                    context.close();
                }
            })) {
                ready.completeExceptionally(new IllegalStateException("连接回调队列已满"));
                context.close();
            }
        }

        private void handleTokenUpdate(ChannelHandlerContext context, PanelFrame frame) {
            if (tokenUpdates == null) {
                context.close();
                return;
            }
            if (!frame.meta().has("token")) {
                PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(400, "token 缺失"));
                return;
            }
            String next = frame.meta().get("token").getAsString();
            if (!submitControl(() -> {
                try {
                    PanelWire.sendResponse(context, frame.requestId(), tokenUpdates.apply(next));
                } catch (Exception error) {
                    PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(500, messageOf(error)));
                }
            })) {
                PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(503, PanelNet.BUSY));
            }
        }

        private void handleIncoming(ChannelHandlerContext context, PanelFrame frame,
                                    Function<PanelRequest, PanelResponse> handler) {
            if (handler == null) {
                context.close();
                return;
            }
            if (!PanelNet.submitBounded(callbacks, this.inFlight, MAX_IN_FLIGHT_PER_CONNECTION,
                    this::alive, () -> {
                PanelRequest request;
                try {
                    request = PanelWire.request(frame);
                } catch (Exception error) {
                    PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(400, messageOf(error)));
                    return;
                }
                try {
                    PanelWire.sendResponse(context, frame.requestId(), handler.apply(request));
                } catch (Exception error) {
                    PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(500, messageOf(error)));
                }
            })) {
                PanelWire.sendResponse(context, frame.requestId(), PanelResponse.error(503, PanelNet.BUSY));
            }
        }

        private boolean alive() {
            Channel current = PanelTcpClient.this.channel;
            return !this.inactive && current != null && current.isActive();
        }

        private boolean submitControl(Runnable task) {
            if (!alive()) return false;
            try {
                controlCallbacks.execute(() -> {
                    if (!this.inactive) task.run();
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                return false;
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            this.inactive = true;
            this.heartbeat.reset();
            if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("连接在注册前断开"));
            pending.failAll("连接已断开");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (!ready.isDone()) ready.completeExceptionally(cause);
            context.close();
        }
    }

}
