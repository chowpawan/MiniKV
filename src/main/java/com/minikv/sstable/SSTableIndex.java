package com.minikv.sstable;

import java.util.ArrayList;
import java.util.List;

public class SSTableIndex {
    public record IndexEntry(String key, long offset) {}

    private final List<IndexEntry> entries = new ArrayList<>();

    public void add(String key, long offset) { entries.add(new IndexEntry(key, offset)); }

    public long findBlockOffset(String targetKey) {
        if (entries.isEmpty()) return 0;
        int lo = 0, hi = entries.size() - 1;
        long result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = entries.get(mid).key().compareTo(targetKey);
            if (cmp <= 0) { result = entries.get(mid).offset(); lo = mid + 1; }
            else { hi = mid - 1; }
        }
        return result;
    }

    public long[] findBlockRange(String targetKey) {
        if (entries.isEmpty()) return new long[]{0, Long.MAX_VALUE};
        int lo = 0, hi = entries.size() - 1, startIdx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = entries.get(mid).key().compareTo(targetKey);
            if (cmp <= 0) { startIdx = mid; lo = mid + 1; }
            else { hi = mid - 1; }
        }
        if (startIdx < 0) startIdx = 0;
        long start = entries.get(startIdx).offset();
        long end = (startIdx + 1 < entries.size()) ? entries.get(startIdx + 1).offset() : Long.MAX_VALUE;
        return new long[]{start, end};
    }

    public List<IndexEntry> getEntries() { return entries; }
    public int size() { return entries.size(); }
}
