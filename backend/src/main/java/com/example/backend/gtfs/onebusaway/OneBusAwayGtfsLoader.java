package com.example.backend.gtfs.onebusaway;

import com.example.backend.gtfs.GtfsLoadException;
import com.example.backend.gtfs.GtfsLoader;
import com.example.backend.model.FeedScopedId;
import com.example.backend.model.GtfsFeed;
import com.example.backend.model.Route;
import com.example.backend.model.ServiceCalendar;
import com.example.backend.model.ServiceCalendarDate;
import com.example.backend.model.Stop;
import com.example.backend.model.StopTime;
import com.example.backend.model.Trip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.AgencyAndId;
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
            throw new GtfsLoadException("GTFS source does not exist: " + source);
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
            throw new GtfsLoadException(
                "Failed to load GTFS source " + source + ": " + rootMessage(exception),
                exception
            );
        }
    }

    private static GtfsFeed mapAndValidate(GtfsRelationalDao dao, String feedId, Path source) {
        ZoneId agencyZone = agencyZone(dao, source);

        List<Stop> stops = dao.getAllStops().stream()
            .map(stop -> new Stop(
                scoped(feedId, stop.getId()),
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
                scoped(feedId, route.getId()),
                route.getShortName(),
                route.getLongName(),
                route.getType()
            ))
            .sorted(Comparator.comparing(Route::id))
            .toList();

        List<Trip> trips = dao.getAllTrips().stream()
            .map(trip -> {
                if (trip.getRoute() == null) {
                    throw invalidReference("trip", raw(trip.getId()), "route");
                }
                if (trip.getServiceId() == null) {
                    throw invalidReference("trip", raw(trip.getId()), "service");
                }
                return new Trip(
                    scoped(feedId, trip.getId()),
                    scoped(feedId, trip.getRoute().getId()),
                    scoped(feedId, trip.getServiceId()),
                    trip.getTripHeadsign(),
                    trip.getDirectionId()
                );
            })
            .sorted(Comparator.comparing(Trip::id))
            .toList();

        List<StopTime> stopTimes = dao.getAllStopTimes().stream()
            .map(stopTime -> {
                if (stopTime.getTrip() == null) {
                    throw new GtfsLoadException("stop_time references a missing required trip");
                }
                if (stopTime.getStop() == null || stopTime.getStop().getId() == null) {
                    throw new GtfsLoadException(
                        "stop_time for trip " + raw(stopTime.getTrip().getId())
                            + " references a missing required stop"
                    );
                }
                return new StopTime(
                    scoped(feedId, stopTime.getTrip().getId()),
                    new FeedScopedId(feedId, stopTime.getStop().getId().getId()),
                    stopTime.getStopSequence(),
                    stopTime.isArrivalTimeSet() ? stopTime.getArrivalTime() : null,
                    stopTime.isDepartureTimeSet() ? stopTime.getDepartureTime() : null
                );
            })
            .sorted(Comparator.comparing(StopTime::tripId).thenComparingInt(StopTime::stopSequence))
            .toList();

        List<ServiceCalendar> calendars = dao.getAllCalendars().stream()
            .map(calendar -> new ServiceCalendar(
                scoped(feedId, calendar.getServiceId()),
                activeDays(calendar),
                localDate(calendar.getStartDate()),
                localDate(calendar.getEndDate())
            ))
            .sorted(Comparator.comparing(ServiceCalendar::serviceId))
            .toList();

        List<ServiceCalendarDate> calendarDates = dao.getAllCalendarDates().stream()
            .map(calendarDate -> new ServiceCalendarDate(
                scoped(feedId, calendarDate.getServiceId()),
                localDate(calendarDate.getDate()),
                switch (calendarDate.getExceptionType()) {
                    case org.onebusaway.gtfs.model.ServiceCalendarDate.EXCEPTION_TYPE_ADD ->
                        ServiceCalendarDate.ExceptionType.ADDED;
                    case org.onebusaway.gtfs.model.ServiceCalendarDate.EXCEPTION_TYPE_REMOVE ->
                        ServiceCalendarDate.ExceptionType.REMOVED;
                    default -> throw new GtfsLoadException(
                        "Unsupported calendar exception type " + calendarDate.getExceptionType()
                    );
                }
            ))
            .sorted(Comparator.comparing(ServiceCalendarDate::date)
                .thenComparing(ServiceCalendarDate::serviceId))
            .toList();

        validateRelationships(stops, routes, trips, stopTimes, calendars, calendarDates);
        return new GtfsFeed(feedId, agencyZone, stops, routes, trips, stopTimes, calendars, calendarDates);
    }

    private static void validateRelationships(
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
                throw invalidReference("stop", stop.id().toString(), "parent stop " + stop.parentStationId());
            }
        }
        for (Trip trip : trips) {
            if (!routeIds.contains(trip.routeId())) {
                throw invalidReference("trip", trip.id().toString(), "route " + trip.routeId());
            }
            if (!serviceIds.contains(trip.serviceId())) {
                throw invalidReference("trip", trip.id().toString(), "service " + trip.serviceId());
            }
        }
        for (StopTime stopTime : stopTimes) {
            if (!tripIds.contains(stopTime.tripId())) {
                throw invalidReference("stop_time", stopTime.tripId().toString(), "trip");
            }
            if (!stopIds.contains(stopTime.stopId())) {
                throw invalidReference("stop_time", stopTime.tripId().toString(), "stop " + stopTime.stopId());
            }
        }
    }

    private static Set<FeedScopedId> ids(Collection<FeedScopedId> values) {
        return new HashSet<>(values);
    }

    private static ZoneId agencyZone(GtfsRelationalDao dao, Path source) {
        Set<String> timezones = new HashSet<>();
        dao.getAllAgencies().forEach(agency -> timezones.add(agency.getTimezone()));
        if (timezones.size() != 1 || timezones.contains(null) || timezones.contains("")) {
            throw new GtfsLoadException(
                "GTFS source " + source + " must declare exactly one agency timezone; found " + timezones
            );
        }
        try {
            return ZoneId.of(timezones.iterator().next());
        } catch (ZoneRulesException exception) {
            throw new GtfsLoadException("Invalid agency timezone in " + source + ": " + timezones, exception);
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

    private static FeedScopedId scoped(String feedId, AgencyAndId id) {
        if (id == null) {
            throw new GtfsLoadException("GTFS entity is missing a required ID");
        }
        return new FeedScopedId(feedId, id.getId());
    }

    private static FeedScopedId nullableScoped(String feedId, String id) {
        return id == null || id.isBlank() ? null : new FeedScopedId(feedId, id);
    }

    private static String raw(AgencyAndId id) {
        return id == null ? "<missing>" : id.getId();
    }

    private static GtfsLoadException invalidReference(String entity, String id, String target) {
        return new GtfsLoadException(entity + " " + id + " references missing required " + target);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
