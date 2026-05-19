package com.minikv;

import com.minikv.cache.LRUBlockCache;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;
import com.minikv.sstable.SSTable;
import com.minikv.sstable.SSTableReader;
import com.minikv.sstable.SSTableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class BlockCacheTest {
    @TempDir Path tempDir;

    @Test
    void repeatedReadsHitCacheAfterFirstMiss() throws Exception {
        Path sstPath = tempDir.resolve("test.sst");
        TreeMap<String, Entry> map = new TreeMap<>();
        for (int i = 0; i < 20; i++) {
            String k = String.format("key-%02d", i);
            map.put(k, new Entry(k, ("val-" + i).getBytes(), i, EntryType.PUT, 0));
        }
        SSTable sst = new SSTableWriter(sstPath).write(map.entrySet().iterator(), map.size());
        sst.close();

        LRUBlockCache cache = new LRUBlockCache(64);
        SSTableReader reader = new SSTableReader(sstPath);
        try {
            Entry first = reader.get("key-00", cache);
            assertNotNull(first);
            assertEquals("val-0", new String(first.getValue()));
            assertEquals(1, reader.diskReadCount(), "first get must be a disk read");

            for (int i = 1; i < 100; i++) assertNotNull(reader.get("key-00", cache));
            assertEquals(1, reader.diskReadCount(), "99 cache hits must not increment diskReads");

            assertNotNull(reader.get("key-16", cache));
            assertEquals(2, reader.diskReadCount(), "different block triggers one more disk read");

            reader.get("key-16", cache);
            assertEquals(2, reader.diskReadCount(), "re-read of key-16 comes from cache");
        } finally { reader.close(); }
    }

    @Test
    void nullCacheDoesNotCrash() throws Exception {
        Path sstPath = tempDir.resolve("nocache.sst");
        TreeMap<String, Entry> map = new TreeMap<>();
        map.put("alpha", new Entry("alpha", "v".getBytes(), 1, EntryType.PUT, 0));
        new SSTableWriter(sstPath).write(map.entrySet().iterator(), 1).close();

        SSTableReader reader = new SSTableReader(sstPath);
        try {
            assertNotNull(reader.get("alpha", null));
            assertEquals(1, reader.diskReadCount());
            reader.get("alpha", null);
            assertEquals(2, reader.diskReadCount(), "every call without cache is a disk read");
        } finally { reader.close(); }
    }
}
