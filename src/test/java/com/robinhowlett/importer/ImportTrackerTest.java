package com.robinhowlett.importer;

import com.robinhowlett.importer.model.ImportResult.Status;
import com.robinhowlett.importer.pipeline.ImportTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ImportTrackerTest {

    @TempDir
    Path tempDir;

    private ImportTracker tracker;
    private Path somePdf;

    @BeforeEach
    void setUp() throws SQLException {
        tracker = new ImportTracker(tempDir.resolve("test.db"));
        somePdf = Path.of("/fake/path/race-charts.pdf");
    }

    @AfterEach
    void tearDown() throws SQLException {
        tracker.close();
    }

    @Test
    void alreadyProcessed_NewFile_ReturnsFalse() {
        assertFalse(tracker.alreadyProcessed(somePdf));
    }

    @Test
    void alreadyProcessed_SuccessfulFile_ReturnsTrue() {
        tracker.recordSuccess(somePdf, 10);
        assertTrue(tracker.alreadyProcessed(somePdf));
    }

    @Test
    void alreadyProcessed_FailedFile_ReturnsFalse_SoItIsRetried() {
        tracker.recordFailure(somePdf, Status.PARSE_FAILED, new RuntimeException("oops"));
        assertFalse(tracker.alreadyProcessed(somePdf),
                "FAILED entries must not be filtered — they should be retried on re-run");
    }

    @Test
    void alreadyProcessed_WriteFailedFile_ReturnsFalse_SoItIsRetried() {
        tracker.recordFailure(somePdf, Status.WRITE_FAILED, new RuntimeException("db error"));
        assertFalse(tracker.alreadyProcessed(somePdf));
    }

    @Test
    void recordSuccess_OverwritesPreviousFailure() {
        tracker.recordFailure(somePdf, Status.PARSE_FAILED, new RuntimeException("first try"));
        tracker.recordSuccess(somePdf, 5);
        assertTrue(tracker.alreadyProcessed(somePdf));
    }
}
