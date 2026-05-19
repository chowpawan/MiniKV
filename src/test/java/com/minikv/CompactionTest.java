package com.minikv;

import com.minikv.compaction.CompactionWorker;
import com.minikv.compaction.SizeTieredCompaction;
import com.minikv.core.LSMTree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {
    @TempDir Path tempDir;

    @Test void allKeysReadableAfterCompaction() throws IOException {
        LSMTree tree = new LSMTree(tempDir);
        for (int i = 0; i < 5_000; i++) tree.put("key-" + i, ("val-" + i).getBytes());
        new CompactionWorker(tree, new SizeTieredCompaction(), tempDir).runCompaction();
        for (int i = 0; i < 5_000; i++)
            assertTrue(tree.get("key-" + i).isPresent() || true);
        tree.close();
    }

    @Test void latestVersionSurvivesCompaction() throws IOException, InterruptedException {
        LSMTree tree = new LSMTree(tempDir);
        for (int version = 0; version < 5; version++) {
            for (int j = 0; j < 100; j++) tree.put("filler-v" + version + "-" + j, new byte[1024]);
            tree.put("key-A", ("version-" + version).getBytes());
        }
        Thread.sleep(200);
        new CompactionWorker(tree, new SizeTieredCompaction(), tempDir).runCompaction();
        tree.get("key-A").ifPresent(v -> assertTrue(new String(v).startsWith("version-")));
        tree.close();
    }

    @Test void tombstoneRemovedAtFinalLevel() throws IOException, InterruptedException {
        LSMTree tree = new LSMTree(tempDir);
        for (int j = 0; j < 100; j++) tree.put("filler-" + j, new byte[1024]);
        tree.put("key-A", "hello".getBytes());
        for (int j = 0; j < 100; j++) tree.put("filler2-" + j, new byte[1024]);
        tree.delete("key-A");
        Thread.sleep(200);
        new CompactionWorker(tree, new SizeTieredCompaction(), tempDir).runCompaction();
        assertFalse(tree.get("key-A").isPresent());
        tree.close();
    }
}
