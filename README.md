# pdf-importer

Bulk loader for Equibase horse racing chart PDFs into PostgreSQL. Parses PDFs using [chart-parser](https://github.com/robinhowlett/chart-parser) and writes structured race data via jOOQ.

## Quick Start

Download the latest fat JAR from [Releases](https://github.com/robinhowlett/pdf-importer/releases):

```bash
java -Xmx4g -jar pdf-importer-1.0.0.RELEASE.jar [sourceDir]
```

If no `sourceDir` is provided, defaults to `~/horseracing`.

## Configuration

Override defaults with environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `IMPORTER_JDBC_URL` | `jdbc:postgresql://localhost:5433/handycapper` | PostgreSQL connection URL |
| `IMPORTER_DB_USER` | `handycapper` | Database username |
| `IMPORTER_DB_PASSWORD` | `handycapper` | Database password |
| `IMPORTER_THREADS` | CPU core count | Parallel processing threads |
| `IMPORTER_BATCH_SIZE` | `500` | Batch insert size |

## Requirements

- Java 21+
- PostgreSQL database

## Example

```bash
export IMPORTER_JDBC_URL=jdbc:postgresql://myhost:5432/races
export IMPORTER_DB_USER=admin
export IMPORTER_DB_PASSWORD=secret
java -Xmx4g -jar pdf-importer-1.0.0.RELEASE.jar /path/to/pdfs
```
