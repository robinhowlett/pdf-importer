package com.robinhowlett.importer;

import com.robinhowlett.importer.db.ConnectionPool;
import com.robinhowlett.importer.model.ImportResult.Status;
import com.robinhowlett.importer.pipeline.ImportTracker;
import com.robinhowlett.importer.pipeline.PdfParser;
import com.robinhowlett.importer.pipeline.PdfScanner;
import com.robinhowlett.importer.pipeline.RaceWriter;
import com.robinhowlett.importer.pipeline.UnimportableClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
        // Suppress PDFBox JUL warnings (font fallbacks etc.) — they bypass Logback.
        // Must silence both the logger level AND each handler's level; JUL checks both.
        java.util.logging.Logger rootJul = java.util.logging.Logger.getLogger("");
        rootJul.setLevel(java.util.logging.Level.SEVERE);
        for (java.util.logging.Handler h : rootJul.getHandlers()) {
            h.setLevel(java.util.logging.Level.SEVERE);
        }

        List<Path> roots = args.length > 0
                ? java.util.Arrays.stream(args).map(Path::of).toList()
                : List.of(Path.of(System.getProperty("user.home"), "horseracing"));

        // Use the first root for config resolution (progress DB location etc.)
        ImportConfig config = ImportConfig.fromEnvironment(roots.get(0));

        log.info("Sources: {}", roots);
        log.info("DB:      {}", config.jdbcUrl());
        log.info("Threads: {}", config.threadCount());

        try (var tracker = new ImportTracker(config.progressDb());
             var pool    = ConnectionPool.create(config)) {

            var scanner = new PdfScanner();
            var parser  = new PdfParser();
            var writer  = new RaceWriter(pool.getDataSource());

            List<Path> files = scanner.scan(roots, tracker);
            System.out.printf("Found %,d unprocessed PDFs%n", files.size());

            if (files.isEmpty()) {
                System.out.println("Nothing to do.");
                return;
            }

            var success = new AtomicInteger();
            var failed  = new AtomicInteger();
            long start  = System.currentTimeMillis();

            // Semaphore caps concurrent PDFs in memory — without it all 200K tasks submit at
            // once and PDFBox's per-PDF heap usage causes OOM. Virtual threads park on acquire()
            // rather than blocking a carrier thread, so this adds no CPU overhead.
            var semaphore = new Semaphore(config.threadCount());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Path pdf : files) {
                    semaphore.acquire();
                    executor.submit(() -> {
                        try {
                            var parsed = parser.parse(pdf);
                            if (parsed instanceof PdfParser.ParseOutcome.Failure f) {
                                Status classified = UnimportableClassifier
                                        .classifyParseFailure(pdf, f.cause());
                                tracker.recordFailure(pdf, classified, f.cause());
                                failed.incrementAndGet();
                                log.warn("{} {}: {}", classified, pdf.getFileName(),
                                        f.cause().getMessage());
                                return;
                            }
                            var results = ((PdfParser.ParseOutcome.Success) parsed).results();
                            // IMP-T5.3: a successful parse with zero races is a known
                            // unimportable shape (e.g., OldChartFormat where the
                            // chart-parser silently swallowed per-race exceptions).
                            // Mark UNIMPORTABLE so PdfScanner skips it on next run.
                            if (results.isEmpty()) {
                                Status classified = UnimportableClassifier
                                        .classifyZeroRaceSuccess(pdf);
                                tracker.recordFailure(pdf, classified, null);
                                failed.incrementAndGet();
                                log.warn("{} {}: parse produced 0 races",
                                        classified, pdf.getFileName());
                                return;
                            }
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
                        } finally {
                            semaphore.release();
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
