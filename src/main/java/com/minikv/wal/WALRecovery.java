package com.minikv.wal;

import com.minikv.memtable.MemTable;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class WALRecovery {
    private final Path walDir;

    public WALRecovery(Path walDir) { this.walDir = walDir; }

    public RecoveryResult recover(AtomicLong seqCounter) throws IOException {
        List<Path> walFiles = findWALFiles();
        MemTable memTable = new MemTable();
        long maxSeq = 0;
        int replayedCount = 0;

        for (Path walFile : walFiles) {
            byte[] fileBytes = Files.readAllBytes(walFile);
            ByteBuffer buf = ByteBuffer.wrap(fileBytes);
            while (buf.hasRemaining()) {
                WALEntry walEntry = WALEntry.deserialize(buf);
                if (walEntry == null) break; // partial write at end
                Entry entry = new Entry(walEntry.key, walEntry.value, walEntry.seqNum, walEntry.type);
                memTable.put(walEntry.key, entry);
                maxSeq = Math.max(maxSeq, walEntry.seqNum);
                replayedCount++;
            }
        }

        seqCounter.set(maxSeq + 1);
        return new RecoveryResult(memTable, walFiles.size(), replayedCount);
    }

    private List<Path> findWALFiles() throws IOException {
        if (!Files.exists(walDir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(walDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("wal-")
                            && p.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    public record RecoveryResult(MemTable memTable, int segmentsReplayed, int entriesReplayed) {}
}
