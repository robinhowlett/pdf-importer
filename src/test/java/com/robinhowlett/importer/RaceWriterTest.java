package com.robinhowlett.importer;

import com.robinhowlett.chartparser.charts.pdf.Cancellation;
import com.robinhowlett.chartparser.charts.pdf.RaceResult;
import com.robinhowlett.chartparser.tracks.Track;
import com.robinhowlett.importer.model.ImportResult;
import com.robinhowlett.importer.pipeline.PdfParser;
import com.robinhowlett.importer.pipeline.RaceWriter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.robinhowlett.handycapper.domain.tables.Cancelled.CANCELLED;
import static com.robinhowlett.handycapper.domain.tables.Races.RACES;
import static com.robinhowlett.handycapper.domain.tables.Starters.STARTERS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RaceWriter}.
 * <p>
 * Requires a running PostgreSQL instance. By default connects to
 * {@code localhost:5432/handycapper_test} with user {@code handycapper}/{@code handycapper}.
 * Override via system properties or environment variables:
 * <ul>
 *   <li>{@code TEST_JDBC_URL} — defaults to {@code jdbc:postgresql://localhost:5432/handycapper_test}</li>
 *   <li>{@code TEST_DB_USER}  — defaults to {@code handycapper}</li>
 *   <li>{@code TEST_DB_PASS}  — defaults to {@code handycapper}</li>
 * </ul>
 */
class RaceWriterTest {

    static final Path SAMPLE_PDF =
            Path.of("src/test/resources/ARP_2016-07-24_race-charts.pdf");

    static final String JDBC_URL = System.getenv().getOrDefault("TEST_JDBC_URL",
            "jdbc:postgresql://localhost:5432/handycapper_test");
    static final String DB_USER = System.getenv().getOrDefault("TEST_DB_USER", "handycapper");
    static final String DB_PASS = System.getenv().getOrDefault("TEST_DB_PASS", "handycapper");

    static HikariDataSource dataSource;
    static DSLContext dsl;
    static List<RaceResult> sampleResults;

    @BeforeAll
    static void setUpAll() {
        // Apply Flyway migrations to the test database.
        // clean() drops and recreates the schema on each test run for a clean slate.
        var flyway = Flyway.configure()
                .dataSource(JDBC_URL, DB_USER, DB_PASS)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(JDBC_URL);
        hc.setUsername(DB_USER);
        hc.setPassword(DB_PASS);
        dataSource = new HikariDataSource(hc);
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

        // Parse the sample PDF once; reused across all tests
        var outcome = new PdfParser().parse(SAMPLE_PDF);
        assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome,
                "Sample PDF must parse successfully");
        sampleResults = ((PdfParser.ParseOutcome.Success) outcome).results();
    }

    @BeforeEach
    void cleanDatabase() {
        // Wipe all race data between tests so they run independently
        dsl.execute("TRUNCATE handycapper.races CASCADE");
        dsl.execute("TRUNCATE handycapper.cancelled CASCADE");
    }

    @Test
    void write_NewPdf_InsertsAllRacesAndStarters() {
        var writer = new RaceWriter(dataSource);
        var result = writer.write(SAMPLE_PDF, sampleResults);

        assertTrue(result.isSuccess());
        assertEquals(9, result.racesLoaded());

        int raceCount = dsl.fetchCount(RACES);
        assertEquals(9, raceCount, "Should have 9 races");

        int starterCount = dsl.fetchCount(STARTERS);
        assertTrue(starterCount >= 45,
                "Should have at least 45 starters (9 races × ~8 starters), got " + starterCount);

        // IMP-T5.5: every race got a wagering-interests count populated. The
        // ARP 2016-07-24 card has no coupled entries, so for this card it
        // should equal number_of_runners on every race.
        Integer mismatched = dsl.fetchOne(
                "SELECT count(*) FROM handycapper.races r "
                + "WHERE r.number_of_wagering_interests IS NULL "
                + "   OR r.number_of_wagering_interests <> r.number_of_runners")
                .into(Integer.class);
        assertEquals(0, mismatched,
                "On the no-coupled-entry ARP card, NWI must equal NoR for every race");
    }

    @Test
    void write_SamePdfTwice_IsIdempotent() {
        var writer = new RaceWriter(dataSource);

        var first  = writer.write(SAMPLE_PDF, sampleResults);
        var second = writer.write(SAMPLE_PDF, sampleResults);

        assertTrue(first.isSuccess(), "First write must succeed");
        assertTrue(second.isSuccess(), "Second write must succeed");

        assertEquals(9, dsl.fetchCount(RACES),
                "Row count must be the same after second write — no duplicates");
        assertTrue(dsl.fetchCount(STARTERS) >= 45);
    }

    @Test
    void write_ReimportPropagatesChangedNonKeyColumn() {
        // IMP-T5.1: a re-import must overwrite ALL non-key columns on the
        // existing race row, not just the legacy 6-column subset that the
        // pre-fix doUpdate enumerated. Verifies the audit's named bug:
        // off_turf / female_only / age_code etc. used to silently retain
        // stale values from the original import.
        var writer = new RaceWriter(dataSource);
        writer.write(SAMPLE_PDF, sampleResults);

        // Simulate a stale row by mutating fields the OLD doUpdate would
        // never have touched: surface, off_turf, conditions, post_time,
        // weather, plus track_record_holder. These all live on RACES but
        // none of them was in the legacy 6-column doUpdate.
        int updated = dsl.execute(
                "UPDATE handycapper.races SET "
                + "surface = 'STALE', off_turf = true, conditions = 'STALE_TEXT', "
                + "post_time = 'STALE_TIME', weather = 'STALE_WX', "
                + "track_record_holder = 'STALE_HOLDER', female_only = NOT female_only, "
                + "age_code = 'STALE_CODE'");
        assertTrue(updated > 0, "Must have stale rows to re-write over");

        // Re-import: with the new delete-then-insert strategy, every column
        // is rewritten from the parsed RaceResult.
        var second = writer.write(SAMPLE_PDF, sampleResults);
        assertTrue(second.isSuccess());

        Integer staleSurface = dsl.fetchCount(RACES, RACES.SURFACE.eq("STALE"));
        Integer staleOffTurf = dsl.fetchCount(RACES, RACES.CONDITIONS.eq("STALE_TEXT"));
        Integer staleHolder  = dsl.fetchCount(RACES, RACES.TRACK_RECORD_HOLDER.eq("STALE_HOLDER"));
        Integer stalePostTime = dsl.fetchCount(RACES, RACES.POST_TIME.eq("STALE_TIME"));
        Integer staleWeather  = dsl.fetchCount(RACES, RACES.WEATHER.eq("STALE_WX"));
        Integer staleAgeCode  = dsl.fetchCount(RACES, RACES.AGE_CODE.eq("STALE_CODE"));
        assertEquals(0, staleSurface, "surface must be rewritten on re-import");
        assertEquals(0, staleOffTurf, "conditions must be rewritten on re-import");
        assertEquals(0, staleHolder,  "track_record_holder must be rewritten on re-import");
        assertEquals(0, stalePostTime, "post_time must be rewritten on re-import");
        assertEquals(0, staleWeather,  "weather must be rewritten on re-import");
        assertEquals(0, staleAgeCode,  "age_code must be rewritten on re-import");

        // Sanity: row count unchanged (re-import is in-place replacement).
        assertEquals(9, dsl.fetchCount(RACES));
    }

    @Test
    void write_RaceFlipsBetweenCancelledAndRun_KeepsTablesDisjoint() {
        var writer = new RaceWriter(dataSource);

        Track track = new Track();
        track.setCode("ARP");
        track.setCanonical("ARP");
        track.setCountry("USA");
        track.setName("Arapahoe Park");

        var raceDate = java.time.LocalDate.of(2016, 7, 24);
        var raceNumber = 99;

        var cancelled = new RaceResult(new Cancellation("weather"), raceDate, track, raceNumber);
        var run       = new RaceResult(null, raceDate, track, raceNumber);

        // Step 1: cancelled run lands in cancelled
        writer.write(SAMPLE_PDF, List.of(cancelled));
        assertEquals(1, dsl.fetchCount(CANCELLED));
        assertEquals(0, dsl.fetchCount(RACES));

        // Step 2: same key now classified as run — cancelled row must go away
        writer.write(SAMPLE_PDF, List.of(run));
        assertEquals(0, dsl.fetchCount(CANCELLED),
                "cancelled row must be removed when race is reclassified as run");
        assertEquals(1, dsl.fetchCount(RACES));

        // Step 3: flip back to cancelled — races row (and its CASCADE children) must go away
        writer.write(SAMPLE_PDF, List.of(cancelled));
        assertEquals(1, dsl.fetchCount(CANCELLED));
        assertEquals(0, dsl.fetchCount(RACES),
                "races row must be removed when race is reclassified as cancelled");
    }

    @Test
    void write_PartialFailure_DoesNotLeaveOrphanRows() {
        // Build a list where the last element is a RaceResult with a null date,
        // which will cause a NOT NULL constraint violation in PostgreSQL.
        List<RaceResult> bad = new ArrayList<>(sampleResults);
        bad.add(new RaceResult(null, null, null, null));  // null date → constraint violation

        var writer = new RaceWriter(dataSource);
        var result = writer.write(SAMPLE_PDF, bad);

        assertEquals(ImportResult.Status.WRITE_FAILED, result.status(),
                "Must report WRITE_FAILED when any race in the batch fails");

        assertEquals(0, dsl.fetchCount(RACES),
                "Transaction must have rolled back completely — 0 rows in DB");
    }
}
