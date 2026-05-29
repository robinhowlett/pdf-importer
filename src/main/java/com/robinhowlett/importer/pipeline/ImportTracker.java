package com.robinhowlett.importer.pipeline;

import com.robinhowlett.importer.model.ImportResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite-backed progress log. Persists across JVM restarts so the import can be resumed.
 * <p>
 * Thread-safe: all mutating operations are synchronised on this instance. SQLite is single-writer
 * so concurrent writes would serialize anyway; explicit synchronisation makes the intent clear.
 */
public class ImportTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ImportTracker.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS import_log (
                path          TEXT    PRIMARY KEY,
                status        TEXT    NOT NULL,
                races_loaded  INTEGER,
                error_type    TEXT,
                error_message TEXT,
                processed_at  TEXT    DEFAULT (datetime('now'))
            )""";

    private static final String UPSERT =
            "INSERT OR REPLACE INTO import_log(path, status, races_loaded, error_type, error_message) " +
            "VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_STATUS =
            "SELECT status FROM import_log WHERE path = ?";

    private final Connection conn;

    public ImportTracker(Path dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute(CREATE_TABLE);
        }
    }

    /**
     * Returns true only when the path has been recorded as SUCCESS.
     * FAILED entries are NOT filtered — they will be retried on re-run.
     */
    public boolean alreadyProcessed(Path pdf) {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_STATUS)) {
            ps.setString(1, pdf.toAbsolutePath().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString(1);
                    return "SUCCESS".equals(status) || "UNIMPORTABLE".equals(status);
                }
            }
        } catch (SQLException e) {
            log.warn("Could not check progress for {}: {}", pdf, e.getMessage());
        }
        return false;
    }

    public synchronized void recordSuccess(Path pdf, int racesLoaded) {
        upsert(pdf.toAbsolutePath().toString(), "SUCCESS", racesLoaded, null, null);
    }

    public synchronized void recordFailure(Path pdf, ImportResult.Status status, Exception e) {
        // e may be null when classifying a zero-race success as UNIMPORTABLE.
        String errorType = (e != null) ? e.getClass().getName() : null;
        String errorMessage = (e != null) ? truncate(e.getMessage(), 500) : null;
        upsert(pdf.toAbsolutePath().toString(), status.name(), 0, errorType, errorMessage);
    }

    private void upsert(String path, String status, int racesLoaded,
                        String errorType, String errorMessage) {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, path);
            ps.setString(2, status);
            ps.setInt(3, racesLoaded);
            ps.setString(4, errorType);
            ps.setString(5, errorMessage);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record {} as {}: {}", path, status, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
