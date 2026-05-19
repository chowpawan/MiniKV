package com.minikv.filter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Bloom filter using Kirsch-Mitzenmacher double hashing with MurmurHash3-128.
 * k=3, 10 bits/key (initial configuration).
 */
public class BloomFilter {
    private static final int K = 3;
    private static final int BITS_PER_KEY = 10;

    private final BitSet bits;
    private final int m;

    public BloomFilter(int expectedKeys) {
        this.m = Math.max(expectedKeys * BITS_PER_KEY, 64);
        this.bits = new BitSet(m);
    }

    public BloomFilter(long[] serialized, int m) {
        this.m = m;
        this.bits = BitSet.valueOf(serialized);
    }

    public void add(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long[] hashes = murmur3Hash128(keyBytes);
        long h1 = hashes[0], h2 = hashes[1];
        for (int i = 0; i < K; i++) {
            int idx = (int) (((h1 + (long) i * h2) & Long.MAX_VALUE) % m);
            bits.set(idx);
        }
    }

    public boolean mightContain(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long[] hashes = murmur3Hash128(keyBytes);
        long h1 = hashes[0], h2 = hashes[1];
        for (int i = 0; i < K; i++) {
            int idx = (int) (((h1 + (long) i * h2) & Long.MAX_VALUE) % m);
            if (!bits.get(idx)) return false;
        }
        return true;
    }

    public long[] serialize() { return bits.toLongArray(); }
    public int getBitSize() { return m; }

    private static long[] murmur3Hash128(byte[] data) {
        final long c1 = 0x87c37b91114253d5L, c2 = 0x4cf5ad432745937fL;
        long h1 = 42, h2 = 42;
        int len = data.length, nblocks = len / 16;
        for (int i = 0; i < nblocks; i++) {
            int off = i * 16;
            long k1 = getLong(data, off), k2 = getLong(data, off + 8);
            k1 *= c1; k1 = Long.rotateLeft(k1, 31); k1 *= c2; h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27); h1 += h2; h1 = h1 * 5 + 0x52dce729L;
            k2 *= c2; k2 = Long.rotateLeft(k2, 33); k2 *= c1; h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31); h2 += h1; h2 = h2 * 5 + 0x38495ab5L;
        }
        long k1 = 0, k2 = 0;
        int tail = nblocks * 16;
        switch (len & 15) {
            case 15: k2 ^= (long)(data[tail+14]&0xFF)<<48;
            case 14: k2 ^= (long)(data[tail+13]&0xFF)<<40;
            case 13: k2 ^= (long)(data[tail+12]&0xFF)<<32;
            case 12: k2 ^= (long)(data[tail+11]&0xFF)<<24;
            case 11: k2 ^= (long)(data[tail+10]&0xFF)<<16;
            case 10: k2 ^= (long)(data[tail+ 9]&0xFF)<< 8;
            case  9: k2 ^= (long)(data[tail+ 8]&0xFF);
                     k2 *= c2; k2 = Long.rotateLeft(k2,33); k2 *= c1; h2 ^= k2;
            case  8: k1 ^= (long)(data[tail+ 7]&0xFF)<<56;
            case  7: k1 ^= (long)(data[tail+ 6]&0xFF)<<48;
            case  6: k1 ^= (long)(data[tail+ 5]&0xFF)<<40;
            case  5: k1 ^= (long)(data[tail+ 4]&0xFF)<<32;
            case  4: k1 ^= (long)(data[tail+ 3]&0xFF)<<24;
            case  3: k1 ^= (long)(data[tail+ 2]&0xFF)<<16;
            case  2: k1 ^= (long)(data[tail+ 1]&0xFF)<< 8;
            case  1: k1 ^= (long)(data[tail]&0xFF);
                     k1 *= c1; k1 = Long.rotateLeft(k1,31); k1 *= c2; h1 ^= k1;
        }
        h1 ^= len; h2 ^= len; h1 += h2; h2 += h1;
        h1 = fmix64(h1); h2 = fmix64(h2); h1 += h2; h2 += h1;
        return new long[]{h1, h2};
    }
    private static long fmix64(long h) {
        h ^= h>>>33; h *= 0xff51afd7ed558ccdL; h ^= h>>>33;
        h *= 0xc4ceb9fe1a85ec53L; h ^= h>>>33; return h;
    }
    private static long getLong(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
