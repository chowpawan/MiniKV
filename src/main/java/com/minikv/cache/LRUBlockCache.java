package com.minikv.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LRUBlockCache {
    private final Map<String, byte[]> cache;
    private final int maxBlocks;

    public LRUBlockCache(int maxBlocks) {
        this.maxBlocks = maxBlocks;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(maxBlocks, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > maxBlocks;
            }
        });
    }

    public byte[] get(String cacheKey) { return cache.get(cacheKey); }
    public void put(String cacheKey, byte[] block) { cache.put(cacheKey, block); }
    public boolean contains(String cacheKey) { return cache.containsKey(cacheKey); }
    public int size() { return cache.size(); }
    public void invalidate(String cacheKey) { cache.remove(cacheKey); }
}
