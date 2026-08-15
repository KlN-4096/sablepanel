package com.klnon.sablepanel.panel.preview.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict encoder/decoder for the version-2 structure preview wire format. */
public final class Spm2Codec {
    private static final int HEADER_BYTES = 32;
    private static final int PROTOCOL_VERSION = 2;
    private static final int FLAG_SHELL_BITMAP = 1;
    private static final int RECORD_BYTES_U16 = 8;
    private static final int RECORD_BYTES_U32 = 16;
    private static final int KNOWN_FLAGS = FLAG_SHELL_BITMAP;

    private Spm2Codec() {
    }

    public static byte[] encode(Spm2Mesh mesh) {
        byte[] metadata = mesh.metadata().getBytes(StandardCharsets.UTF_8);
        int padded = Math.addExact(metadata.length, (4 - metadata.length % 4) % 4);
        List<Spm2Record> records = mesh.records();
        int recordBytes = RECORD_BYTES_U16;
        for (Spm2Record r : records) {
            if (r.x() > 0xffff || r.y() > 0xffff || r.z() > 0xffff || r.stateIndex() > 0xffff) {
                recordBytes = RECORD_BYTES_U32;
                break;
            }
        }
        int shellBytes = mesh.shellBitmap().length;
        long payload = (long) padded + (long) recordBytes * records.size() + shellBytes;
        if (records.size() > 0xffffffffL || payload > 0xffffffffL) {
            throw new IllegalArgumentException("SPM2 payload exceeds uint32 length");
        }
        ByteBuffer out = ByteBuffer.allocate(Math.toIntExact(HEADER_BYTES + payload)).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'S').put((byte) 'P').put((byte) 'M').put((byte) '2');
        out.putShort((short) PROTOCOL_VERSION).putShort((short) FLAG_SHELL_BITMAP);
        out.putInt(HEADER_BYTES).putInt(metadata.length).putInt(records.size());
        out.putInt(shellBytes).putShort((short) recordBytes).putShort((short) 0).putInt(Math.toIntExact(payload));
        out.put(metadata);
        while (out.position() < HEADER_BYTES + padded) out.put((byte) 0);
        for (Spm2Record r : records) {
            if (recordBytes == RECORD_BYTES_U16) {
                out.putShort((short) r.x()).putShort((short) r.y()).putShort((short) r.z()).putShort((short) r.stateIndex());
            } else {
                out.putInt(r.x()).putInt(r.y()).putInt(r.z()).putInt(r.stateIndex());
            }
        }
        out.put(mesh.shellBitmap());
        return out.array();
    }

    public static Spm2Mesh decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES) throw new IllegalArgumentException("SPM2 header truncated");
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (in.get() != 'S' || in.get() != 'P' || in.get() != 'M' || in.get() != '2') throw new IllegalArgumentException("SPM2 magic mismatch");
        int version = Short.toUnsignedInt(in.getShort()), flags = Short.toUnsignedInt(in.getShort());
        long header = Integer.toUnsignedLong(in.getInt()), metaBytes = Integer.toUnsignedLong(in.getInt());
        long count = Integer.toUnsignedLong(in.getInt()), shell = Integer.toUnsignedLong(in.getInt());
        int recordBytes = Short.toUnsignedInt(in.getShort()), reserved = Short.toUnsignedInt(in.getShort());
        long payload = Integer.toUnsignedLong(in.getInt());
        if (version != PROTOCOL_VERSION || (flags & ~KNOWN_FLAGS) != 0 || header != HEADER_BYTES || reserved != 0) throw new IllegalArgumentException("SPM2 header fields invalid");
        if ((flags & FLAG_SHELL_BITMAP) == 0 || recordBytes != RECORD_BYTES_U16 && recordBytes != RECORD_BYTES_U32) throw new IllegalArgumentException("SPM2 flags or record width invalid");
        long padded = (metaBytes + 3) & ~3L, expectedPayload = padded + count * recordBytes + shell;
        if (metaBytes > Integer.MAX_VALUE || shell != (count + 7) / 8 || expectedPayload != payload || HEADER_BYTES + payload != bytes.length) throw new IllegalArgumentException("SPM2 lengths invalid");
        int metaStart = in.position();
        byte[] meta = new byte[Math.toIntExact(metaBytes)]; in.get(meta);
        for (int i = metaStart + (int) metaBytes; i < HEADER_BYTES + padded; i++) if (bytes[i] != 0) throw new IllegalArgumentException("SPM2 metadata padding is nonzero");
        in.position(HEADER_BYTES + Math.toIntExact(padded));
        List<Spm2Record> records = new ArrayList<>(Math.toIntExact(count));
        for (long i = 0; i < count; i++) records.add(recordBytes == 8 ? new Spm2Record(Short.toUnsignedInt(in.getShort()), Short.toUnsignedInt(in.getShort()), Short.toUnsignedInt(in.getShort()), Short.toUnsignedInt(in.getShort())) : new Spm2Record(in.getInt(), in.getInt(), in.getInt(), in.getInt()));
        byte[] bitmap = new byte[Math.toIntExact(shell)]; in.get(bitmap);
        try {
            String metadata = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(meta)).toString();
            return new Spm2Mesh(metadata, records, bitmap);
        } catch (CharacterCodingException invalidUtf8) {
            throw new IllegalArgumentException("SPM2 metadata is not valid UTF-8", invalidUtf8);
        }
    }
}
