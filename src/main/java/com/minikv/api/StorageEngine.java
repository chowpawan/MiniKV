package com.minikv.api;

import com.minikv.model.Entry;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

public interface StorageEngine extends AutoCloseable {
    void put(String key, byte[] value) throws IOException;
    void put(String key, byte[] value, long ttlSeconds) throws IOException;
    Optional<byte[]> get(String key) throws IOException;
    void delete(String key) throws IOException;
    Iterator<Entry> scan(String fromKey, String toKey) throws IOException;
    void close() throws IOException;
}
