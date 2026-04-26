-- Widen arbitrarily-constrained VARCHAR columns to TEXT.
-- PostgreSQL has no storage or performance penalty for TEXT vs VARCHAR(n).
-- These columns receive free-text from PDF extraction, so fixed limits cause
-- failures on unusual historical records (e.g. long restriction codes, horse names).

ALTER TABLE handycapper.races
    ALTER COLUMN black_type          TYPE TEXT,
    ALTER COLUMN restrictions        TYPE TEXT,
    ALTER COLUMN sexes_code          TYPE TEXT,
    ALTER COLUMN surface             TYPE TEXT,
    ALTER COLUMN course              TYPE TEXT,
    ALTER COLUMN scheduled_surface   TYPE TEXT,
    ALTER COLUMN scheduled_course    TYPE TEXT,
    ALTER COLUMN track_record_holder TYPE TEXT,
    ALTER COLUMN type                TYPE TEXT,
    ALTER COLUMN race_name           TYPE TEXT,
    ALTER COLUMN available_money     TYPE TEXT,
    ALTER COLUMN distance_text       TYPE TEXT,
    ALTER COLUMN start_comments      TYPE TEXT,
    ALTER COLUMN purse_text          TYPE TEXT,
    ALTER COLUMN track_condition     TYPE TEXT,
    ALTER COLUMN weather             TYPE TEXT;

ALTER TABLE handycapper.starters
    ALTER COLUMN horse               TYPE TEXT,
    ALTER COLUMN comments            TYPE TEXT,
    ALTER COLUMN jockey_first        TYPE TEXT,
    ALTER COLUMN jockey_last         TYPE TEXT,
    ALTER COLUMN trainer_first       TYPE TEXT,
    ALTER COLUMN trainer_last        TYPE TEXT,
    ALTER COLUMN new_owner_name      TYPE TEXT,
    ALTER COLUMN new_trainer_name    TYPE TEXT,
    ALTER COLUMN last_raced_track_name TYPE TEXT;

ALTER TABLE handycapper.cancelled
    ALTER COLUMN reason              TYPE TEXT,
    ALTER COLUMN track_name          TYPE TEXT;
