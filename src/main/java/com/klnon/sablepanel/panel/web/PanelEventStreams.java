package com.klnon.sablepanel.panel.web;

import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded SSE fan-out. Publishers only replace one pending invalidation per slow client. */
final class PanelEventStreams implements AutoCloseable {
    private static final int MAX_STREAMS = 8;
    private static final int HEARTBEAT_SECONDS = 15;
    private static final byte[] READY = "retry: 2000\nevent: ready\ndata: {}\n\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEARTBEAT = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

    private final Set<Stream> streams = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_STREAMS);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sablepanel-sse-", 0).factory());

    boolean open(HttpExchange exchange) throws IOException {
        if (this.closed.get() || !this.slots.tryAcquire()) return false;
        Stream stream = new Stream(exchange);
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            this.streams.add(stream);
            this.executor.execute(stream);
            return true;
        } catch (RuntimeException | IOException error) {
            this.streams.remove(stream);
            this.slots.release();
            exchange.close();
            throw error;
        }
    }

    void publish(PanelEvent event) {
        if (this.streams.isEmpty()) return;
        byte[] payload = ("id: " + event.revision() + "\nevent: bodies"
                + "\ndata: " + event.toJson() + "\n\n").getBytes(StandardCharsets.UTF_8);
        this.streams.forEach(stream -> stream.offer(payload));
    }

    void resync() {
        this.streams.forEach(stream -> stream.offer(READY));
    }

    void closeStreams() {
        this.streams.forEach(Stream::close);
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        this.streams.forEach(Stream::close);
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Stream implements Runnable {
        private final HttpExchange exchange;
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private final ArrayBlockingQueue<Boolean> wake = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean streamClosed = new AtomicBoolean();

        private Stream(HttpExchange exchange) {
            this.exchange = exchange;
        }

        void offer(byte[] payload) {
            if (this.streamClosed.get()) return;
            this.latest.set(payload);
            this.wake.offer(Boolean.TRUE);
        }

        @Override
        public void run() {
            try (OutputStream output = this.exchange.getResponseBody()) {
                output.write(READY);
                output.flush();
                while (!this.streamClosed.get() && !closed.get()) {
                    boolean changed = this.wake.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS) != null;
                    byte[] payload = changed ? this.latest.getAndSet(null) : HEARTBEAT;
                    if (payload == null) continue;
                    output.write(payload);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Browser disconnects are the normal stream termination path.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                close();
                streams.remove(this);
                slots.release();
            }
        }

        void close() {
            if (this.streamClosed.compareAndSet(false, true)) {
                this.exchange.close();
                this.wake.offer(Boolean.TRUE);
            }
        }
    }
}
