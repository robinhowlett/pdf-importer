# Unimportable Files Report

Generated: 2026-04-10  
Status: **0 zero-race SUCCESS entries** — all formerly zero-race files are now categorized as UNIMPORTABLE.

## Summary

| Category | Count | Description |
|---|---|---|
| HtmlStub | 1,677 | S3 stored Equibase's HTTP error page instead of a real PDF |
| OldChartFormat | 27 | Pre-1993 Equibase format with `Ind.Time/Sp.In.` columns — not supported by chart-parser |
| UnsupportedRaceFormat | 15 | QH futurity charts with no race distance (`On The Dirt` only) — not of interest |
| EmptyPdf | 14 | Valid PDF container but 0 pages — no race data present |
| UnparsableRunningLines | 2 | QH chart with dash placeholder for horse/jockey names |
| UnknownRaceType | 2 | Unable to identify valid race type/breed — non-standard format |
| BrokenFontEmbedding | 1 | All characters at fontSize=1.0 — global column layout failure |
| **Total** | **1,738** | |

## Pending Re-Import

149 BGD (Players Bluegrass Downs) files and 6 VEG (Vegreville) files were deleted from the
tracker and will be retried on the next importer run. Track name aliases were added to
`track-codes.csv` in chart-parser to fix the mismatches:

- **BGD**: PDF says `PLAYERS BLUEGRASS DOWNS`; CSV originally had only `BLUE GRASS DOWNS`
- **VEG**: PDF says `VEGRIVILLE AGRICULTURAL SOCIETY` (typo); CSV originally had only `VEGREVILLE AGRICULTURAL SOCIETY`

## Categories

### HtmlStub (1,677 files)
Files are 3,253-byte (or 8,280-byte) HTML documents — Equibase's "chart unavailable" error
page saved to S3 with a `.pdf` extension during the original data collection. The race data
simply does not exist. Nothing can be done without re-downloading from a source that has the
actual charts. Major tracks affected include TP (168), MED (146), CT (118), EP (113), TRM (106),
ASD (103), FP (99), MNR (93), ATL (82), PEN (73).

### OldChartFormat (27 files)
Pre-1993 Equibase PDFs that use a completely different running-line layout:

```
Last Raced | Pgm | Horse Name (Jockey) | Wgt | M/E | PP | Ind. Time | Sp. In. | Comments
```

vs the current format:
```
LastRaced | Pgm | HorseName(Jockey) | Wgt | M/E | PP | [calls] | Fin | Odds | Comments
```

`RunningLineHeader.identifyHeaderSuffixCharactersForRegistry()` only knows 4 suffixes, all
starting with `Fin`. The old format has neither `Fin` nor `Odds` columns. Supporting it would
require significant chart-parser changes. Not worth doing for 27 files.

Files: DED 1991-11-14 through 1999-01-27 (25 files), GRP 1992-07-04, LBT 1991-04-13.

### UnsupportedRaceFormat (15 files)
Quarter Horse futurity charts that record only individual times with no race distance prefix
(`On The Dirt` only, no `One And One-Sixteenth Miles` etc). `DistanceSurfaceTrackRecord`
requires a spelled-out distance word before `On The [surface]`. Races without distances are
not of interest.

Files: RP 1991–1994 (7 files), BRD 1993, DUN 1996, JRM 1991, KLF 1992/1994, SDY 1992,
TIL 1992/1993.

### EmptyPdf (14 files)
All are El Compas (ELC) files from 1996–1999. Valid PDF containers (binary header present)
but PDFBox extracts 0 pages — no race data present. File sizes are ~547 bytes. Nothing
recoverable.

### UnparsableRunningLines (2 files)
CLG (Trout Springs Training Center) 1991 and 1993 QH races where the horse/jockey column
contains only `-`. `HorseJockey.parse()` cannot extract names from a dash placeholder.

### UnknownRaceType (2 files)
KAM (Kamloops) 1995 and 1996 — chart-parser cannot identify the race type/breed (`Unable
to identify a valid race type, name and/or breed`). The PDF lists a Race 15 in an unusual
non-standard format.

### BrokenFontEmbedding (1 file)
SAC_2010-07-17: PDFBox extracts all characters at `fontSize=1.0` instead of the expected
`7.0`/`5.0`. The `LastRaced` parser uses `fontSize == 5` to distinguish superscript digits
(race number, finish position) from track code characters. A narrow fix attempted for this
file also broke `Weight.parse()` due to cascading column misalignment. Not recoverable
without rewriting the column parser.

## Full Non-HtmlStub File List

```
SAC_2010-07-17_race-charts.pdf        BrokenFontEmbedding
ELC_1996-09-02_race-charts.pdf        EmptyPdf
ELC_1997-12-08_race-charts.pdf        EmptyPdf
ELC_1998-01-12_race-charts.pdf        EmptyPdf
ELC_1998-12-21_race-charts.pdf        EmptyPdf
ELC_1999-02-14_race-charts.pdf        EmptyPdf
ELC_1999-03-01_race-charts.pdf        EmptyPdf
ELC_1999-03-03_race-charts.pdf        EmptyPdf
ELC_1999-03-05_race-charts.pdf        EmptyPdf
ELC_1999-04-30_race-charts.pdf        EmptyPdf
ELC_1999-06-07_race-charts.pdf        EmptyPdf
ELC_1999-07-17_race-charts.pdf        EmptyPdf
ELC_1999-08-22_race-charts.pdf        EmptyPdf
ELC_1999-09-11_race-charts.pdf        EmptyPdf
ELC_1999-11-22_race-charts.pdf        EmptyPdf
DED_1991-11-14_race-charts.pdf        OldChartFormat
DED_1991-12-06_race-charts.pdf        OldChartFormat
DED_1991-12-12_race-charts.pdf        OldChartFormat
DED_1991-12-26_race-charts.pdf        OldChartFormat
DED_1991-12-27_race-charts.pdf        OldChartFormat
DED_1991-12-28_race-charts.pdf        OldChartFormat
DED_1992-01-02_race-charts.pdf        OldChartFormat
DED_1992-01-03_race-charts.pdf        OldChartFormat
DED_1992-01-09_race-charts.pdf        OldChartFormat
DED_1992-01-10_race-charts.pdf        OldChartFormat
DED_1992-01-11_race-charts.pdf        OldChartFormat
DED_1992-01-16_race-charts.pdf        OldChartFormat
DED_1992-01-17_race-charts.pdf        OldChartFormat
DED_1992-01-18_race-charts.pdf        OldChartFormat
DED_1992-01-23_race-charts.pdf        OldChartFormat
DED_1992-01-24_race-charts.pdf        OldChartFormat
DED_1992-01-25_race-charts.pdf        OldChartFormat
DED_1992-01-30_race-charts.pdf        OldChartFormat
DED_1992-01-31_race-charts.pdf        OldChartFormat
DED_1992-02-01_race-charts.pdf        OldChartFormat
DED_1992-02-06_race-charts.pdf        OldChartFormat
DED_1992-02-07_race-charts.pdf        OldChartFormat
DED_1992-02-08_race-charts.pdf        OldChartFormat
DED_1992-10-30_race-charts.pdf        OldChartFormat
DED_1999-01-27_race-charts.pdf        OldChartFormat
GRP_1992-07-04_race-charts.pdf        OldChartFormat
LBT_1991-04-13_race-charts.pdf        OldChartFormat
KAM_1995-09-11_race-charts.pdf        UnknownRaceType
KAM_1996-09-11_race-charts.pdf        UnknownRaceType
CLG_1991-04-09_race-charts.pdf        UnparsableRunningLines
CLG_1993-07-13_race-charts.pdf        UnparsableRunningLines
BRD_1993-10-05_race-charts.pdf        UnsupportedRaceFormat
DUN_1996-03-24_race-charts.pdf        UnsupportedRaceFormat
JRM_1991-04-21_race-charts.pdf        UnsupportedRaceFormat
KLF_1992-07-17_race-charts.pdf        UnsupportedRaceFormat
KLF_1994-07-16_race-charts.pdf        UnsupportedRaceFormat
RP_1991-07-02_race-charts.pdf         UnsupportedRaceFormat
RP_1992-05-28_race-charts.pdf         UnsupportedRaceFormat
RP_1992-06-30_race-charts.pdf         UnsupportedRaceFormat
RP_1993-05-18_race-charts.pdf         UnsupportedRaceFormat
RP_1993-06-29_race-charts.pdf         UnsupportedRaceFormat
RP_1994-05-17_race-charts.pdf         UnsupportedRaceFormat
RP_1994-07-06_race-charts.pdf         UnsupportedRaceFormat
SDY_1992-06-10_race-charts.pdf        UnsupportedRaceFormat
TIL_1992-08-08_race-charts.pdf        UnsupportedRaceFormat
TIL_1993-08-14_race-charts.pdf        UnsupportedRaceFormat
```
