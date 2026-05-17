package com.robinhowlett.importer;

import com.robinhowlett.chartparser.charts.pdf.RaceResult;
import com.robinhowlett.importer.pipeline.PdfParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfParserTest {

    private static final Path SAMPLE_PDF =
            Path.of("src/test/resources/ARP_2016-07-24_race-charts.pdf");

    @Test
    void parse_KnownGoodPdf_ReturnsNineRaces() {
        var parser = new PdfParser();
        var outcome = parser.parse(SAMPLE_PDF);

        assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome);
        List<RaceResult> results = ((PdfParser.ParseOutcome.Success) outcome).results();
        assertEquals(9, results.size(), "ARP_2016-07-24 has 9 parseable races");
        assertEquals("ARP", results.get(0).getTrack().getCode());
    }

    @Test
    void parse_EmptyFile_ReturnsSuccessWithNoRaces() {
        // chart-parser returns an empty list (no exception) for files with no parseable content
        var parser = new PdfParser();
        var outcome = parser.parse(Path.of("/dev/null"));

        assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome,
                "chart-parser returns empty success for unreadable/empty files");
        assertTrue(((PdfParser.ParseOutcome.Success) outcome).results().isEmpty());
    }

    @Test
    void parse_NonExistentFile_ReturnsSuccessWithNoRaces() {
        var parser = new PdfParser();
        var outcome = parser.parse(Path.of("/does/not/exist.pdf"));

        assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome,
                "chart-parser returns empty success when file is missing");
        assertTrue(((PdfParser.ParseOutcome.Success) outcome).results().isEmpty());
    }
}
