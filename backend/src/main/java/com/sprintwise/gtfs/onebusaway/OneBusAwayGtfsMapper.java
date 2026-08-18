package com.sprintwise.gtfs.onebusaway;

import static com.sprintwise.gtfs.GtfsImportDiagnostic.UNSPECIFIED;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.Route;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.model.ServiceCalendarDate;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.services.GtfsRelationalDao;

/** Copies parser-owned OneBusAway entities into immutable SprintWise models. */
final class OneBusAwayGtfsMapper {

    GtfsFeed map(GtfsRelationalDao dao, String feedId, Path source) {
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
                    throw OneBusAwayImportDiagnostics.missingReference(
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
                    throw OneBusAwayImportDiagnostics.missingReference(
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
                    throw OneBusAwayImportDiagnostics.missingReference(
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
                    throw OneBusAwayImportDiagnostics.missingReference(
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
                    default -> throw OneBusAwayImportDiagnostics.failure(
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

        return new GtfsFeed(
            feedId,
            agencyZone,
            stops,
            routes,
            trips,
            stopTimes,
            calendars,
            calendarDates
        );
    }

    private static PickupDropOffType pickupDropOffType(
        Path source,
        String feedId,
        String entityId,
        String field,
        int value
    ) {
        return PickupDropOffType.fromGtfsValue(value).orElseThrow(() ->
            OneBusAwayImportDiagnostics.failure(
                source,
                feedId,
                GtfsDiagnosticCode.INVALID_PICKUP_DROP_OFF_TYPE,
                "stop_times.txt",
                "stop_time",
                entityId,
                field,
                Integer.toString(value),
                "GTFS " + field + " must be one of 0, 1, 2, or 3; found " + value
            )
        );
    }

    private static ZoneId agencyZone(GtfsRelationalDao dao, Path source, String feedId) {
        Set<String> timezones = new HashSet<>();
        dao.getAllAgencies().forEach(agency -> timezones.add(agency.getTimezone()));
        if (timezones.size() != 1 || timezones.contains(null) || timezones.contains("")) {
            throw OneBusAwayImportDiagnostics.failure(
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
            throw OneBusAwayImportDiagnostics.failure(
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

    private static EnumSet<DayOfWeek> activeDays(
        org.onebusaway.gtfs.model.ServiceCalendar calendar
    ) {
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
            throw OneBusAwayImportDiagnostics.failure(
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

    private static String stopTimeEntityId(org.onebusaway.gtfs.model.StopTime stopTime) {
        String tripId = stopTime.getTrip() == null ? UNSPECIFIED : raw(stopTime.getTrip().getId());
        return "trip_id=" + tripId + ",stop_sequence=" + stopTime.getStopSequence();
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
}
