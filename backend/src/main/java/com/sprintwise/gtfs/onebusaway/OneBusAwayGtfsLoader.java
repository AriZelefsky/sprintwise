package com.sprintwise.gtfs.onebusaway;

import static com.sprintwise.gtfs.GtfsDiagnosticSeverity.FATAL;
import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.gtfs.GtfsImportDiagnostic;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.Route;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.model.ServiceCalendarDate;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.onebusaway.csv_entities.exceptions.CsvEntityIOException;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.serialization.EntityReferenceNotFoundException;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.onebusaway.gtfs.services.GtfsRelationalDao;

/**
 * The only production adapter allowed to expose OneBusAway types. All values are
 * copied into SprintWise-owned immutable records before returning.
 */
public final class OneBusAwayGtfsLoader implements GtfsLoader {

    @Override
    public GtfsFeed load(Path source, String feedId) {
        Objects.requireNonNull(source, "source");
        var namespaceProbe = new FeedScopedId(feedId, "validation");
        String namespace = namespaceProbe.feedId();

        if (!Files.exists(source)) {
            throw failure(
                source,
                namespace,
                GtfsDiagnosticCode.SOURCE_MISSING,
                UNSPECIFIED,
                "feed",
                namespace,
                "source",
                UNSPECIFIED,
                "GTFS source does not exist"
            );
        }

        var dao = new GtfsRelationalDaoImpl();
        var reader = new GtfsReader();
        try {
            reader.setInputLocation(source.toFile());
            reader.setEntityStore(dao);
            reader.setInternStrings(true);
            reader.setDefaultAgencyId(namespace);
            reader.run();
            return mapAndValidate(dao, namespace, source);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GtfsLoadException loadException) {
                throw loadException;
            }
            throw readFailure(source, namespace, exception);
        }
    }

    private static GtfsFeed mapAndValidate(GtfsRelationalDao dao, String feedId, Path source) {
        ZoneId agencyZone = agencyZone(dao, source, feedId);

        List<Stop> stops = dao.getAllStops().stream()
            .map(stop -> new Stop(
                scoped(feedId, stop.getId(), source, "stops.txt", "stop", raw(stop.getId()), "stop_id"),
                stop.getName(),
                stop.getLat(),
                stop.getLon(),
                stop.getLocationType(),
                nullableScoped(feedId, stop.getParentStation())
            ))
            .sorted(Comparator.comparing(Stop::id))
            .toList();

        List<Route> routes = dao.getAllRoutes().stream()
            .map(route -> new Route(
                scoped(feedId, route.getId(), source, "routes.txt", "route", raw(route.getId()), "route_id"),
                route.getShortName(),
                route.getLongName(),
                route.getType()
            ))
            .sorted(Comparator.comparing(Route::id))
            .toList();

        List<Trip> trips = dao.getAllTrips().stream()
            .map(trip -> {
                if (trip.getRoute() == null) {
                    throw invalidReference(
                        source,
                        feedId,
                        "trips.txt",
                        "trip",
                        raw(trip.getId()),
                        "route_id",
                        UNSPECIFIED
                    );
                }
                if (trip.getServiceId() == null) {
                    throw invalidReference(
                        source,
                        feedId,
                        "trips.txt",
                        "trip",
                        raw(trip.getId()),
                        "service_id",
                        UNSPECIFIED
                    );
                }
                return new Trip(
                    scoped(feedId, trip.getId(), source, "trips.txt", "trip", raw(trip.getId()), "trip_id"),
                    scoped(
                        feedId,
                        trip.getRoute().getId(),
                        source,
                        "trips.txt",
                        "trip",
                        raw(trip.getId()),
                        "route_id"
                    ),
                    scoped(
                        feedId,
                        trip.getServiceId(),
                        source,
                        "trips.txt",
                        "trip",
                        raw(trip.getId()),
                        "service_id"
                    ),
                    trip.getTripHeadsign(),
                    trip.getDirectionId()
                );
            })
            .sorted(Comparator.comparing(Trip::id))
            .toList();

        List<StopTime> stopTimes = dao.getAllStopTimes().stream()
            .map(stopTime -> {
                String entityId = stopTimeEntityId(stopTime);
                if (stopTime.getTrip() == null) {
                    throw invalidReference(
                        source,
                        feedId,
                        "stop_times.txt",
                        "stop_time",
                        entityId,
                        "trip_id",
                        UNSPECIFIED
                    );
                }
                if (stopTime.getStop() == null || stopTime.getStop().getId() == null) {
                    throw invalidReference(
                        source,
                        feedId,
                        "stop_times.txt",
                        "stop_time",
                        entityId,
                        "stop_id",
                        UNSPECIFIED
                    );
                }
                return new StopTime(
                    scoped(
                        feedId,
                        stopTime.getTrip().getId(),
                        source,
                        "stop_times.txt",
                        "stop_time",
                        entityId,
                        "trip_id"
                    ),
                    scoped(
                        feedId,
                        stopTime.getStop().getId(),
                        source,
                        "stop_times.txt",
                        "stop_time",
                        entityId,
                        "stop_id"
                    ),
                    stopTime.getStopSequence(),
                    stopTime.isArrivalTimeSet() ? stopTime.getArrivalTime() : null,
                    stopTime.isDepartureTimeSet() ? stopTime.getDepartureTime() : null,
                    pickupDropOffType(
                        source,
                        feedId,
                        entityId,
                        "pickup_type",
                        stopTime.getPickupType()
                    ),
                    pickupDropOffType(
                        source,
                        feedId,
                        entityId,
                        "drop_off_type",
                        stopTime.getDropOffType()
                    )
                );
            })
            .sorted(Comparator.comparing(StopTime::tripId).thenComparingInt(StopTime::stopSequence))
            .toList();

        List<ServiceCalendar> calendars = dao.getAllCalendars().stream()
            .map(calendar -> new ServiceCalendar(
                scoped(
                    feedId,
                    calendar.getServiceId(),
                    source,
                    "calendar.txt",
                    "service_calendar",
                    raw(calendar.getServiceId()),
                    "service_id"
                ),
                activeDays(calendar),
                localDate(calendar.getStartDate()),
                localDate(calendar.getEndDate())
            ))
            .sorted(Comparator.comparing(ServiceCalendar::serviceId))
            .toList();

        List<ServiceCalendarDate> calendarDates = dao.getAllCalendarDates().stream()
            .map(calendarDate -> new ServiceCalendarDate(
                scoped(
                    feedId,
                    calendarDate.getServiceId(),
                    source,
                    "calendar_dates.txt",
                    "service_calendar_date",
                    calendarDateEntityId(calendarDate),
                    "service_id"
                ),
                localDate(calendarDate.getDate()),
                switch (calendarDate.getExceptionType()) {
                    case org.onebusaway.gtfs.model.ServiceCalendarDate.EXCEPTION_TYPE_ADD ->
                        ServiceCalendarDate.ExceptionType.ADDED;
                    case org.onebusaway.gtfs.model.ServiceCalendarDate.EXCEPTION_TYPE_REMOVE ->
                        ServiceCalendarDate.ExceptionType.REMOVED;
                    default -> throw failure(
                        source,
                        feedId,
                        GtfsDiagnosticCode.UNSUPPORTED_CALENDAR_EXCEPTION,
                        "calendar_dates.txt",
                        "service_calendar_date",
                        calendarDateEntityId(calendarDate),
                        "exception_type",
                        UNSPECIFIED,
                        "Unsupported calendar exception type " + calendarDate.getExceptionType()
                    );
                }
            ))
            .sorted(Comparator.comparing(ServiceCalendarDate::date)
                .thenComparing(ServiceCalendarDate::serviceId))
            .toList();

        validateRelationships(source, feedId, stops, routes, trips, stopTimes, calendars, calendarDates);
        validateStopTimes(source, feedId, trips, stopTimes);
        return new GtfsFeed(feedId, agencyZone, stops, routes, trips, stopTimes, calendars, calendarDates);
    }

    private static PickupDropOffType pickupDropOffType(
        Path source,
        String feedId,
        String entityId,
        String field,
        int value
    ) {
        return PickupDropOffType.fromGtfsValue(value).orElseThrow(() -> failure(
            source,
            feedId,
            GtfsDiagnosticCode.INVALID_PICKUP_DROP_OFF_TYPE,
            "stop_times.txt",
            "stop_time",
            entityId,
            field,
            Integer.toString(value),
            "GTFS " + field + " must be one of 0, 1, 2, or 3; found " + value
        ));
    }

    /**
     * Validates the Stage 1 timetable contract. Each trip is one complete run,
     * not a collection of stop-to-stop legs. Its endpoints must be timed;
     * intermediate stops may omit both times (no interpolation is attempted),
     * but a lone arrival or departure is rejected as unsupported.
     */
    private static void validateStopTimes(
        Path source,
        String feedId,
        List<Trip> trips,
        List<StopTime> stopTimes
    ) {
        Map<FeedScopedId, List<StopTime>> byTrip = new TreeMap<>();
        for (StopTime stopTime : stopTimes) {
            byTrip.computeIfAbsent(stopTime.tripId(), ignored -> new ArrayList<>())
                .add(stopTime);
        }

        for (Trip trip : trips) {
            List<StopTime> tripStopTimes = byTrip.getOrDefault(trip.id(), List.of());
            if (tripStopTimes.size() < 2) {
                throw invalidStopTime(
                    source,
                    feedId,
                    "trip",
                    trip.id().id(),
                    "trip_id",
                    trip.id().id(),
                    "Trip must contain at least two stop times; found " + tripStopTimes.size()
                );
            }

            Integer previousSequence = null;
            Integer previousDeparture = null;
            for (int index = 0; index < tripStopTimes.size(); index++) {
                StopTime stopTime = tripStopTimes.get(index);
                String entityId = stopTimeEntityId(stopTime);
                if (stopTime.stopSequence() < 0) {
                    throw invalidStopTime(
                        source, feedId, "stop_time", entityId, "stop_sequence",
                        Integer.toString(stopTime.stopSequence()),
                        "stop_sequence must be a non-negative integer"
                    );
                }
                if (previousSequence != null && stopTime.stopSequence() <= previousSequence) {
                    throw invalidStopTime(
                        source, feedId, "stop_time", entityId, "stop_sequence",
                        Integer.toString(stopTime.stopSequence()),
                        "stop_sequence values must be unique and strictly increasing within a trip"
                    );
                }

                boolean arrivalSet = stopTime.arrivalSeconds() != null;
                boolean departureSet = stopTime.departureSeconds() != null;
                boolean endpoint = index == 0 || index == tripStopTimes.size() - 1;
                if (endpoint && (!arrivalSet || !departureSet)) {
                    throw invalidStopTime(
                        source, feedId, "stop_time", entityId,
                        !arrivalSet ? "arrival_time" : "departure_time", UNSPECIFIED,
                        "The first and last stop times of a trip require both arrival_time and departure_time"
                    );
                }
                if (arrivalSet != departureSet) {
                    throw invalidStopTime(
                        source, feedId, "stop_time", entityId,
                        !arrivalSet ? "arrival_time" : "departure_time", UNSPECIFIED,
                        "Intermediate stop times must provide both arrival_time and departure_time or neither"
                    );
                }
                if (arrivalSet) {
                    if (stopTime.departureSeconds() < stopTime.arrivalSeconds()) {
                        throw invalidStopTime(
                            source, feedId, "stop_time", entityId, "departure_time",
                            Integer.toString(stopTime.departureSeconds()),
                            "departure_time must not be earlier than arrival_time"
                        );
                    }
                    if (previousDeparture != null && stopTime.arrivalSeconds() < previousDeparture) {
                        throw invalidStopTime(
                            source, feedId, "stop_time", entityId, "arrival_time",
                            Integer.toString(stopTime.arrivalSeconds()),
                            "Known stop times must not move backward within a trip"
                        );
                    }
                    previousDeparture = stopTime.departureSeconds();
                }
                previousSequence = stopTime.stopSequence();
            }
        }
    }

    private static GtfsLoadException invalidStopTime(
        Path source,
        String feedId,
        String entityType,
        String entityId,
        String field,
        String value,
        String detail
    ) {
        return failure(
            source,
            feedId,
            GtfsDiagnosticCode.INVALID_STOP_TIME,
            "stop_times.txt",
            entityType,
            entityId,
            field,
            value,
            detail
        );
    }

    private static void validateRelationships(
        Path source,
        String feedId,
        List<Stop> stops,
        List<Route> routes,
        List<Trip> trips,
        List<StopTime> stopTimes,
        List<ServiceCalendar> calendars,
        List<ServiceCalendarDate> calendarDates
    ) {
        Set<FeedScopedId> stopIds = ids(stops.stream().map(Stop::id).toList());
        Set<FeedScopedId> routeIds = ids(routes.stream().map(Route::id).toList());
        Set<FeedScopedId> serviceIds = ids(calendars.stream().map(ServiceCalendar::serviceId).toList());
        serviceIds.addAll(calendarDates.stream().map(ServiceCalendarDate::serviceId).toList());
        Set<FeedScopedId> tripIds = ids(trips.stream().map(Trip::id).toList());

        for (Stop stop : stops) {
            if (stop.parentStationId() != null && !stopIds.contains(stop.parentStationId())) {
                throw invalidReference(
                    source,
                    feedId,
                    "stops.txt",
                    "stop",
                    stop.id().id(),
                    "parent_station",
                    stop.parentStationId().id()
                );
            }
        }
        for (Trip trip : trips) {
            if (!routeIds.contains(trip.routeId())) {
                throw invalidReference(
                    source,
                    feedId,
                    "trips.txt",
                    "trip",
                    trip.id().id(),
                    "route_id",
                    trip.routeId().id()
                );
            }
            if (!serviceIds.contains(trip.serviceId())) {
                throw invalidReference(
                    source,
                    feedId,
                    "trips.txt",
                    "trip",
                    trip.id().id(),
                    "service_id",
                    trip.serviceId().id()
                );
            }
        }
        for (StopTime stopTime : stopTimes) {
            String entityId = stopTimeEntityId(stopTime);
            if (!tripIds.contains(stopTime.tripId())) {
                throw invalidReference(
                    source,
                    feedId,
                    "stop_times.txt",
                    "stop_time",
                    entityId,
                    "trip_id",
                    stopTime.tripId().id()
                );
            }
            if (!stopIds.contains(stopTime.stopId())) {
                throw invalidReference(
                    source,
                    feedId,
                    "stop_times.txt",
                    "stop_time",
                    entityId,
                    "stop_id",
                    stopTime.stopId().id()
                );
            }
        }
    }

    private static Set<FeedScopedId> ids(Collection<FeedScopedId> values) {
        return new HashSet<>(values);
    }

    private static ZoneId agencyZone(GtfsRelationalDao dao, Path source, String feedId) {
        Set<String> timezones = new HashSet<>();
        dao.getAllAgencies().forEach(agency -> timezones.add(agency.getTimezone()));
        if (timezones.size() != 1 || timezones.contains(null) || timezones.contains("")) {
            throw failure(
                source,
                feedId,
                GtfsDiagnosticCode.AMBIGUOUS_AGENCY_TIMEZONE,
                "agency.txt",
                "agency",
                agencyEntityId(dao),
                "agency_timezone",
                UNSPECIFIED,
                "Feed must declare exactly one nonblank agency timezone; found " + timezones
            );
        }
        try {
            return ZoneId.of(timezones.iterator().next());
        } catch (ZoneRulesException exception) {
            throw failure(
                source,
                feedId,
                GtfsDiagnosticCode.INVALID_AGENCY_TIMEZONE,
                "agency.txt",
                "agency",
                agencyEntityId(dao),
                "agency_timezone",
                UNSPECIFIED,
                "Invalid agency timezone " + timezones.iterator().next(),
                exception
            );
        }
    }

    private static EnumSet<DayOfWeek> activeDays(org.onebusaway.gtfs.model.ServiceCalendar calendar) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (calendar.getMonday() == 1) days.add(DayOfWeek.MONDAY);
        if (calendar.getTuesday() == 1) days.add(DayOfWeek.TUESDAY);
        if (calendar.getWednesday() == 1) days.add(DayOfWeek.WEDNESDAY);
        if (calendar.getThursday() == 1) days.add(DayOfWeek.THURSDAY);
        if (calendar.getFriday() == 1) days.add(DayOfWeek.FRIDAY);
        if (calendar.getSaturday() == 1) days.add(DayOfWeek.SATURDAY);
        if (calendar.getSunday() == 1) days.add(DayOfWeek.SUNDAY);
        return days;
    }

    private static LocalDate localDate(org.onebusaway.gtfs.model.calendar.ServiceDate date) {
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
    }

    private static FeedScopedId scoped(
        String feedId,
        AgencyAndId id,
        Path source,
        String sourceFile,
        String entityType,
        String entityId,
        String field
    ) {
        if (id == null || id.getId() == null || id.getId().isBlank()) {
            throw failure(
                source,
                feedId,
                GtfsDiagnosticCode.MISSING_REQUIRED_ID,
                sourceFile,
                entityType,
                entityId,
                field,
                UNSPECIFIED,
                "GTFS entity is missing required field " + field
            );
        }
        return new FeedScopedId(feedId, id.getId());
    }

    private static FeedScopedId nullableScoped(String feedId, String id) {
        return id == null || id.isBlank() ? null : new FeedScopedId(feedId, id);
    }

    private static String raw(AgencyAndId id) {
        return id == null || id.getId() == null || id.getId().isBlank()
            ? UNSPECIFIED
            : id.getId();
    }

    private static GtfsLoadException invalidReference(
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

    private static GtfsLoadException readFailure(
        Path source,
        String feedId,
        Throwable cause
    ) {
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

    private static GtfsLoadException failure(
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

    private static GtfsLoadException failure(
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

    private static String stopTimeEntityId(org.onebusaway.gtfs.model.StopTime stopTime) {
        String tripId = stopTime.getTrip() == null ? UNSPECIFIED : raw(stopTime.getTrip().getId());
        return "trip_id=" + tripId + ",stop_sequence=" + stopTime.getStopSequence();
    }

    private static String stopTimeEntityId(StopTime stopTime) {
        return "trip_id=" + stopTime.tripId().id() + ",stop_sequence=" + stopTime.stopSequence();
    }

    private static String calendarDateEntityId(
        org.onebusaway.gtfs.model.ServiceCalendarDate calendarDate
    ) {
        String date = calendarDate.getDate() == null
            ? UNSPECIFIED
            : localDate(calendarDate.getDate()).toString();
        return "service_id=" + raw(calendarDate.getServiceId()) + ",date=" + date;
    }

    private static String agencyEntityId(GtfsRelationalDao dao) {
        if (dao.getAllAgencies().size() != 1) {
            return UNSPECIFIED;
        }
        String agencyId = dao.getAllAgencies().iterator().next().getId();
        return agencyId == null || agencyId.isBlank() ? UNSPECIFIED : agencyId;
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
