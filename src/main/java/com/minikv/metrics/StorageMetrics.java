package com.minikv.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

public class StorageMetrics {
    public static final Counter writesTotal = Counter.build()
            .name("minikv_writes_total").help("Total put operations").register();
    public static final Counter readsTotal = Counter.build()
            .name("minikv_reads_total").help("Total get operations").register();
    public static final Counter bloomSkips = Counter.build()
            .name("minikv_bloom_skips_total").help("SSTables skipped by Bloom filter").register();
    public static final Counter bloomFalsePositives = Counter.build()
            .name("minikv_bloom_false_positives_total")
            .help("Bloom filter false positives").register();
    public static final Counter deletesTotal = Counter.build()
            .name("minikv_deletes_total").help("Total delete operations").register();
    public static final Counter memTableFlushes = Counter.build()
            .name("minikv_memtable_flushes_total").help("MemTable flushes to SSTable").register();
    public static final Histogram compactionDurationMs = Histogram.build()
            .name("minikv_compaction_duration_ms").help("Compaction duration in ms")
            .buckets(10, 50, 100, 500, 1000, 5000).register();
    public static final Histogram readLatencyMs = Histogram.build()
            .name("minikv_read_latency_ms").help("Read latency in ms")
            .buckets(0.1, 0.5, 1, 5, 10, 50, 100).register();

    private StorageMetrics() {}
}
