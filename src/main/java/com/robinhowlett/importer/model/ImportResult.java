package com.robinhowlett.importer.model;

import java.nio.file.Path;

/**
 * The outcome of processing one PDF file through the full pipeline.
 */
public record ImportResult(Path pdf, Status status, int racesLoaded, Exception cause) {

    public enum Status {
        SUCCESS, PARSE_FAILED, WRITE_FAILED
    }

    public static ImportResult success(Path pdf, int racesLoaded) {
        return new ImportResult(pdf, Status.SUCCESS, racesLoaded, null);
    }

    public static ImportResult parseFailed(Path pdf, Exception cause) {
        return new ImportResult(pdf, Status.PARSE_FAILED, 0, cause);
    }

    public static ImportResult writeFailed(Path pdf, Exception cause) {
        return new ImportResult(pdf, Status.WRITE_FAILED, 0, cause);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
