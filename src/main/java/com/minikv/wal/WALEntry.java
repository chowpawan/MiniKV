package com.minikv.wal;

import com.minikv.model.EntryType;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Binary format per entry:
 * [4: keyLen][N: key][4: valLen][M: value][1: type][8: seqNum][4: CRC32]
 */
public class WALEntry {
    private static final int OVERHEAD = 4 + 4 + 1 + 8 + 4;

    public final String key;
    public final byte[] value;
    public final EntryType type;
    public final long seqNum;

    public WALEntry(String key, byte[] value, EntryType type, long seqNum) {
        this.key = key;
        this.value = value == null ? new byte[0] : value;
        this.type = type;
        this.seqNum = seqNum;
    }

    public byte[] serialize() {
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalSize = OVERHEAD + keyBytes.length + value.length;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(value.length);
        buf.put(value);
        buf.put(type.code);
        buf.putLong(seqNum);
        byte[] payload = new byte[buf.position()];
        buf.rewind();
        buf.get(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    public static WALEntry deserialize(ByteBuffer buf) {
        if (buf.remaining() < 4) return null;
        int startPos = buf.position();
        int keyLen = buf.getInt();
        if (keyLen < 0 || buf.remaining() < keyLen) return null;
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        if (buf.remaining() < 4) return null;
        int valLen = buf.getInt();
        if (valLen < 0 || buf.remaining() < valLen) return null;
        byte[] value = new byte[valLen];
        buf.get(value);
        if (buf.remaining() < 1 + 8 + 4) return null;
        byte opCode = buf.get();
        long seqNum = buf.getLong();
        int storedCrc = buf.getInt();
        int endPos = buf.position();
        int payloadLen = endPos - startPos - 4;
        byte[] payload = new byte[payloadLen];
        buf.position(startPos);
        buf.get(payload);
        buf.position(endPos);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != storedCrc) return null;
        EntryType entryType = EntryType.fromCode(opCode);
        String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
        return new WALEntry(key, value, entryType, seqNum);
    }
}
