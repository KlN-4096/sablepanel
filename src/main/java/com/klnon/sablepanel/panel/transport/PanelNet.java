package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.api.PanelResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * transport 两端(TcpServer/TcpClient/网关)共用的线程与连接管线小件。
 * 从前每样各写两三份:具名守护线程工厂×8、有界池×5、关停×4、PING/PONG 探活×2、
 * 在途配额提交×2、pending 请求跟踪×2。
 */
public final class PanelNet {
    /** 外层(浏览器→网关→HOST)请求超时 */
    public static final int REQUEST_TIMEOUT_SECONDS = 30;
    /** 内层(HOST→PEER)超时:比外层短 5 秒,PEER 卡死时先到期,断层可归因 */
    public static final int PEER_TIMEOUT_SECONDS = 25;
    /** 请求体上限:HTTP 侧(网关 readBody)与 TLS 帧侧(PanelFrameDecoder)必须恒等,单一事实源 */
    public static final int MAX_REQUEST_BODY = 1024 * 1024;
    static final String BUSY = "服务器繁忙,请稍后重试";
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 2;

    private PanelNet() {
    }

    /** 整块 gzip:PanelWire 响应压缩与网关静态资源缓存共用 */
    public static byte[] gzip(byte[] value) throws java.io.IOException {
        var output = new java.io.ByteArrayOutputStream(Math.max(64, value.length / 3));
        try (var gzip = new java.util.zip.GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    public static ThreadFactory daemonThreads(String prefix) {
        AtomicLong ids = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 固定线程+有界队列+满即拒绝:transport 所有池的唯一形状,拒绝由调用方兑成 503 */
    public static ExecutorService boundedPool(String prefix, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonThreads(prefix),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 先礼后兵:给在途任务 2 秒收尾(可能是做到一半的操作),再强停 */
    public static void shutdown(ExecutorService pool) {
        if (pool == null) return;
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

    /** 带在途配额的池提交:超配额或池满返回 false,由调用方回 503;跑前再验一次连接还活着 */
    static boolean submitBounded(ExecutorService pool, AtomicInteger slots, int max,
                                 BooleanSupplier alive, Runnable task) {
        if (!alive.getAsBoolean()) return false;
        if (slots.incrementAndGet() > max) {
            slots.decrementAndGet();
            return false;
        }
        try {
            pool.execute(() -> {
                try {
                    if (alive.getAsBoolean()) task.run();
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

    /** 读空闲探活:发 PING,PONG 不在限时内回来就断。状态只在通道的事件循环上读写 */
    static final class Heartbeat {
        private final AtomicLong ids;
        private final int pongTimeoutSeconds;
        private long awaitingPongId;

        Heartbeat(AtomicLong ids, int pongTimeoutSeconds) {
            this.ids = ids;
            this.pongTimeoutSeconds = pongTimeoutSeconds;
        }

        void pingOrClose(ChannelHandlerContext context) {
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
            }, this.pongTimeoutSeconds, TimeUnit.SECONDS);
        }

        void pong(long id) {
            if (this.awaitingPongId == id) this.awaitingPongId = 0;
        }

        void reset() {
            this.awaitingPongId = 0;
        }
    }

    /** 在途请求跟踪:发帧+登记 future,写失败/超时兜底,响应帧回来对号完成 */
    static final class Pending {
        private final Map<Long, CompletableFuture<PanelResponse>> map = new ConcurrentHashMap<>();

        CompletableFuture<PanelResponse> track(Channel channel, PanelFrame frame,
                                               int timeoutSeconds, String timeoutMessage) {
            long id = frame.requestId();
            CompletableFuture<PanelResponse> future = new CompletableFuture<>();
            this.map.put(id, future);
            channel.writeAndFlush(frame).addListener(write -> {
                if (!write.isSuccess()) fail(id, write.cause());
            });
            channel.eventLoop().schedule(() -> fail(id, new TimeoutException(timeoutMessage)),
                    timeoutSeconds, TimeUnit.SECONDS);
            return future;
        }

        /** 解包失败先让等待方拿到根因再抛(从前直接抛,future 已出表只能干等超时),连接照关 */
        void complete(PanelFrame frame) throws IOException {
            CompletableFuture<PanelResponse> future = this.map.remove(frame.requestId());
            if (future == null) return;
            try {
                future.complete(PanelWire.response(frame));
            } catch (IOException | RuntimeException error) {
                future.completeExceptionally(error);
                throw error;
            }
        }

        void fail(long id, Throwable error) {
            CompletableFuture<PanelResponse> future = this.map.remove(id);
            if (future != null) future.completeExceptionally(error);
        }

        void failAll(String message) {
            this.map.values().forEach(future ->
                    future.completeExceptionally(new IllegalStateException(message)));
            this.map.clear();
        }
    }
}
