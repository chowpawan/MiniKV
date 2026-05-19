package com.minikv.sstable;

import com.minikv.filter.BloomFilter;
import com.minikv.model.Entry;
import com.minikv.model.EntryType;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class SSTableReader implements Closeable {

    private final Path path;
    private final SSTableIndex index;
    private final BloomFilter bloomFilter;
    private final long dataEndOffset;
    private final long fileSize;
    private final FileChannel channel;
    private final AtomicLong diskReads = new AtomicLong();

    public SSTableReader(Path path) throws IOException {
        this.path = path;
        this.fileSize = path.toFile().length();
        this.channel = FileChannel.open(path, StandardOpenOption.READ);

        ByteBuffer footer = ByteBuffer.allocate(20);
        channel.read(footer, fileSize - 20);
        footer.flip();
        long indexOffset = footer.getLong();
        long bloomOffset = footer.getLong();
        int indexEntryCount = footer.getInt();

        this.dataEndOffset = indexOffset;
        this.index = loadIndex(indexOffset, bloomOffset, indexEntryCount);
        this.bloomFilter = loadBloom(bloomOffset, fileSize - 20);
    }

    @Override
    public void close() throws IOException { channel.close(); }

    public BloomFilter getBloomFilter() { return bloomFilter; }
    public long diskReadCount() { return diskReads.get(); }

    public Entry get(String key) {
        try {
            long blockStart = index.findBlockOffset(key);
            long blockLen = dataEndOffset - blockStart;
            if (blockLen <= 0) return null;
            byte[] block = new byte[(int) blockLen];
            ByteBuffer buf = ByteBuffer.allocate((int) blockLen);
            channel.read(buf, blockStart);
            buf.flip();
            buf.get(block);
            diskReads.incrementAndGet();
            return scanBlock(block, key);
        } catch (IOException e) {
            return null;
        }
    }

    public Iterator<Entry> iterator() {
        List<Entry> entries = new ArrayList<>();
        try {
            if (dataEndOffset <= 0) return entries.iterator();
            byte[] data = new byte[(int) dataEndOffset];
            ByteBuffer buf = ByteBuffer.allocate((int) dataEndOffset);
            channel.read(buf, 0);
            buf.flip();
            buf.get(data);
            int pos = 0;
            while (pos + 8 <= data.length) {
                int keyLen = readInt(data, pos);
                if (keyLen <= 0 || pos + 4 + keyLen + 4 > data.length) break;
                String k = new String(data, pos + 4, keyLen, StandardCharsets.UTF_8);
                int valStart = pos + 4 + keyLen;
                int valLen = readInt(data, valStart);
                int metaStart = valStart + 4 + Math.max(0, valLen);
                if (metaStart + 9 > data.length) break;
                byte[] value = new byte[Math.max(0, valLen)];
                if (valLen > 0) System.arraycopy(data, valStart + 4, value, 0, valLen);
                EntryType type = EntryType.fromCode(data[metaStart]);
                long seqNum = readLong(data, metaStart + 1);
                entries.add(new Entry(k, value, seqNum, type));
                pos = metaStart + 9;
            }
        } catch (IOException ignored) {}
        return entries.iterator();
    }

    private static Entry scanBlock(byte[] block, String targetKey) {
        int pos = 0;
        while (pos + 8 <= block.length) {
            int keyLen = readInt(block, pos);
            if (keyLen <= 0 || pos + 4 + keyLen + 4 > block.length) break;
            String key = new String(block, pos + 4, keyLen, StandardCharsets.UTF_8);
            int valStart = pos + 4 + keyLen;
            if (valStart + 4 > block.length) break;
            int valLen = readInt(block, valStart);
            int metaStart = valStart + 4 + Math.max(0, valLen);
            if (metaStart + 9 > block.length) break;
            int cmp = key.compareTo(targetKey);
            if (cmp == 0) {
                byte[] value = new byte[Math.max(0, valLen)];
                if (valLen > 0) System.arraycopy(block, valStart + 4, value, 0, valLen);
                EntryType type = EntryType.fromCode(block[metaStart]);
                long seqNum = readLong(block, metaStart + 1);
                return new Entry(key, value, seqNum, type);
            }
            if (cmp > 0) break;
            pos = metaStart + 9;
        }
        return null;
    }

    private SSTableIndex loadIndex(long indexOffset, long bloomOffset, int count)
            throws IOException {
        SSTableIndex idx = new SSTableIndex();
        int size = (int) (bloomOffset - indexOffset);
        if (size <= 0) return idx;
        ByteBuffer buf = ByteBuffer.allocate(size);
        channel.read(buf, indexOffset); buf.flip();
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            int kl = buf.getInt();
            byte[] kb = new byte[kl]; buf.get(kb);
            idx.add(new String(kb, StandardCharsets.UTF_8), buf.getLong());
        }
        return idx;
    }

    private BloomFilter loadBloom(long bloomOffset, long bloomEnd) throws IOException {
        int size = (int) (bloomEnd - bloomOffset);
        if (size < 4) return new BloomFilter(16);
        ByteBuffer buf = ByteBuffer.allocate(size);
        channel.read(buf, bloomOffset); buf.flip();
        int bitSize = buf.getInt();
        long[] data = new long[(size - 4) / 8];
        for (int i = 0; i < data.length; i++) data[i] = buf.getLong();
        return new BloomFilter(data, bitSize);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off]&0xFF)<<24)|((b[off+1]&0xFF)<<16)|((b[off+2]&0xFF)<<8)|(b[off+3]&0xFF);
    }
    private static long readLong(byte[] b, int off) {
        return ((long)(b[off]&0xFF)<<56)|((long)(b[off+1]&0xFF)<<48)
              |((long)(b[off+2]&0xFF)<<40)|((long)(b[off+3]&0xFF)<<32)
              |((long)(b[off+4]&0xFF)<<24)|((long)(b[off+5]&0xFF)<<16)
              |((long)(b[off+6]&0xFF)<<8)|((long)(b[off+7]&0xFF));
    }

    public Path getPath() { return path; }
    public long fileSize() { return fileSize; }
}
