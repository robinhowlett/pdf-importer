-- IMP-T5.5: number_of_runners counts coupled entries (1 + 1A) as separate
-- physical horses. That's correct for "starters" semantics but wrong for
-- "distinct wagering interests" — the count that determines pool dynamics,
-- A/E denominators, and field-size-based bias factors.
--
-- Add a parallel column number_of_wagering_interests = COUNT(DISTINCT
-- entry_program). On TB 2014 data, ~5.5% of races have at least one coupled
-- entry, so the two columns disagree on that fraction of races.
--
-- number_of_runners is left untouched — too many downstream consumers treat
-- it as physical-horse count.

ALTER TABLE handycapper.races
    ADD COLUMN IF NOT EXISTS number_of_wagering_interests smallint;

-- For backfill on production, run once after deploying RaceWriter:
--   UPDATE handycapper.races r
--   SET number_of_wagering_interests = (
--       SELECT count(DISTINCT s.entry_program)
--       FROM handycapper.starters s
--       WHERE s.race_id = r.id
--   )
--   WHERE number_of_wagering_interests IS NULL;
-- Re-imports of any race will populate the new column going forward
-- (the IMP-T5.1 delete-then-insert pattern means re-imports cover it).
