package com.minikv.compaction;

import com.minikv.core.LSMTree;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;
import com.minikv.sstable.SSTable;
import com.minikv.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CompactionWorker {
    private static final Logger LOG = Logger.getLogger(CompactionWorker.class.getName());

    private final LSMTree lsmTree;
    private final CompactionStrategy strategy;
    private final Path dataDir;
    private final ScheduledExecutorService scheduler;

    public CompactionWorker(LSMTree lsmTree, CompactionStrategy strategy, Path dataDir) {
        this.lsmTree = lsmTree;
        this.strategy = strategy;
        this.dataDir = dataDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "compaction-worker"); t.setDaemon(true); return t;
        });
    }

    public void start() { scheduler.scheduleWithFixedDelay(this::runCompaction, 10, 10, TimeUnit.SECONDS); }

    public void stop() {
        scheduler.shutdown();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void runCompaction() {
        List<SSTable> snapshot = lsmTree.getSSTables();
        for (List<SSTable> group : strategy.selectFilesToMerge(snapshot)) {
            try { compactGroup(group); }
            catch (IOException e) { LOG.log(Level.SEVERE, "Compaction failed", e); }
        }
    }

    private void compactGroup(List<SSTable> group) throws IOException {
        List<Iterator<Entry>> iterators = new ArrayList<>();
        for (SSTable sst : group) iterators.add(sst.iterator());

        List<Entry> merged = kWayMerge(iterators);
        if (merged.isEmpty()) {
            lsmTree.atomicSwapSSTables(group, List.of());
            for (SSTable sst : group) sst.delete();
            return;
        }

        Path outputPath = dataDir.resolve("sstable-" + System.currentTimeMillis() + ".sst");
        SSTable output = new SSTableWriter(outputPath)
                .write(merged.stream().<Map.Entry<String,Entry>>map(e -> Map.entry(e.getKey(), e))
                        .iterator(), merged.size());
        lsmTree.atomicSwapSSTables(group, List.of(output));
        for (SSTable sst : group) sst.delete();
    }

    private List<Entry> kWayMerge(List<Iterator<Entry>> iterators) {
        record HeapEntry(Entry entry, int sourceIdx) {}
        PriorityQueue<HeapEntry> heap = new PriorityQueue<>(
                Comparator.comparing((HeapEntry he) -> he.entry().getKey())
                        .thenComparingLong(he -> -he.entry().getSeqNum()));
        for (int i = 0; i < iterators.size(); i++)
            if (iterators.get(i).hasNext()) heap.offer(new HeapEntry(iterators.get(i).next(), i));

        List<Entry> result = new ArrayList<>();
        String lastKey = null;
        while (!heap.isEmpty()) {
            HeapEntry he = heap.poll();
            Entry entry = he.entry();
            if (iterators.get(he.sourceIdx()).hasNext())
                heap.offer(new HeapEntry(iterators.get(he.sourceIdx()).next(), he.sourceIdx()));
            if (entry.getKey().equals(lastKey)) continue;
            lastKey = entry.getKey();
            if (entry.isTombstone()) continue; // always drop tombstones (to be fixed)
            result.add(entry);
        }
        return result;
    }
}
