package com.klnon.sablepanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件/作业日志每次启动新建一个文件,从前既不轮转也不淘汰 —— 反复重启就是无限累积文件。
 * 文件名带启动时间戳,字典序即时间序,淘汰只按名字排序即可。
 */
class EventLogRetentionTest {

    @TempDir
    Path dir;

    @Test
    void pruneKeepsOnlyTheNewestFilesAndIgnoresOtherPrefixes() throws Exception {
        for (int i = 1; i <= 6; i++) {
            Files.writeString(this.dir.resolve(String.format("events-2026010%d-000000.jsonl", i)), "{}\n");
        }
        Files.writeString(this.dir.resolve("jobs-20260101-000000.jsonl"), "{}\n");

        EventLog.prune(this.dir, "events-", 3);

        List<String> left;
        try (var stream = Files.list(this.dir)) {
            left = stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of(
                "events-20260104-000000.jsonl",
                "events-20260105-000000.jsonl",
                "events-20260106-000000.jsonl",
                "jobs-20260101-000000.jsonl"), left, "只留最新 3 个 events-,别的前缀不许动");
    }

    /**
     * 单文件写满 16 MiB 就换文件,但文件名只精确到秒 —— 同一秒内写满时旧实现会以 APPEND
     * 重开同一个路径,轮转等于没做。撞名要往后加分片号。
     */
    @Test
    void nextFileSkipsFilesThatAreAlreadyFull() throws Exception {
        // 时间戳写死:用 now() 的话,fill() 期间跨过一秒就会拿到新名字,
        // 本该测的"同秒撞名"路径根本没走到 —— 这条用例从前 5 次里挂 1 次
        String time = "20260101-120000";
        Path first = EventLog.nextFile(this.dir, "events-", time);
        // 没写满就继续用它,不能每调用一次就开一个新分片
        assertEquals(first, EventLog.nextFile(this.dir, "events-", time));

        fill(first);
        Path second = EventLog.nextFile(this.dir, "events-", time);
        assertNotEquals(first, second, "同秒内写满必须换名字,否则会追加回同一个文件");
        assertTrue(second.getFileName().toString().endsWith("-1.jsonl"), second.getFileName().toString());

        fill(second);
        assertTrue(EventLog.nextFile(this.dir, "events-", time).getFileName().toString().endsWith("-2.jsonl"));

        // 分片名必须排在下一秒之前,prune 的字典序才仍然是时间序
        assertTrue("events-20260101-120000-1.jsonl".compareTo("events-20260101-120001.jsonl") < 0);
    }

    /** 把文件撑到上限:只写最后一个字节,别为了测边界真往堆里塞 16 MiB */
    private static void fill(Path file) throws Exception {
        try (var channel = Files.newByteChannel(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(EventLog.MAX_LOG_BYTES - 1);
            channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
    }

    @Test
    void pruneIsANoOpWhenUnderTheLimitOrDirectoryMissing() throws Exception {
        Files.writeString(this.dir.resolve("events-20260101-000000.jsonl"), "{}\n");
        EventLog.prune(this.dir, "events-", 20);
        assertTrue(Files.exists(this.dir.resolve("events-20260101-000000.jsonl")));
        // 目录不存在时静默返回,不能把启动流程炸掉
        EventLog.prune(this.dir.resolve("nope"), "events-", 1);
    }
}
