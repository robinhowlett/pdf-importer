package com.robinhowlett.importer.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Walks source directories/files and returns PDF paths not yet successfully processed.
 * Accepts both Equibase "race-charts" naming and the newer "*.standard.pdf" convention.
 * PPS and other non-result PDFs (which match neither pattern) are skipped.
 */
public class PdfScanner {

    public List<Path> scan(List<Path> roots, ImportTracker tracker) throws IOException {
        List<Path> result = new java.util.ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> isResultChart(p))
                    .filter(Predicate.not(tracker::alreadyProcessed))
                    .forEach(result::add);
            }
        }
        result.sort(java.util.Comparator.naturalOrder());
        return result;
    }

    private static boolean isResultChart(Path p) {
        if (!p.toString().endsWith(".pdf")) return false;
        String name = p.getFileName().toString();
        return name.contains("race-charts") || name.endsWith(".standard.pdf");
    }
}
