package com.minikv.sstable;

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

    public Entry get(String key) { return reader.get(key); }

    public BloomFilter bloomFilter() { return reader.getBloomFilter(); }
    public boolean mightContain(String key) { return reader.getBloomFilter().mightContain(key); }
    public Iterator<Entry> iterator() { return reader.iterator(); }
    public Path getPath() { return path; }
    public long sizeBytes() { return path.toFile().length(); }

    @Override
    public void close() throws IOException { reader.close(); }

    public void delete() throws IOException {
        reader.close();
        java.nio.file.Files.deleteIfExists(path);
    }
}
