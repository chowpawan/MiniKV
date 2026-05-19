package com.minikv.benchmarks;

import com.minikv.core.LSMTree;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class MixedBenchmark {

    @State(Scope.Benchmark)
    public static class SharedState {
        LSMTree engine;
        byte[] value;
        AtomicLong writeCounter;

        @Setup public void setup() throws IOException {
            engine = new LSMTree(Files.createTempDirectory("minikv-mixed-bench"));
            value = new byte[100];
            writeCounter = new AtomicLong(0);
            for (int i = 0; i < 10_000; i++) engine.put("key-" + i, value);
        }

        @TearDown public void tearDown() throws IOException { engine.close(); }
    }

    @Benchmark @Group("mixed") @GroupThreads(8)
    public Object read(SharedState state) throws IOException {
        return state.engine.get("key-" + ThreadLocalRandom.current().nextInt(10_000));
    }

    @Benchmark @Group("mixed") @GroupThreads(2)
    public void write(SharedState state) throws IOException {
        state.engine.put("key-" + state.writeCounter.getAndIncrement(), state.value);
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder().include(MixedBenchmark.class.getSimpleName()).build()).run();
    }
}
