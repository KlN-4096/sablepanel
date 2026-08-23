package com.klnon.sablepanel.panel.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试 classpath 上是 sable 2.0.4(record),访问器应解析到 {@code type()};
 * 2.0.3 的 {@code getType()} 回退分支没法进单测 classpath,由实机(run/ 换 2.0.3 jar)验证。
 */
class ForceLoadTicketAccessorTest {

    @Test
    void resolvesRecordAccessorOn204Classpath() {
        assertEquals("type", ForceLoadService.resolveTicketTypeAccessor().getName());
    }
}
