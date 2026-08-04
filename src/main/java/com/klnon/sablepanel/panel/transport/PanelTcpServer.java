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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PanelTcpServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PanelTcpServer.class);
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workers = new NioEventLoopGroup(2);
    private final ExecutorService callbacks = Executors.newFixedThreadPool(4);
    private final Map<String, ConnectionHandler> peers = new ConcurrentHashMap<>();
    private final Function<PanelRequest, PanelResponse> managerRequests;
    private final Supplier<String> token;
    private final String selfId;
    private volatile Channel channel;

    public PanelTcpServer(String selfId, Function<PanelRequest, PanelResponse> managerRequests,
                          Supplier<String> token) {
        this.selfId = selfId;
        this.managerRequests = managerRequests;
        this.token = token;
    }

    public void start(String bind, int port, SslContext ssl) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap().group(this.boss, this.workers)
                .channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                        channel.pipeline().addLast(new IdleStateHandler(45, 20, 0));
                        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                PanelWire.MAX_FRAME_BYTES, 0, 4, 0, 4));
                        channel.pipeline().addLast(new LengthFieldPrepender(4));
                        channel.pipeline().addLast(new PanelFrameCodec());
                        channel.pipeline().addLast(new ConnectionHandler());
                    }
                });
        ChannelFuture future = bootstrap.bind(bind, port).sync();
        this.channel = future.channel();
    }

    public Set<String> peerIds() {
        return new TreeSet<>(this.peers.keySet());
    }

    public int port() {
        if (this.channel == null) return -1;
        return ((InetSocketAddress) this.channel.localAddress()).getPort();
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
        if (this.channel != null) this.channel.close().awaitUninterruptibly();
        this.peers.values().forEach(ConnectionHandler::close);
        this.callbacks.shutdownNow();
        this.workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        this.boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private final class ConnectionHandler extends SimpleChannelInboundHandler<PanelFrame> {
        private final Map<Long, CompletableFuture<PanelResponse>> pending = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private volatile ChannelHandlerContext context;
        private volatile String peerId;

        @Override
        public void handlerAdded(ChannelHandlerContext context) {
            this.context = context;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, PanelFrame frame) {
            try {
                switch (frame.type()) {
                    case PanelFrame.PEER_REGISTER -> registerPeer(context, frame);
                    case PanelFrame.REQUEST -> callbacks.execute(() -> sendResponse(context, frame.requestId(),
                            managerRequests.apply(PanelWire.request(frame))));
                    case PanelFrame.RESPONSE, PanelFrame.ERROR -> {
                        CompletableFuture<PanelResponse> future = this.pending.remove(frame.requestId());
                        if (future != null) future.complete(PanelWire.response(frame));
                    }
                    case PanelFrame.PING -> context.writeAndFlush(
                            new PanelFrame(PanelFrame.PONG, frame.requestId(), new JsonObject(), new byte[0]));
                    case PanelFrame.PONG -> { }
                    default -> context.close();
                }
            } catch (Exception error) {
                context.close();
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
            ConnectionHandler previous = peers.put(id, this);
            if (previous != null && previous != this) previous.close();
            JsonObject meta = new JsonObject();
            meta.addProperty("token", token.get());
            context.writeAndFlush(new PanelFrame(PanelFrame.PEER_REGISTERED, 0, meta, new byte[0]));
            LOGGER.info("sablepanel: peer {} joined the TLS cluster", id);
        }

        CompletableFuture<PanelResponse> request(PanelRequest request) {
            long id = this.ids.incrementAndGet();
            CompletableFuture<PanelResponse> future = new CompletableFuture<>();
            this.pending.put(id, future);
            this.context.writeAndFlush(PanelWire.request(id, request));
            scheduleTimeout(id);
            return future;
        }

        CompletableFuture<PanelResponse> updateToken(String next) {
            long id = this.ids.incrementAndGet();
            CompletableFuture<PanelResponse> future = new CompletableFuture<>();
            this.pending.put(id, future);
            JsonObject meta = new JsonObject();
            meta.addProperty("token", next);
            this.context.writeAndFlush(new PanelFrame(PanelFrame.TOKEN_UPDATE, id, meta, new byte[0]));
            scheduleTimeout(id);
            return future;
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

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) {
            if (event instanceof IdleStateEvent) {
                context.writeAndFlush(new PanelFrame(PanelFrame.PING, this.ids.incrementAndGet(), new JsonObject(), new byte[0]));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (this.peerId != null) peers.remove(this.peerId, this);
            this.pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("PEER 已断开")));
            this.pending.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }

        void close() {
            if (this.context != null) this.context.close();
        }
    }
}
