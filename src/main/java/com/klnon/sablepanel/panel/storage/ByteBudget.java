package com.klnon.sablepanel.panel.storage;

import com.google.gson.JsonElement;

/**
 * 一份响应的字节账本:量、判、记账三件事绑成一次调用。
 * <p>
 * 拆开写过两轮,两轮都出同一类问题 —— 要么"检查在前、加入在后"中间隔了一段循环
 * (成员进来之后调色板还能继续无限追加),要么"至少发一条"的兜底分支自己没有上限。
 * 这两个坑不是某个字段写错了,是每个发送点都在各自重新实现一遍同样的规则。
 * <p>
 * 所以规则只实现一次:想把东西放进响应,就得走 {@link #offer};它算得下才返回 true,
 * 同时把字节记上。拿不到"只判不记账"或"只记账不判"的用法。真正必须发出去的兜底
 * 走 {@link #charge},它无条件记账 —— 记账这一步没有跳过的余地。
 */
public final class ByteBudget {
    private final long limit;
    private long spent;

    public ByteBudget(long limit) {
        this.limit = limit;
    }

    public long spent() {
        return this.spent;
    }

    /** 额度已经用完:外层循环据此停下,不必再造候选 */
    public boolean exhausted() {
        return this.spent >= this.limit;
    }

    /**
     * 量一下这个候选,装得下就记账收下。
     *
     * @return false 表示放不下,调用方必须丢弃这个候选
     */
    public boolean offer(JsonElement candidate) {
        return offerBytes(JsonSize.of(candidate));
    }

    /** 已经量好尺寸的候选(条目连同它牵连的调色板字节)走这里,判与记账同样一次完成 */
    public boolean offerBytes(long size) {
        if (this.spent + size > this.limit) return false;
        this.spent += size;
        return true;
    }

    /**
     * 无条件收下并记账。只给"这一条必须发出去"的场合用(空列表比超限更糟的那种),
     * 调用方要自己保证这个候选的大小是有界的。
     *
     * @return 这个候选的字节数
     */
    public long charge(JsonElement candidate) {
        long size = JsonSize.of(candidate);
        this.spent += size;
        return size;
    }

    /** 把子预算(如 clone_sets)已花掉的字节并进总账 */
    public void charge(long bytes) {
        this.spent += bytes;
    }
}
