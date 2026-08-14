package com.sprintwise.gtfs;

import java.nio.file.Path;
import java.util.Objects;

/** Machine-readable context for one GTFS ingestion problem. */
public record GtfsImportDiagnostic(
    GtfsDiagnosticSeverity severity,
    GtfsDiagnosticCode code,
    String feedId,
    Path feedSource,
    String sourceFile,
    String entityType,
    String entityId,
    String field,
    String referencedId,
    String detail
) {
    public static final String UNSPECIFIED = "unspecified";

    public GtfsImportDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        feedId = requireText(feedId, "feedId");
        feedSource = Objects.requireNonNull(feedSource, "feedSource").toAbsolutePath().normalize();
        sourceFile = optionalContext(sourceFile);
        entityType = optionalContext(entityType);
        entityId = optionalContext(entityId);
        field = optionalContext(field);
        referencedId = optionalContext(referencedId);
        detail = requireText(detail, "detail");
    }

    /** Generates the readable exception/log message from the structured fields. */
    public String formatMessage() {
        return "%s GTFS %s for feed %s at %s [file=%s, entity=%s:%s, field=%s, referencedId=%s]: %s"
            .formatted(
                severity.wireValue(),
                code.wireValue(),
                feedId,
                feedSource,
                sourceFile,
                entityType,
                entityId,
                field,
                referencedId,
                detail
            );
    }

    private static String optionalContext(String value) {
        return value == null || value.isBlank() ? UNSPECIFIED : value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
