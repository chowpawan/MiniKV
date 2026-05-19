package com.minikv.compaction;

import com.minikv.sstable.SSTable;
import java.util.*;

/**
 * Groups SSTables by log-base-4 size class.
 * When a tier has >= 4 files, they are selected for compaction.
 */
public class SizeTieredCompaction implements CompactionStrategy {
    private static final int MIN_MERGE_COUNT = 4;
    private static final double LOG4 = Math.log(4);

    @Override
    public List<List<SSTable>> selectFilesToMerge(List<SSTable> allFiles) {
        Map<Integer, List<SSTable>> sizeClasses = new HashMap<>();
        for (SSTable sst : allFiles) {
            int tier = sst.sizeBytes() <= 0 ? 0 : (int)(Math.log(sst.sizeBytes()) / LOG4);
            sizeClasses.computeIfAbsent(tier, k -> new ArrayList<>()).add(sst);
        }
        List<List<SSTable>> result = new ArrayList<>();
        for (List<SSTable> group : sizeClasses.values())
            if (group.size() >= MIN_MERGE_COUNT) result.add(new ArrayList<>(group));
        return result;
    }
}
