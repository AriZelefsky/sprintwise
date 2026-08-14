package com.sprintwise.index;

import com.sprintwise.gtfs.calendar.ServiceCalendarResolver;
import com.sprintwise.gtfs.time.ServiceTime;
import com.sprintwise.gtfs.time.ServiceTimeResolver;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.Route;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Immutable, in-memory lookup structures for one GTFS feed.
 *
 * <p>Stage 1 does not define a separate trip-pattern model. The ordered stop
 * times and trips-serving-stop structures preserve the information needed to
 * derive patterns when the RAPTOR phase specifies its pattern representation.</p>
 */
public final class GtfsIndex {

    private static final Comparator<ScheduledDeparture> SCHEDULE_ORDER =
        Comparator.comparingInt(ScheduledDeparture::departureSeconds)
            .thenComparing(ScheduledDeparture::tripId)
            .thenComparingInt(ScheduledDeparture::stopSequence);

    private static final Comparator<TimetableDeparture> RESOLVED_ORDER =
        Comparator.comparing(TimetableDeparture::departureInstant)
            .thenComparing(TimetableDeparture::tripId)
            .thenComparingInt(TimetableDeparture::stopSequence)
            .thenComparing(departure -> departure.serviceTime().serviceDate());

    private final String feedId;
    private final NavigableMap<FeedScopedId, Stop> stopsById;
    private final NavigableMap<FeedScopedId, Route> routesById;
    private final NavigableMap<FeedScopedId, Trip> tripsById;
    private final Map<FeedScopedId, List<StopTime>> stopTimesByTrip;
    private final Map<FeedScopedId, List<Trip>> tripsByStop;
    private final Map<FeedScopedId, List<ScheduledDeparture>> departuresByStop;
    private final ServiceCalendarResolver calendarResolver;
    private final ServiceTimeResolver timeResolver;
    private final GtfsIndexStats stats;

    public GtfsIndex(GtfsFeed feed) {
        Objects.requireNonNull(feed, "feed");
        this.feedId = feed.feedId();
        this.stopsById = entitiesById(feed.stops(), Stop::id, "stop");
        this.routesById = entitiesById(feed.routes(), Route::id, "route");
        this.tripsById = entitiesById(feed.trips(), Trip::id, "trip");
        this.calendarResolver = new ServiceCalendarResolver(feed);
        this.timeResolver = ServiceTimeResolver.forFeed(feed);

        validateTripReferences();
        this.stopTimesByTrip = buildStopTimesByTrip(feed.stopTimes());
        this.tripsByStop = buildTripsByStop(feed.stopTimes());
        this.departuresByStop = buildDeparturesByStop(feed.stopTimes());
        this.stats = new GtfsIndexStats(
            stopsById.size(),
            routesById.size(),
            tripsById.size(),
            stopTimesByTrip.values().stream().mapToLong(List::size).sum(),
            departuresByStop.values().stream().mapToLong(List::size).sum()
        );
    }

    public String feedId() {
        return feedId;
    }

    public List<Stop> stops() {
        return List.copyOf(stopsById.values());
    }

    public List<Route> routes() {
        return List.copyOf(routesById.values());
    }

    public List<Trip> trips() {
        return List.copyOf(tripsById.values());
    }

    public Optional<Stop> stop(FeedScopedId stopId) {
        return Optional.ofNullable(stopsById.get(stopId));
    }

    public Optional<Route> route(FeedScopedId routeId) {
        return Optional.ofNullable(routesById.get(routeId));
    }

    public Optional<Trip> trip(FeedScopedId tripId) {
        return Optional.ofNullable(tripsById.get(tripId));
    }

    /** Unknown trip IDs have no stop times and return an immutable empty list. */
    public List<StopTime> stopTimesForTrip(FeedScopedId tripId) {
        return stopTimesByTrip.getOrDefault(tripId, List.of());
    }

    /** Unknown stop IDs have no serving trips and return an immutable empty list. */
    public List<Trip> tripsServingStop(FeedScopedId stopId) {
        return tripsByStop.getOrDefault(stopId, List.of());
    }

    /** Primarily useful for schedule inspection; the result is sorted and immutable. */
    public List<ScheduledDeparture> scheduledDeparturesAtStop(FeedScopedId stopId) {
        return departuresByStop.getOrDefault(stopId, List.of());
    }

    /**
     * Finds the next departures at a stop. Unknown stops return an empty list.
     * The per-stop schedule is reached directly, binary-searched by GTFS seconds,
     * and evaluated for every service date capable of contributing a departure,
     * based on the feed's maximum GTFS time.
     */
    public List<TimetableDeparture> nextDepartures(
        FeedScopedId stopId,
        Instant queryInstant,
        int limit
    ) {
        Objects.requireNonNull(stopId, "stopId");
        Objects.requireNonNull(queryInstant, "queryInstant");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        if (limit == 0) {
            return List.of();
        }

        List<ScheduledDeparture> schedule = departuresByStop.get(stopId);
        if (schedule == null || schedule.isEmpty()) {
            return List.of();
        }

        int initialCapacity = (int) Math.min((long) limit * 2, schedule.size());
        var resolved = new ArrayList<TimetableDeparture>(initialCapacity);
        for (LocalDate serviceDate : timeResolver.serviceDateCandidates(queryInstant)) {
            addCandidateDepartures(schedule, serviceDate, queryInstant, limit, resolved);
        }

        resolved.sort(RESOLVED_ORDER);
        return List.copyOf(resolved.subList(0, Math.min(limit, resolved.size())));
    }

    public GtfsIndexStats stats() {
        return stats;
    }

    public Set<FeedScopedId> activeServiceIds(LocalDate serviceDate) {
        return calendarResolver.activeServiceIds(serviceDate);
    }

    private void addCandidateDepartures(
        List<ScheduledDeparture> schedule,
        LocalDate serviceDate,
        Instant queryInstant,
        int limit,
        List<TimetableDeparture> target
    ) {
        Set<FeedScopedId> activeServices = calendarResolver.activeServiceIds(serviceDate);
        if (activeServices.isEmpty()) {
            return;
        }

        long threshold = secondsSinceServiceDayStart(serviceDate, queryInstant);
        if (threshold > Integer.MAX_VALUE) {
            return;
        }
        int startIndex = lowerBound(schedule, Math.max(0, threshold));
        int added = 0;
        for (int index = startIndex; index < schedule.size() && added < limit; index++) {
            ScheduledDeparture departure = schedule.get(index);
            if (!activeServices.contains(departure.serviceId())) {
                continue;
            }

            var serviceTime = new ServiceTime(serviceDate, departure.departureSeconds());
            Instant departureInstant = timeResolver.toInstant(serviceTime);
            if (departureInstant.isBefore(queryInstant)) {
                continue;
            }

            target.add(new TimetableDeparture(
                departure.stopId(),
                departure.tripId(),
                departure.routeId(),
                departure.serviceId(),
                departure.stopSequence(),
                serviceTime,
                departureInstant
            ));
            added++;
        }
    }

    private long secondsSinceServiceDayStart(LocalDate serviceDate, Instant queryInstant) {
        Instant serviceDayStart = timeResolver.toInstant(new ServiceTime(serviceDate, 0));
        return Duration.between(serviceDayStart, queryInstant).getSeconds();
    }

    private static int lowerBound(List<ScheduledDeparture> departures, long departureSeconds) {
        int low = 0;
        int high = departures.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (departures.get(middle).departureSeconds() < departureSeconds) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private void validateTripReferences() {
        for (Trip trip : tripsById.values()) {
            requireNamespace(trip.id());
            requireNamespace(trip.routeId());
            requireNamespace(trip.serviceId());
            if (!routesById.containsKey(trip.routeId())) {
                throw new IllegalArgumentException(
                    "Trip " + trip.id() + " references unknown route " + trip.routeId()
                );
            }
        }
    }

    private Map<FeedScopedId, List<StopTime>> buildStopTimesByTrip(List<StopTime> stopTimes) {
        var mutable = new HashMap<FeedScopedId, List<StopTime>>();
        for (StopTime stopTime : stopTimes) {
            validateStopTimeReferences(stopTime);
            mutable.computeIfAbsent(stopTime.tripId(), ignored -> new ArrayList<>()).add(stopTime);
        }

        var result = new HashMap<FeedScopedId, List<StopTime>>();
        mutable.forEach((tripId, values) -> {
            values.sort(Comparator.comparingInt(StopTime::stopSequence));
            rejectDuplicateSequences(tripId, values);
            result.put(tripId, List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private Map<FeedScopedId, List<Trip>> buildTripsByStop(List<StopTime> stopTimes) {
        var tripIdsByStop = new HashMap<FeedScopedId, LinkedHashSet<FeedScopedId>>();
        for (StopTime stopTime : stopTimes) {
            tripIdsByStop.computeIfAbsent(stopTime.stopId(), ignored -> new LinkedHashSet<>())
                .add(stopTime.tripId());
        }

        var result = new HashMap<FeedScopedId, List<Trip>>();
        tripIdsByStop.forEach((stopId, tripIds) -> result.put(
            stopId,
            tripIds.stream().sorted().map(tripsById::get).toList()
        ));
        return Map.copyOf(result);
    }

    private Map<FeedScopedId, List<ScheduledDeparture>> buildDeparturesByStop(
        List<StopTime> stopTimes
    ) {
        var mutable = new HashMap<FeedScopedId, List<ScheduledDeparture>>();
        for (StopTime stopTime : stopTimes) {
            if (stopTime.departureSeconds() == null) {
                continue;
            }
            Trip trip = tripsById.get(stopTime.tripId());
            mutable.computeIfAbsent(stopTime.stopId(), ignored -> new ArrayList<>())
                .add(new ScheduledDeparture(
                    stopTime.stopId(),
                    trip.id(),
                    trip.routeId(),
                    trip.serviceId(),
                    stopTime.stopSequence(),
                    stopTime.departureSeconds()
                ));
        }

        var result = new HashMap<FeedScopedId, List<ScheduledDeparture>>();
        mutable.forEach((stopId, values) -> {
            values.sort(SCHEDULE_ORDER);
            result.put(stopId, List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private void validateStopTimeReferences(StopTime stopTime) {
        Objects.requireNonNull(stopTime, "stopTime");
        requireNamespace(stopTime.tripId());
        requireNamespace(stopTime.stopId());
        if (!tripsById.containsKey(stopTime.tripId())) {
            throw new IllegalArgumentException(
                "Stop time references unknown trip " + stopTime.tripId()
            );
        }
        if (!stopsById.containsKey(stopTime.stopId())) {
            throw new IllegalArgumentException(
                "Stop time for " + stopTime.tripId() + " references unknown stop " + stopTime.stopId()
            );
        }
    }

    private void requireNamespace(FeedScopedId id) {
        Objects.requireNonNull(id, "feed-scoped ID");
        if (!feedId.equals(id.feedId())) {
            throw new IllegalArgumentException(
                "ID " + id + " is outside feed namespace " + feedId
            );
        }
    }

    private static void rejectDuplicateSequences(FeedScopedId tripId, List<StopTime> stopTimes) {
        for (int index = 1; index < stopTimes.size(); index++) {
            if (stopTimes.get(index - 1).stopSequence() == stopTimes.get(index).stopSequence()) {
                throw new IllegalArgumentException(
                    "Trip " + tripId + " has duplicate stop sequence "
                        + stopTimes.get(index).stopSequence()
                );
            }
        }
    }

    private <T> NavigableMap<FeedScopedId, T> entitiesById(
        Collection<T> entities,
        Function<T, FeedScopedId> idFunction,
        String entityName
    ) {
        var result = new TreeMap<FeedScopedId, T>();
        for (T entity : entities) {
            Objects.requireNonNull(entity, entityName);
            FeedScopedId id = idFunction.apply(entity);
            requireNamespace(id);
            if (result.putIfAbsent(id, entity) != null) {
                throw new IllegalArgumentException("Duplicate " + entityName + " ID " + id);
            }
        }
        return Collections.unmodifiableNavigableMap(result);
    }
}
