package com.robinhowlett.importer.pipeline;

import com.robinhowlett.chartparser.charts.pdf.Cancellation;
import com.robinhowlett.chartparser.charts.pdf.DistanceSurfaceTrackRecord;
import com.robinhowlett.chartparser.charts.pdf.DistanceSurfaceTrackRecord.RaceDistance;
import com.robinhowlett.chartparser.charts.pdf.DistanceSurfaceTrackRecord.TrackRecord;
import com.robinhowlett.chartparser.charts.pdf.Horse;
import com.robinhowlett.chartparser.charts.pdf.Jockey;
import com.robinhowlett.chartparser.charts.pdf.Owner;
import com.robinhowlett.chartparser.charts.pdf.PostTimeStartCommentsTimer;
import com.robinhowlett.chartparser.charts.pdf.Purse;
import com.robinhowlett.chartparser.charts.pdf.RaceConditions;
import com.robinhowlett.chartparser.charts.pdf.RaceResult;
import com.robinhowlett.chartparser.charts.pdf.RaceResult.Weather;
import com.robinhowlett.chartparser.charts.pdf.RaceRestrictions;
import com.robinhowlett.chartparser.charts.pdf.RaceTypeNameBlackTypeBreed;
import com.robinhowlett.chartparser.charts.pdf.Rating;
import com.robinhowlett.chartparser.charts.pdf.Scratch;
import com.robinhowlett.chartparser.charts.pdf.Starter;
import com.robinhowlett.chartparser.charts.pdf.Starter.Claim;
import com.robinhowlett.chartparser.charts.pdf.Trainer;
import com.robinhowlett.chartparser.charts.pdf.WindSpeedDirection;
import com.robinhowlett.chartparser.charts.pdf.running_line.LastRaced;
import com.robinhowlett.chartparser.charts.pdf.running_line.LastRaced.LastRacePerformance;
import com.robinhowlett.chartparser.charts.pdf.running_line.MedicationEquipment;
import com.robinhowlett.chartparser.charts.pdf.running_line.MedicationEquipment.Equipment;
import com.robinhowlett.chartparser.charts.pdf.running_line.MedicationEquipment.Medication;
import com.robinhowlett.chartparser.charts.pdf.running_line.Weight;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.ExoticPayoffPool;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff.WinPlaceShow;
import com.robinhowlett.chartparser.fractionals.FractionalPoint.Fractional;
import com.robinhowlett.chartparser.fractionals.FractionalPoint.Split;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall.RelativePosition;
import com.robinhowlett.chartparser.tracks.Track;
import com.robinhowlett.handycapper.domain.tables.records.RacesRecord;
import com.robinhowlett.handycapper.domain.tables.records.StartersRecord;
import com.robinhowlett.importer.model.ImportResult;

import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.robinhowlett.handycapper.domain.tables.Breeding.BREEDING;
import static com.robinhowlett.handycapper.domain.tables.Cancelled.CANCELLED;
import static com.robinhowlett.handycapper.domain.tables.Equip.EQUIP;
import static com.robinhowlett.handycapper.domain.tables.Exotics.EXOTICS;
import static com.robinhowlett.handycapper.domain.tables.Fractionals.FRACTIONALS;
import static com.robinhowlett.handycapper.domain.tables.IndivFractionals.INDIV_FRACTIONALS;
import static com.robinhowlett.handycapper.domain.tables.IndivRatings.INDIV_RATINGS;
import static com.robinhowlett.handycapper.domain.tables.IndivSplits.INDIV_SPLITS;
import static com.robinhowlett.handycapper.domain.tables.Meds.MEDS;
import static com.robinhowlett.handycapper.domain.tables.PointsOfCall.POINTS_OF_CALL;
import static com.robinhowlett.handycapper.domain.tables.Races.RACES;
import static com.robinhowlett.handycapper.domain.tables.Ratings.RATINGS;
import static com.robinhowlett.handycapper.domain.tables.Scratches.SCRATCHES;
import static com.robinhowlett.handycapper.domain.tables.Splits.SPLITS;
import static com.robinhowlett.handycapper.domain.tables.Starters.STARTERS;
import static com.robinhowlett.handycapper.domain.tables.Wps.WPS;

/**
 * Writes a list of {@link RaceResult} objects (one PDF's worth) to PostgreSQL in a single
 * transaction using jOOQ. Idempotent: a second write of the same PDF produces the same state.
 * <p>
 * Idempotency strategy:
 * <ul>
 *   <li>Races: ON CONFLICT (date, track, number) DO UPDATE — returns the existing id if the race
 *       was already present, or inserts and returns the new id.</li>
 *   <li>Starters and all child tables: DELETE existing rows for the race_id, then INSERT fresh.
 *       The cascade DELETE on all FK constraints means a single delete on starters cleans up
 *       points_of_call, fractionals, splits, meds, equip, breeding, wps, and indiv_ratings.</li>
 *   <li>Scratches, fractionals, splits, exotics, ratings: DELETE by race_id and re-insert.</li>
 * </ul>
 */
public class RaceWriter {

    private static final Logger log = LoggerFactory.getLogger(RaceWriter.class);

    private final DSLContext dsl;

    public RaceWriter(DataSource dataSource) {
        this.dsl = DSL.using(
                new com.robinhowlett.importer.db.NullByteStrippingDataSource(dataSource),
                SQLDialect.POSTGRES);
    }

    /** Package-private: allows tests to inject a DSLContext directly. */
    RaceWriter(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Writes all races for one PDF in a single transaction.
     * On any exception the transaction rolls back and the file is marked WRITE_FAILED.
     */
    public ImportResult write(Path pdf, List<RaceResult> results) {
        try {
            dsl.transaction(config -> {
                DSLContext tx = DSL.using(config);
                for (RaceResult result : results) {
                    writeRace(tx, result);
                }
            });
            return ImportResult.success(pdf, results.size());
        } catch (Exception e) {
            log.error("Write failed for {}: {}", pdf.getFileName(), e.getMessage(), e);
            return ImportResult.writeFailed(pdf, e);
        }
    }

    // -------------------------------------------------------------------------
    // Race-level
    // -------------------------------------------------------------------------

    private void writeRace(DSLContext tx, RaceResult r) {
        Cancellation cancellation = r.getCancellation();
        if (cancellation != null && cancellation.isCancelled()) {
            writeCancelled(tx, r);
            return;
        }

        RacesRecord race = upsertRace(tx, r);
        long raceId = race.getId();

        // Delete and re-insert child rows for idempotency
        tx.deleteFrom(SCRATCHES).where(SCRATCHES.RACE_ID.eq(raceId)).execute();
        tx.deleteFrom(FRACTIONALS).where(FRACTIONALS.RACE_ID.eq(raceId)).execute();
        tx.deleteFrom(SPLITS).where(SPLITS.RACE_ID.eq(raceId)).execute();
        tx.deleteFrom(EXOTICS).where(EXOTICS.RACE_ID.eq(raceId)).execute();
        tx.deleteFrom(RATINGS).where(RATINGS.RACE_ID.eq(raceId)).execute();
        // Cascade: deleting starters also removes poc, indiv_fractionals, indiv_splits,
        // meds, equip, breeding, wps, indiv_ratings
        tx.deleteFrom(STARTERS).where(STARTERS.RACE_ID.eq(raceId)).execute();

        writeScratches(tx, r.getScratches(), raceId);
        writeFractionals(tx, r.getFractionals(), raceId);
        writeSplits(tx, r.getSplits(), raceId);
        writeExotics(tx, r, raceId);
        writeRaceRatings(tx, r.getRatings(), raceId);
        writeStarters(tx, r.getStarters(), raceId);
    }

    private RacesRecord upsertRace(DSLContext tx, RaceResult r) {
        return buildRaceInsert(tx, r)
                .onConflictOnConstraint(DSL.name("uq_races_date_track_number"))
                .doUpdate()
                .set(RACES.TRACK_NAME, r.getTrack() != null ? r.getTrack().getName() : null)
                .set(RACES.FINAL_TIME, r.getFinalTime())
                .set(RACES.FINAL_MILLIS, r.getFinalMillis())
                .set(RACES.DEAD_HEAT, r.isDeadHeat())
                .set(RACES.NUMBER_OF_RUNNERS, toShort(r.getNumberOfRunners()))
                .set(RACES.FOOTNOTES, r.getFootnotes())
                .returning(RACES.ID)
                .fetchOne();
    }

    private InsertSetMoreStep<RacesRecord> buildRaceInsert(DSLContext tx, RaceResult r) {
        var step = tx.insertInto(RACES)
                .set(RACES.DATE, r.getRaceDate());

        Track track = r.getTrack();
        if (track != null) {
            step.set(RACES.TRACK, track.getCode())
                .set(RACES.TRACK_CANONICAL, track.getCanonical())
                .set(RACES.TRACK_COUNTRY, track.getCountry())
                .set(RACES.TRACK_STATE, track.getState())
                .set(RACES.TRACK_NAME, track.getName());
        }

        step.set(RACES.NUMBER, toShort(r.getRaceNumber()));

        RaceConditions rc = r.getRaceConditions();
        if (rc != null) {
            step.set(RACES.CONDITIONS, rc.getText());

            RaceTypeNameBlackTypeBreed tb = rc.getRaceTypeNameBlackTypeBreed();
            if (tb != null) {
                step.set(RACES.TYPE, tb.getType())
                    .set(RACES.CODE, tb.getCode())
                    .set(RACES.RACE_NAME, tb.getName())
                    .set(RACES.GRADE, toShort(tb.getGrade()))
                    .set(RACES.BLACK_TYPE, tb.getBlackType());
                if (tb.getBreed() != null) {
                    step.set(RACES.BREED, tb.getBreed().getCode());
                }
            }

            var claim = rc.getClaimingPriceRange();
            if (claim != null) {
                step.set(RACES.MIN_CLAIM, toLong(claim.getMin()))
                    .set(RACES.MAX_CLAIM, toLong(claim.getMax()));
            }

            Purse purse = rc.getPurse();
            if (purse != null) {
                step.set(RACES.PURSE, toLong(purse.getValue()))
                    .set(RACES.PURSE_TEXT, purse.getText())
                    .set(RACES.AVAILABLE_MONEY, purse.getAvailableMoney())
                    .set(RACES.VALUE_OF_RACE, purse.getValueOfRace())
                    .set(RACES.PURSE_ENHANCEMENTS, purse.getEnhancements());
            }

            RaceRestrictions restr = rc.getRestrictions();
            if (restr != null) {
                step.set(RACES.RESTRICTIONS, restr.getCode())
                    .set(RACES.MIN_AGE, toShort(restr.getMinAge()))
                    .set(RACES.MAX_AGE, toShort(restr.getMaxAge()))
                    .set(RACES.SEXES, toShort(restr.getSexes()))
                    .set(RACES.SEXES_CODE, restr.getSexesCode())
                    .set(RACES.STATE_BRED, restr.isStateBred());
            }
        }

        DistanceSurfaceTrackRecord dst = r.getDistanceSurfaceTrackRecord();
        if (dst != null) {
            step.set(RACES.SURFACE, dst.getSurface())
                .set(RACES.COURSE, dst.getCourse())
                .set(RACES.SCHEDULED_SURFACE, dst.getScheduledSurface())
                .set(RACES.SCHEDULED_COURSE, dst.getScheduledCourse())
                .set(RACES.FORMAT, dst.getFormat())
                .set(RACES.TRACK_CONDITION, dst.getTrackCondition());

            if (dst.getRaceDistance() != null) {
                RaceDistance rd = dst.getRaceDistance();
                step.set(RACES.DISTANCE_TEXT, rd.getText())
                    .set(RACES.DISTANCE_COMPACT, rd.getCompact())
                    .set(RACES.EXACT, rd.isExact())
                    .set(RACES.FEET, toShort(rd.getFeet()))
                    .set(RACES.FURLONGS, toBigDecimal(rd.getFurlongs()))
                    .set(RACES.RUN_UP, toShort(rd.getRunUp()))
                    .set(RACES.TEMP_RAIL, toShort(rd.getTempRail()));
            }

            if (dst.getTrackRecord() != null) {
                TrackRecord tr = dst.getTrackRecord();
                String holderName = tr.getHolder() != null ? tr.getHolder().getName() : null;
                step.set(RACES.TRACK_RECORD_HOLDER, holderName)
                    .set(RACES.TRACK_RECORD_TIME, tr.getTime())
                    .set(RACES.TRACK_RECORD_MILLIS, tr.getMillis())
                    .set(RACES.TRACK_RECORD_DATE, tr.getRaceDate());
            }
        }

        Weather w = r.getWeather();
        if (w != null) {
            step.set(RACES.WEATHER, w.getText());
            WindSpeedDirection wsd = w.getWindSpeedDirection();
            if (wsd != null) {
                step.set(RACES.WIND_SPEED, toShort(wsd.getSpeed()))
                    .set(RACES.WIND_DIRECTION, wsd.getDirection());
            }
        }

        PostTimeStartCommentsTimer pt = r.getPostTimeStartCommentsTimer();
        if (pt != null) {
            step.set(RACES.POST_TIME, pt.getPostTime())
                .set(RACES.START_COMMENTS, pt.getStartComments())
                .set(RACES.TIMER, pt.getTimer());
        }

        step.set(RACES.DEAD_HEAT, r.isDeadHeat())
            .set(RACES.NUMBER_OF_RUNNERS, toShort(r.getNumberOfRunners()))
            .set(RACES.FINAL_TIME, r.getFinalTime())
            .set(RACES.FINAL_MILLIS, r.getFinalMillis())
            .set(RACES.FOOTNOTES, r.getFootnotes());

        WagerPayoffPools wpp = r.getWagerPayoffPools();
        if (wpp != null) {
            WinPlaceShowPayoffPool wps = wpp.getWinPlaceShowPayoffPools();
            if (wps != null && wps.getTotalWinPlaceShowPool() != null) {
                step.set(RACES.TOTAL_WPS_POOL, toLong(wps.getTotalWinPlaceShowPool()));
            }
        }

        return step;
    }

    private void writeCancelled(DSLContext tx, RaceResult r) {
        Cancellation c = r.getCancellation();
        Track track = r.getTrack();

        var step = tx.insertInto(CANCELLED)
                .set(CANCELLED.DATE, r.getRaceDate())
                .set(CANCELLED.NUMBER, toShort(r.getRaceNumber()));

        if (c != null) {
            step.set(CANCELLED.REASON, c.getReason());
        }

        if (track != null) {
            step.set(CANCELLED.TRACK, track.getCode())
                .set(CANCELLED.TRACK_CANONICAL, track.getCanonical())
                .set(CANCELLED.TRACK_COUNTRY, track.getCountry())
                .set(CANCELLED.TRACK_STATE, track.getState())
                .set(CANCELLED.TRACK_NAME, track.getName());
        }

        step.onConflictOnConstraint(DSL.name("uq_cancelled_date_track_number"))
            .doUpdate()
            .set(CANCELLED.REASON, c != null ? c.getReason() : null)
            .execute();
    }

    // -------------------------------------------------------------------------
    // Race-level child tables
    // -------------------------------------------------------------------------

    private void writeScratches(DSLContext tx, List<Scratch> scratches, long raceId) {
        if (scratches == null) return;
        for (Scratch s : scratches) {
            Horse h = s.getHorse();
            tx.insertInto(SCRATCHES)
              .set(SCRATCHES.RACE_ID, raceId)
              .set(SCRATCHES.HORSE, h != null ? h.getName() : null)
              .set(SCRATCHES.REASON, s.getReason())
              .execute();
        }
    }

    private void writeFractionals(DSLContext tx, List<Fractional> fractionals, long raceId) {
        if (fractionals == null) return;
        for (Fractional f : fractionals) {
            tx.insertInto(FRACTIONALS)
              .set(FRACTIONALS.RACE_ID, raceId)
              .set(FRACTIONALS.POINT, toShort(f.getPoint()))
              .set(FRACTIONALS.TEXT, f.getText())
              .set(FRACTIONALS.COMPACT, f.getCompact())
              .set(FRACTIONALS.FEET, toShort(f.getFeet()))
              .set(FRACTIONALS.FURLONGS, toBigDecimal(f.getFurlongs()))
              .set(FRACTIONALS.TIME, f.getTime())
              .set(FRACTIONALS.MILLIS, f.getMillis())
              .execute();
        }
    }

    private void writeSplits(DSLContext tx, List<Split> splits, long raceId) {
        if (splits == null) return;
        for (Split s : splits) {
            var step = tx.insertInto(SPLITS)
                .set(SPLITS.RACE_ID, raceId)
                .set(SPLITS.POINT, toShort(s.getPoint()))
                .set(SPLITS.TEXT, s.getText())
                .set(SPLITS.COMPACT, s.getCompact())
                .set(SPLITS.FEET, toShort(s.getFeet()))
                .set(SPLITS.FURLONGS, toBigDecimal(s.getFurlongs()))
                .set(SPLITS.TIME, s.getTime())
                .set(SPLITS.MILLIS, s.getMillis());

            Fractional from = s.getFrom();
            if (from != null) {
                step.set(SPLITS.FROM_POINT, toShort(from.getPoint()))
                    .set(SPLITS.FROM_TEXT, from.getText())
                    .set(SPLITS.FROM_COMPACT, from.getCompact())
                    .set(SPLITS.FROM_FEET, toShort(from.getFeet()))
                    .set(SPLITS.FROM_FURLONGS, toBigDecimal(from.getFurlongs()))
                    .set(SPLITS.FROM_TIME, from.getTime())
                    .set(SPLITS.FROM_MILLIS, from.getMillis());
            }
            Fractional to = s.getTo();
            if (to != null) {
                step.set(SPLITS.TO_POINT, toShort(to.getPoint()))
                    .set(SPLITS.TO_TEXT, to.getText())
                    .set(SPLITS.TO_COMPACT, to.getCompact())
                    .set(SPLITS.TO_FEET, toShort(to.getFeet()))
                    .set(SPLITS.TO_FURLONGS, toBigDecimal(to.getFurlongs()))
                    .set(SPLITS.TO_TIME, to.getTime())
                    .set(SPLITS.TO_MILLIS, to.getMillis());
            }
            step.execute();
        }
    }

    private void writeExotics(DSLContext tx, RaceResult r, long raceId) {
        WagerPayoffPools wpp = r.getWagerPayoffPools();
        if (wpp == null) return;
        List<ExoticPayoffPool> exotics = wpp.getExoticPayoffPools();
        if (exotics == null) return;
        for (ExoticPayoffPool e : exotics) {
            tx.insertInto(EXOTICS)
              .set(EXOTICS.RACE_ID, raceId)
              .set(EXOTICS.UNIT, toBigDecimal(e.getUnit()))
              .set(EXOTICS.NAME, e.getName())
              .set(EXOTICS.WINNING_NUMBERS, e.getWinningNumbers())
              .set(EXOTICS.NUMBER_CORRECT, e.getNumberCorrect() != null ? String.valueOf(e.getNumberCorrect()) : null)
              .set(EXOTICS.PAYOFF, toBigDecimal(e.getPayoff()))
              .set(EXOTICS.ODDS, toBigDecimal(e.getOdds()))
              .set(EXOTICS.POOL, toBigDecimal(e.getPool()))
              .set(EXOTICS.CARRYOVER, toBigDecimal(e.getCarryover()))
              .execute();
        }
    }

    private void writeRaceRatings(DSLContext tx, List<Rating> ratings, long raceId) {
        if (ratings == null) return;
        for (Rating rating : ratings) {
            tx.insertInto(RATINGS)
              .set(RATINGS.RACE_ID, raceId)
              .set(RATINGS.NAME, rating.getName())
              .set(RATINGS.TEXT, rating.getText())
              .set(RATINGS.VALUE, rating.getValue())
              .set(RATINGS.EXTRA, rating.getExtra())
              .execute();
        }
    }

    // -------------------------------------------------------------------------
    // Starters
    // -------------------------------------------------------------------------

    private void writeStarters(DSLContext tx, List<Starter> starters, long raceId) {
        if (starters == null) return;
        for (Starter starter : starters) {
            StartersRecord sr = insertStarter(tx, starter, raceId);
            long starterId = sr.getId();

            writeMeds(tx, starter, starterId);
            writeEquip(tx, starter, starterId);
            writePointsOfCall(tx, starter.getPointsOfCall(), starterId);
            writeIndivFractionals(tx, starter.getFractionals(), starterId);
            writeIndivSplits(tx, starter.getSplits(), starterId);
            writeIndivRatings(tx, starter.getRatings(), starterId);
            writeBreeding(tx, starter, starterId);
            writeWps(tx, starter, starterId);
        }
    }

    private StartersRecord insertStarter(DSLContext tx, Starter s, long raceId) {
        var step = tx.insertInto(STARTERS)
                .set(STARTERS.RACE_ID, raceId);

        // last raced
        LastRaced lr = s.getLastRaced();
        if (lr != null) {
            if (lr.getRaceDate() != null) {
                step.set(STARTERS.LAST_RACED_DATE, lr.getRaceDate());
            }
            step.set(STARTERS.LAST_RACED_DAYS_SINCE, toShort(lr.getDaysSince()));
            LastRacePerformance lrp = lr.getLastRacePerformance();
            if (lrp != null) {
                step.set(STARTERS.LAST_RACED_NUMBER, toShort(lrp.getRaceNumber()))
                    .set(STARTERS.LAST_RACED_POSITION, toShort(lrp.getOfficialPosition()));
                Track t = lrp.getTrack();
                if (t != null) {
                    step.set(STARTERS.LAST_RACED_TRACK, t.getCode())
                        .set(STARTERS.LAST_RACED_TRACK_CANONICAL, t.getCanonical())
                        .set(STARTERS.LAST_RACED_TRACK_COUNTRY, t.getCountry())
                        .set(STARTERS.LAST_RACED_TRACK_STATE, t.getState())
                        .set(STARTERS.LAST_RACED_TRACK_NAME, t.getName());
                }
            }
        }

        step.set(STARTERS.PROGRAM, s.getProgram())
            .set(STARTERS.ENTRY, s.isEntry())
            .set(STARTERS.ENTRY_PROGRAM, s.getEntryProgram());

        Horse h = s.getHorse();
        if (h != null) step.set(STARTERS.HORSE, h.getName());

        Jockey j = s.getJockey();
        if (j != null) {
            step.set(STARTERS.JOCKEY_FIRST, j.getFirstName())
                .set(STARTERS.JOCKEY_LAST, j.getLastName());
        }

        Trainer t = s.getTrainer();
        if (t != null) {
            step.set(STARTERS.TRAINER_FIRST, t.getFirstName())
                .set(STARTERS.TRAINER_LAST, t.getLastName());
        }

        Owner o = s.getOwner();
        if (o != null) step.set(STARTERS.OWNER, o.getName());

        Weight w = s.getWeight();
        if (w != null) {
            step.set(STARTERS.WEIGHT, toShort(w.getWeightCarried()))
                .set(STARTERS.JOCKEY_ALLOWANCE, toShort(w.getJockeyAllowance()));
        }

        MedicationEquipment me = s.getMedicationEquipment();
        if (me != null) step.set(STARTERS.MEDICATION_EQUIPMENT, me.getText());

        Claim claim = s.getClaim();
        if (claim != null) {
            step.set(STARTERS.CLAIM_PRICE, toLong(claim.getPrice()))
                .set(STARTERS.CLAIMED, claim.isClaimed())
                .set(STARTERS.NEW_TRAINER_NAME, claim.getNewTrainerName())
                .set(STARTERS.NEW_OWNER_NAME, claim.getNewOwnerName());
        }

        step.set(STARTERS.PP, toShort(s.getPostPosition()))
            .set(STARTERS.FINISH_POSITION, toShort(s.getFinishPosition()))
            .set(STARTERS.OFFICIAL_POSITION, toShort(s.getOfficialPosition()))
            .set(STARTERS.POSITION_DEAD_HEAT, s.isPositionDeadHeat())
            .set(STARTERS.WAGERING_POSITION, toShort(s.getWageringPosition()))
            .set(STARTERS.WINNER, s.isWinner())
            .set(STARTERS.DISQUALIFIED, s.isDisqualified())
            .set(STARTERS.ODDS, toBigDecimal(s.getOdds()))
            .set(STARTERS.CHOICE, toShort(s.getChoice()))
            .set(STARTERS.FAVORITE, s.isFavorite())
            .set(STARTERS.COMMENTS, s.getComments());

        return step.returning(STARTERS.ID).fetchOne();
    }

    // -------------------------------------------------------------------------
    // Starter child tables
    // -------------------------------------------------------------------------

    private void writeMeds(DSLContext tx, Starter s, long starterId) {
        MedicationEquipment me = s.getMedicationEquipment();
        if (me == null || me.getMedications() == null) return;
        for (Medication m : me.getMedications()) {
            tx.insertInto(MEDS)
              .set(MEDS.STARTER_ID, starterId)
              .set(MEDS.CODE, String.valueOf(m.getCode()))
              .set(MEDS.TEXT, m.getText())
              .execute();
        }
    }

    private void writeEquip(DSLContext tx, Starter s, long starterId) {
        MedicationEquipment me = s.getMedicationEquipment();
        if (me == null || me.getEquipment() == null) return;
        for (Equipment e : me.getEquipment()) {
            tx.insertInto(EQUIP)
              .set(EQUIP.STARTER_ID, starterId)
              .set(EQUIP.CODE, String.valueOf(e.getCode()))
              .set(EQUIP.TEXT, e.getText())
              .execute();
        }
    }

    private void writePointsOfCall(DSLContext tx, List<PointOfCall> pocs, long starterId) {
        if (pocs == null) return;
        for (PointOfCall poc : pocs) {
            var step = tx.insertInto(POINTS_OF_CALL)
                .set(POINTS_OF_CALL.STARTER_ID, starterId)
                .set(POINTS_OF_CALL.POINT, toShort(poc.getPoint()))
                .set(POINTS_OF_CALL.TEXT, poc.getText())
                .set(POINTS_OF_CALL.COMPACT, poc.getCompact())
                .set(POINTS_OF_CALL.FEET, toShort(poc.getFeet()))
                .set(POINTS_OF_CALL.FURLONGS, toBigDecimal(poc.getFurlongs()));

            RelativePosition rp = poc.getRelativePosition();
            if (rp != null) {
                step.set(POINTS_OF_CALL.POSITION, toShort(rp.getPosition()));
                if (rp.getLengthsAhead() != null) {
                    step.set(POINTS_OF_CALL.LEN_AHEAD_TEXT, rp.getLengthsAhead().getText())
                        .set(POINTS_OF_CALL.LEN_AHEAD, toBigDecimal(rp.getLengthsAhead().getLengths()));
                }
                if (rp.getTotalLengthsBehind() != null) {
                    step.set(POINTS_OF_CALL.TOT_LEN_BHD_TEXT, rp.getTotalLengthsBehind().getText())
                        .set(POINTS_OF_CALL.TOT_LEN_BHD, toBigDecimal(rp.getTotalLengthsBehind().getLengths()));
                }
                if (rp.getWide() != null) {
                    step.set(POINTS_OF_CALL.WIDE, toShort(rp.getWide()));
                }
            }
            step.execute();
        }
    }

    private void writeIndivFractionals(DSLContext tx, List<Fractional> fractionals, long starterId) {
        if (fractionals == null) return;
        for (Fractional f : fractionals) {
            tx.insertInto(INDIV_FRACTIONALS)
              .set(INDIV_FRACTIONALS.STARTER_ID, starterId)
              .set(INDIV_FRACTIONALS.POINT, toShort(f.getPoint()))
              .set(INDIV_FRACTIONALS.TEXT, f.getText())
              .set(INDIV_FRACTIONALS.COMPACT, f.getCompact())
              .set(INDIV_FRACTIONALS.FEET, toShort(f.getFeet()))
              .set(INDIV_FRACTIONALS.FURLONGS, toBigDecimal(f.getFurlongs()))
              .set(INDIV_FRACTIONALS.TIME, f.getTime())
              .set(INDIV_FRACTIONALS.MILLIS, f.getMillis())
              .execute();
        }
    }

    private void writeIndivSplits(DSLContext tx, List<Split> splits, long starterId) {
        if (splits == null) return;
        for (Split s : splits) {
            var step = tx.insertInto(INDIV_SPLITS)
                .set(INDIV_SPLITS.STARTER_ID, starterId)
                .set(INDIV_SPLITS.POINT, toShort(s.getPoint()))
                .set(INDIV_SPLITS.TEXT, s.getText())
                .set(INDIV_SPLITS.COMPACT, s.getCompact())
                .set(INDIV_SPLITS.FEET, toShort(s.getFeet()))
                .set(INDIV_SPLITS.FURLONGS, toBigDecimal(s.getFurlongs()))
                .set(INDIV_SPLITS.TIME, s.getTime())
                .set(INDIV_SPLITS.MILLIS, s.getMillis());

            Fractional from = s.getFrom();
            if (from != null) {
                step.set(INDIV_SPLITS.FROM_POINT, toShort(from.getPoint()))
                    .set(INDIV_SPLITS.FROM_TEXT, from.getText())
                    .set(INDIV_SPLITS.FROM_COMPACT, from.getCompact())
                    .set(INDIV_SPLITS.FROM_FEET, toShort(from.getFeet()))
                    .set(INDIV_SPLITS.FROM_FURLONGS, toBigDecimal(from.getFurlongs()))
                    .set(INDIV_SPLITS.FROM_TIME, from.getTime())
                    .set(INDIV_SPLITS.FROM_MILLIS, from.getMillis());
            }
            Fractional to = s.getTo();
            if (to != null) {
                step.set(INDIV_SPLITS.TO_POINT, toShort(to.getPoint()))
                    .set(INDIV_SPLITS.TO_TEXT, to.getText())
                    .set(INDIV_SPLITS.TO_COMPACT, to.getCompact())
                    .set(INDIV_SPLITS.TO_FEET, toShort(to.getFeet()))
                    .set(INDIV_SPLITS.TO_FURLONGS, toBigDecimal(to.getFurlongs()))
                    .set(INDIV_SPLITS.TO_TIME, to.getTime())
                    .set(INDIV_SPLITS.TO_MILLIS, to.getMillis());
            }
            step.execute();
        }
    }

    private void writeIndivRatings(DSLContext tx, List<Rating> ratings, long starterId) {
        if (ratings == null) return;
        for (Rating rating : ratings) {
            tx.insertInto(INDIV_RATINGS)
              .set(INDIV_RATINGS.STARTER_ID, starterId)
              .set(INDIV_RATINGS.NAME, rating.getName())
              .set(INDIV_RATINGS.TEXT, rating.getText())
              .set(INDIV_RATINGS.VALUE, rating.getValue())
              .set(INDIV_RATINGS.EXTRA, rating.getExtra())
              .execute();
        }
    }

    private void writeBreeding(DSLContext tx, Starter s, long starterId) {
        // handycapper only persists breeding for winners
        if (!s.isWinner()) return;
        Horse horse = s.getHorse();
        if (horse == null) return;

        var step = tx.insertInto(BREEDING)
            .set(BREEDING.STARTER_ID, starterId)
            .set(BREEDING.HORSE, horse.getName())
            .set(BREEDING.COLOR, horse.getColor())
            .set(BREEDING.SEX, horse.getSex());

        if (horse.getSire() != null)    step.set(BREEDING.SIRE, horse.getSire().getName());
        if (horse.getDam() != null)     step.set(BREEDING.DAM, horse.getDam().getName());
        if (horse.getDamSire() != null) step.set(BREEDING.DAM_SIRE, horse.getDamSire().getName());
        if (horse.getFoalingDate() != null)
            step.set(BREEDING.FOALING_DATE, horse.getFoalingDate());
        step.set(BREEDING.FOALING_LOCATION, horse.getFoalingLocation());
        if (horse.getBreeder() != null) step.set(BREEDING.BREEDER, horse.getBreeder().getName());

        step.execute();
    }

    private void writeWps(DSLContext tx, Starter s, long starterId) {
        WinPlaceShowPayoff payoff = s.getWinPlaceShowPayoff();
        if (payoff == null) return;
        for (WinPlaceShow wager : payoff.getWinPlaceShows()) {
            if (wager == null) continue;
            tx.insertInto(WPS)
              .set(WPS.STARTER_ID, starterId)
              .set(WPS.TYPE, wager.getType())
              .set(WPS.UNIT, toBigDecimal(wager.getUnit()))
              .set(WPS.PAYOFF, toBigDecimal(wager.getPayoff()))
              .set(WPS.ODDS, toBigDecimal(wager.getOdds()))
              .execute();
        }
    }

    /** Safely converts Integer/int to Short for smallint columns. Returns null if input is null. */
    private static Short toShort(Integer v) {
        return v != null ? v.shortValue() : null;
    }

    private static Short toShort(int v) {
        return (short) v;
    }

    /** Safely converts Integer to Long for bigint columns. Returns null if input is null. */
    private static Long toLong(Integer v) {
        return v != null ? v.longValue() : null;
    }

    /** Safely converts Double to BigDecimal for numeric columns. Returns null if input is null. */
    private static java.math.BigDecimal toBigDecimal(Double v) {
        return v != null ? java.math.BigDecimal.valueOf(v) : null;
    }

    private static java.math.BigDecimal toBigDecimal(double v) {
        return java.math.BigDecimal.valueOf(v);
    }
}
