package com.klnon.sablepanel.panel;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 主线程任务提交的唯一入口:同线程直跑快路径 + 可选超时。
 * 从前 OpsService 与 ConsistencyService 各写一份,快路径与超时语义不一致。
 */
public final class MainThread {
    private MainThread() {
    }

    public static <T> CompletableFuture<T> submit(MinecraftServer server, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** 有超时:请求路径用,主线程卡死时调用方拿到 TimeoutException 而不是永远等 */
    public static <T> T on(MinecraftServer server, long timeoutSeconds, Callable<T> task) throws Exception {
        if (server.isSameThread()) return task.call();
        return submit(server, task).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /** 无超时:加载可能触发区块同步生成的写路径用(跑在作业线程上,进度由 JobService 可见) */
    public static <T> T onUntilComplete(MinecraftServer server, Callable<T> task) throws Exception {
        if (server.isSameThread()) return task.call();
        return submit(server, task).get();
    }
}
