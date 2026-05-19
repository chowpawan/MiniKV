package com.minikv.core;

import com.minikv.api.StorageEngine;
import com.minikv.memtable.MemTable;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;
import com.minikv.wal.WAL;
import com.minikv.wal.WALRecovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class LSMTree implements StorageEngine {
    private static final Logger LOG = Logger.getLogger(LSMTree.class.getName());

    private final Path dataDir;
    private final AtomicLong seqCounter = new AtomicLong(0);
    private volatile MemTable activeMemTable;
    private volatile WAL activeWAL;

    public LSMTree(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);

        WALRecovery recovery = new WALRecovery(dataDir);
        WALRecovery.RecoveryResult result = recovery.recover(seqCounter);
        this.activeMemTable = result.memTable();
        if (result.entriesReplayed() > 0) {
            LOG.info("WAL recovery: replayed " + result.entriesReplayed() + " entries from "
                    + result.segmentsReplayed() + " segments");
        }

        this.activeWAL = new WAL(dataDir, seqCounter.get());
    }

    @Override
    public void put(String key, byte[] value) throws IOException {
        put(key, value, 0);
    }

    @Override
    public void put(String key, byte[] value, long ttlSeconds) throws IOException {
        long seq = seqCounter.getAndIncrement();
        Entry entry = new Entry(key, value, seq, EntryType.PUT);
        synchronized (this) {
            activeWAL.appendPut(key, value, seq);
            activeMemTable.put(key, entry);
        }
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException {
        Entry entry = activeMemTable.get(key);
        if (entry != null) {
            if (entry.isTombstone()) return Optional.empty();
            return Optional.of(entry.getValue());
        }
        return Optional.empty();
    }

    @Override
    public void delete(String key) throws IOException {
        long seq = seqCounter.getAndIncrement();
        Entry tombstone = new Entry(key, new byte[0], seq, EntryType.DELETE);
        synchronized (this) {
            activeWAL.appendDelete(key, seq);
            activeMemTable.put(key, tombstone);
        }
    }

    @Override
    public Iterator<Entry> scan(String fromKey, String toKey) throws IOException {
        List<Entry> results = new ArrayList<>();
        Iterator<Map.Entry<String, Entry>> it = activeMemTable.iterator(fromKey, toKey);
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (!e.isTombstone()) results.add(e);
        }
        return results.iterator();
    }

    @Override
    public void close() throws IOException {
        synchronized (this) { activeWAL.close(); }
    }
}
