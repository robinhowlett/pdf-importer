# Importing new chart PDFs into chartbase (robinpc)

How to add a new day's Equibase charts (e.g. a Breeders' Cup card) to the live database. First done for
Keeneland 2026-10-30/31 on 2026-11-03.

## Where things live

| What | Where |
|---|---|
| Live database | `chartbase` (schema `handycapper`) in the Docker Desktop container `onecli-postgres-1`, `localhost:5432`, user/pass `handycapper`. The 14-0 tools (`export_registry.py`, `db_audit.py`, …) read this one. A database called `handycapper` sits in the same container: it's an older copy, not the target. |
| Chart PDFs (the corpus) | **Inside WSL** (Ubuntu): `~/horseracing/s3/data/equibase/charts/` (~205K files), named `TRK_YYYY-MM-DD_race-charts.pdf` |
| Canonical import log | WSL `~/import-progress.db` (the one `~/run-import.sh` and the 123K-file run used) |
| Importer JAR | `C:\Users\robin\dev\pdf-importer\target\pdf-importer-1.1.0.RELEASE.jar` (from WSL: `/mnt/c/Users/robin/dev/pdf-importer/target/...`). WSL has its own older checkout in `~/dev/pdf-importer`; prefer the Windows build, which has the latest chart-parser. |

**Do you need WSL?** Only because the PDFs and the import log live there. Java 21 and the JAR run
on Windows too, and Postgres is in Docker Desktop either way. Running from WSL keeps the PDFs and the
log in one place. Docker Desktop must be running first, or the connection is refused on 5432.

## Steps

1. **Start Docker Desktop** and wait until `docker ps` shows `onecli-postgres-1 … 0.0.0.0:5432->5432`.
2. **Put the PDFs in the corpus folder with the corpus name.** Equibase downloads come as
   `KEE103126USA.pdf`. The scanner (`PdfScanner.isResultChart`) only accepts names containing
   `race-charts` or ending `.standard.pdf`, so a file under its download name is **skipped silently**.
   In WSL:
   ```bash
   cp /mnt/c/path/to/KEE103126USA.pdf ~/horseracing/s3/data/equibase/charts/KEE_2026-10-31_race-charts.pdf
   ```
3. **Import just those files** (pass file paths, not the folder: a folder walk of all 205K files works too,
   but it retries the ~936 known failures every time):
   ```bash
   C=~/horseracing/s3/data/equibase/charts
   IMPORTER_JDBC_URL=jdbc:postgresql://localhost:5432/chartbase IMPORTER_THREADS=2 \
     java -Xmx2g -jar /mnt/c/Users/robin/dev/pdf-importer/target/pdf-importer-1.1.0.RELEASE.jar \
     $C/KEE_2026-10-30_race-charts.pdf $C/KEE_2026-10-31_race-charts.pdf
   ```
   Always set `IMPORTER_JDBC_URL`: the built-in default is `localhost:5433/handycapper`, the wrong port
   and the wrong database.
4. **Read the log for `[ERROR]` lines, not just the summary.** "Success: 2 Failed: 0" counts *files*.
   A race that fails to parse is dropped and the rest of the file still loads. Then check the race count
   per day:
   ```bash
   docker exec onecli-postgres-1 psql -U handycapper -d chartbase -Atc \
     "select date,count(*) from handycapper.races where date in ('2026-10-30','2026-10-31') group by 1"
   docker exec onecli-postgres-1 psql -U handycapper -d chartbase -Atc \
     "select year,count(*) from handycapper.bc_championship_races where year=2026 group by 1"   # 14
   ```
   Re-running is safe. Races upsert, and child rows are deleted and re-inserted per race. To re-run a file
   the log already marks SUCCESS, pass it again after deleting its row from `~/import-progress.db`, or
   import it from another path.

## Known failure: an unknown last-raced track

`Unable to find Track for code XXX` means a starter's *last race* was at a track missing from
chart-parser's `src/main/resources/track-codes.csv`, and that whole race is dropped. That's typical for
foreign shippers. 2026: the BC Dirt Mile was dropped because Lord Fons (JPN) last ran at `URA` (Urawa).
Fix:

1. Add a row to `chart-parser/src/main/resources/track-codes.csv`, in code order
   (`code;canonical;country;state;city;name`, e.g. `URA;URA;JPN;;;URAWA`).
2. `cd C:\Users\robin\dev\chart-parser; mvn install -DskipTests`. This overwrites the local
   `2.1.0.RELEASE` in `~/.m2`, which pdf-importer pins.
3. `cd C:\Users\robin\dev\pdf-importer; mvn -o package -DskipTests`, then confirm with
   `unzip -p target/pdf-importer-1.1.0.RELEASE.jar track-codes.csv | grep URA`.
4. Re-import the file (step 3 above).

## After importing

- 14-0's live card (`app/bc2026.json`, `live_field.py`) does not read the charts. Results only reach
  14-0 when an exporter is re-run.
- `performance_ratings*` are not computed by the importer. New races have no ratings until the
  performance-rating pipeline runs over them.
