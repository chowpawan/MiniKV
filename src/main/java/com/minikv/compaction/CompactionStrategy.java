package com.minikv.compaction;

import com.minikv.sstable.SSTable;
import java.util.List;

public interface CompactionStrategy {
    List<List<SSTable>> selectFilesToMerge(List<SSTable> allFiles);
}
