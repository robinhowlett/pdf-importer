# Horse Racing Game — Design Brainstorm

*Started 2026-04-09. Import still running (~280 min remaining at time of writing).*

## Context

- **Data**: 204,602 Equibase PDFs, 1991–2018, all USA thoroughbred racing, bulk-loading into PostgreSQL (`handycapper` schema)
- **Goal**: Build a horse racing simulation/game whose statistics and physics are derived from real historical race data
- **Modeling stack**: Python for statistical modeling, separate model store for derived stats, PostgreSQL as raw data source

## Player Roles
Combination of owner, trainer, jockey, and bettor.

## Game Type
- Generates **new fictional races** whose distributions match historical reality (not replaying historical races)
- **Real-time** race simulation (can start turn-based and evolve)
- Thoroughbreds only, USA tracks

---

## Architecture (Three Layers)

```
PostgreSQL (raw race history — handycapper schema)
      ↓
Python model store (speed figures, horse profiles, pace model)
      ↓
Game engine (race generator, entity model, simulation loop)
```

---

## Layer 1: Speed Figures

The foundation. Normalize raw race times into a single comparable number across all tracks, distances, surfaces, conditions.

**Calculation:**
1. **Par times** — for each track/distance/surface combo, compute median winning time. e.g. "A fast-track 6f sprint at Aqueduct has a par of 1:10.2"
2. **Daily track variant** — average all race times vs. par on a given day at a given track → single adjustment number ("+2" = track was 2 points slow). This is the hardest part and most important.
3. **Figure** = `base_figure - (actual_time - par + variant) × scale`
   - Scale converts time to lengths (~5 lengths/sec) then to points
   - ~100 = solid allowance horse; 120+ = graded stakes quality

27 years of data is enough to compute reliable pars even for smaller tracks.

---

## Layer 2: Horse Profile

Derived from speed figure history per horse.

| Attribute | How derived |
|---|---|
| Peak ability | 95th percentile of career figures |
| Current form | Recency-weighted avg of last 5 figures |
| Consistency | Std dev of figures |
| Distance profile | Separate medians for sprint (<8f) vs. route (≥8f) |
| Surface profile | Dirt vs. turf figure split |
| Trajectory | Slope of last 6 figures (improving/declining) |
| Running style | Derived from points_of_call (see Pace Model) |

**Running style classification** from `points_of_call` table:
- Compute avg early position (calls 1-2) vs. avg late position (last call)
- Map to: `E` (front-runner), `EP`, `P` (presser), `PC`, `C` (closer)
- Learnable directly from historical data

---

## Layer 3: Pace Model

The core "physics" of thoroughbred racing — horses have a finite energy budget.

- **E1** — speed rating for first half of race (calls 1→2)
- **E2** — speed rating for second half (call 2→finish)

From 27 years of data, learn:
- Correlation between pace scenario (fast/slow early) and running style outcome
  - e.g. "When E1 is top quartile for distance, front-runners hold on only 18% of the time"
  - e.g. "Closers in slow-pace races show avg 4.2-point figure regression"
- These become **lookup/regression tables** the race generator uses to adjust probabilities per horse based on field composition

---

## Layer 4: Jockey & Trainer Profiles

From `starters` table — jockey + trainer on every race, 27 years.

**Jockey profile:**
- Win % by condition (track, distance, surface, class level)
- Pace preference (does this jockey tend to go to the front?)
- Performance in stakes vs. claiming

**Trainer profile:**
- Specialty (claimers, turf, 2-year-olds, etc.)
- Barn current form
- Trainer-jockey pair bonus (partnerships that outperform expectations)

These become **modifiers** in the race generator.

---

## Race Generator Algorithm

Given a field of horses + track/distance/conditions:

```
1. Compute each horse's "base figure" for this race
   → draw from their distribution (mean + noise)

2. Assign running styles (from profile, with variance)

3. Predict pace scenario
   → fast if multiple E/EP horses, slow if field is mostly closers

4. Apply pace adjustments per horse
   → E horses penalized in fast pace, C horses benefited

5. Apply jockey/trainer modifiers

6. Add trip noise
   → wide trips, traffic, stumbles (distribution learned from data)

7. Simulate point-by-point positions (driven by points_of_call distributions)

8. Generate final times, margins, payoffs
```

Output: full running line for every horse (not just a winner) — fractional times, positions at each call, final margins, payoffs.

---

## Game Entity Model

```
Horse
  - attributes: speed potential, stamina, surface pref, distance pref
  - health: soundness, freshness (days since last race)
  - career: record, class history, earnings
  - running style: E/EP/P/PC/C

Jockey
  - riding style (aggressive, conservative, pace-setter)
  - specialty: sprint / route / turf
  - physical: weight, fitness
  - contract: stable affiliations, availability

Trainer
  - specialty: claimers, turf, 2-year-olds, etc.
  - barn: number of horses, current form
  - strategy profile

Owner
  - budget, goals (win stakes? build a stable? breed?)
  - relationship with trainer/jockey

Race (generated)
  - conditions: distance, surface, class level, purse
  - field: 6-14 starters
  - result: full running line, fractional times, payoffs
```

---

## Phased Build Plan

| Phase | Work |
|---|---|
| 1 | Speed figures + horse profiles (Python/pandas → PostgreSQL) |
| 2 | Pace model calibration from historical data |
| 3 | Race generator (Monte Carlo, validate against historical outcome distributions) |
| 4 | Game loop: turn-based (enter horse → simulate → see result) |
| 5 | Real-time race with play-by-play at each fraction |
| 6 | Full player roles (jockey mid-race, trainer pre-race, owner strategy) |

---

## Open Questions (to answer in next session)

1. **Horse identity**: Name = identity (with manual edge-case handling), or deduplicate using foaling date + sire/dam from `breeding` table to build proper horse entities?

2. **Historical vs. fictional characters**:
   - Are game horses recognizably fictional ("Shadowfax, bay colt by Storm Cat") or real names from the data?
   - Should "simulate 1995 Skip Away vs. 2000 Tiznow" be possible, or purely forward-looking with fictional entities?

3. **Game session loop**: Managing a stable across a season (enter horses, watch them run, claim/sell/develop)? Or handicap-and-bet on races you don't control? Or both?

4. **Visuals**: Text commentary feed, numbers only, or 2D animated horses eventually? Affects simulation granularity (6 points of call vs. 100ms intervals).

---

## Natural Language Querying (separate but related goal)

Want to ask questions about the data in plain English. Three approaches, in increasing power:

1. **Pre-built parameterized queries** — SQL views / materialized views backing question templates
2. **LLM text-to-SQL** — Claude reads schema, translates freeform questions to SQL
3. **Analytics layer first** (recommended foundation) — dbt-style derived tables: horse career stats, trainer/jockey profiles, track biases, speed figures

Open questions:
- Query interface: REPL/terminal, web UI, or chat?
- Question types: historical facts ("who won X?") or analytical patterns ("which jockeys overperform on wet tracks?")?
