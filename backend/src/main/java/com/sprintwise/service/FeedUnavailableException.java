package com.sprintwise.service;

import com.sprintwise.gtfs.GtfsImportDiagnostic;
import com.sprintwise.gtfs.GtfsLoadException;
import java.nio.file.Path;
import java.util.Optional;

public final class FeedUnavailableException extends RuntimeException {

    private final String feedId;
    private final Path source;
    private final GtfsImportDiagnostic diagnostic;

    FeedUnavailableException(String feedId, Path source, Throwable cause) {
        super("GTFS feed " + feedId + " is unavailable from " + source + ": " + cause.getMessage(), cause);
        this.feedId = feedId;
        this.source = source;
        this.diagnostic = cause instanceof GtfsLoadException loadException
            ? loadException.diagnostic()
            : null;
    }

    public String feedId() {
        return feedId;
    }

    public Path source() {
        return source;
    }

    public Optional<GtfsImportDiagnostic> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
}
