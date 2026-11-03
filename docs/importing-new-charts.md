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

## Races with no Equibase PDF: `ManualEntryWriter`

The PDF corpus starts in 1991. The 1984–1990 Breeders' Cups (7 races a year, the only 49 races in
chartbase before 1991) were entered from DRF chart images with
`com.robinhowlett.importer.ManualEntryWriter` (Robin, 2026-11-03). It takes one race as JSON
(`ManualEntryWriter <enriched.json>`): track, date, conditions, distance and surface, fractions and splits,
and the starters with their calls, margins, odds and WPS payoffs. It builds a `RaceResult` and writes it
with the same `RaceWriter`, so the race looks like any imported one. It uses the same `IMPORTER_JDBC_URL`.

The full chain:

1. **DRF chart image** (Windows `~/horseracing/breederscup/` holds 421 one-page image PDFs, `310.pdf`–`730.pdf`, with no text layer).
2. **Robin's web app** for entering and correcting the race data from the image (LLM vision plus a form),
   which outputs an "extracted" JSON. Example: `C:\Users\robin\dev\chart-parser-test\race_310_extracted.json`.
   The app itself wasn't found on robinpc (Windows, WSL or claude.ai artifacts, checked 2026-11-03),
   so it's probably on the MacBook.
3. **`ManualEntryEnricher`** (`C:\Users\robin\dev\chart-parser-test\src\main\java\ManualEntryEnricher.java`,
   not a git repo) runs chart-parser's own services over it (track lookup, distance in feet, fraction points,
   splits, millis) and writes the "enriched" JSON.
4. **`ManualEntryWriter`** writes the enriched JSON to the database.

Limits: flat races only; call points fixed at Start, ¼, ½, ¾, Str and Fin (feet assume a mile); no exotics,
claims, ratings or last-raced data. Both Java files were untracked as of 2026-11-03. The writer is in the
built JAR, so run it with
`java -cp target/pdf-importer-1.1.0.RELEASE.jar com.robinhowlett.importer.ManualEntryWriter race.json`.

## After importing: what the import does not do

Parsing is fast: two full BC cards (22 races) took about 5 seconds. Everything below is separate, and
none of it runs automatically.

**1. Ratings.** The importer writes race facts only. New races have no class level and no performance
rating until performance-rating runs over them, and that can't be done incrementally yet (checked
2026-11-03):

- `scripts/compute_class_levels.py` **drops and rebuilds** `race_class_levels`. The GitHub version
  (e44387e) classifies all years; robinpc's stale checkouts still have the 1996–2017-only version, which
  would delete the 2018+ BC class levels. Pull before running anything.
- `scripts/compute_ratings_v22.py` **drops `performance_ratings_v22`** at the start of any non-dry run,
  even with `--year 2026`. On robinpc that table is empty, but on the MacBook it holds the 6,610 BC
  ratings that 14-0's registry is built from (`docs/db-audit/README.md` in 14-0). Running it there for one
  year would wipe the rest.
- `project_true_talent.py` (v22 PR → `bc_true_talent`, which `export_registry.py` reads) is on GitHub but
  not in robinpc's stale checkouts. Its inputs (`performance_ratings_v22`, `ifha_rankings`, the BC-patched
  `par_curves` and `pr_std_lookup`) exist only on the MacBook. The full recipe for BC-only years is
  `run_bc_coverage_backfill.sh`, then `PR_STD_FROM_TABLE=1 compute_ratings_v22.py`. The plan to put it all
  on robinpc is `14-0/docs/db-audit/one-chartbase-plan.md`.

So rating new races needs an incremental mode first: class levels upserted for given race ids, and v22
deleting and re-inserting only the races it computed. The compute itself is small for a few cards; the
runtime is mostly loading par curves and segment stats. Not measured yet.

**2. 14-0.** Nothing in 14-0 reads the charts directly:

- The live card (`app/bc2026.json` → `live_field.py`) is built from bookmakers' prices and the morning
  line, not from chartbase.
- The all-time game's ratings come from `export_registry.py` (MacBook: `bc_true_talent`, `_v22`). The
  registry's per-division `years` don't include the live year, so a new BC year joins the all-time pool
  only through a deliberate rebuild.
- Adding a year to the all-time pool changes the ratings the game draws from. That's a 14-0 human gate:
  re-run the Monte Carlos (L2) and get Robin's sign-off.
- Race facts that need no ratings are available as soon as the import finishes: results, running styles,
  post positions, margins, fractions (e.g. `export_registry.py --style-only`, `export_bc_margins.py`).
