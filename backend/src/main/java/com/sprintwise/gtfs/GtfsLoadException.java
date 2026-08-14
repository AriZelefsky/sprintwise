package com.sprintwise.gtfs;

public final class GtfsLoadException extends RuntimeException {

    private final GtfsImportDiagnostic diagnostic;

    public GtfsLoadException(GtfsImportDiagnostic diagnostic) {
        super(diagnostic.formatMessage());
        this.diagnostic = diagnostic;
    }

    public GtfsLoadException(GtfsImportDiagnostic diagnostic, Throwable cause) {
        super(diagnostic.formatMessage(), cause);
        this.diagnostic = diagnostic;
    }

    public GtfsImportDiagnostic diagnostic() {
        return diagnostic;
    }
}
