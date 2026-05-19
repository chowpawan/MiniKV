package com.minikv;

import com.minikv.core.LSMTree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LSMTreeIntegrationTest {
    @TempDir Path tempDir;

    @Test void basicPutAndGet() throws IOException {
        try (LSMTree tree = new LSMTree(tempDir)) {
            tree.put("hello", "world".getBytes());
            assertTrue(tree.get("hello").isPresent());
            assertArrayEquals("world".getBytes(), tree.get("hello").get());
        }
    }

    @Test void missingKeyReturnsEmpty() throws IOException {
        try (LSMTree tree = new LSMTree(tempDir)) {
            assertFalse(tree.get("nonexistent").isPresent());
        }
    }

    @Test void deleteReturnsTombstone() throws IOException {
        try (LSMTree tree = new LSMTree(tempDir)) {
            tree.put("key", "value".getBytes());
            tree.delete("key");
            assertFalse(tree.get("key").isPresent());
        }
    }

    @Test void crashRecoveryViaWAL() throws IOException {
        Path dataDir = tempDir.resolve("crash-test");
        try (LSMTree tree = new LSMTree(dataDir)) {
            for (int i = 0; i < 100; i++) tree.put("key-" + i, ("value-" + i).getBytes());
        }
        try (LSMTree recovered = new LSMTree(dataDir)) {
            for (int i = 0; i < 100; i++) {
                assertTrue(recovered.get("key-" + i).isPresent(), "key-" + i + " should survive");
                assertArrayEquals(("value-" + i).getBytes(), recovered.get("key-" + i).get());
            }
        }
    }

    @Test void overwriteReturnsLatestValue() throws IOException {
        try (LSMTree tree = new LSMTree(tempDir)) {
            tree.put("key", "original".getBytes());
            tree.put("key", "updated".getBytes());
            assertArrayEquals("updated".getBytes(), tree.get("key").get());
        }
    }

    @Test void concurrentWritesAreThreadSafe() throws Exception {
        try (LSMTree tree = new LSMTree(tempDir)) {
            int threads = 8, writesPerThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger errors = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < writesPerThread; i++)
                            tree.put("thread-" + tid + "-key-" + i, ("val-" + i).getBytes());
                    } catch (Exception e) { errors.incrementAndGet(); }
                    finally { latch.countDown(); }
                });
            }
            latch.await(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(0, errors.get());
        }
    }

    @Test void scanReturnsEntriesInOrder() throws IOException {
        try (LSMTree tree = new LSMTree(tempDir)) {
            tree.put("b", "2".getBytes()); tree.put("a", "1".getBytes());
            tree.put("c", "3".getBytes()); tree.put("e", "5".getBytes());
            tree.put("d", "4".getBytes());
            var iter = tree.scan("a", "e");
            String prev = null; int count = 0;
            while (iter.hasNext()) {
                String key = iter.next().getKey();
                if (prev != null) assertTrue(key.compareTo(prev) > 0);
                prev = key; count++;
            }
            assertEquals(5, count);
        }
    }
}
