package com.robinhowlett.importer;

import com.robinhowlett.chartparser.ChartParser;
import com.robinhowlett.chartparser.charts.pdf.ChartCharacter;
import com.robinhowlett.chartparser.charts.pdf.RaceResult;
import com.robinhowlett.chartparser.charts.pdf.TrackRaceDateRaceNumber;
import com.robinhowlett.importer.pipeline.PdfParser;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void diagnose_VEG_1991_extractedTrackName() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/VEG_1991-06-15_race-charts.pdf");
    }

    @Test
    void diagnose_BGD_1991() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/BGD_1991-10-11_race-charts.pdf");
    }

    @Test
    void diagnose_BRD_1993() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/BRD_1993-10-05_race-charts.pdf");
    }

    @Test
    void diagnose_CLG_1991() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/CLG_1991-04-09_race-charts.pdf");
    }

    @Test
    void diagnose_DED_1999() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/DED_1999-01-27_race-charts.pdf");
    }

    @Test
    void diagnose_DUN_1996() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/DUN_1996-03-24_race-charts.pdf");
    }

    @Test
    void diagnose_GRP_1992() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/GRP_1992-07-04_race-charts.pdf");
    }

    @Test
    void diagnose_JRM_1991() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/JRM_1991-04-21_race-charts.pdf");
    }

    @Test
    void diagnose_KAM_1995() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/KAM_1995-09-11_race-charts.pdf");
    }

    @Test
    void diagnose_KLF_1994() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/KLF_1994-07-16_race-charts.pdf");
    }

    @Test
    void diagnose_LBT_1991() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/LBT_1991-04-13_race-charts.pdf");
    }

    @Test
    void diagnose_SDY_1992() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/SDY_1992-06-10_race-charts.pdf");
    }

    @Test
    void diagnose_TIL_1992() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/TIL_1992-08-08_race-charts.pdf");
    }

    @Test
    void diagnose_SAC_2010() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/SAC_2010-07-17_race-charts.pdf");
    }

    /**
     * Dumps font sizes for the "Last Raced" column characters in the first running line of
     * SAC_2010-07-17, to determine why parseFromLastRaced treats digits as track-code characters.
     * Expected: race-number and finish-position digits at fontSize==5 (superscript).
     * If they appear at a different size, that's the bug.
     */
    @Test
    void diagnose_SAC_2010_lastRacedFontSizes() throws Exception {
        File pdf = new File("/home/robin/horseracing/s3/data/equibase/charts/SAC_2010-07-17_race-charts.pdf");
        assumeTrue(pdf.exists(), "SAC PDF not present — skipping");

        // Use a known-good SAC file from the same week for comparison
        File goodPdf = new File("/home/robin/horseracing/s3/data/equibase/charts/SAC_2010-07-14_race-charts.pdf");

        for (File f : new File[]{pdf, goodPdf}) {
            if (!f.exists()) continue;
            System.out.println("=== " + f.getName() + " ===");
            List<String> csvCharts = ChartParser.convertToCsv(f);
            if (csvCharts.isEmpty()) { System.out.println("  (no pages)"); continue; }

            // Only inspect page 1
            List<com.robinhowlett.chartparser.charts.pdf.ChartCharacter> chars =
                    ChartParser.readChartCsv(csvCharts.get(0));
            List<List<com.robinhowlett.chartparser.charts.pdf.ChartCharacter>> lines =
                    ChartParser.separateIntoLines(chars);

            // Find the first running line (starts after "Last Raced|Pgm" header)
            boolean headerSeen = false;
            for (List<com.robinhowlett.chartparser.charts.pdf.ChartCharacter> line : lines) {
                String text = com.robinhowlett.chartparser.charts.pdf.Chart.convertToText(line);
                if (text.startsWith("Last Raced|Pgm")) { headerSeen = true; continue; }
                if (headerSeen && !text.isBlank()) {
                    System.out.println("  First running line text: " + text);
                    // Print each character's unicode and fontSize to spot the superscript digits
                    for (com.robinhowlett.chartparser.charts.pdf.ChartCharacter c : line) {
                        if (c.getHeight() > 2 && c.getUnicode() != '\u0000') {
                            System.out.printf("    [%c] fontSize=%.1f height=%.1f%n",
                                    c.getUnicode(), c.getFontSize(), c.getHeight());
                        }
                    }
                    break;
                }
            }
        }
    }

    @Test
    void diagnose_ELC_1999() throws Exception {
        diagnoseZeroRacePdf("/home/robin/horseracing/s3/data/equibase/charts/ELC_1999-03-01_race-charts.pdf");
    }

    @Test
    void diagnose_runningLineHeaders() throws Exception {
        // DED/GRP/LBT fail with RunningLineHeaderSuffix null — inspect the header text for all three
        for (String path : new String[]{
                "/home/robin/horseracing/s3/data/equibase/charts/DED_1999-01-27_race-charts.pdf",
                "/home/robin/horseracing/s3/data/equibase/charts/GRP_1992-07-04_race-charts.pdf",
                "/home/robin/horseracing/s3/data/equibase/charts/LBT_1991-04-13_race-charts.pdf",
        }) {
            File pdf = new File(path);
            if (!pdf.exists()) continue;
            System.out.println("=== " + pdf.getName() + " ===");
            List<String> csvCharts = ChartParser.convertToCsv(pdf);
            if (csvCharts.isEmpty()) { System.out.println("  (no pages)"); continue; }
            List<ChartCharacter> chars = ChartParser.readChartCsv(csvCharts.get(0));
            List<List<ChartCharacter>> lines = ChartParser.separateIntoLines(chars);
            for (List<ChartCharacter> line : lines) {
                String text = com.robinhowlett.chartparser.charts.pdf.Chart.convertToText(line);
                if (text.contains("Last Raced") || text.contains("Fin") || text.contains("Odds")
                        || text.contains("Comments") || text.contains("Ind") || text.contains("Sp.")) {
                    System.out.println("  Header candidate: [" + text + "]");
                }
            }
        }
    }

    @Test
    void diagnose_DED_runningLineHeader() throws Exception {
        // DED 1991-92 files fail with RunningLineHeaderSuffix null — inspect the header text
        File pdf = new File("/home/robin/horseracing/s3/data/equibase/charts/DED_1999-01-27_race-charts.pdf");
        assumeTrue(pdf.exists(), "DED PDF not present — skipping");

        List<String> csvCharts = ChartParser.convertToCsv(pdf);
        assertFalse(csvCharts.isEmpty());

        List<ChartCharacter> chars = ChartParser.readChartCsv(csvCharts.get(0));
        List<List<ChartCharacter>> lines = ChartParser.separateIntoLines(chars);

        // Print every line that plausibly contains "Last Raced" or "Fin" to spot the header
        for (List<ChartCharacter> line : lines) {
            String text = com.robinhowlett.chartparser.charts.pdf.Chart.convertToText(line);
            if (text.contains("Last Raced") || text.contains("Fin") || text.contains("Odds")
                    || text.contains("Comments")) {
                System.out.println("Header candidate: [" + text + "]");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Prints the track name extracted from each page and the full parse result. */
    private void diagnoseZeroRacePdf(String path) throws Exception {
        File pdf = new File(path);
        assumeTrue(pdf.exists(), path + " not present — skipping");

        List<String> csvCharts = ChartParser.convertToCsv(pdf);
        assumeTrue(!csvCharts.isEmpty(), "PDFBox extracted 0 pages from " + path + " — zero-page PDF, skipping");

        for (int i = 0; i < csvCharts.size(); i++) {
            List<ChartCharacter> chars = ChartParser.readChartCsv(csvCharts.get(i));
            List<List<ChartCharacter>> lines = ChartParser.separateIntoLines(chars);
            try {
                TrackRaceDateRaceNumber parsed = TrackRaceDateRaceNumber.parse(lines);
                System.out.printf("Page %d: track name = [%s]%n", i + 1, parsed.getTrackName());
            } catch (Exception e) {
                System.out.printf("Page %d: parse failed: %s%n", i + 1, e.getMessage());
            }
        }

        var outcome = new PdfParser().parse(pdf.toPath());
        if (outcome instanceof PdfParser.ParseOutcome.Failure f) {
            System.out.printf("Full parse FAILED: %s%n", f.cause().getMessage());
            f.cause().printStackTrace(System.out);
        }
        assertInstanceOf(PdfParser.ParseOutcome.Success.class, outcome);
        List<RaceResult> results = ((PdfParser.ParseOutcome.Success) outcome).results();
        System.out.printf("Full parse: %d races%n", results.size());
    }
}
