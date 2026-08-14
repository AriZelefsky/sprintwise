package com.sprintwise.gtfs;

/** Severity of a structured GTFS import diagnostic. Stage 1 fails fast on fatal issues. */
public enum GtfsDiagnosticSeverity {
    FATAL("fatal"),
    WARNING("warning");

    private final String wireValue;

    GtfsDiagnosticSeverity(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
