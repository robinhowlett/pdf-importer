package com.robinhowlett.importer.pipeline;

import com.robinhowlett.chartparser.ChartParser;
import com.robinhowlett.chartparser.charts.pdf.RaceResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Exception-safe wrapper around {@link ChartParser}.
 * <p>
 * Every exception is caught and returned as a structured {@link Failure}. Nothing propagates to
 * the thread pool in a way that silently swallows the failure.
 * <p>
 * {@code ChartParser.create()} is not thread-safe to call concurrently (it initialises static
 * state). Create one instance and share it across threads — the {@code parse()} method is safe
 * to call from multiple threads once the parser is constructed.
 */
public class PdfParser {

    private final ChartParser chartParser;

    public PdfParser() {
        this.chartParser = ChartParser.create();
    }

    public ParseOutcome parse(Path pdf) {
        try {
            List<RaceResult> results = chartParser.parse(pdf.toFile());
            return new ParseOutcome.Success(pdf, results);
        } catch (Exception e) {
            return new ParseOutcome.Failure(pdf, e);
        }
    }

    public sealed interface ParseOutcome permits ParseOutcome.Success, ParseOutcome.Failure {
        Path pdf();

        record Success(Path pdf, List<RaceResult> results) implements ParseOutcome {}

        record Failure(Path pdf, Exception cause) implements ParseOutcome {}
    }
}
