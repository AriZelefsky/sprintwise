package com.example.backend.gtfs;

/** Stable machine-readable categories for fatal GTFS ingestion failures. */
public enum GtfsDiagnosticCode {
    SOURCE_MISSING("source_missing"),
    READ_FAILURE("read_failure"),
    MISSING_REQUIRED_ID("missing_required_id"),
    MISSING_REQUIRED_REFERENCE("missing_required_reference"),
    INVALID_AGENCY_TIMEZONE("invalid_agency_timezone"),
    AMBIGUOUS_AGENCY_TIMEZONE("ambiguous_agency_timezone"),
    UNSUPPORTED_CALENDAR_EXCEPTION("unsupported_calendar_exception");

    private final String wireValue;

    GtfsDiagnosticCode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
