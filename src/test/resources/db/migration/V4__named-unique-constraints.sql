-- Promote bare unique indexes to named constraints so that jOOQ's
-- onConflictOnConstraint(DSL.name(...)) can reference them by name.
ALTER TABLE handycapper.races
    ADD CONSTRAINT uq_races_date_track_number
    UNIQUE USING INDEX idx_24645_idx_races_date_track_number;

ALTER TABLE handycapper.cancelled
    ADD CONSTRAINT uq_cancelled_date_track_number
    UNIQUE USING INDEX idx_24591_idx_cancelled_date_track_number;
