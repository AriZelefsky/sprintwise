package com.sprintwise.gtfs.onebusaway;

import static com.sprintwise.gtfs.GtfsDiagnosticSeverity.FATAL;
import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.gtfs.GtfsImportDiagnostic;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.validation.GtfsFeedValidationException;
import java.nio.file.Path;
import org.onebusaway.csv_entities.exceptions.CsvEntityIOException;
import org.onebusaway.gtfs.serialization.EntityReferenceNotFoundException;

/** Converts adapter, parser, and shared-validation failures into import diagnostics. */
final class OneBusAwayImportDiagnostics {

    private OneBusAwayImportDiagnostics() {}

    static GtfsLoadException validationFailure(
        Path source,
        String feedId,
        GtfsFeedValidationException cause
    ) {
        return failure(
            source,
            feedId,
            cause.code(),
            cause.sourceFile(),
            cause.entityType(),
            cause.entityId(),
            cause.field(),
            cause.referencedId(),
            cause.detail(),
            cause
        );
    }

    static GtfsLoadException missingReference(
        Path source,
        String feedId,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId
    ) {
        return failure(
            source,
            feedId,
            GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            "GTFS " + entityType + " references missing required " + field + " " + referencedId
        );
    }

    static GtfsLoadException readFailure(Path source, String feedId, Throwable cause) {
        CsvEntityIOException csvFailure = findCause(cause, CsvEntityIOException.class);
        EntityReferenceNotFoundException referenceFailure = findCause(
            cause,
            EntityReferenceNotFoundException.class
        );

        String sourceFile = csvFailure == null
            ? UNSPECIFIED
            : sourceFile(csvFailure.getPath());
        String entityType = csvFailure == null
            ? UNSPECIFIED
            : entityType(csvFailure.getEntityType());

        if (referenceFailure != null) {
            String field = referenceField(
                csvFailure == null ? null : csvFailure.getEntityType(),
                referenceFailure.getEntityType()
            );
            String referencedId = referencedId(referenceFailure);
            return failure(
                source,
                feedId,
                GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE,
                sourceFile,
                entityType,
                UNSPECIFIED,
                field,
                referencedId,
                "OneBusAway found a missing required " + field + " reference " + referencedId
                    + "; the referring entity ID is not exposed by OneBusAway",
                cause
            );
        }

        return failure(
            source,
            feedId,
            GtfsDiagnosticCode.READ_FAILURE,
            sourceFile,
            entityType,
            UNSPECIFIED,
            UNSPECIFIED,
            UNSPECIFIED,
            "OneBusAway could not read GTFS data: " + rootMessage(cause),
            cause
        );
    }

    static GtfsLoadException failure(
        Path source,
        String feedId,
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        return new GtfsLoadException(diagnostic(
            source,
            feedId,
            code,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            detail
        ));
    }

    static GtfsLoadException failure(
        Path source,
        String feedId,
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail,
        Throwable cause
    ) {
        return new GtfsLoadException(diagnostic(
            source,
            feedId,
            code,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            detail
        ), cause);
    }

    private static GtfsImportDiagnostic diagnostic(
        Path source,
        String feedId,
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        return new GtfsImportDiagnostic(
            FATAL,
            code,
            feedId,
            source,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            detail
        );
    }

    private static String sourceFile(String path) {
        if (path == null || path.isBlank()) {
            return UNSPECIFIED;
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static String entityType(Class<?> type) {
        if (type == null) {
            return UNSPECIFIED;
        }
        return switch (type.getSimpleName()) {
            case "Agency" -> "agency";
            case "Route" -> "route";
            case "Stop" -> "stop";
            case "Trip" -> "trip";
            case "StopTime" -> "stop_time";
            case "ServiceCalendar" -> "service_calendar";
            case "ServiceCalendarDate" -> "service_calendar_date";
            default -> type.getSimpleName();
        };
    }

    private static String referenceField(Class<?> referringType, Class<?> referencedType) {
        if (referringType == null || referencedType == null) {
            return UNSPECIFIED;
        }
        String referring = referringType.getSimpleName();
        String referenced = referencedType.getSimpleName();
        if ("Trip".equals(referring) && "Route".equals(referenced)) {
            return "route_id";
        }
        if ("Trip".equals(referring) && referenced.startsWith("ServiceCalendar")) {
            return "service_id";
        }
        if ("StopTime".equals(referring) && "Trip".equals(referenced)) {
            return "trip_id";
        }
        if ("StopTime".equals(referring) && "Stop".equals(referenced)) {
            return "stop_id";
        }
        return UNSPECIFIED;
    }

    /** OneBusAway exposes the missing ID only through this exception's stable message. */
    private static String referencedId(EntityReferenceNotFoundException exception) {
        String message = exception.getMessage();
        String marker = " id=";
        int markerIndex = message == null ? -1 : message.lastIndexOf(marker);
        return markerIndex < 0 ? UNSPECIFIED : message.substring(markerIndex + marker.length());
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
