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

    public List<IndexEntry> getEntries() { return entries; }
    public int size() { return entries.size(); }
}
