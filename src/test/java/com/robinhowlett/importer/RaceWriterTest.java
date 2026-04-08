package com.robinhowlett.importer;

import com.robinhowlett.chartparser.charts.pdf.RaceResult;
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
