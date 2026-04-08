package com.robinhowlett.importer;

import com.robinhowlett.importer.db.ConnectionPool;
import com.robinhowlett.importer.model.ImportResult.Status;
import com.robinhowlett.importer.pipeline.ImportTracker;
import com.robinhowlett.importer.pipeline.PdfParser;
import com.robinhowlett.importer.pipeline.PdfScanner;
import com.robinhowlett.importer.pipeline.RaceWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point. Walks a source directory of Equibase PDFs, parses each one with chart-parser,
 * and writes the results to PostgreSQL using jOOQ.
 * <p>
 * Usage:
 * <pre>
 *   java -Xmx4g -jar pdf-importer.jar [sourceDir]
 * </pre>
 * Defaults to ~/horseracing if no argument is provided.
 * <p>
 * Override connection settings with environment variables:
 * <ul>
 *   <li>IMPORTER_JDBC_URL — defaults to jdbc:postgresql://localhost:5433/handycapper</li>
 *   <li>IMPORTER_DB_USER  — defaults to handycapper</li>
 *   <li>IMPORTER_DB_PASSWORD — defaults to handycapper</li>
 *   <li>IMPORTER_THREADS — defaults to number of CPU cores</li>
 *   <li>IMPORTER_BATCH_SIZE — defaults to 500</li>
 * </ul>
 */
public class PdfImporter {

    private static final Logger log = LoggerFactory.getLogger(PdfImporter.class);

    public static void main(String[] args) throws Exception {
        Path sourceDir = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.home"), "horseracing");

        ImportConfig config = ImportConfig.fromEnvironment(sourceDir);

        log.info("Source:  {}", config.sourceDir());
        log.info("DB:      {}", config.jdbcUrl());
        log.info("Threads: {}", config.threadCount());

        try (var tracker = new ImportTracker(config.progressDb());
             var pool    = ConnectionPool.create(config)) {

            var scanner = new PdfScanner();
            var parser  = new PdfParser();
            var writer  = new RaceWriter(pool.getDataSource());

            List<Path> files = scanner.scan(sourceDir, tracker);
            System.out.printf("Found %,d unprocessed PDFs%n", files.size());

            if (files.isEmpty()) {
                System.out.println("Nothing to do.");
                return;
            }

            var success = new AtomicInteger();
            var failed  = new AtomicInteger();
            long start  = System.currentTimeMillis();

            // Virtual threads: one per PDF. JVM schedules them over CPU carrier threads.
            // Yielding during I/O (PostgreSQL writes) keeps CPUs busy with parsing.
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Path pdf : files) {
                    executor.submit(() -> {
                        try {
                            var parsed = parser.parse(pdf);
                            if (parsed instanceof PdfParser.ParseOutcome.Failure f) {
                                tracker.recordFailure(pdf, Status.PARSE_FAILED, f.cause());
                                failed.incrementAndGet();
                                log.warn("PARSE_FAILED {}: {}", pdf.getFileName(),
                                        f.cause().getMessage());
                                return;
                            }
                            var results = ((PdfParser.ParseOutcome.Success) parsed).results();
                            var outcome = writer.write(pdf, results);
                            if (outcome.isSuccess()) {
                                tracker.recordSuccess(pdf, outcome.racesLoaded());
                                success.incrementAndGet();
                            } else {
                                tracker.recordFailure(pdf, Status.WRITE_FAILED, outcome.cause());
                                failed.incrementAndGet();
                                log.warn("WRITE_FAILED {}: {}", pdf.getFileName(),
                                        outcome.cause().getMessage());
                            }
                        } catch (Exception e) {
                            tracker.recordFailure(pdf, Status.WRITE_FAILED, e);
                            failed.incrementAndGet();
                            log.error("Unexpected error for {}: {}", pdf.getFileName(), e.getMessage());
                        }
                        printProgress(success, failed, files.size(), start);
                    });
                }
            } // blocks until all tasks complete

            long elapsed = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("Done in %d min %d sec.  Success: %,d  Failed: %,d%n",
                    elapsed / 60, elapsed % 60, success.get(), failed.get());
        }
    }

    private static void printProgress(AtomicInteger success, AtomicInteger failed,
                                      int total, long start) {
        int done = success.get() + failed.get();
        if (done % 1000 == 0) {
            long elapsed = System.currentTimeMillis() - start;
            double rate = done / (elapsed / 1000.0);
            long remaining = (long) ((total - done) / rate / 60);
            System.out.printf("[%,d/%,d] %.1f PDFs/sec, ~%d min remaining%n",
                    done, total, rate, remaining);
        }
    }
}
