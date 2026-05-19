package com.minikv.sstable;

import com.minikv.cache.LRUBlockCache;
import com.minikv.filter.BloomFilter;
import com.minikv.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

public class SSTable implements Closeable {
    private final Path path;
    private volatile SSTableReader reader;

    public SSTable(Path path) throws IOException {
        this.path = path;
        this.reader = new SSTableReader(path);
    }

    public Entry get(String key, LRUBlockCache cache) { return reader.get(key, cache); }

    public BloomFilter bloomFilter() { return reader.getBloomFilter(); }
    public boolean mightContain(String key) { return reader.getBloomFilter().mightContain(key); }
    public Iterator<Entry> iterator() { return reader.iterator(); }
    public Path getPath() { return path; }
    public long sizeBytes() { return path.toFile().length(); }
    public long diskReadCount() { return reader.diskReadCount(); }

    @Override
    public void close() throws IOException { reader.close(); }

    public void delete() throws IOException {
        reader.close(); // MappedByteBuffer mapping stays valid per Java spec
        java.nio.file.Files.deleteIfExists(path);
    }
}
