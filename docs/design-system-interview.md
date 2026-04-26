# Design a Horse Racing PDF Bulk Import System
### In the style of *System Design Interview* by Alex Xu

---

## Step 1 — Understand the Problem and Establish Design Scope

Before jumping into solutions, we ask clarifying questions to nail down requirements.
In a real interview these would be a back-and-forth; here we record both sides.

---

**Q: What is the scale of the data we need to import?**

A: Approximately 200,000 Equibase PDF race chart files. Each file covers one raceday
at one track. Files span from 1991 to 2019.

**Q: Do we need to build the PDF parsing logic from scratch?**

A: No. `chart-parser` is a battle-tested library that already handles this corpus
successfully. We treat it as a black box that takes a PDF file and returns a
`List<RaceResult>`.

**Q: What is the target database?**

A: PostgreSQL 16, running locally. The schema already exists (handycapper project).
jOOQ generated classes are available for type-safe SQL.

**Q: What are the latency requirements? Does this need to be real-time?**

A: No. This is a one-time bulk load. The goal is to complete the full import in a
reasonable time — ideally under 4 hours on a developer workstation.

**Q: What happens if a file fails to parse or write?**

A: The file should be logged as failed and skipped. The import must continue
processing remaining files. Failed files should be retryable without reprocessing
successful ones.

**Q: Can the import be interrupted and resumed?**

A: Yes. With 200,000 files the process may take hours. It must be safe to kill and
restart without re-importing already-processed data.

**Q: What consistency guarantees do we need?**

A: Per-file atomicity. Either all rows for a PDF are committed or none are. If a
write fails midway, the database must not contain partial data for that file.

**Q: Do we need to support incremental loads (new PDFs arriving over time)?**

A: Not in scope for this design. This is a one-time historical backfill. The same
tool could be repurposed for incremental loads with minor changes.

---

### Functional Requirements

1. Parse all PDFs under a given source directory using chart-parser
2. Write parsed race data to PostgreSQL in the handycapper schema
3. Support resuming an interrupted run without reprocessing successful files
4. Log per-file outcomes (success, parse failure, write failure)
5. Allow retrying failed files without reprocessing successful ones

### Non-Functional Requirements

1. **Throughput**: Complete 200,000 files in ≤ 4 hours
2. **Reliability**: One failed file must not block or corrupt others
3. **Idempotency**: Re-running on the same file produces the same database state
4. **Observability**: Progress, rate, and estimated completion visible at runtime
5. **Simplicity**: No external infrastructure (no Kafka, no distributed systems)

### Out of Scope

- Building a PDF parser (chart-parser handles this)
- Real-time or streaming import
- Distributed processing across multiple machines
- A web UI or API for triggering imports

---

## Step 2 — High-Level Design

### Back-of-the-Envelope Estimation

Let us validate that the requirements are achievable before designing anything.

**Data volume:**
```
200,000 PDFs
× ~8 races per PDF (average; varies from 4 to 14)
= ~1,600,000 races

× ~10 starters per race (average)
= ~16,000,000 starters

Each starter has related rows across ~8 sub-tables:
  points_of_call:      ~6 rows/starter  →  ~96M rows
  indiv_fractionals:   ~5 rows/starter  →  ~80M rows
  indiv_splits:        ~5 rows/starter  →  ~80M rows
  meds/equip:          ~2 rows/starter  →  ~32M rows
  breeding:            ~1 row/starter   →  ~16M rows

Total estimated rows: ~50–100 million across all tables
Estimated database size: ~40–80 GB
```

**Throughput target:**
```
200,000 files in 4 hours = 13.9 files/sec sustained

PDF parse time (chart-parser, measured): ~0.5–1.0 sec/file
DB write time per file (estimated, localhost): ~50–200ms

With 16 threads:
  16 × (1/0.75 sec) ≈ 21 files/sec  ✓ meets target

With 8 threads:
  8 × (1/0.75 sec) ≈ 11 files/sec  → ~5 hours  (marginal)
```

Conclusion: 16 threads comfortably meets the 4-hour target. The bottleneck is
CPU-bound PDF parsing, not the database. Index maintenance during insert would
significantly slow writes; we will drop and rebuild indices around the load.

---

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    PdfImporter (main)                    │
│                                                         │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────────┐│
│  │PdfScanner│──▶│PdfParser │──▶│     RaceWriter        ││
│  │          │   │          │   │  (jOOQ batch upserts) ││
│  │ walks    │   │ wraps    │   │                       ││
│  │ ~/horse  │   │ ChartPar-│   │  one transaction      ││
│  │ racing   │   │ ser lib  │   │  per PDF file         ││
│  └──────────┘   └──────────┘   └──────────────────────┘│
│        │               │                    │           │
│        ▼               ▼                    ▼           │
│  ┌─────────────────────────────────────────────────────┐│
│  │               ImportTracker                          ││
│  │         (SQLite progress log)                        ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
              │                          │
              ▼                          ▼
    ┌──────────────────┐      ┌──────────────────────┐
    │  ~/horseracing/  │      │  PostgreSQL 16        │
    │  (200K PDFs)     │      │  handycapper schema   │
    └──────────────────┘      └──────────────────────┘
```

**Data flow:**

1. `PdfScanner` walks the source directory and yields unprocessed PDF paths,
   filtered by `ImportTracker` (skip already-successful files)
2. Each path is submitted to a virtual thread pool
3. `PdfParser` calls `ChartParser.parse(file)` → `List<RaceResult>`
4. `RaceWriter` writes all rows for the PDF in a single PostgreSQL transaction
5. `ImportTracker` records the outcome (SUCCESS / PARSE_FAILED / WRITE_FAILED)
6. Progress is printed to stdout every 1,000 files

---

### Core APIs (internal)

Since this is a batch tool rather than a web service, "API" means the interfaces
between components.

```java
// PdfScanner: returns sorted list of paths not yet successfully processed
List<Path> scan(Path root, ImportTracker tracker) throws IOException;

// PdfParser: safe wrapper — never throws, always returns a structured result
sealed interface ParseOutcome {
    record Success(Path pdf, List<RaceResult> results) implements ParseOutcome {}
    record Failure(Path pdf, Exception cause)          implements ParseOutcome {}
}
ParseOutcome parse(Path pdf);

// RaceWriter: writes one PDF's worth of races atomically
record ImportResult(Path pdf, Status status, int racesLoaded, Exception cause) {}
ImportResult write(Path pdf, List<RaceResult> results);

// ImportTracker: durable progress log
boolean alreadyProcessed(Path pdf);
void recordSuccess(Path pdf, int racesLoaded);
void recordFailure(Path pdf, ImportResult.Status status, Exception e);
```

---

## Step 3 — Design Deep Dive

### Component 1: Concurrency Model

**Option A — Fixed thread pool (ExecutorService)**
- Simple to reason about
- Must manually tune thread count
- Threads block during DB writes, wasting resources

**Option B — Virtual threads (Java 21)**
- JVM multiplexes virtual threads onto carrier threads automatically
- Naturally saturates CPU during parsing, yields during I/O waits
- No manual tuning; thread count can be "one per file in flight"
- Zero overhead compared to OS threads for blocking I/O

We choose **Option B**. Java 21 virtual threads are the right tool: the workload
alternates between CPU-heavy parsing and I/O-heavy DB writes, which is exactly the
use case virtual threads were designed for.

```java
// Semaphore bounds concurrent in-flight PDFs to threadCount.
// Without it, all 200K tasks are submitted at once — PDFBox's per-PDF heap
// allocation (50–200MB) causes OOM before the carrier threads can drain the queue.
// Virtual threads park on acquire() rather than blocking a carrier thread.
var semaphore = new Semaphore(config.threadCount());

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Path pdf : files) {
        semaphore.acquire();
        executor.submit(() -> {
            try {
                processPdf(pdf);
            } finally {
                semaphore.release();
            }
        });
    }
} // blocks until all tasks complete
```

**Important:** `newVirtualThreadPerTaskExecutor()` submits all tasks immediately. With
200,000 files this creates 200,000 virtual threads at once, each trying to load a PDF
into memory. The `Semaphore(threadCount)` is essential to bound peak memory usage.
Virtual threads park on `acquire()` rather than blocking a carrier thread, so this adds
no CPU cost — it is a backpressure valve, not a throttle.

---

### Component 2: Database Write Strategy

This is the most critical performance decision. Three options:

**Option A — Single-row inserts**
```
200,000 PDFs × ~300 rows/PDF × 8 tables = 480 million INSERT statements
At 1ms each: 480,000 seconds = 133 hours  ✗ completely unacceptable
```

**Option B — Batch inserts per table per PDF**
```
200,000 PDFs × 8 tables = 1,600,000 executeBatch() calls
Each batch: ~300 rows submitted in one round-trip
At 20ms each: 32,000 seconds = ~9 hours  ✗ still too slow with indices
Without indices: ~2 hours  ✓ meets target
```

**Option C — COPY protocol**
PostgreSQL's `COPY` command is the fastest possible bulk load mechanism, streaming
CSV directly into a table without row-by-row parsing. It is 5–10× faster than batch
inserts but requires implementing CSV serialisation and does not support ON CONFLICT
upsert semantics natively (you would COPY to a staging table then merge).

We choose **Option B** with index dropping. The added complexity of COPY (staging
tables, CSV generation, merge logic) is not justified when batch inserts with no
indices already meet the throughput target. If profiling reveals the DB is still the
bottleneck, Option C is the upgrade path.

**Upsert not delete-then-reinsert:**

The original handycapper used `DuplicateKeyException` → delete → reinsert. This is:
- Not atomic (gap between delete and reinsert)
- 2× the write amplification
- Incompatible with concurrent access

We use PostgreSQL's native upsert:

```sql
INSERT INTO handycapper.races (date, track_canonical, number, ...)
VALUES (...)
ON CONFLICT (date, track_canonical, number)
DO UPDATE SET track_name = EXCLUDED.track_name, ...;
```

This is a single atomic operation. Re-running on the same file is safe and produces
the same result.

**Transaction boundary:**

One transaction per PDF file. This is the right granularity because:
- A failed mid-PDF write rolls back cleanly; no orphaned rows
- PostgreSQL gets large WAL batches (better throughput than per-row commits)
- Matches the natural unit of work
- The progress tracker marks failed files for retry

---

### Component 3: ImportTracker (Resume Support)

**Why not use the PostgreSQL database itself for tracking?**

Using the application database for import state creates a circular dependency: if the
database is unavailable, we cannot check whether files were already processed. SQLite
is a better fit: it is embedded, requires no server, survives independently of the
application database, and is fast enough for 200,000 single-row operations.

**Schema:**

```sql
CREATE TABLE IF NOT EXISTS import_log (
    path          TEXT    PRIMARY KEY,
    status        TEXT    NOT NULL,     -- SUCCESS, PARSE_FAILED, WRITE_FAILED
    races_loaded  INTEGER,
    error_type    TEXT,                 -- Java exception class name
    error_message TEXT,
    processed_at  TEXT    DEFAULT (datetime('now'))
);
```

**Resume logic:**

On startup, `PdfScanner` filters out paths where `status = 'SUCCESS'`. Files with
`status = 'PARSE_FAILED'` or `'WRITE_FAILED'` are re-submitted — they are retried on
every run until they succeed or the operator deletes their entries.

This is intentional: PARSE_FAILED files may succeed after a chart-parser update.
WRITE_FAILED files may succeed after fixing a database issue.

**Concurrency note:**

Multiple virtual threads will call `ImportTracker` concurrently. SQLite handles this
safely with WAL mode, but writes must be serialised through a single connection or a
`synchronized` block. Given that tracker writes are fast (microseconds), a simple
`synchronized` method is sufficient; a connection pool would be over-engineering.

---

### Component 4: Index Management

**The problem:**

PostgreSQL must maintain every index for every inserted row. With 26 indices on the
races and starters tables, each insert touches 26+ B-trees. At 50–100 million rows
this is the dominant cost.

**The solution:**

Drop all non-essential indices before the load, rebuild after.

```sql
-- Before load: keep only the unique constraints that upserts depend on
-- Drop everything else (fetch the list with: \di handycapper.*)

DROP INDEX CONCURRENTLY IF EXISTS handycapper.idx_races_track;
DROP INDEX CONCURRENTLY IF EXISTS handycapper.idx_starters_horse_name;
-- ... etc

-- After load completes: rebuild concurrently (no table lock)
CREATE INDEX CONCURRENTLY idx_races_track
    ON handycapper.races(track_canonical);
CREATE INDEX CONCURRENTLY idx_starters_horse_name
    ON handycapper.starters(horse_name);
-- ...
```

`CONCURRENTLY` means the table remains fully queryable during index builds. On 50M+
rows, rebuilding all indices will take 20–40 minutes, but this happens once at the
end of the load, not during it.

---

### Component 5: Failure Handling and Observability

**Failure taxonomy:**

| Failure type | Cause | Handling |
|---|---|---|
| PARSE_FAILED | chart-parser exception, corrupt PDF | Log, skip, continue |
| WRITE_FAILED | constraint violation, DB timeout | Rollback tx, log, continue |
| INFRASTRUCTURE | disk full, DB down | Fatal; stop the run |

Infrastructure failures bubble up as uncaught exceptions from the thread pool and
terminate the process. The progress tracker preserves all state; the run can be
restarted once the infrastructure issue is resolved.

**Progress output (stdout every 1,000 files):**

```
[12,000/200,000] 18.4 PDFs/sec, ~169 min remaining  (success: 11,987  failed: 13)
```

**Post-run analysis via SQLite:**

```bash
# Summary
sqlite3 ~/import-progress.db \
  "SELECT status, count(*) FROM import_log GROUP BY status;"

# Top failure types
sqlite3 ~/import-progress.db \
  "SELECT error_type, count(*) FROM import_log
   WHERE status != 'SUCCESS'
   GROUP BY error_type ORDER BY 2 DESC LIMIT 10;"

# Success rate by year
sqlite3 ~/import-progress.db \
  "SELECT substr(path, instr(path,'/',-1)-4, 4) year,
          count(*) total,
          sum(status = 'SUCCESS') ok,
          printf('%.1f%%', 100.0 * sum(status='SUCCESS') / count(*)) rate
   FROM import_log GROUP BY year ORDER BY year;"
```

---

### Data Write Order (within a transaction)

PostgreSQL enforces foreign key constraints at statement time (by default). The write
order must respect the dependency chain:

```
races                           ← no dependencies
  └── starters (race_id)
        ├── points_of_call (starter_id)
        ├── indiv_fractionals (starter_id)
        ├── indiv_splits (starter_id)
        ├── indiv_ratings (starter_id)
        ├── meds (starter_id)
        ├── equip (starter_id)
        └── breeding (starter_id)
  ├── fractionals (race_id)
  ├── splits (race_id)
  ├── wps (race_id)
  ├── exotics (race_id)
  └── scratches (race_id)
```

jOOQ's `insertInto(...).returning(...)` retrieves the generated `race_id` and
`starter_id` from each parent insert and passes them to the child inserts.

---

## Step 4 — Wrap Up

### Summary

We designed a bulk PDF import system that:

- Drives an existing battle-tested parser library (chart-parser) with no modifications
- Uses virtual threads to saturate CPU during parsing and yield during DB writes
- Achieves per-file atomicity via single-transaction writes per PDF
- Supports resume via a SQLite progress log independent of the application database
- Uses PostgreSQL upserts for idempotent writes
- Meets the 4-hour throughput target through batch inserts and index management

The design is intentionally minimal: no distributed systems, no external queues, no
frameworks. The complexity of the problem is in the parsing (already solved) and the
data volume (addressed with concurrency and batch writes). Adding infrastructure
would solve problems we do not have.

---

### Potential Bottlenecks and Mitigations

| Bottleneck | Symptom | Mitigation |
|---|---|---|
| CPU (parsing) | All cores at 100%, DB idle | Already the design target; add cores |
| Disk I/O (PDF reads) | High iowait, CPU idle | Use NVMe; pre-stage PDFs on fast disk |
| DB write throughput | Threads blocked in write | Batch size tuning; COPY protocol |
| DB index maintenance | Write throughput degrades over time | Drop indices before load (already planned) |
| JVM heap pressure | GC pauses, OOM | `-Xmx4g`; semaphore limits concurrent PDFs in memory to `threadCount` |
| SQLite write contention | Tracker writes slow | WAL mode + synchronized writes (already planned) |

---

### Further Improvements

**If we need 10× more throughput:**

Distribute across multiple machines. Partition the PDF directory by year or track and
run one importer instance per partition, each writing to the same PostgreSQL cluster
with a connection pool. The upsert-based write strategy means concurrent importers
will not corrupt each other — they will simply merge results on conflict.

**If we need ongoing incremental loads:**

Add a file watcher (Java `WatchService`) to monitor a hot directory for new PDFs.
The progress tracker already provides idempotency; new files are processed once,
existing successful files are skipped. The ImportTracker schema would need a
`source` column to distinguish the initial backfill from incremental files.

**If chart-parser parse failures cluster in a specific era:**

The failure analysis query (grouped by year) will reveal this pattern. The mitigation
is to update chart-parser with format-specific handling for that era — the importer
does not change, it retries automatically on the next run after the library update.

**If the target database is remote (cloud PostgreSQL):**

Switch from batch inserts to the COPY protocol with staging tables. Network latency
makes individual round-trips expensive; COPY streams the entire payload in one
connection. The tradeoff is more complex write logic (copy to temp table, merge with
ON CONFLICT). Worth implementing if network latency exceeds ~10ms.

---

### What a Good Candidate Covers in This Interview

In Alex Xu's format, a strong answer demonstrates:

✓ Establishing scale before designing (envelope math showing thread count is sufficient)
✓ Recognising that parsing is already solved and not re-solving it
✓ Choosing virtual threads over a fixed pool and explaining why
✓ Choosing batch upserts over COPY and explaining the trade-off
✓ Understanding that index management is a bulk-load concern, not an afterthought
✓ Using SQLite for the progress tracker instead of the application database
✓ Defining per-file transaction boundaries and explaining the rollback behaviour
✓ Providing a clear failure taxonomy with different handling per failure type
✓ Identifying future upgrade paths (COPY protocol, distributed load) without
  over-engineering the initial solution

A weak answer jumps straight to implementation details, picks distributed systems
tools for a single-machine problem, or fails to quantify the throughput target before
choosing a concurrency model.
