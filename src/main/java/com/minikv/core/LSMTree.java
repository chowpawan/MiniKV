package com.minikv.core;

import com.minikv.api.StorageEngine;
import com.minikv.cache.LRUBlockCache;
import com.minikv.compaction.CompactionStrategy;
import com.minikv.compaction.CompactionWorker;
import com.minikv.compaction.SizeTieredCompaction;
import com.minikv.memtable.MemTable;
import com.minikv.metrics.StorageMetrics;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;
import com.minikv.sstable.SSTable;
import com.minikv.sstable.SSTableWriter;
import com.minikv.wal.WAL;
import com.minikv.wal.WALRecovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LSMTree implements StorageEngine {
    private static final Logger LOG = Logger.getLogger(LSMTree.class.getName());
    private static final long MEMTABLE_FLUSH_THRESHOLD = 4 * 1024 * 1024;
    private static final int BLOCK_CACHE_MAX_BLOCKS = 1024;

    private final Path dataDir;
    private final AtomicLong seqCounter = new AtomicLong(0);
    private volatile MemTable activeMemTable;
    private volatile WAL activeWAL;

    private final List<SSTable> sstables = new CopyOnWriteArrayList<>();
    private final ReadWriteLock sstableLock = new ReentrantReadWriteLock();
    private final LRUBlockCache blockCache;
    private final ExecutorService flushExecutor;
    private final CompactionWorker compactionWorker;

    public LSMTree(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        this.blockCache = new LRUBlockCache(BLOCK_CACHE_MAX_BLOCKS);
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flush-worker"); t.setDaemon(true); return t;
        });

        WALRecovery recovery = new WALRecovery(dataDir);
        WALRecovery.RecoveryResult result = recovery.recover(seqCounter);
        this.activeMemTable = result.memTable();
        if (result.entriesReplayed() > 0) {
            LOG.info("WAL recovery: replayed " + result.entriesReplayed() + " entries from "
                    + result.segmentsReplayed() + " segments");
        }

        loadExistingSSTables();
        this.activeWAL = new WAL(dataDir, seqCounter.get());
        CompactionStrategy strategy = new SizeTieredCompaction();
        this.compactionWorker = new CompactionWorker(this, strategy, dataDir);
        this.compactionWorker.start();
    }

    @Override public void put(String key, byte[] value) throws IOException { put(key, value, 0); }

    @Override
    public void put(String key, byte[] value, long ttlSeconds) throws IOException {
        long seq = seqCounter.getAndIncrement();
        long expiresAt = ttlSeconds > 0 ? System.currentTimeMillis() + ttlSeconds * 1000L : 0;
        Entry entry = new Entry(key, value, seq, EntryType.PUT, expiresAt);
        synchronized (this) {
            activeWAL.appendPut(key, value, seq, expiresAt);
            activeMemTable.put(key, entry);
            StorageMetrics.writesTotal.inc();
            maybeFlush();
        }
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException {
        StorageMetrics.readsTotal.inc();
        long startNs = System.nanoTime();
        try {
            Entry entry = activeMemTable.get(key);
            if (entry != null) {
                if (entry.isTombstone() || entry.isExpired()) return Optional.empty();
                return Optional.of(entry.getValue());
            }
            sstableLock.readLock().lock();
            try {
                List<SSTable> snapshot = new ArrayList<>(sstables);
                for (int i = snapshot.size() - 1; i >= 0; i--) {
                    SSTable sst = snapshot.get(i);
                    if (!sst.mightContain(key)) { StorageMetrics.bloomSkips.inc(); continue; }
                    Entry found = sst.get(key, blockCache);
                    if (found != null) {
                        if (!sst.mightContain(key)) StorageMetrics.bloomFalsePositives.inc();
                        if (found.isTombstone() || found.isExpired()) return Optional.empty();
                        return Optional.of(found.getValue());
                    }
                }
            } finally { sstableLock.readLock().unlock(); }
            return Optional.empty();
        } finally {
            StorageMetrics.readLatencyMs.observe((System.nanoTime() - startNs) / 1_000_000.0);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        long seq = seqCounter.getAndIncrement();
        Entry tombstone = new Entry(key, new byte[0], seq, EntryType.DELETE);
        synchronized (this) {
            activeWAL.appendDelete(key, seq);
            activeMemTable.put(key, tombstone);
            StorageMetrics.deletesTotal.inc();
            maybeFlush();
        }
    }

    @Override
    public Iterator<Entry> scan(String fromKey, String toKey) throws IOException {
        TreeMap<String, Entry> merged = new TreeMap<>();
        sstableLock.readLock().lock();
        try {
            for (SSTable sst : sstables) {
                Iterator<Entry> it = sst.iterator();
                while (it.hasNext()) {
                    Entry e = it.next();
                    if (e.getKey().compareTo(fromKey) >= 0 && e.getKey().compareTo(toKey) <= 0)
                        merged.merge(e.getKey(), e, (ex, in) -> in.getSeqNum() > ex.getSeqNum() ? in : ex);
                }
            }
        } finally { sstableLock.readLock().unlock(); }
        Iterator<Map.Entry<String, Entry>> memIt = activeMemTable.iterator(fromKey, toKey);
        while (memIt.hasNext()) { Map.Entry<String, Entry> me = memIt.next(); merged.put(me.getKey(), me.getValue()); }
        return merged.values().stream().filter(e -> !e.isTombstone() && !e.isExpired()).iterator();
    }

    @Override
    public void close() throws IOException {
        compactionWorker.stop();
        flushExecutor.shutdown();
        try { flushExecutor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) { activeWAL.close(); }
        for (SSTable sst : sstables) { try { sst.close(); } catch (IOException ignored) {} }
    }

    private void maybeFlush() {
        if (activeMemTable.shouldFlush(MEMTABLE_FLUSH_THRESHOLD)) triggerFlush();
    }

    private synchronized void triggerFlush() {
        if (!activeMemTable.shouldFlush(MEMTABLE_FLUSH_THRESHOLD) || activeMemTable.isFrozen()) return;
        MemTable frozen = activeMemTable;
        WAL oldWAL = activeWAL;
        frozen.freeze();
        try { activeWAL = new WAL(dataDir, seqCounter.get()); }
        catch (IOException e) { LOG.log(Level.SEVERE, "Failed to create new WAL", e); return; }
        activeMemTable = new MemTable();
        flushExecutor.submit(() -> {
            try { flushMemTable(frozen); oldWAL.delete(); StorageMetrics.memTableFlushes.inc(); }
            catch (IOException e) { LOG.log(Level.SEVERE, "Flush failed", e); }
        });
    }

    private void flushMemTable(MemTable frozen) throws IOException {
        Path sstPath = dataDir.resolve("sstable-" + System.currentTimeMillis() + ".sst");
        SSTable newSSTable = new SSTableWriter(sstPath).write(frozen.iterator(), frozen.size());
        sstableLock.writeLock().lock();
        try { sstables.add(newSSTable); } finally { sstableLock.writeLock().unlock(); }
    }

    public void atomicSwapSSTables(List<SSTable> toRemove, List<SSTable> toAdd) {
        sstableLock.writeLock().lock();
        try { sstables.removeAll(toRemove); sstables.addAll(toAdd); }
        finally { sstableLock.writeLock().unlock(); }
    }

    public List<SSTable> getSSTables() {
        sstableLock.readLock().lock();
        try { return new ArrayList<>(sstables); } finally { sstableLock.readLock().unlock(); }
    }

    private void loadExistingSSTables() throws IOException {
        if (!Files.exists(dataDir)) return;
        List<Path> sstFiles = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".sst"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(sstFiles::add);
        }
        for (Path p : sstFiles) {
            try { sstables.add(new SSTable(p)); }
            catch (IOException e) { LOG.warning("Could not load " + p + ": " + e.getMessage()); }
        }
    }
}
