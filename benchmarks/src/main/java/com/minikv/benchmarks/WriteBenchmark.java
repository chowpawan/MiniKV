package com.minikv.benchmarks;

import com.minikv.core.LSMTree;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class WriteBenchmark {
    private LSMTree engine;
    private byte[] value;
    private AtomicLong counter;

    @Setup public void setup() throws IOException {
        engine = new LSMTree(Files.createTempDirectory("minikv-write-bench"));
        value = new byte[100];
        ThreadLocalRandom.current().nextBytes(value);
        counter = new AtomicLong(0);
    }

    @TearDown public void tearDown() throws IOException { engine.close(); }

    @Benchmark public void sequentialWrites() throws IOException {
        engine.put("key-" + counter.getAndIncrement(), value);
    }

    @Benchmark public void randomWrites() throws IOException {
        engine.put("key-" + ThreadLocalRandom.current().nextLong(1_000_000), value);
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder().include(WriteBenchmark.class.getSimpleName()).build()).run();
    }
}
