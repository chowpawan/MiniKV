package com.minikv;

import com.minikv.filter.BloomFilter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    void containsAllInsertedKeys() {
        BloomFilter bf = new BloomFilter(1000);
        for (int i = 0; i < 1000; i++) bf.add("key-" + i);
        for (int i = 0; i < 1000; i++) assertTrue(bf.mightContain("key-" + i));
    }

    @Test
    void falsePositiveRateBelow1Percent() {
        int n = 100_000;
        BloomFilter bf = new BloomFilter(n);
        for (int i = 0; i < n; i++) bf.add("existing-" + i);
        int fp = 0;
        for (int i = 0; i < n; i++) if (bf.mightContain("nonexistent-" + i)) fp++;
        double fpRate = (double) fp / n;
        assertTrue(fpRate < 0.01, "FPR was " + (fpRate * 100) + "%, expected <1%");
    }

    @Test
    void falsePositiveRateBelow1PercentWith1MillionKeys() {
        int n = 1_000_000;
        BloomFilter bf = new BloomFilter(n);
        for (int i = 0; i < n; i++) bf.add("existing-" + i);
        int fp = 0, probes = 100_000;
        for (int i = 0; i < probes; i++) if (bf.mightContain("nonexistent-" + i)) fp++;
        double fpRate = (double) fp / probes;
        assertTrue(fpRate < 0.01, "FPR at 1M keys was " + (fpRate * 100) + "%, expected <1%");
    }

    @Test
    void serializeAndDeserialize() {
        BloomFilter bf = new BloomFilter(100);
        bf.add("alpha"); bf.add("beta"); bf.add("gamma");
        BloomFilter restored = new BloomFilter(bf.serialize(), bf.getBitSize());
        assertTrue(restored.mightContain("alpha"));
        assertTrue(restored.mightContain("beta"));
        assertTrue(restored.mightContain("gamma"));
    }
}
