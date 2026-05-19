# MiniKV

A key-value storage engine built from scratch in Java using the **LSM-tree**
(Log-Structured Merge-tree) architecture — the same core design behind
LevelDB, RocksDB, Apache Cassandra, and HBase.

```
                        ┌─────────────────┐
                        │   Client / API  │
                        │ get/put/delete  │
                        └────────┬────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │ write path       │                  │ read path
              ▼                  ▼                  ▼
       ┌─────────────┐   ┌─────────────┐    ┌─────────────┐
       │     WAL     │   │  MemTable   │───▶│ Bloom Filter│
       │(append-only)│   │(SkipListMap)│    │(per SSTable)│
       └─────────────┘   └──────┬──────┘    └──────┬──────┘
                                │ flush             │
                                ▼                   ▼
                  ┌─────────────────────────────────────────┐
                  │            Immutable SSTables            │
                  └─────────────────────────────────────────┘
                                         ▲
                            ┌────────────┴────────────┐
                            │  Size-tiered compaction  │
                            │     (background, 10s)    │
                            └─────────────────────────┘
```

---

## Performance

Measured on Apple M-series, JDK 21, JMH 1.37.

| Workload | Metric | Result |
|---|---|---|
| Point read — warm cache (p50) | latency | **< 1 µs** |
| Point read — warm cache (p99) | latency | **~1 µs** |
| Read miss — Bloom filter hit | latency | **< 0.1 µs** |
| Mixed read (8 threads, 10 k keys) | throughput | **9.25 M ops/s** |
| Sequential write — fsync per write | throughput | ~300 ops/s |
| WAL crash recovery (10 K entries) | recovery time | **< 50 ms** |
| Bloom filter false-positive rate | accuracy | **< 1%** (verified at 1 M keys) |

> **On write throughput:** every write is fsynced to disk before ack — the
> same guarantee PostgreSQL gives with `synchronous_commit = on` and RocksDB
> with `sync = true`. The ~3–4 ms per-fsync cost is the price of that
> guarantee, not an engine limitation. Group commit (batching writes per fsync)
> pushes throughput past 100 K ops/s without relaxing durability.

---

## How it works

### The big picture

Every database has to answer two questions:

- **How do I write fast?** Seeking all over a file for every write is slow.
- **How do I read fast?** Data might be spread across many files.

LSM-trees answer writes first: **always append, never update in place.**
Think of the storage in three layers — a whiteboard, a filing cabinet, and
an archive.

---

### Layer 1 — WAL (Write-Ahead Log): the safety net

Before MiniKV touches anything in memory, it appends every write to a log
file on disk and calls `fsync` — forcing the OS to flush it to physical
storage. Only then does it update memory.

**Why?** If the process crashes at any point, the log survives. On restart,
MiniKV reads the log top to bottom and replays every entry, rebuilding the
in-memory state exactly as it was. This is crash recovery.

Each log entry carries a CRC32 checksum. If the process died mid-write, the
last entry's checksum fails — MiniKV discards it and stops at the last fully
written record. The last complete write is always safe.

```
WAL entry format
────────────────
[4 bytes] key length
[N bytes] key
[4 bytes] value length
[M bytes] value
[1 byte ] type (PUT = 0, DELETE = 1)
[8 bytes] sequence number
[8 bytes] expiry timestamp (0 = no TTL)
[4 bytes] CRC32 checksum
```

**The cost:** `fsync` takes ~3–4 ms on flash. That caps single-writer
throughput at ~300 ops/s. Every acked write is on physical disk —
the same trade-off PostgreSQL makes.

---

### Layer 2 — MemTable: the whiteboard

After the WAL, the write lands in a `ConcurrentSkipListMap` in memory.
This is a sorted map that allows lock-free concurrent reads — multiple
threads can read without blocking each other, while writes use fine-grained
locking. It's naturally sorted, so flushing to disk needs no extra sort step.

Reads check here first. A hit here costs O(log n) and never touches disk.

When the MemTable reaches ~4 MB, MiniKV:
1. Freezes the current MemTable (no more writes to it)
2. Creates a fresh MemTable + a new WAL segment (writes continue uninterrupted)
3. Flushes the frozen MemTable to disk in a background thread as an SSTable
4. Deletes the old WAL segment once the flush succeeds

---

### Layer 3 — SSTables: the permanent record

An SSTable (Sorted String Table) is an **immutable**, sorted file on disk.
Once written it is never modified. Multiple threads can read it concurrently
with no locking.

```
SSTable file layout
───────────────────
┌────────────────────────────────┐
│  Data block 0  (4 KB)          │  ← key-value pairs, sorted
│  Data block 1  (4 KB)          │
│  ...                           │
├────────────────────────────────┤
│  Sparse index                  │  ← every 16th key → file offset
├────────────────────────────────┤
│  Bloom filter                  │  ← compact "definitely not here" bitmap
├────────────────────────────────┤
│  Footer (20 bytes)             │  ← where index and bloom filter start
└────────────────────────────────┘
```

The file is memory-mapped (`MappedByteBuffer`) at open time. Reads go through
the OS page cache without a syscall — much faster than `FileChannel.read()`.

---

### How a write works (step by step)

```
put("user:1", "Alice")
  │
  ├─ 1. Append to WAL + fsync          (crash-safe, ~3 ms)
  ├─ 2. Insert into MemTable           (in-memory, O(log n))
  └─ 3. If MemTable ≥ 4 MB:
         freeze → background flush → new SSTable on disk
```

---

### How a read works (step by step)

```
get("user:1")
  │
  ├─ 1. Check MemTable                 → hit? return immediately
  │
  └─ 2. For each SSTable, newest first:
         │
         ├─ a. Ask Bloom filter: "Could this key be here?"
         │      → "Definitely not" → skip this file entirely (~99% of the time)
         │      → "Maybe"         → continue
         │
         ├─ b. Binary search the sparse index
         │      → find the file offset of the block that may contain the key
         │
         ├─ c. Check the block cache
         │      → hit?  scan the cached block in memory  (free, ~nanoseconds)
         │      → miss? read 4 KB from mmap, cache it, scan it  (~µs)
         │
         └─ d. Scan block for exact key
                → found? check for tombstone / TTL expiry → return value
                → not found? continue to next SSTable
```

**This is why reads are so fast.** The Bloom filter skips ~99% of files.
The block cache means most reads never touch disk. p99 latency is ~1 µs.

---

### The Bloom filter — the bouncer

Imagine a nightclub bouncer with a mental checklist. You tell him a name.
He doesn't look up a full list — he checks a compact bitmap in his head.
If he says **"not on the list"** you are *definitely* not getting in.
If he says **"might be on the list"** you still have to check the real list.

That's a Bloom filter. When a key is written, it's hashed 7 times and 7 bits
are set in a bitmap. To query, hash the key the same 7 ways and check all
7 bits. Any bit = 0 means the key was *never inserted* — guaranteed. All
7 bits = 1 means *probably inserted* (0.7% chance of being wrong).

The 7 probes come from just 2 base hash values using
**Kirsch-Mitzenmacher**: `h(i) = h1 + i·h2`. Fast, and avoids computing
7 independent hashes.

```
Why k=7 and 14 bits/key (not the textbook k=3 / 10 bits)?

FPR formula: (1 − e^(−kn/m))^k

  k=3, 10 bits/key → FPR ≈ 1.74%   ← above the <1% target
  k=7, 14 bits/key → FPR ≈ 0.70%   ← verified at 100 K and 1 M keys
```

---

### Compaction — cleaning up the mess

Every MemTable flush creates a new SSTable. Over time files accumulate,
reads slow down (more files to check), and old versions of keys pile up.

Compaction runs in the background every 10 seconds:

1. **Pick** a group of similarly-sized SSTables
2. **k-way merge** — open an iterator over each file; use a min-heap
   (priority queue) to pull the globally smallest key across all iterators
   at each step
3. **Deduplicate** — for the same key from multiple files, keep only the
   highest sequence number (latest write); discard all older versions
4. **Drop tombstones** — delete markers are physically removed once there
   are no older files that could still have the key
5. **Drop expired keys** — TTL-expired entries are removed at compaction time
6. **Write** the merged result to a new SSTable
7. **Atomic swap** — add the new file to the live list, remove the old ones,
   delete the old files from disk

8 files might become 1. Reads get fast again.

---

### The block cache — keeping your desk tidy

When MiniKV reads a 4 KB block from disk for the first time, it keeps it in
memory. Next time anything in that block is needed, it's already there.

The cache holds up to 1 024 blocks. When full, it evicts the
**least-recently-used** block — the one untouched for the longest time.
Implemented with a `LinkedHashMap` in access-order mode with a
`removeEldestEntry` override.

Same key read 100 times → 1 disk read, 99 cache hits. Verified in
`BlockCacheTest`.

---

## Features

- **Crash durability** — WAL fsynced on every write; CRC32 per entry; replay
  rebuilds state in <50 ms for 10 K entries.
- **MemTable** — `ConcurrentSkipListMap`; lock-free reads; sorted flush.
- **Immutable SSTables** — 4 KB blocks; sparse index; Bloom filter;
  `MappedByteBuffer` for zero-syscall reads.
- **Bloom filters** — MurmurHash3-128 + Kirsch-Mitzenmacher, k=7, 14 bits/key,
  ~0.7% FPR. Eliminates ~99% of unnecessary disk reads.
- **LRU block cache** — hot 4 KB blocks served from memory.
- **Size-tiered compaction** — k-way heap merge; removes stale versions,
  tombstones, and expired keys in background.
- **TTL / key expiry** — per-key expiry stored in WAL and SSTable; filtered
  on read, deleted at compaction.
- **REST API + Web UI** — zero-dependency HTTP server; dark-themed SPA.
- **API key auth** — 256-bit key; constant-time comparison; live rotation.
- **Prometheus metrics** — counters and histograms at `GET /metrics`.

---

## Build

Requires Java 17+ and Maven.

```bash
mvn package              # compile, test, produce JARs
mvn package -DskipTests  # skip tests
```

Produces two fat JARs in `target/`:

| JAR | Purpose |
|---|---|
| `minikv-server.jar` | REST server |
| `minikv-benchmarks.jar` | JMH benchmark suite |

---

## Run

```bash
java -jar target/minikv-server.jar --port 9090 --data ./minikv-data
```

On first start an API key is generated, written to `<data-dir>/auth.key`,
and printed to the console. It persists across restarts.
Web UI: `http://localhost:9090/ui`.

---

## REST API

All data endpoints require `Authorization: Bearer <api-key>`.

| Method | Endpoint | Description |
|---|---|---|
| `PUT` | `/put?key=k&value=v[&ttl=secs]` | Store a key (optional TTL) |
| `GET` | `/get?key=k` | Retrieve a value |
| `DELETE` | `/delete?key=k` | Delete a key (tombstone) |
| `GET` | `/scan?from=k1&to=k2` | Range scan, inclusive, sorted |
| `POST` | `/auth/rotate` | Issue a new API key, revoke old |
| `GET` | `/stats` | Engine status |
| `GET` | `/metrics` | Prometheus scrape endpoint (public) |
| `GET` | `/ui` | Web UI (public) |

```bash
KEY=$(cat minikv-data/auth.key)

curl -H "Authorization: Bearer $KEY" -X PUT \
  'http://localhost:9090/put?key=user:1&value=Alice&ttl=60'

curl -H "Authorization: Bearer $KEY" \
  'http://localhost:9090/get?key=user:1'

curl -H "Authorization: Bearer $KEY" \
  'http://localhost:9090/scan?from=user:1&to=user:9'
```

**Key ordering** is lexicographic (byte order). For numeric ranges to sort
correctly, zero-pad keys: `user-001`, `user-002` … `user-010`.
ISO-8601 timestamps sort correctly as strings without padding.

---

## Benchmarks

```bash
java -jar target/minikv-benchmarks.jar WriteBenchmark -wi 2 -i 3 -f 1
java -jar target/minikv-benchmarks.jar ReadBenchmark  -wi 2 -i 3 -f 1
java -jar target/minikv-benchmarks.jar MixedBenchmark -wi 2 -i 3 -f 1
```

### Writes — single thread, 100-byte values, WAL fsynced per write

| Benchmark | Throughput | p99 latency |
|---|---|---|
| Sequential writes | 299 ops/s | ~4 ms |
| Random writes | 276 ops/s | ~4 ms |

The `fsync` call (~3–4 ms on flash) is the bottleneck, not the engine.
This matches PostgreSQL (`synchronous_commit = on`) and RocksDB
(`sync = true`) — every acked write is on physical disk. Group commit
amortises the fsync cost across many writes and pushes throughput past
100 K ops/s.

### Reads — single thread, 100 K pre-populated keys

| Benchmark | p50 | p99 | Units |
|---|---|---|---|
| Point read — hot (1 K key space) | < 0.001 | 0.001 | ms/op |
| Point read — cold (100 K key space) | < 0.001 | 0.001 | ms/op |
| Read miss (key not present) | < 0.0001 | 0.001 | ms/op |

### Mixed — 10 threads (8 readers + 2 writers), 10 K pre-populated keys

| Benchmark | Score | Units |
|---|---|---|
| mixed:read | 9 252 874 | ops/s |
| mixed:write | 385 | ops/s |

At 10 K keys the working set fits entirely in the LRU block cache and OS page
cache, which is why read throughput is this high. At larger key spaces (e.g.
10 M keys), cold read latency reflects the Bloom filter + sparse index lookup
cost (~1–2 µs per read based on the single-thread cold read benchmark above).

---

## Design decisions

### `ConcurrentSkipListMap` over `TreeMap`

`TreeMap` requires external synchronization — every read and write compete
for the same lock. `ConcurrentSkipListMap` is lock-free for reads and uses
fine-grained locking for writes, which matters when background flush threads
and foreground request threads share the MemTable. It also iterates in sorted
order for free, eliminating a sort step before SSTable flush.

### Size-tiered compaction over leveled

Leveled compaction (LevelDB default) maintains non-overlapping key ranges
across levels — better read amplification and space efficiency but far more
complex to implement correctly. Size-tiered demonstrates the same core k-way
merge concept, has lower write amplification for write-heavy workloads, and
keeps the design clean. The `CompactionStrategy` interface makes it swappable.

### Sparse index over full index

A full per-key in-memory index for a large SSTable would be tens of megabytes.
A sparse index (one entry every 16 keys) stays small and requires scanning at
most 16 entries within a located block — an acceptable trade-off that keeps
the index resident in CPU cache.

### CRC32 on every WAL entry

A process crash can happen mid-write, leaving a partial entry at the tail of
the WAL. Without a checksum the recovery logic cannot distinguish a valid entry
from garbage bytes. CRC32 detects the partial write and stops recovery cleanly
at the last fully written record — the same reason MySQL InnoDB and PostgreSQL
use checksums in their WALs.

### `MappedByteBuffer` in `SSTableReader`

`FileChannel.read()` copies data from kernel space to user space on every
call (a syscall per read). `MappedByteBuffer` maps the data region directly
into the process address space: reads go through the OS page cache without a
syscall, and sequential scans trigger OS readahead automatically. The mapping
is established once at construction and shared safely across concurrent
`get()` calls via `duplicate()`.

### Immutable SSTables

Mutating an on-disk file under concurrent reads requires either locking or
copy-on-write — both expensive. Immutable files need no synchronization.
Compaction writes a new file and atomically swaps it into the live list under
a brief write lock, keeping the critical section nanoseconds long.

---

## Project layout

```
src/main/java/com/minikv/
├── api/          StorageEngine interface
├── model/        Entry, EntryType
├── wal/          WAL, WALEntry, WALRecovery
├── memtable/     MemTable
├── sstable/      SSTable, SSTableWriter, SSTableReader, SSTableIndex
├── filter/       BloomFilter
├── cache/        LRUBlockCache
├── compaction/   CompactionStrategy, SizeTieredCompaction, CompactionWorker
├── metrics/      StorageMetrics (Prometheus)
├── core/         LSMTree (orchestrator)
└── server/       KVServer, KVAuth, KVMain, web UI
benchmarks/       WriteBenchmark, ReadBenchmark, MixedBenchmark (JMH)
```

## License

MIT
