package com.minikv.sstable;

import com.minikv.filter.BloomFilter;
import com.minikv.model.Entry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;

/**
 * SSTable binary layout:
 * [Data blocks][Sparse index][Bloom filter][Footer: 8+8+4 bytes]
 * Entry format: [4:keyLen][key][4:valLen][value][1:type][8:seqNum][8:expiresAt]
 */
public class SSTableWriter {
    private static final int SPARSE_INDEX_INTERVAL = 16;
    private final Path outputPath;

    public SSTableWriter(Path outputPath) { this.outputPath = outputPath; }

    public SSTable write(Iterator<Map.Entry<String, Entry>> sortedEntries, int expectedKeys)
            throws IOException {
        try (FileChannel channel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            SSTableIndex index = new SSTableIndex();
            BloomFilter bloomFilter = new BloomFilter(Math.max(expectedKeys, 16));
            int entryCount = 0;
            long currentOffset = 0;

            while (sortedEntries.hasNext()) {
                Map.Entry<String, Entry> mapEntry = sortedEntries.next();
                Entry entry = mapEntry.getValue();
                byte[] value = entry.getValue() != null ? entry.getValue() : new byte[0];
                byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);

                bloomFilter.add(entry.getKey());
                if (entryCount % SPARSE_INDEX_INTERVAL == 0) index.add(entry.getKey(), currentOffset);

                // [4:keyLen][key][4:valLen][val][1:type][8:seqNum][8:expiresAt]
                int entrySize = 4 + keyBytes.length + 4 + value.length + 1 + 8 + 8;
                ByteBuffer entryBuf = ByteBuffer.allocate(entrySize);
                entryBuf.putInt(keyBytes.length); entryBuf.put(keyBytes);
                entryBuf.putInt(value.length); entryBuf.put(value);
                entryBuf.put(entry.getType().code);
                entryBuf.putLong(entry.getSeqNum());
                entryBuf.putLong(entry.getExpiresAt());
                entryBuf.flip();
                while (entryBuf.hasRemaining()) currentOffset += channel.write(entryBuf);
                entryCount++;
            }

            long indexOffset = currentOffset;
            for (SSTableIndex.IndexEntry ie : index.getEntries()) {
                byte[] kb = ie.key().getBytes(StandardCharsets.UTF_8);
                ByteBuffer idxBuf = ByteBuffer.allocate(4 + kb.length + 8);
                idxBuf.putInt(kb.length); idxBuf.put(kb); idxBuf.putLong(ie.offset());
                idxBuf.flip();
                while (idxBuf.hasRemaining()) channel.write(idxBuf);
            }

            long bloomOffset = channel.position();
            long[] bloomData = bloomFilter.serialize();
            ByteBuffer bloomBuf = ByteBuffer.allocate(4 + bloomData.length * 8);
            bloomBuf.putInt(bloomFilter.getBitSize());
            for (long l : bloomData) bloomBuf.putLong(l);
            bloomBuf.flip(); channel.write(bloomBuf);

            ByteBuffer footer = ByteBuffer.allocate(20);
            footer.putLong(indexOffset); footer.putLong(bloomOffset);
            footer.putInt(index.size()); footer.flip();
            channel.write(footer);
            channel.force(true);
        }
        return new SSTable(outputPath);
    }
}
