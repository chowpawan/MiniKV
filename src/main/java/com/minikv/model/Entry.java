package com.minikv.model;

public class Entry implements Comparable<Entry> {
    private final String key;
    private final byte[] value;
    private final long seqNum;
    private final EntryType type;

    public Entry(String key, byte[] value, long seqNum, EntryType type) {
        this.key = key;
        this.value = value;
        this.seqNum = seqNum;
        this.type = type;
    }

    public String getKey() { return key; }
    public byte[] getValue() { return value; }
    public long getSeqNum() { return seqNum; }
    public EntryType getType() { return type; }
    public boolean isTombstone() { return type == EntryType.DELETE; }

    @Override
    public int compareTo(Entry other) {
        int keyCmp = this.key.compareTo(other.key);
        if (keyCmp != 0) return keyCmp;
        return Long.compare(other.seqNum, this.seqNum);
    }
}
