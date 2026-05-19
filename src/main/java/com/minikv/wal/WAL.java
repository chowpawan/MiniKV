package com.minikv.wal;

import com.minikv.model.EntryType;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class WAL implements Closeable {
    private final Path path;
    private FileChannel channel;

    public WAL(Path walDir, long segmentId) throws IOException {
        this.path = walDir.resolve(String.format("wal-%020d.log", segmentId));
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    public void append(WALEntry entry) throws IOException {
        byte[] data = entry.serialize();
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) channel.write(buf);
        channel.force(true); // fsync — durability guarantee
    }

    public void appendPut(String key, byte[] value, long seqNum) throws IOException {
        append(new WALEntry(key, value, EntryType.PUT, seqNum));
    }

    public void appendDelete(String key, long seqNum) throws IOException {
        append(new WALEntry(key, new byte[0], EntryType.DELETE, seqNum));
    }

    public Path getPath() { return path; }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }

    public void delete() throws IOException {
        close();
        java.nio.file.Files.deleteIfExists(path);
    }
}
