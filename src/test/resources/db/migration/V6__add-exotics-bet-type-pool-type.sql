-- IMP-T5.8: bring canonical schema in sync with the live database. The
-- bet_type and pool_type columns on exotics were added directly to
-- production via ALTER (driven by wagering-analytics needs) but the
-- canonical schema and the schema spec at github/docs/specs/handycapper-schema.md
-- never tracked them. The columns are referenced throughout AN1
-- (populate_stern_fair, fit_payoff_models, verify_payoff_skill_*) and
-- by race-day-sim's exotic-payoff projections.
--
-- bet_type values observed in production (top 10):
--   EXACTA, TRIFECTA, SUPERFECTA, PICK_3, DAILY_DOUBLE, QUINELLA,
--   PICK_4, PICK_6, PICK_5, HI_5
-- pool_type values: STANDARD, CONSOLATION, JACKPOT, FUTURE.

ALTER TABLE handycapper.exotics ADD COLUMN IF NOT EXISTS bet_type  varchar(30);
ALTER TABLE handycapper.exotics ADD COLUMN IF NOT EXISTS pool_type varchar(20);

CREATE INDEX IF NOT EXISTS idx_exotics_bet_type  ON handycapper.exotics (bet_type);
CREATE INDEX IF NOT EXISTS idx_exotics_pool_type ON handycapper.exotics (pool_type);
