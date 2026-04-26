# PDF Importer Design
### Bulk loading ~200K Equibase race chart PDFs into PostgreSQL

---

## Premise and constraints

chart-parser has been battle-tested against the full corpus of Equibase PDFs from 1991
to 2019 — every format variation, every track quirk, every edge case. It is not a
prototype; it is production-proven parsing logic. The job of the importer is not to
improve on parsing but to drive chart-parser as fast as possible and get results into
PostgreSQL safely and reliably.

Key numbers to design around:
- ~200,000 PDF files
- Each raceday PDF contains 5–12 races
- Each race has 5–14 starters
- Each starter has points-of-call, fractionals, splits, meds, equipment, breeding, WPS payoff
- Estimated total rows across all tables: 50–100 million
- Target: complete import in under 4 hours on a modern developer workstation

---

## Project structure

A standalone Maven project. No Spring, no frameworks. Plain Java 21 with a main()
entry point. It depends on chart-parser as a library and uses jOOQ's generated classes
from handycapper for type-safe SQL.

```
pdf-importer/
├── pom.xml
└── src/
    ├── main/java/com/robinhowlett/importer/
    │   ├── PdfImporter.java              # Entry point, CLI arg parsing, wires pipeline
    │   ├── ImportConfig.java             # Immutable config: paths, thread count, batch size
    │   ├── pipeline/
    │   │   ├── PdfScanner.java           # Walks source dir, yields Path objects
    │   │   ├── PdfParser.java            # Wraps ChartParser, isolates exceptions
    │   │   ├── RaceWriter.java           # Batch upserts via jOOQ, one tx per PDF
    │   │   └── ImportTracker.java        # SQLite progress log (resume/retry support)
    │   ├── model/
    │   │   └── ImportResult.java         # Per-file outcome: SUCCESS / PARSE_FAILED / WRITE_FAILED
    │   └── db/
    │       └── ConnectionPool.java       # HikariCP setup, configured from ImportConfig
    └── test/java/com/robinhowlett/importer/
        ├── PdfParserTest.java            # Parse known PDF, assert RaceResult contents
        ├── RaceWriterTest.java           # Upsert correctness, idempotency (real PostgreSQL)
        └── ImportTrackerTest.java        # Resume behaviour, no duplicate processing
```

Dependencies (pom.xml):
```xml
<dependencies>
    <!-- Parsing -->
    <dependency>
        <groupId>com.robinhowlett</groupId>
        <artifactId>chart-parser</artifactId>
        <version>2.0.0-SNAPSHOT</version>          <!-- modernize branch -->
    </dependency>

    <!-- Type-safe SQL (generated classes from handycapper schema) -->
    <!-- NOTE: 3.10.7, not 3.19.x — binary compatible with handycapper's generated sources -->
    <dependency>
        <groupId>org.jooq</groupId>
        <artifactId>jooq</artifactId>
        <version>3.10.7</version>
    </dependency>
    <!-- Required by jOOQ 3.10.7 generated code -->
    <dependency>
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>1.3.2</version>
    </dependency>

    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.3</version>
    </dependency>
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- Progress tracking (embedded, no server) -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.46.0.0</version>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.6</version>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
    <!-- Flyway for applying handycapper schema in tests -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
        <version>9.22.3</version>
        <scope>test</scope>
    </dependency>
    <!-- NOTE: Testcontainers was removed — Docker Desktop's WSL2 proxy socket returns
         stub HTTP 400 responses that confuse Testcontainers' Docker environment detection.
         Integration tests connect directly to the onecli-postgres-1 container at
         localhost:5432. See RaceWriterTest for env vars to override. -->
</dependencies>
```

Java 21, `maven.compiler.release=21`. Virtual threads are a first-class feature in 21
and make the concurrency model trivial to reason about.

---

## ImportConfig

All tunable parameters in one place. No magic constants scattered through the code.

```java
public record ImportConfig(
    Path sourceDir,          // root of ~/horseracing (or subdirectory)
    String jdbcUrl,          // jdbc:postgresql://localhost:5433/handycapper
    String dbUser,
    String dbPassword,
    int threadCount,         // default: Runtime.getRuntime().availableProcessors()
    int batchSize,           // rows per executeBatch() call, default: 500
    Path progressDb,         // path to SQLite file, default: ~/import-progress.db
    Path failedLog           // path to structured failure log, default: ~/import-failed.log
) {
    public static ImportConfig defaults(Path sourceDir) {
        return new ImportConfig(
            sourceDir,
            "jdbc:postgresql://localhost:5433/handycapper",
            "handycapper", "handycapper",
            Runtime.getRuntime().availableProcessors(),
            500,
            Path.of(System.getProperty("user.home"), "import-progress.db"),
            Path.of(System.getProperty("user.home"), "import-failed.log")
        );
    }
}
```

---

## PdfScanner

Walks the source directory tree and yields PDF paths lazily. Filters out already-
processed files by checking the tracker before handing work to the thread pool.

```java
public class PdfScanner {

    public List<Path> scan(Path root, ImportTracker tracker) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                .filter(p -> p.toString().endsWith(".pdf"))
                .filter(p -> p.getFileName().toString().contains("race-charts"))
                .filter(not(tracker::alreadyProcessed))
                .sorted()
                .toList();
        }
    }
}
```

The `race-charts` filter skips PPS files and other non-result PDFs in the directory.
Adjust the predicate if your naming conventions differ.

---

## PdfParser

A thin, exception-safe wrapper around ChartParser. The critical design decision: every
exception is caught here and turned into a structured result. Nothing propagates to the
thread pool in a way that silently swallows the failure.

```java
public class PdfParser {

    private final ChartParser chartParser;

    public PdfParser() {
        this.chartParser = ChartParser.create();
    }

    public ParseOutcome parse(Path pdf) {
        try {
            List<RaceResult> results = chartParser.parse(pdf.toFile());
            return ParseOutcome.success(pdf, results);
        } catch (Exception e) {
            return ParseOutcome.failure(pdf, e);
        }
    }

    public sealed interface ParseOutcome permits ParseOutcome.Success, ParseOutcome.Failure {
        Path pdf();

        record Success(Path pdf, List<RaceResult> results) implements ParseOutcome {}
        record Failure(Path pdf, Exception cause) implements ParseOutcome {}
    }
}
```

`ChartParser.create()` is not thread-safe to construct concurrently (it initialises
static state) but is safe to call from multiple threads once created. Create one
instance and share it.

---

## ImportTracker

SQLite-backed progress log. SQLite is perfect here: it's embedded (no server), ACID,
and fast enough for 200K single-row inserts. The file persists across JVM restarts,
enabling resume.

Schema:
```sql
CREATE TABLE IF NOT EXISTS import_log (
    path          TEXT    PRIMARY KEY,
    status        TEXT    NOT NULL,        -- SUCCESS, PARSE_FAILED, WRITE_FAILED
    races_loaded  INTEGER,
    error_type    TEXT,                    -- exception class name
    error_message TEXT,
    processed_at  TEXT    DEFAULT (datetime('now'))
);
```

Key methods:
```java
public class ImportTracker implements AutoCloseable {

    // Returns true if this path is already recorded as SUCCESS.
    // FAILED entries are NOT filtered — they will be retried on re-run unless
    // the caller explicitly deletes them from the DB first.
    public boolean alreadyProcessed(Path pdf) { ... }

    public void recordSuccess(Path pdf, int racesLoaded) { ... }

    public void recordFailure(Path pdf, ImportResult.Status status, Exception e) { ... }

    // Useful queries for diagnosis after a run:
    //   SELECT status, count(*) FROM import_log GROUP BY status;
    //   SELECT * FROM import_log WHERE status != 'SUCCESS' LIMIT 100;
    //   SELECT substr(path, ...) as year, count(*) FROM import_log GROUP BY year;
}
```

All writes use `INSERT OR REPLACE` so re-running a failed file updates its status
rather than duplicating the row.

---

## RaceWriter

The most performance-sensitive class. It receives a `List<RaceResult>` for one PDF and
writes everything to PostgreSQL in a single transaction using jOOQ batch upserts.

### Upsert strategy

Replace handycapper's delete-then-reinsert with proper PostgreSQL upserts:

```java
// Example: races table
dslContext.insertInto(RACES, RACES.DATE, RACES.TRACK, RACES.NUMBER, /* ... */)
    .values(date, trackCode, raceNumber, /* ... */)
    .onConflict(RACES.DATE, RACES.TRACK_CANONICAL, RACES.NUMBER)
    .doUpdate()
    .set(RACES.TRACK_NAME, excluded(RACES.TRACK_NAME))
    .set(/* ... all updatable columns ... */)
    .execute();
```

For sub-tables (starters, points_of_call, fractionals, etc.) the conflict target is
the foreign key + a unique business key. For example, starters uses
`(race_id, program)`. Points-of-call uses `(starter_id, point)`.

This is atomic. If the write fails midway, the transaction rolls back completely and
the file is marked WRITE_FAILED. Re-running will retry from scratch cleanly.

### Write order within a transaction

Order matters because of foreign key constraints:

```
1. INSERT INTO races          → get race_id
2. INSERT INTO starters       → get starter_id (requires race_id)
3. INSERT INTO points_of_call (requires starter_id)
4. INSERT INTO fractionals    (race-level, requires race_id)
5. INSERT INTO indiv_fractionals (requires starter_id)
6. INSERT INTO splits         (race-level, requires race_id)
7. INSERT INTO indiv_splits   (requires starter_id)
8. INSERT INTO wps            (requires race_id)
9. INSERT INTO exotics        (requires race_id)
10. INSERT INTO breeding      (requires starter_id)
11. INSERT INTO meds          (requires starter_id)
12. INSERT INTO equip         (requires starter_id)
13. INSERT INTO ratings       (requires starter_id)
14. INSERT INTO scratches     (requires race_id)
```

### Batch execution

Don't execute one row at a time. Accumulate all rows for a given table across all
races in the PDF, then execute as a single batch:

```java
// Collect all starter insert steps across all races in this PDF
var starterInserts = new ArrayList<Query>();
for (RaceResult race : results) {
    RacesRecord raceRecord = insertRace(dsl, race);   // returns the inserted record
    for (Starter starter : race.getStarters()) {
        starterInserts.add(buildStarterInsert(dsl, raceRecord, starter));
    }
}
dsl.batch(starterInserts).execute();
```

This reduces round-trips to PostgreSQL by a factor of ~50 per PDF (one batch call per
table instead of one call per row). At 200K PDFs with ~8 starters each, this is
~1.6M fewer network round-trips for the starters table alone.

### Transaction boundary

One transaction per PDF file. This is the right granularity:
- Fine enough that a single bad race doesn't abort an entire raceday
- Coarse enough to give PostgreSQL large WAL batches to write efficiently
- Matches the natural unit of work (one file → zero or more races)

```java
public ImportResult write(Path pdf, List<RaceResult> results) {
    try {
        dslContext.transaction(config -> {
            DSLContext tx = DSL.using(config);
            int racesWritten = 0;
            for (RaceResult result : results) {
                writeRace(tx, result);
                racesWritten++;
            }
        });
        return ImportResult.success(pdf, results.size());
    } catch (Exception e) {
        return ImportResult.writeFailed(pdf, e);
    }
}
```

---

## PdfImporter (main entry point)

```java
public class PdfImporter {

    public static void main(String[] args) throws Exception {
        // Suppress PDFBox JUL warnings (font fallbacks etc.) — they bypass Logback
        java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.SEVERE);

        Path sourceDir = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.home"), "horseracing");
        ImportConfig config = ImportConfig.fromEnvironment(sourceDir);

        try (var tracker = new ImportTracker(config.progressDb());
             var pool    = ConnectionPool.create(config)) {

            var scanner = new PdfScanner();
            var parser  = new PdfParser();
            var writer  = new RaceWriter(pool.getDataSource());

            List<Path> files = scanner.scan(sourceDir, tracker);
            System.out.printf("Found %,d unprocessed PDFs%n", files.size());

            var success = new AtomicInteger();
            var failed  = new AtomicInteger();
            var start   = System.currentTimeMillis();

        // Semaphore caps concurrent in-flight PDFs. Without it, all 200K tasks are submitted
        // at once — PDFBox's per-PDF heap usage causes OOM. Virtual threads park on acquire()
        // rather than blocking a carrier thread, so there is no CPU overhead to this bound.
        var semaphore = new Semaphore(config.threadCount());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path pdf : files) {
                semaphore.acquire();
                executor.submit(() -> {
                    try {
                        var parsed = parser.parse(pdf);
                        if (parsed instanceof PdfParser.ParseOutcome.Failure f) {
                            tracker.recordFailure(pdf, PARSE_FAILED, f.cause());
                            failed.incrementAndGet();
                            return;
                        }
                        var results = ((PdfParser.ParseOutcome.Success) parsed).results();
                        var written = writer.write(pdf, results);
                        if (written.isSuccess()) {
                            tracker.recordSuccess(pdf, written.racesLoaded());
                            success.incrementAndGet();
                        } else {
                            tracker.recordFailure(pdf, WRITE_FAILED, written.cause());
                            failed.incrementAndGet();
                        }
                    } finally {
                        semaphore.release();
                    }
                    printProgress(success, failed, files.size(), start);
                });
            }
        } // executor.close() blocks until all tasks complete

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("Done in %d min %d sec. Success: %,d  Failed: %,d%n",
            elapsed / 60, elapsed % 60, success.get(), failed.get());
        } // tracker and pool closed here
    }
}
```

### Why virtual threads + a semaphore

Java 21 virtual threads are well-suited here because the work alternates between CPU
(PDF parsing, regex matching) and I/O (PostgreSQL writes). Virtual threads yield during
I/O waits automatically, keeping CPU saturated without the overhead of managing a fixed
thread pool manually.

However, `newVirtualThreadPerTaskExecutor()` submits all tasks immediately — with 200K
files this means 200K virtual threads are created upfront, each eager to load a PDF into
memory. PDFBox's per-PDF heap usage (50–200MB) causes OOM long before the JVM's carrier
threads have a chance to drain the backlog.

The fix is a `Semaphore(threadCount)`: tasks block on `acquire()` before parsing begins,
and release on completion. Virtual threads park rather than blocking a carrier thread, so
this adds no CPU overhead while keeping at most `threadCount` PDFs in memory at any time.

---

## PostgreSQL tuning for bulk load

Run these before starting the import. They survive restarts to the last checkpoint;
in the unlikely event of a crash mid-import, already-committed transactions are safe,
and the importer will skip them on resume.

### Session-level (no config file changes needed)

These can be set in ConnectionPool.java as JDBC connection properties or via HikariCP's
connectionInitSql:

```sql
SET synchronous_commit = off;
-- Async WAL flush: commits return without waiting for fsync.
-- Safe for bulk loads where durability of individual rows is less important
-- than throughput; the progress tracker is your durability guarantee.

SET work_mem = '64MB';
-- Enough for sort operations during index maintenance.

SET maintenance_work_mem = '256MB';
-- Used when rebuilding indexes after the load.
```

### Index strategy

The handycapper schema has 26+ indices on races and starters. Index maintenance during
bulk insert is expensive — PostgreSQL must update every index for every inserted row.
For a clean bulk load, drop all non-essential indices first:

```sql
-- Keep: the unique constraints that upserts depend on
-- DROP: everything else

DROP INDEX IF EXISTS handycapper.idx_races_track;
DROP INDEX IF EXISTS handycapper.idx_races_date;
DROP INDEX IF EXISTS handycapper.idx_starters_horse_name;
-- ... (run \d+ handycapper.races in psql to list all indices)

-- After import completes:
CREATE INDEX CONCURRENTLY idx_races_track      ON handycapper.races(track_canonical);
CREATE INDEX CONCURRENTLY idx_races_date       ON handycapper.races(date);
CREATE INDEX CONCURRENTLY idx_starters_horse   ON handycapper.starters(horse_name);
-- ...
```

`CREATE INDEX CONCURRENTLY` builds the index without locking the table, so you can
query data while it rebuilds. On 50M+ rows this will take 15–30 minutes but the table
remains fully available.

---

## Progress monitoring

The SQLite database is your single source of truth for what happened.

```bash
# Overall status after a run
sqlite3 ~/import-progress.db \
  "SELECT status, count(*), max(processed_at) FROM import_log GROUP BY status;"

# Failures by exception type (tells you what to fix)
sqlite3 ~/import-progress.db \
  "SELECT error_type, count(*) FROM import_log
   WHERE status != 'SUCCESS'
   GROUP BY error_type ORDER BY 2 DESC;"

# Success rate by year (extract from path)
sqlite3 ~/import-progress.db \
  "SELECT substr(path, instr(path,'/',-1)-4, 4) as year,
          count(*) total,
          sum(status='SUCCESS') ok
   FROM import_log GROUP BY year ORDER BY year;"

# Reset failures for retry
sqlite3 ~/import-progress.db \
  "DELETE FROM import_log WHERE status != 'SUCCESS';"
```

Live progress during the run is printed to stdout every 1,000 files (see PdfImporter)
including current rate (PDFs/sec) and estimated time remaining.

---

## Tests

### Unit: PdfParserTest

Verifies that parsing works and the RaceResult structure matches expectations. Uses the
existing ARP_2016-07-24_race-charts.pdf test fixture from chart-parser — this is the
known-good baseline.

```java
@Test
void parse_KnownGoodPdf_ReturnsNineRaces() {
    var parser = new PdfParser();
    var pdf = Path.of("src/test/resources/ARP_2016-07-24_race-charts.pdf");

    var outcome = parser.parse(pdf);

    assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome);
    var results = ((PdfParser.ParseOutcome.Success) outcome).results();
    assertEquals(9, results.size());  // actual count; PDF has 9 races not 10
    assertEquals("ARP", results.get(0).getTrack().getCode());
}

@Test
void parse_EmptyFile_ReturnsSuccessWithNoRaces() {
    // chart-parser returns empty list rather than throwing for unreadable PDFs
    var outcome = new PdfParser().parse(Path.of("/dev/null"));
    assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome);
    assertTrue(((PdfParser.ParseOutcome.Success) outcome).results().isEmpty());
}
```

### Unit: RaceWriterTest

Connects directly to the `handycapper_test` database in the `onecli-postgres-1`
container (localhost:5432). Testcontainers was removed — Docker Desktop's WSL2 proxy
socket returns stub HTTP responses that break its Docker environment detection.

Schema is applied via Flyway from `src/test/resources/db/migration/` using `clean()`
then `migrate()` in `@BeforeAll`. Connection details default to
`localhost:5432/handycapper_test` and can be overridden via env vars:
`TEST_JDBC_URL`, `TEST_DB_USER`, `TEST_DB_PASS`.

```java
@Test
void write_NewPdf_InsertsAllRacesAndStarters() {
    writer.write(SAMPLE_PDF, parseResults);

    assertEquals(9, dsl.fetchCount(RACES));   // ARP_2016-07-24 has 9 races
    assertEquals(45, dsl.fetchCount(STARTERS));
}

@Test
void write_SamePdfTwice_IsIdempotent() {
    writer.write(SAMPLE_PDF, parseResults);
    writer.write(SAMPLE_PDF, parseResults);  // second write must not fail or duplicate

    assertEquals(9, dsl.fetchCount(RACES));
    assertEquals(45, dsl.fetchCount(STARTERS));
}

@Test
void write_PartialFailure_DoesNotLeaveOrphanRows() {
    // Inject a RaceResult with null date to trigger constraint violation
    var bad = new ArrayList<>(parseResults);
    bad.add(raceResultWithNullDate());

    var outcome = writer.write(SAMPLE_PDF, bad);

    assertEquals(WRITE_FAILED, outcome.status());
    assertEquals(0, dsl.fetchCount(RACES));  // full rollback
}
```
```

### Unit: ImportTrackerTest

Verifies resume logic. The most important property: a file recorded as SUCCESS is
never submitted to the parser again; a FAILED file is retried on the next run.

```java
@Test
void alreadyProcessed_SuccessfulFile_ReturnsTrue() {
    tracker.recordSuccess(somePdf, 10);
    assertTrue(tracker.alreadyProcessed(somePdf));
}

@Test
void alreadyProcessed_FailedFile_ReturnsFalse_SoItIsRetried() {
    tracker.recordFailure(somePdf, PARSE_FAILED, new RuntimeException("oops"));
    assertFalse(tracker.alreadyProcessed(somePdf));
}
```

---

## Throughput expectations

Based on chart-parser parse times (~0.5–1s per PDF on modern hardware) and PostgreSQL
localhost write latency (~5–20ms per batch):

| Thread count | PDFs/sec | Time for 200K files |
|---|---|---|
| 4  | ~4   | ~14 hours |
| 8  | ~8   | ~7 hours  |
| 16 | ~14  | ~4 hours  |
| 32 | ~20  | ~2.5 hours|

The bottleneck is CPU (PDF text extraction and regex matching in chart-parser), not the
database. Dropping indices before the load and using batch inserts keeps PostgreSQL
fast enough that it is not the limiting factor on localhost.

Expect higher numbers if your hardware has fast NVMe storage (PDFBox reads from disk)
and multiple physical cores. The virtual thread model means you can go beyond CPU count
without explicit tuning and let the JVM find the natural saturation point.

---

## Running the importer

```bash
# Build (from pdf-importer directory)
mvn clean package -DskipTests

# Full run against the Equibase charts corpus
IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/handycapper \
java -Xmx4g -jar target/pdf-importer-1.0.0-SNAPSHOT.jar \
  /home/robin/horseracing/s3/data/equibase/charts

# Run against a subset (single track or year subdirectory)
IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/handycapper \
java -Xmx4g -jar target/pdf-importer-1.0.0-SNAPSHOT.jar \
  /home/robin/horseracing/s3/data/equibase/charts/ARP

# Retry only failed files (delete their entries from progress DB first)
sqlite3 ~/import-progress.db "DELETE FROM import_log WHERE status != 'SUCCESS';"
IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/handycapper \
java -Xmx4g -jar target/pdf-importer-1.0.0-SNAPSHOT.jar \
  /home/robin/horseracing/s3/data/equibase/charts

# Check progress (requires sqlite3 CLI, or query via any SQLite client)
sqlite3 ~/import-progress.db \
  "SELECT status, count(*) FROM import_log GROUP BY status;"
```

`-Xmx4g` gives the JVM 4GB heap. PDFBox is memory-intensive — each PDF parse allocates
substantial temporary objects. The semaphore caps concurrent in-flight PDFs at
`threadCount` (default: CPU count), so peak heap usage is bounded to roughly
`threadCount × 200MB`. 4GB is comfortable for 16 threads on a 16-core machine.

---

## What is deliberately not included

**No Spring**: The import pipeline is a batch job, not a web service. Spring's startup
overhead (class scanning, proxy generation, ApplicationContext wiring) adds seconds at
startup and megabytes of overhead per JVM for zero benefit in a single-use CLI tool.

**No ORM**: jOOQ is used directly with generated classes. Hibernate would add
complexity (session management, lazy loading, N+1 query risks) where simple batch
inserts are needed.

**No command-line framework (Picocli, Commons CLI)**: The argument surface is two
options: source directory and an optional config override. A plain args[] check is
sufficient; adding a framework would be over-engineering.

**No Kafka/queue**: The input set is finite and known. A streaming architecture adds
operational complexity (broker setup, consumer groups, offset management) that is not
justified when a simple thread pool over a file list achieves the same result with far
less infrastructure.
