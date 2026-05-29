package com.robinhowlett.importer.pipeline;

import com.robinhowlett.importer.model.ImportResult.Status;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IMP-T5.3: classifies a PDF parse outcome as UNIMPORTABLE (don't retry) vs
 * PARSE_FAILED (transient — retry on next run).
 *
 * <p>Without this classifier every parse failure was recorded as PARSE_FAILED,
 * which {@link ImportTracker#alreadyProcessed} treats as "still-to-do" — the
 * 1,738 known-bad PDFs catalogued in {@code docs/zero-race-files.md} were
 * being parsed on every fresh re-run. They were first classified manually
 * via direct SQLite UPDATE; this classifier encodes that taxonomy in source
 * control so other hosts inherit it.
 *
 * <p>Categories returning UNIMPORTABLE (mirroring {@code docs/zero-race-files.md}):
 * <ul>
 *   <li>HtmlStub — tiny HTML error page saved with .pdf extension</li>
 *   <li>EmptyPdf — valid PDF container with 0 pages</li>
 *   <li>OldChartFormat — pre-1993 layout chart-parser doesn't support</li>
 *   <li>UnsupportedRaceFormat — QH futurity charts with no race distance</li>
 *   <li>UnparsableRunningLines — running line column has only "-"</li>
 *   <li>UnknownRaceType — non-standard format chart-parser can't identify</li>
 * </ul>
 *
 * <p>Anything else (or anything we can't confidently classify) stays
 * PARSE_FAILED so the file is retried on the next run — the safe direction
 * is "retry one extra time" rather than "permanently mark a recoverable
 * file unimportable."
 */
public final class UnimportableClassifier {

    /** Equibase HTML error pages saved with .pdf extension are this size. */
    private static final long[] HTML_STUB_SIZES = { 3253L, 8280L };

    /** EmptyPdf files (ELC 1996-1999) are roughly this size. */
    private static final long EMPTY_PDF_THRESHOLD = 600L;

    private UnimportableClassifier() {}

    /**
     * Classify a parse-stage failure. Inspects the exception type and the
     * raw file (size + magic bytes) to recognize known-unimportable shapes.
     */
    public static Status classifyParseFailure(Path pdf, Throwable cause) {
        // 1. HtmlStub: tiny file with HTML magic. Cheap to check; runs first.
        if (isHtmlStub(pdf)) {
            return Status.UNIMPORTABLE;
        }

        // 2. EmptyPdf: tiny file with PDF magic, 0 pages. PDFBox throws an
        //    IOException for these — caught upstream and wrapped.
        if (isLikelyEmptyPdf(pdf)) {
            return Status.UNIMPORTABLE;
        }

        // 3. Specific chart-parser exception types that mark a permanently
        //    unsupported chart shape. Class-name match keeps this code from
        //    needing a hard dependency on every chart-parser exception type.
        String name = exceptionTypeName(cause);
        if (name != null) {
            switch (name) {
                case "MalformedRaceException":              // OldChartFormat
                case "NoRaceDistanceFound":                 // UnsupportedRaceFormat
                case "MissingHorseJockeyException":         // UnparsableRunningLines
                case "RaceTypeNameOrBreedNotIdentifiable":  // UnknownRaceType
                    return Status.UNIMPORTABLE;
                default:
                    break;
            }
        }

        // 4. Default: not confidently classified. Leave as PARSE_FAILED so
        //    the file is retried on the next run.
        return Status.PARSE_FAILED;
    }

    /**
     * Classify a successful parse that produced zero races. The chart-parser
     * silently swallows per-race exceptions (continuing to the next race);
     * if every race fails the outer parse still returns Success with an
     * empty list. Such PDFs are unimportable in the same sense as a parse
     * exception — no race rows will ever be written.
     */
    public static Status classifyZeroRaceSuccess(Path pdf) {
        // Same upstream signals as a parse failure. Most zero-race outcomes
        // are OldChartFormat where MalformedRaceException fired for every
        // race — but we don't see that exception at this level, only the
        // empty result list. Treat zero-race success as UNIMPORTABLE
        // unconditionally; the file shape is wrong regardless of the
        // specific reason.
        return Status.UNIMPORTABLE;
    }

    private static boolean isHtmlStub(Path pdf) {
        try {
            long size = Files.size(pdf);
            for (long known : HTML_STUB_SIZES) {
                if (size == known) {
                    return true;
                }
            }
            // Also accept "small file with <html or <!doctype magic" — covers
            // size variations (server might return slightly different stubs).
            if (size > 0 && size < 16_384) {
                byte[] head = readHead(pdf, 64);
                String s = new String(head).trim().toLowerCase();
                return s.startsWith("<!doctype") || s.startsWith("<html");
            }
        } catch (Exception ignored) {
            // If we can't read the file size, fall through to other checks.
        }
        return false;
    }

    private static boolean isLikelyEmptyPdf(Path pdf) {
        try {
            long size = Files.size(pdf);
            if (size > EMPTY_PDF_THRESHOLD) {
                return false;
            }
            byte[] head = readHead(pdf, 5);
            // PDF magic is "%PDF-"
            return head.length >= 5
                    && head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                    && head[3] == 'F' && head[4] == '-';
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readHead(Path pdf, int maxBytes) {
        try (var in = Files.newInputStream(pdf)) {
            return in.readNBytes(maxBytes);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** Walk the cause chain, returning the simple name of the first match
     *  for one of the exception types we classify. */
    private static String exceptionTypeName(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            switch (n) {
                case "MalformedRaceException":
                case "NoRaceDistanceFound":
                case "MissingHorseJockeyException":
                case "RaceTypeNameOrBreedNotIdentifiable":
                    return n;
                default:
                    break;
            }
        }
        return null;
    }
}
