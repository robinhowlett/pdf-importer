package com.robinhowlett.importer;

import com.robinhowlett.importer.model.ImportResult.Status;
import com.robinhowlett.importer.pipeline.UnimportableClassifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMP-T5.3 — verifies the parse-failure classifier permanently marks
 * known-unimportable shapes as UNIMPORTABLE so they're skipped on re-runs,
 * and lets transient failures stay PARSE_FAILED for retry.
 */
class UnimportableClassifierTest {

    @Test
    void classify_HtmlStubBy3253ByteSize_IsUnimportable(@TempDir Path tmp) throws IOException {
        Path pdf = writeFile(tmp.resolve("htmlstub.pdf"), repeat("X", 3253));
        Status s = UnimportableClassifier.classifyParseFailure(pdf, new IOException("not a PDF"));
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_HtmlStubBy8280ByteSize_IsUnimportable(@TempDir Path tmp) throws IOException {
        Path pdf = writeFile(tmp.resolve("htmlstub.pdf"), repeat("X", 8280));
        Status s = UnimportableClassifier.classifyParseFailure(pdf, new IOException("not a PDF"));
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_SmallFileWithHtmlMagic_IsUnimportable(@TempDir Path tmp) throws IOException {
        Path pdf = writeFile(tmp.resolve("htmlstub.pdf"),
                "<!DOCTYPE html><html><body>Chart unavailable</body></html>");
        Status s = UnimportableClassifier.classifyParseFailure(pdf, new IOException("not a PDF"));
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_TinyValidPdf_IsUnimportable(@TempDir Path tmp) throws IOException {
        // Tiny "%PDF-" header with no pages. Real EmptyPdf files in the
        // wild are ~547 bytes. Use a 100-byte placeholder.
        Path pdf = writeFile(tmp.resolve("empty.pdf"),
                "%PDF-1.4\n" + repeat("x", 90));
        Status s = UnimportableClassifier.classifyParseFailure(pdf, new IOException("0 pages"));
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_MalformedRaceException_IsUnimportable(@TempDir Path tmp) throws IOException {
        // OldChartFormat: chart-parser throws MalformedRaceException because
        // RunningLineHeader doesn't recognize pre-1993 column suffixes.
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Throwable cause = new MalformedRaceException("Unable to create RunningLineHeader");
        Status s = UnimportableClassifier.classifyParseFailure(pdf, cause);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_NoRaceDistanceFound_IsUnimportable(@TempDir Path tmp) throws IOException {
        // UnsupportedRaceFormat: QH futurity charts with no distance prefix.
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Throwable cause = new NoRaceDistanceFound("On The Dirt only");
        Status s = UnimportableClassifier.classifyParseFailure(pdf, cause);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_MissingHorseJockeyException_IsUnimportable(@TempDir Path tmp) throws IOException {
        // UnparsableRunningLines: dash placeholder for horse/jockey names.
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Throwable cause = new MissingHorseJockeyException("dash placeholder");
        Status s = UnimportableClassifier.classifyParseFailure(pdf, cause);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_RaceTypeNameOrBreedNotIdentifiable_IsUnimportable(@TempDir Path tmp) throws IOException {
        // UnknownRaceType: non-standard chart format.
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Throwable cause = new RaceTypeNameOrBreedNotIdentifiable("non-standard format");
        Status s = UnimportableClassifier.classifyParseFailure(pdf, cause);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_ExceptionInCauseChain_IsRecognized(@TempDir Path tmp) throws IOException {
        // Real production exceptions come wrapped (e.g. RuntimeException(MalformedRaceException)).
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Throwable inner = new MalformedRaceException("inner");
        Throwable wrapped = new RuntimeException("outer", inner);
        Status s = UnimportableClassifier.classifyParseFailure(pdf, wrapped);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    @Test
    void classify_GenericIOException_OnNormalSizedFile_IsParseFailed(@TempDir Path tmp) throws IOException {
        // Transient I/O failure on a normal-sized PDF — leave as PARSE_FAILED
        // so the file is retried on the next run.
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Status s = UnimportableClassifier.classifyParseFailure(pdf,
                new IOException("read timed out"));
        assertEquals(Status.PARSE_FAILED, s);
    }

    @Test
    void classify_ZeroRaceSuccess_IsUnimportable(@TempDir Path tmp) throws IOException {
        Path pdf = writeFile(tmp.resolve("normal.pdf"),
                "%PDF-1.4\n" + repeat("x", 50_000));
        Status s = UnimportableClassifier.classifyZeroRaceSuccess(pdf);
        assertEquals(Status.UNIMPORTABLE, s);
    }

    // --- Local exception fakes ---
    // We don't take a hard dependency on chart-parser's exception types;
    // the classifier matches by simple class name. These local fakes have
    // the same simple names and the same parent (Exception) so they
    // exercise the classifier exactly the way real chart-parser exceptions
    // would.

    private static class MalformedRaceException extends Exception {
        MalformedRaceException(String m) { super(m); }
    }
    private static class NoRaceDistanceFound extends Exception {
        NoRaceDistanceFound(String m) { super(m); }
    }
    private static class MissingHorseJockeyException extends Exception {
        MissingHorseJockeyException(String m) { super(m); }
    }
    private static class RaceTypeNameOrBreedNotIdentifiable extends Exception {
        RaceTypeNameOrBreedNotIdentifiable(String m) { super(m); }
    }

    // --- Test helpers ---

    private static Path writeFile(Path target, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(content.getBytes());
        }
        return target;
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }
}
