package com.klnon.sablepanel.panel.transport;

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

import java.util.Map;
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
import java.util.function.Function;

public final class PanelTcpClient implements AutoCloseable {
    private static final int CALLBACK_THREADS = 2;
    private static final int CALLBACK_QUEUE_CAPACITY = 32;
    private static final int CONTROL_QUEUE_CAPACITY = 16;
    private static final int MAX_IN_FLIGHT_PER_CONNECTION = 4;
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final int PONG_TIMEOUT_SECONDS = 10;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final AtomicLong CALLBACK_THREAD_IDS = new AtomicLong();
    private final NioEventLoopGroup group = new NioEventLoopGroup(1, runnable -> {
        Thread thread = new Thread(runnable, "sablepanel-tcp-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService callbacks = new ThreadPoolExecutor(
            CALLBACK_THREADS, CALLBACK_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CALLBACK_QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable,
                        "sablepanel-tcp-client-callback-" + CALLBACK_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService controlCallbacks = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable,
                        "sablepanel-tcp-client-control-" + CALLBACK_THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<Long, CompletableFuture<PanelResponse>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingRequests = new AtomicInteger();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Function<PanelRequest, PanelResponse> incomingRequests;
    private final Function<String, PanelResponse> tokenUpdates;
    private final String peerId;
    private volatile Channel channel;

    private PanelTcpClient(Function<PanelRequest, PanelResponse> incomingRequests,
                           Function<String, PanelResponse> tokenUpdates, String peerId) {
        this.incomingRequests = incomingRequests;
        this.tokenUpdates = tokenUpdates;
        this.peerId = peerId;
    }

    public static PanelTcpClient connectManager(PanelEndpoint endpoint, String expectedFingerprint)
            throws Exception {
        PinnedTrustManager trust = new PinnedTrustManager(expectedFingerprint);
        SslContext ssl = SslContextBuilder.forClient().trustManager(trust)
                .protocols("TLSv1.3", "TLSv1.2").build();
        PanelTcpClient client = new PanelTcpClient(null, null, null);
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
        PanelTcpClient client = new PanelTcpClient(requests, tokenUpdates, peerId);
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
        if (this.pendingRequests.incrementAndGet() > MAX_PENDING_REQUESTS) {
            this.pendingRequests.decrementAndGet();
            return CompletableFuture.failedFuture(new RejectedExecutionException("待处理请求过多"));
        }
        long id = frame.requestId();
        CompletableFuture<PanelResponse> result = new CompletableFuture<>();
        result.whenComplete((response, error) -> this.pendingRequests.decrementAndGet());
        this.pending.put(id, result);
        this.channel.writeAndFlush(frame).addListener(future -> {
            if (!future.isSuccess()) completeExceptionally(id, future.cause());
        });
        this.channel.eventLoop().schedule(() -> completeExceptionally(id,
                new java.util.concurrent.TimeoutException("面板请求超时")), 30, TimeUnit.SECONDS);
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
        this.pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("连接已关闭")));
        this.pending.clear();
        shutdownExecutor(this.controlCallbacks);
        shutdownExecutor(this.callbacks);
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void completeExceptionally(long id, Throwable error) {
        CompletableFuture<PanelResponse> future = this.pending.remove(id);
        if (future != null) future.completeExceptionally(error);
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<PanelFrame> {
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile boolean inactive;
        private long awaitingPongId;

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
                sendPingOrClose(context);
                return;
            }
            super.userEventTriggered(context, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, PanelFrame frame) {
            try {
                switch (frame.type()) {
                    case PanelFrame.RESPONSE, PanelFrame.ERROR -> {
                        CompletableFuture<PanelResponse> future = pending.remove(frame.requestId());
                        if (future != null) future.complete(PanelWire.response(frame));
                    }
                    case PanelFrame.REQUEST -> handleIncoming(context, frame, incomingRequests);
                    case PanelFrame.TOKEN_UPDATE -> handleTokenUpdate(context, frame);
                    case PanelFrame.PING -> context.writeAndFlush(
                            new PanelFrame(PanelFrame.PONG, frame.requestId(), new JsonObject(), new byte[0]));
                    case PanelFrame.PONG -> acceptPong(frame.requestId());
                    case PanelFrame.PEER_REGISTERED -> handlePeerRegistered(context, frame);
                    default -> context.close();
                }
            } catch (Exception error) {
                completeExceptionally(frame.requestId(), error);
                if (!ready.isDone()) ready.completeExceptionally(error);
                context.close();
            }
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
                sendResponse(context, frame.requestId(), PanelResponse.error(400, "token 缺失"));
                return;
            }
            String next = frame.meta().get("token").getAsString();
            if (!submitControl(() -> {
                try {
                    PanelResponse response = tokenUpdates.apply(next);
                    sendResponse(context, frame.requestId(), response != null
                            ? response : PanelResponse.error(500, "token 更新未返回响应"));
                } catch (Exception error) {
                    sendResponse(context, frame.requestId(), PanelResponse.error(500, messageOf(error)));
                }
            })) {
                sendResponse(context, frame.requestId(), PanelResponse.error(503, "服务器繁忙,请稍后重试"));
            }
        }

        private void handleIncoming(ChannelHandlerContext context, PanelFrame frame,
                                    Function<PanelRequest, PanelResponse> handler) {
            if (handler == null) {
                context.close();
                return;
            }
            if (!submitCallback(() -> {
                PanelRequest request;
                try {
                    request = PanelWire.request(frame);
                } catch (Exception error) {
                    sendResponse(context, frame.requestId(), PanelResponse.error(400, messageOf(error)));
                    return;
                }
                try {
                    PanelResponse response = handler.apply(request);
                    sendResponse(context, frame.requestId(), response != null
                            ? response : PanelResponse.error(500, "请求处理未返回响应"));
                } catch (Exception error) {
                    sendResponse(context, frame.requestId(), PanelResponse.error(500, messageOf(error)));
                }
            })) {
                sendResponse(context, frame.requestId(), PanelResponse.error(503, "服务器繁忙,请稍后重试"));
            }
        }

        private boolean submitCallback(Runnable task) {
            if (this.inactive || PanelTcpClient.this.channel == null || !PanelTcpClient.this.channel.isActive()) {
                return false;
            }
            if (this.inFlight.incrementAndGet() > MAX_IN_FLIGHT_PER_CONNECTION) {
                this.inFlight.decrementAndGet();
                return false;
            }
            try {
                callbacks.execute(() -> {
                    try {
                        if (!this.inactive) task.run();
                    } finally {
                        this.inFlight.decrementAndGet();
                    }
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                this.inFlight.decrementAndGet();
                return false;
            }
        }

        private boolean submitControl(Runnable task) {
            if (this.inactive || PanelTcpClient.this.channel == null || !PanelTcpClient.this.channel.isActive()) {
                return false;
            }
            try {
                controlCallbacks.execute(() -> {
                    if (!this.inactive) task.run();
                });
                return true;
            } catch (RejectedExecutionException rejected) {
                return false;
            }
        }

        private void sendResponse(ChannelHandlerContext context, long id, PanelResponse response) {
            try {
                context.writeAndFlush(PanelWire.response(id,
                        response != null ? response : PanelResponse.error(500, "请求处理未返回响应")));
            } catch (Exception error) {
                context.close();
            }
        }

        private void sendPingOrClose(ChannelHandlerContext context) {
            if (this.awaitingPongId != 0) {
                context.close();
                return;
            }
            long pingId = requestIds.incrementAndGet();
            this.awaitingPongId = pingId;
            context.writeAndFlush(new PanelFrame(PanelFrame.PING, pingId, new JsonObject(), new byte[0]))
                    .addListener(write -> {
                        if (!write.isSuccess()) context.close();
                    });
            context.executor().schedule(() -> {
                if (this.awaitingPongId == pingId && context.channel().isActive()) context.close();
            }, PONG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void acceptPong(long id) {
            if (this.awaitingPongId == id) this.awaitingPongId = 0;
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            this.inactive = true;
            this.awaitingPongId = 0;
            if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("连接在注册前断开"));
            pending.forEach((id, future) -> future.completeExceptionally(new IllegalStateException("连接已断开")));
            pending.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            if (!ready.isDone()) ready.completeExceptionally(cause);
            context.close();
        }
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }
}
