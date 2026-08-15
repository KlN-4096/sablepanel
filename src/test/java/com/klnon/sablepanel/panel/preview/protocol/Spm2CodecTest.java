package com.klnon.sablepanel.panel.preview.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Spm2CodecTest {
    /** record 对数组按身份比较;往返断言在测试侧逐字段比,生产不再为此背 equals/hashCode */
    private static void assertRoundTrip(Spm2Mesh mesh) {
        Spm2Mesh decoded = Spm2Codec.decode(Spm2Codec.encode(mesh));
        assertEquals(mesh.metadata(), decoded.metadata());
        assertEquals(mesh.records(), decoded.records());
        assertArrayEquals(mesh.shellBitmap(), decoded.shellBitmap());
    }

    @Test void roundTripBothWidthsAndEmpty() {
        assertRoundTrip(new Spm2Mesh("{\"n\":1}", List.of(new Spm2Record(1, 2, 3, 4)), new byte[]{1}));
        assertRoundTrip(new Spm2Mesh("", List.of(new Spm2Record(65536, 0, 1, 2)), new byte[]{0}));
        assertRoundTrip(new Spm2Mesh("{}", List.of(), new byte[0]));
    }

    @Test void rejectsUnknownFlagsLengthsAndTail() {
        Spm2Mesh mesh = new Spm2Mesh("x", List.of(), new byte[0]);
        byte[] encoded = Spm2Codec.encode(mesh);
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(6, (short) 2);
        byte[] unknownFlags = encoded;
        assertThrows(IllegalArgumentException.class, () -> Spm2Codec.decode(unknownFlags));
        encoded = Spm2Codec.encode(mesh);
        header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(28, 999);
        byte[] badLength = encoded;
        assertThrows(IllegalArgumentException.class, () -> Spm2Codec.decode(badLength));
        byte[] tail = java.util.Arrays.copyOf(Spm2Codec.encode(mesh), encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> Spm2Codec.decode(tail));
    }

    @Test void rejectsInvalidRecordsAndBitmap() {
        assertThrows(IllegalArgumentException.class, () -> new Spm2Record(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Spm2Mesh("", List.of(new Spm2Record(0, 0, 0, 0)), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Spm2Codec.decode(new byte[31]));

        byte[] invalidUtf8 = Spm2Codec.encode(new Spm2Mesh("x", List.of(), new byte[0]));
        // 空 metadata 空载荷的编码长度就是头长——头字段变化时偏移跟着走,写坏的始终是 metadata 首字节
        int headerBytes = Spm2Codec.encode(new Spm2Mesh("", List.of(), new byte[0])).length;
        invalidUtf8[headerBytes] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> Spm2Codec.decode(invalidUtf8));
    }
}
