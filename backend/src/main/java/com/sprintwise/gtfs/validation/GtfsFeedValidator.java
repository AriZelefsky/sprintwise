package com.sprintwise.gtfs.validation;

import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.Route;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.model.ServiceCalendarDate;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The single parser-neutral authority for SprintWise GTFS feed invariants. */
public final class GtfsFeedValidator {

    private GtfsFeedValidator() {}

    /** Validates a configured namespace without manufacturing an entity ID. */
    public static String requireValidFeedId(String feedId) {
        Objects.requireNonNull(feedId, "feedId");
        if (feedId.isBlank() || feedId.contains(":")) {
            throw new IllegalArgumentException(
                "feedId must be a nonblank namespace without a colon"
            );
        }
        return feedId;
    }

    public static void validate(GtfsFeed feed) {
        Objects.requireNonNull(feed, "feed");
        String feedId = requireValidFeedId(feed.feedId());
        Objects.requireNonNull(feed.agencyZoneId(), "agencyZoneId");

        Map<FeedScopedId, Stop> stops = stops(feed, feedId);
        Map<FeedScopedId, Route> routes = routes(feed, feedId);
        Set<FeedScopedId> serviceIds = services(feed, feedId);
        Map<FeedScopedId, Trip> trips = trips(feed, feedId);

        validateStopReferences(stops, feedId);
        validateTripReferences(trips, routes.keySet(), serviceIds, feedId);
        validateStopTimes(feed.stopTimes(), trips, stops.keySet(), feedId);
    }

    private static Map<FeedScopedId, Stop> stops(GtfsFeed feed, String feedId) {
        var result = new TreeMap<FeedScopedId, Stop>();
        for (Stop stop : feed.stops()) {
            if (stop == null) {
                throw missingId("stops.txt", "stop", UNSPECIFIED, "stop_id");
            }
            FeedScopedId id = requireEntityId(
                stop.id(), feedId, "stops.txt", "stop", UNSPECIFIED, "stop_id"
            );
            putUnique(result, id, stop, "stops.txt", "stop", "stop_id");
        }
        return result;
    }

    private static Map<FeedScopedId, Route> routes(GtfsFeed feed, String feedId) {
        var result = new TreeMap<FeedScopedId, Route>();
        for (Route route : feed.routes()) {
            if (route == null) {
                throw missingId("routes.txt", "route", UNSPECIFIED, "route_id");
            }
            FeedScopedId id = requireEntityId(
                route.id(), feedId, "routes.txt", "route", UNSPECIFIED, "route_id"
            );
            putUnique(result, id, route, "routes.txt", "route", "route_id");
        }
        return result;
    }

    private static Map<FeedScopedId, Trip> trips(GtfsFeed feed, String feedId) {
        var result = new TreeMap<FeedScopedId, Trip>();
        for (Trip trip : feed.trips()) {
            if (trip == null) {
                throw missingId("trips.txt", "trip", UNSPECIFIED, "trip_id");
            }
            FeedScopedId id = requireEntityId(
                trip.id(), feedId, "trips.txt", "trip", UNSPECIFIED, "trip_id"
            );
            putUnique(result, id, trip, "trips.txt", "trip", "trip_id");
        }
        return result;
    }

    private static Set<FeedScopedId> services(GtfsFeed feed, String feedId) {
        var calendars = new TreeMap<FeedScopedId, ServiceCalendar>();
        for (ServiceCalendar calendar : feed.serviceCalendars()) {
            if (calendar == null) {
                throw missingId(
                    "calendar.txt", "service_calendar", UNSPECIFIED, "service_id"
                );
            }
            FeedScopedId id = requireEntityId(
                calendar.serviceId(),
                feedId,
                "calendar.txt",
                "service_calendar",
                UNSPECIFIED,
                "service_id"
            );
            putUnique(
                calendars,
                id,
                calendar,
                "calendar.txt",
                "service_calendar",
                "service_id"
            );
            if (calendar.activeDays() == null
                || calendar.startDate() == null
                || calendar.endDate() == null
                || calendar.startDate().isAfter(calendar.endDate())) {
                throw invalid(
                    GtfsDiagnosticCode.INVALID_SERVICE_CALENDAR,
                    "calendar.txt",
                    "service_calendar",
                    id.id(),
                    "start_date/end_date",
                    UNSPECIFIED,
                    "Service calendar requires active days and a non-reversed date range"
                );
            }
        }

        var serviceIds = new java.util.HashSet<>(calendars.keySet());
        var exceptions = new TreeMap<String, ServiceCalendarDate>();
        for (ServiceCalendarDate exception : feed.serviceCalendarDates()) {
            if (exception == null) {
                throw missingId(
                    "calendar_dates.txt",
                    "service_calendar_date",
                    UNSPECIFIED,
                    "service_id"
                );
            }
            String entityId = calendarDateEntityId(exception);
            FeedScopedId serviceId = requireEntityId(
                exception.serviceId(),
                feedId,
                "calendar_dates.txt",
                "service_calendar_date",
                entityId,
                "service_id"
            );
            if (exception.date() == null) {
                throw invalid(
                    GtfsDiagnosticCode.INVALID_SERVICE_CALENDAR,
                    "calendar_dates.txt",
                    "service_calendar_date",
                    entityId,
                    "date",
                    UNSPECIFIED,
                    "Calendar-date exception requires a date"
                );
            }
            if (exception.exceptionType() == null) {
                throw invalid(
                    GtfsDiagnosticCode.UNSUPPORTED_CALENDAR_EXCEPTION,
                    "calendar_dates.txt",
                    "service_calendar_date",
                    entityId,
                    "exception_type",
                    UNSPECIFIED,
                    "Calendar-date exception requires a supported exception type"
                );
            }
            String key = serviceId + "@" + exception.date();
            if (exceptions.putIfAbsent(key, exception) != null) {
                throw duplicate(
                    "calendar_dates.txt",
                    "service_calendar_date",
                    entityId,
                    "service_id/date",
                    key
                );
            }
            serviceIds.add(serviceId);
        }
        return Set.copyOf(serviceIds);
    }

    private static void validateStopReferences(
        Map<FeedScopedId, Stop> stops,
        String feedId
    ) {
        for (Stop stop : stops.values()) {
            if (stop.parentStationId() == null) {
                continue;
            }
            FeedScopedId parentId = requireReferenceId(
                stop.parentStationId(),
                feedId,
                "stops.txt",
                "stop",
                stop.id().id(),
                "parent_station"
            );
            if (!stops.containsKey(parentId)) {
                throw missingReference(
                    "stops.txt",
                    "stop",
                    stop.id().id(),
                    "parent_station",
                    parentId.id()
                );
            }
        }
    }

    private static void validateTripReferences(
        Map<FeedScopedId, Trip> trips,
        Set<FeedScopedId> routeIds,
        Set<FeedScopedId> serviceIds,
        String feedId
    ) {
        for (Trip trip : trips.values()) {
            FeedScopedId routeId = requireReferenceId(
                trip.routeId(), feedId, "trips.txt", "trip", trip.id().id(), "route_id"
            );
            if (!routeIds.contains(routeId)) {
                throw missingReference(
                    "trips.txt", "trip", trip.id().id(), "route_id", routeId.id()
                );
            }
            FeedScopedId serviceId = requireReferenceId(
                trip.serviceId(), feedId, "trips.txt", "trip", trip.id().id(), "service_id"
            );
            if (!serviceIds.contains(serviceId)) {
                throw missingReference(
                    "trips.txt", "trip", trip.id().id(), "service_id", serviceId.id()
                );
            }
        }
    }

    private static void validateStopTimes(
        List<StopTime> stopTimes,
        Map<FeedScopedId, Trip> trips,
        Set<FeedScopedId> stopIds,
        String feedId
    ) {
        Map<FeedScopedId, List<StopTime>> byTrip = new TreeMap<>();
        for (StopTime stopTime : stopTimes) {
            if (stopTime == null) {
                throw missingReference(
                    "stop_times.txt",
                    "stop_time",
                    UNSPECIFIED,
                    "trip_id",
                    UNSPECIFIED
                );
            }
            String entityId = stopTimeEntityId(stopTime);
            FeedScopedId tripId = requireReferenceId(
                stopTime.tripId(),
                feedId,
                "stop_times.txt",
                "stop_time",
                entityId,
                "trip_id"
            );
            FeedScopedId stopId = requireReferenceId(
                stopTime.stopId(),
                feedId,
                "stop_times.txt",
                "stop_time",
                entityId,
                "stop_id"
            );
            if (!trips.containsKey(tripId)) {
                throw missingReference(
                    "stop_times.txt", "stop_time", entityId, "trip_id", tripId.id()
                );
            }
            if (!stopIds.contains(stopId)) {
                throw missingReference(
                    "stop_times.txt", "stop_time", entityId, "stop_id", stopId.id()
                );
            }
            if (stopTime.pickupType() == null || stopTime.dropOffType() == null) {
                throw invalid(
                    GtfsDiagnosticCode.INVALID_PICKUP_DROP_OFF_TYPE,
                    "stop_times.txt",
                    "stop_time",
                    entityId,
                    stopTime.pickupType() == null ? "pickup_type" : "drop_off_type",
                    UNSPECIFIED,
                    "Pickup and drop-off types are required"
                );
            }
            byTrip.computeIfAbsent(tripId, ignored -> new ArrayList<>()).add(stopTime);
        }

        for (Trip trip : trips.values()) {
            List<StopTime> tripStopTimes = new ArrayList<>(
                byTrip.getOrDefault(trip.id(), List.of())
            );
            tripStopTimes.sort(java.util.Comparator.comparingInt(StopTime::stopSequence));
            validateTripStopTimes(trip, tripStopTimes);
        }
    }

    private static void validateTripStopTimes(Trip trip, List<StopTime> stopTimes) {
        if (stopTimes.size() < 2) {
            throw invalidStopTime(
                "trip",
                trip.id().id(),
                "trip_id",
                trip.id().id(),
                "Trip must contain at least two stop times; found " + stopTimes.size()
            );
        }

        Integer previousSequence = null;
        Integer previousDeparture = null;
        for (int index = 0; index < stopTimes.size(); index++) {
            StopTime stopTime = stopTimes.get(index);
            String entityId = stopTimeEntityId(stopTime);
            if (stopTime.stopSequence() < 0) {
                throw invalidStopTime(
                    "stop_time",
                    entityId,
                    "stop_sequence",
                    Integer.toString(stopTime.stopSequence()),
                    "stop_sequence must be a non-negative integer"
                );
            }
            if (previousSequence != null && stopTime.stopSequence() <= previousSequence) {
                throw invalidStopTime(
                    "stop_time",
                    entityId,
                    "stop_sequence",
                    Integer.toString(stopTime.stopSequence()),
                    "stop_sequence values must be unique and strictly increasing within a trip"
                );
            }

            boolean arrivalSet = stopTime.arrivalSeconds() != null;
            boolean departureSet = stopTime.departureSeconds() != null;
            boolean endpoint = index == 0 || index == stopTimes.size() - 1;
            if (endpoint && (!arrivalSet || !departureSet)) {
                throw invalidStopTime(
                    "stop_time",
                    entityId,
                    !arrivalSet ? "arrival_time" : "departure_time",
                    UNSPECIFIED,
                    "The first and last stop times of a trip require both arrival_time and departure_time"
                );
            }
            if (arrivalSet != departureSet) {
                throw invalidStopTime(
                    "stop_time",
                    entityId,
                    !arrivalSet ? "arrival_time" : "departure_time",
                    UNSPECIFIED,
                    "Intermediate stop times must provide both arrival_time and departure_time or neither"
                );
            }
            if (arrivalSet) {
                if (stopTime.departureSeconds() < stopTime.arrivalSeconds()) {
                    throw invalidStopTime(
                        "stop_time",
                        entityId,
                        "departure_time",
                        Integer.toString(stopTime.departureSeconds()),
                        "departure_time must not be earlier than arrival_time"
                    );
                }
                if (previousDeparture != null && stopTime.arrivalSeconds() < previousDeparture) {
                    throw invalidStopTime(
                        "stop_time",
                        entityId,
                        "arrival_time",
                        Integer.toString(stopTime.arrivalSeconds()),
                        "Known stop times must not move backward within a trip"
                    );
                }
                previousDeparture = stopTime.departureSeconds();
            }
            previousSequence = stopTime.stopSequence();
        }
    }

    private static FeedScopedId requireEntityId(
        FeedScopedId id,
        String feedId,
        String sourceFile,
        String entityType,
        String entityId,
        String field
    ) {
        if (id == null) {
            throw missingId(sourceFile, entityType, entityId, field);
        }
        requireNamespace(id, feedId, sourceFile, entityType, id.id(), field);
        return id;
    }

    private static FeedScopedId requireReferenceId(
        FeedScopedId id,
        String feedId,
        String sourceFile,
        String entityType,
        String entityId,
        String field
    ) {
        if (id == null) {
            throw missingReference(sourceFile, entityType, entityId, field, UNSPECIFIED);
        }
        requireNamespace(id, feedId, sourceFile, entityType, entityId, field);
        return id;
    }

    private static void requireNamespace(
        FeedScopedId id,
        String feedId,
        String sourceFile,
        String entityType,
        String entityId,
        String field
    ) {
        if (!feedId.equals(id.feedId())) {
            throw invalid(
                GtfsDiagnosticCode.INVALID_FEED_NAMESPACE,
                sourceFile,
                entityType,
                entityId,
                field,
                id.toString(),
                "ID " + id + " is outside feed namespace " + feedId
            );
        }
    }

    private static <T> void putUnique(
        Map<FeedScopedId, T> target,
        FeedScopedId id,
        T entity,
        String sourceFile,
        String entityType,
        String field
    ) {
        if (target.putIfAbsent(id, entity) != null) {
            throw duplicate(sourceFile, entityType, id.id(), field, id.id());
        }
    }

    private static GtfsFeedValidationException missingId(
        String sourceFile,
        String entityType,
        String entityId,
        String field
    ) {
        return invalid(
            GtfsDiagnosticCode.MISSING_REQUIRED_ID,
            sourceFile,
            entityType,
            entityId,
            field,
            UNSPECIFIED,
            "GTFS entity is missing required field " + field
        );
    }

    private static GtfsFeedValidationException missingReference(
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId
    ) {
        return invalid(
            GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            "GTFS " + entityType + " references missing required " + field + " " + referencedId
        );
    }

    private static GtfsFeedValidationException duplicate(
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId
    ) {
        return invalid(
            GtfsDiagnosticCode.DUPLICATE_ENTITY_ID,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            "Duplicate " + entityType + " identity " + referencedId
        );
    }

    private static GtfsFeedValidationException invalidStopTime(
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        return invalid(
            GtfsDiagnosticCode.INVALID_STOP_TIME,
            "stop_times.txt",
            entityType,
            entityId,
            field,
            referencedId,
            detail
        );
    }

    private static GtfsFeedValidationException invalid(
        GtfsDiagnosticCode code,
        String sourceFile,
        String entityType,
        String entityId,
        String field,
        String referencedId,
        String detail
    ) {
        return new GtfsFeedValidationException(
            code,
            sourceFile,
            entityType,
            entityId,
            field,
            referencedId,
            detail
        );
    }

    private static String stopTimeEntityId(StopTime stopTime) {
        String tripId = stopTime.tripId() == null ? UNSPECIFIED : stopTime.tripId().id();
        return "trip_id=" + tripId + ",stop_sequence=" + stopTime.stopSequence();
    }

    private static String calendarDateEntityId(ServiceCalendarDate exception) {
        String serviceId = exception.serviceId() == null
            ? UNSPECIFIED
            : exception.serviceId().id();
        String date = exception.date() == null ? UNSPECIFIED : exception.date().toString();
        return "service_id=" + serviceId + ",date=" + date;
    }
}
