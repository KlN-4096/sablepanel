package com.klnon.sablepanel.panel.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.Writer;

/**
 * 量一个 JSON 片段序列化之后到底有多少字节。
 * <p>
 * 从前是按字段类型估:{@code MEMBER_BASE_BYTES}、{@code GROUP_BASE_BYTES}、
 * {@code PALETTE_ENTRY_BYTES} 之类一堆常量,加起来当作响应大小的上界。问题是估算函数和
 * 真正拼 JSON 的代码是两处独立的东西 —— 输出里多了一个字段而估算里没跟上,预算就悄悄失真,
 * 而且失真的方式无声无息。连续四轮审计,每轮都能找出一个漏记的字段:clone_sets 的名称、
 * 组名、冗余条目列表……本质上是同一个 bug 反复出现。
 * <p>
 * 所以不估了,直接量。代价是候选片段要多走一次序列化,但只过计数器不落字符串,不额外占堆。
 * 量的是 {@link Gson} 转义之后的真实输出,和最终发出去的字节一致。
 */
final class JsonSize {
    private static final Gson GSON = new Gson();

    private JsonSize() {
    }

    /** 序列化后的 UTF-8 字节数 */
    static long of(JsonElement element) {
        CountingWriter counter = new CountingWriter();
        GSON.toJson(element, counter);
        return counter.bytes;
    }

    /** 只数字节、不留内容的 Writer */
    private static final class CountingWriter extends Writer {
        private long bytes;

        @Override
        public void write(char[] buffer, int offset, int length) {
            for (int i = 0; i < length; i++) this.bytes += utf8Length(buffer[offset + i]);
        }

        @Override
        public void write(int c) {
            this.bytes += utf8Length((char) c);
        }

        @Override
        public void write(String text, int offset, int length) {
            for (int i = 0; i < length; i++) this.bytes += utf8Length(text.charAt(offset + i));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private static int utf8Length(char c) {
            if (c < 0x80) return 1;
            if (c < 0x800) return 2;
            // 代理对的两个 char 合起来是 4 字节,各算一半
            return Character.isSurrogate(c) ? 2 : 3;
        }
    }
}
