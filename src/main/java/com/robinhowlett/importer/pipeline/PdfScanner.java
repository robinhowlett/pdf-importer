package com.robinhowlett.importer.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Walks a source directory tree and returns PDF paths not yet successfully processed.
 * The "race-charts" filename filter skips PPS and other non-result PDFs.
 */
public class PdfScanner {

    public List<Path> scan(Path root, ImportTracker tracker) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .filter(p -> p.getFileName().toString().contains("race-charts"))
                    .filter(Predicate.not(tracker::alreadyProcessed))
                    .sorted()
                    .toList();
        }
    }
}
