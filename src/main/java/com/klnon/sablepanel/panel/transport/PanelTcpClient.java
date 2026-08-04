package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class PanelTcpClient implements AutoCloseable {
    private final NioEventLoopGroup group = new NioEventLoopGroup(1, runnable -> {
        Thread thread = new Thread(runnable, "sablepanel-tcp-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService callbacks = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sablepanel-tcp-callback");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, CompletableFuture<PanelResponse>> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
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
        Bootstrap bootstrap = new Bootstrap().group(this.group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(ssl.newHandler(channel.alloc(), endpoint.host(), endpoint.port()));
                        channel.pipeline().addLast(new IdleStateHandler(45, 20, 0));
                        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                PanelWire.MAX_FRAME_BYTES, 0, 4, 0, 4));
                        channel.pipeline().addLast(new LengthFieldPrepender(4));
                        channel.pipeline().addLast(new PanelFrameCodec());
                        channel.pipeline().addLast(new ClientHandler());
                    }
                });
        this.channel = bootstrap.connect(endpoint.host(), endpoint.port()).sync().channel();
        this.ready.get(10, TimeUnit.SECONDS);
    }

    public CompletableFuture<PanelResponse> request(PanelRequest request) {
        if (!isActive()) return CompletableFuture.failedFuture(new IllegalStateException("未连接到服务器"));
        long id = this.requestIds.incrementAndGet();
        CompletableFuture<PanelResponse> result = new CompletableFuture<>();
        this.pending.put(id, result);
        this.channel.writeAndFlush(PanelWire.request(id, request)).addListener(future -> {
            if (!future.isSuccess()) completeExceptionally(id, future.cause());
        });
        this.channel.eventLoop().schedule(() -> completeExceptionally(id,
                new java.util.concurrent.TimeoutException("面板请求超时")), 30, TimeUnit.SECONDS);
        return result;
    }

    public boolean isActive() {
        return this.channel != null && this.channel.isActive() && this.ready.isDone() && !this.ready.isCompletedExceptionally();
    }

    @Override
    public void close() {
        if (this.channel != null) this.channel.close().awaitUninterruptibly();
        this.pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("连接已关闭")));
        this.pending.clear();
        this.callbacks.shutdownNow();
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private void completeExceptionally(long id, Throwable error) {
        CompletableFuture<PanelResponse> future = this.pending.remove(id);
        if (future != null) future.completeExceptionally(error);
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<PanelFrame> {
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
                    case PanelFrame.PEER_REGISTERED -> {
                        if (tokenUpdates != null && frame.meta().has("token")) {
                            tokenUpdates.apply(frame.meta().get("token").getAsString());
                            ready.complete(null);
                        } else {
                            context.close();
                        }
                    }
                    default -> context.close();
                }
            } catch (Exception error) {
                completeExceptionally(frame.requestId(), error);
            }
        }

        private void handleTokenUpdate(ChannelHandlerContext context, PanelFrame frame) {
            if (tokenUpdates == null) {
                context.close();
                return;
            }
            callbacks.execute(() -> sendResponse(context, frame.requestId(),
                    tokenUpdates.apply(frame.meta().get("token").getAsString())));
        }

        private void handleIncoming(ChannelHandlerContext context, PanelFrame frame,
                                    Function<PanelRequest, PanelResponse> handler) {
            if (handler == null) {
                context.close();
                return;
            }
            callbacks.execute(() -> sendResponse(context, frame.requestId(), handler.apply(PanelWire.request(frame))));
        }

        private void sendResponse(ChannelHandlerContext context, long id, PanelResponse response) {
            try {
                context.writeAndFlush(PanelWire.response(id, response));
            } catch (Exception error) {
                context.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
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
}
