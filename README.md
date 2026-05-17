# pdf-importer

Bulk loader for Equibase horse racing chart PDFs into PostgreSQL. Parses PDFs using
[chart-parser](https://github.com/robinhowlett/chart-parser) and writes structured race
data via jOOQ. Designed to import ~200,000 PDFs (1991–2019) in under 4 hours on a
modern workstation.

## Quick Start (Docker)

The easiest path if you don't have PostgreSQL installed. Docker runs PostgreSQL in an
isolated container — no installation or configuration needed beyond Docker itself.

**Prerequisites:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Mac/Windows) or [Docker Engine](https://docs.docker.com/engine/install/) (Linux), Java 21+

```bash
# 1. Start the database in the background (-d = detached)
#    Listens on localhost:5433 to avoid clashing with any locally installed Postgres
docker compose up -d

# 2. Download the latest fat JAR from Releases and run it
java -Xmx4g -jar pdf-importer-1.1.0.RELEASE.jar /path/to/your/pdfs
```

That's it. The importer's default `IMPORTER_JDBC_URL` is `localhost:5433/handycapper`,
which matches exactly what Docker Compose starts. No environment variables needed.

Progress is tracked in `~/import-progress.db` (SQLite). Re-running the same command
after an interruption will skip already-processed files automatically.

When you're done:
```bash
docker compose down          # stop the container, keep your data
docker compose down -v       # stop the container and delete all data
```

## Quick Start (Existing PostgreSQL)

If you already have a PostgreSQL instance running on the standard port (5432), apply
the schema once and override the connection URL:

```bash
# Apply schema (once)
psql -U myuser -d mydatabase -f db/schema.sql

# Run the importer — note port 5432 (not 5433, which is the Docker default)
IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/mydatabase \
IMPORTER_DB_USER=myuser \
IMPORTER_DB_PASSWORD=mypassword \
java -Xmx4g -jar pdf-importer-1.1.0.RELEASE.jar /path/to/your/pdfs
```

Progress is tracked in `~/import-progress.db`. Re-running after an interruption skips
already-processed files automatically.

## Building from Source

**Prerequisites:** Java 21+, Maven 3.8+, a GitHub personal access token with
`read:packages` scope to resolve the `chart-parser` dependency from GitHub Packages.

```bash
# Configure GitHub Packages access in ~/.m2/settings.xml:
# <server>
#   <id>github</id>
#   <username>YOUR_GITHUB_USERNAME</username>
#   <password>YOUR_GITHUB_TOKEN</password>
# </server>

mvn clean package -DskipTests
java -Xmx4g -jar target/pdf-importer-*.jar /path/to/your/pdfs
```

## Configuration

All settings have sensible defaults. Override with environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `IMPORTER_JDBC_URL` | `jdbc:postgresql://localhost:5433/handycapper` | PostgreSQL connection URL |
| `IMPORTER_DB_USER` | `handycapper` | Database username |
| `IMPORTER_DB_PASSWORD` | `handycapper` | Database password |
| `IMPORTER_THREADS` | CPU core count | Parallel processing threads |
| `IMPORTER_BATCH_SIZE` | `500` | Batch insert size |

If no source directory is given, defaults to `~/horseracing`.

## Progress Monitoring

The importer tracks progress in an SQLite file at `~/import-progress.db`, so runs can
be interrupted and resumed. Stdout prints rate and estimated time remaining every 1,000
files.

Useful queries (requires `sqlite3` CLI or any SQLite client):

```bash
# Status summary after a run
sqlite3 ~/import-progress.db \
  "SELECT status, count(*) FROM import_log GROUP BY status;"

# Failures by exception type
sqlite3 ~/import-progress.db \
  "SELECT error_type, count(*) FROM import_log
   WHERE status != 'SUCCESS' GROUP BY error_type ORDER BY 2 DESC;"

# Reset failed files for retry
sqlite3 ~/import-progress.db \
  "DELETE FROM import_log WHERE status != 'SUCCESS';"
```

## Throughput Expectations

The bottleneck is CPU (PDF text extraction in chart-parser), not the database.

| Threads | PDFs/sec | Time for 200K files |
|---------|----------|---------------------|
| 4       | ~4       | ~14 hours           |
| 8       | ~8       | ~7 hours            |
| 16      | ~14      | ~4 hours            |
| 32      | ~20      | ~2.5 hours          |

`-Xmx4g` is recommended. PDFBox is memory-intensive; the semaphore caps concurrent
in-flight PDFs at `IMPORTER_THREADS`, so peak heap usage is roughly
`threads × 200MB`.

## Requirements

- Java 21+
- PostgreSQL 12+ (or Docker)
