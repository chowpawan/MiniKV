package com.minikv.benchmarks;

import com.minikv.core.LSMTree;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class ReadBenchmark {
    private static final int NUM_KEYS = 100_000;
    private LSMTree engine;

    @Setup public void setup() throws IOException {
        engine = new LSMTree(Files.createTempDirectory("minikv-read-bench"));
        byte[] value = new byte[100];
        for (int i = 0; i < NUM_KEYS; i++) engine.put("key-" + i, value);
    }

    @TearDown public void tearDown() throws IOException { engine.close(); }

    @Benchmark public Object pointReadHot() throws IOException {
        return engine.get("key-" + ThreadLocalRandom.current().nextInt(1000));
    }

    @Benchmark public Object pointReadCold() throws IOException {
        return engine.get("key-" + ThreadLocalRandom.current().nextInt(NUM_KEYS));
    }

    @Benchmark public Object readMiss() throws IOException {
        return engine.get("key-" + (ThreadLocalRandom.current().nextInt(1_000_000) + NUM_KEYS));
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder().include(ReadBenchmark.class.getSimpleName()).build()).run();
    }
}
