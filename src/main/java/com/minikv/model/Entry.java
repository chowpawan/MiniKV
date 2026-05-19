package com.minikv.model;

public class Entry implements Comparable<Entry> {
    private final String key;
    private final byte[] value;
    private final long seqNum;
    private final EntryType type;
    private final long expiresAt; // epoch ms; 0 = never expires

    public Entry(String key, byte[] value, long seqNum, EntryType type) {
        this(key, value, seqNum, type, 0);
    }

    public Entry(String key, byte[] value, long seqNum, EntryType type, long expiresAt) {
        this.key = key; this.value = value; this.seqNum = seqNum;
        this.type = type; this.expiresAt = expiresAt;
    }

    public String getKey() { return key; }
    public byte[] getValue() { return value; }
    public long getSeqNum() { return seqNum; }
    public EntryType getType() { return type; }
    public long getExpiresAt() { return expiresAt; }
    public boolean isTombstone() { return type == EntryType.DELETE; }
    public boolean isExpired() { return expiresAt > 0 && System.currentTimeMillis() > expiresAt; }
    public boolean hasTTL() { return expiresAt > 0; }

    @Override
    public int compareTo(Entry other) {
        int keyCmp = this.key.compareTo(other.key);
        if (keyCmp != 0) return keyCmp;
        return Long.compare(other.seqNum, this.seqNum);
    }
}
