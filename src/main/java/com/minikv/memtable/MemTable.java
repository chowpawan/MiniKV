package com.minikv.memtable;

import com.minikv.model.Entry;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MemTable {
    private final ConcurrentSkipListMap<String, Entry> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sizeBytes = new AtomicLong(0);
    private final AtomicBoolean frozen = new AtomicBoolean(false);

    public void put(String key, Entry entry) {
        if (frozen.get()) throw new IllegalStateException("MemTable is frozen");
        Entry old = data.put(key, entry);
        long delta = key.length() + (entry.getValue() != null ? entry.getValue().length : 0);
        if (old != null) {
            long oldSize = old.getKey().length() + (old.getValue() != null ? old.getValue().length : 0);
            sizeBytes.addAndGet(delta - oldSize);
        } else {
            sizeBytes.addAndGet(delta);
        }
    }

    public Entry get(String key) { return data.get(key); }

    public boolean shouldFlush(long thresholdBytes) { return sizeBytes.get() >= thresholdBytes; }

    public void freeze() { frozen.set(true); }
    public boolean isFrozen() { return frozen.get(); }
    public long sizeBytes() { return sizeBytes.get(); }
    public int size() { return data.size(); }

    public Iterator<Map.Entry<String, Entry>> iterator() { return data.entrySet().iterator(); }

    public Iterator<Map.Entry<String, Entry>> iterator(String fromKey, String toKey) {
        return data.subMap(fromKey, true, toKey, true).entrySet().iterator();
    }
}
