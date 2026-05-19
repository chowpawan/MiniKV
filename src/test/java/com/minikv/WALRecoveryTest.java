package com.minikv;

import com.minikv.memtable.MemTable;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;
import com.minikv.wal.WAL;
import com.minikv.wal.WALRecovery;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WALRecoveryTest {

    @TempDir Path tempDir;

    @Test
    void recovers1000Entries() throws IOException {
        WAL wal = new WAL(tempDir, 0);
        for (int i = 0; i < 1000; i++) wal.appendPut("key-" + i, ("value-" + i).getBytes(), i, 0);
        wal.close();

        AtomicLong seq = new AtomicLong(0);
        WALRecovery.RecoveryResult result = new WALRecovery(tempDir).recover(seq);
        assertEquals(1000, result.entriesReplayed());
        for (int i = 0; i < 1000; i++) {
            Entry e = result.memTable().get("key-" + i);
            assertNotNull(e);
            assertArrayEquals(("value-" + i).getBytes(), e.getValue());
        }
    }

    @Test
    void latestValueWinsOnOverwrite() throws IOException {
        WAL wal = new WAL(tempDir, 0);
        for (int i = 0; i < 100; i++) wal.appendPut("key-" + i, ("original-" + i).getBytes(), i, 0);
        for (int i = 0; i < 50; i++) wal.appendPut("key-" + i, ("updated-" + i).getBytes(), 100+i, 0);
        wal.close();

        MemTable recovered = new WALRecovery(tempDir).recover(new AtomicLong()).memTable();
        for (int i = 0; i < 50; i++)
            assertArrayEquals(("updated-" + i).getBytes(), recovered.get("key-" + i).getValue());
        for (int i = 50; i < 100; i++)
            assertArrayEquals(("original-" + i).getBytes(), recovered.get("key-" + i).getValue());
    }

    @Test
    void tombstonesPreservedOnRecovery() throws IOException {
        WAL wal = new WAL(tempDir, 0);
        for (int i = 0; i < 50; i++) wal.appendPut("key-" + i, ("v-" + i).getBytes(), i, 0);
        for (int i = 0; i < 25; i++) wal.appendDelete("key-" + i, 50 + i);
        wal.close();

        MemTable recovered = new WALRecovery(tempDir).recover(new AtomicLong()).memTable();
        for (int i = 0; i < 25; i++)
            assertEquals(EntryType.DELETE, recovered.get("key-" + i).getType());
        for (int i = 25; i < 50; i++)
            assertEquals(EntryType.PUT, recovered.get("key-" + i).getType());
    }

    @Test
    void recoveryTimeFor10kEntriesUnder50ms() throws IOException {
        WAL wal = new WAL(tempDir, 0);
        for (int i = 0; i < 10_000; i++) wal.appendPut("key-" + i, ("value-" + i).getBytes(), i, 0);
        wal.close();
        long start = System.nanoTime();
        new WALRecovery(tempDir).recover(new AtomicLong());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 50, "Recovery took " + ms + "ms, expected <50ms");
    }
}
