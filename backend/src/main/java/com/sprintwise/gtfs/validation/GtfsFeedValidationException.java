package com.sprintwise.gtfs.validation;

import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import java.util.Objects;

/** Parser-neutral description of one fatal violation in a SprintWise GTFS feed. */
public final class GtfsFeedValidationException extends IllegalArgumentException {

    private final GtfsDiagnosticCode code;
    private final String sourceFile;
    private final String entityType;
    private final String entityId;
    private final String field;
    private final String referencedId;
    private final String detail;

    GtfsFeedValidationException(
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        super(format(code, sourceFile, entityType, entityId, field, referencedId, detail));
        this.code = Objects.requireNonNull(code, "code");
        this.sourceFile = context(sourceFile);
        this.entityType = context(entityType);
        this.entityId = context(entityId);
        this.field = context(field);
        this.referencedId = context(referencedId);
        this.detail = requireText(detail, "detail");
    }

    public GtfsDiagnosticCode code() {
        return code;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public String entityType() {
        return entityType;
    }

    public String entityId() {
        return entityId;
    }

    public String field() {
        return field;
    }

    public String referencedId() {
        return referencedId;
    }

    public String detail() {
        return detail;
    }

    private static String format(
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        return "GTFS %s [file=%s, entity=%s:%s, field=%s, referencedId=%s]: %s"
            .formatted(
                Objects.requireNonNull(code, "code").wireValue(),
                context(sourceFile),
                context(entityType),
                context(entityId),
                context(field),
                context(referencedId),
                requireText(detail, "detail")
            );
    }

    private static String context(String value) {
        return value == null || value.isBlank() ? UNSPECIFIED : value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
