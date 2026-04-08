package com.robinhowlett.importer;

import java.nio.file.Path;

/**
 * All tunable parameters in one place. Passed through the pipeline; no magic constants elsewhere.
 */
public record ImportConfig(
        Path sourceDir,
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        int threadCount,
        int batchSize,
        Path progressDb,
        Path failedLog
) {
    public static ImportConfig defaults(Path sourceDir) {
        return new ImportConfig(
                sourceDir,
                "jdbc:postgresql://localhost:5433/handycapper",
                "handycapper",
                "handycapper",
                Runtime.getRuntime().availableProcessors(),
                500,
                Path.of(System.getProperty("user.home"), "import-progress.db"),
                Path.of(System.getProperty("user.home"), "import-failed.log")
        );
    }

    /** Override specific fields via system properties or environment variables. */
    public static ImportConfig fromEnvironment(Path sourceDir) {
        String jdbcUrl = System.getenv().getOrDefault("IMPORTER_JDBC_URL",
                "jdbc:postgresql://localhost:5433/handycapper");
        String dbUser  = System.getenv().getOrDefault("IMPORTER_DB_USER", "handycapper");
        String dbPass  = System.getenv().getOrDefault("IMPORTER_DB_PASSWORD", "handycapper");
        int threads    = Integer.parseInt(System.getenv().getOrDefault("IMPORTER_THREADS",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        int batch      = Integer.parseInt(System.getenv().getOrDefault("IMPORTER_BATCH_SIZE", "500"));
        return new ImportConfig(sourceDir, jdbcUrl, dbUser, dbPass, threads, batch,
                Path.of(System.getProperty("user.home"), "import-progress.db"),
                Path.of(System.getProperty("user.home"), "import-failed.log"));
    }
}
