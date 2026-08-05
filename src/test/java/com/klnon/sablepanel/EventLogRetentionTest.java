package com.klnon.sablepanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void pruneIsANoOpWhenUnderTheLimitOrDirectoryMissing() throws Exception {
        Files.writeString(this.dir.resolve("events-20260101-000000.jsonl"), "{}\n");
        EventLog.prune(this.dir, "events-", 20);
        assertTrue(Files.exists(this.dir.resolve("events-20260101-000000.jsonl")));
        // 目录不存在时静默返回,不能把启动流程炸掉
        EventLog.prune(this.dir.resolve("nope"), "events-", 1);
    }
}
