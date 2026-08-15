package com.klnon.sablepanel.panel.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 原语:此前预览/资源五处各抄一份 {@code getInstance("SHA-256")} + 不可能异常兜底。
 * JDK 保证 SHA-256 必在,{@link NoSuchAlgorithmException} 一律折成 AssertionError。
 */
public final class Digests {
    private Digests() {
    }

    /** 流式调用方自取实例(逐段 update 后自行 formatHex)。 */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    /** 整块字节 → 小写 hex 摘要。 */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }
}
