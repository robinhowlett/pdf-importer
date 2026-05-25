# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Bulk loader that imports ~200K Equibase horse racing chart PDFs (1991–2019) into PostgreSQL. Parsing is done by `chart-parser` (a private companion library); this project drives it in parallel and writes results via jOOQ.

## Build & Run

```bash
# Build (requires GitHub Packages auth in ~/.m2/settings.xml for chart-parser)
mvn clean package -DskipTests

# Run against Docker Compose Postgres (port 5433)
docker compose up -d
java -Xmx4g -jar target/pdf-importer-*.jar /path/to/pdfs

# Run against existing Postgres (port 5432)
IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/handycapper \
java -Xmx4g -jar target/pdf-importer-*.jar /path/to/pdfs
```

## Testing

```bash
# Unit tests (ImportTrackerTest, PdfParserTest) — no DB needed
mvn test -pl . -Dtest="ImportTrackerTest,PdfParserTest"

# Integration tests (RaceWriterTest) — needs a running Postgres with handycapper_test DB
# Default: localhost:5432/handycapper_test, user/pass: handycapper/handycapper
# Override with: TEST_JDBC_URL, TEST_DB_USER, TEST_DB_PASS
mvn test -pl . -Dtest="RaceWriterTest"

# All tests
mvn test
```

Integration tests use Flyway to apply migrations from `src/test/resources/db/migration/` — they `clean()` and re-migrate on each run.

## jOOQ Code Generation

Generated classes live in `src/main/generated/` and are committed to version control. Codegen is skipped by default. To regenerate after schema changes:

```bash
# Requires a live Postgres at localhost:5432/handycapper with the current schema applied
mvn generate-sources -Pcodegen
```

## Architecture

The pipeline is a linear flow: **Scan → Parse → Write**, coordinated by `PdfImporter.main()` using virtual threads with a semaphore to cap memory usage.

| Class | Role |
|---|---|
| `PdfImporter` | Entry point. Virtual-thread executor + semaphore for backpressure |
| `ImportConfig` | Record holding all env-var-driven configuration |
| `PdfScanner` | Walks directories, filters to result-chart PDFs, skips already-processed |
| `PdfParser` | Wraps `ChartParser`; returns sealed `ParseOutcome` (Success/Failure) |
| `RaceWriter` | Single-transaction jOOQ writes per PDF; idempotent via upsert + delete-reinsert |
| `ImportTracker` | SQLite-backed progress log (`~/import-progress.db`); enables resume |
| `ConnectionPool` | HikariCP with bulk-load Postgres session settings |
| `NullByteStrippingDataSource` | Strips 0x00 bytes from strings (1990s PDFs produce them) |

Key design decisions:
- **Idempotency**: races use `ON CONFLICT DO UPDATE`; child tables are delete-and-reinserted per race_id within a transaction.
- **Thread safety**: `ChartParser.create()` must be called once (static init); the resulting instance's `parse()` is safe for concurrent use.
- **Progress tracking**: only SUCCESS and UNIMPORTABLE are treated as "done" — failed files are automatically retried on re-run.

## Database

Schema: `handycapper` (in Postgres). Tables: `races`, `starters`, `cancelled`, `scratches`, `fractionals`, `splits`, `exotics`, `ratings`, `points_of_call`, `indiv_fractionals`, `indiv_splits`, `indiv_ratings`, `meds`, `equip`, `breeding`, `wps`.

Canonical schema is `db/schema.sql`. Test migrations are in `src/test/resources/db/migration/`.

## Releasing

- Versions: `X.Y.Z.SNAPSHOT` (dev) / `X.Y.Z.RELEASE` (release)
- Pushing a `v*` tag triggers the release workflow (builds, deploys to GitHub Packages, attaches fat JAR to GitHub Release)
- If releasing both chart-parser and pdf-importer, release chart-parser first

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `IMPORTER_JDBC_URL` | `jdbc:postgresql://localhost:5433/handycapper` | Postgres connection |
| `IMPORTER_DB_USER` | `handycapper` | DB username |
| `IMPORTER_DB_PASSWORD` | `handycapper` | DB password |
| `IMPORTER_THREADS` | CPU core count | Parallel threads (also caps concurrent PDFs in memory) |
| `IMPORTER_BATCH_SIZE` | `500` | Batch insert size |
