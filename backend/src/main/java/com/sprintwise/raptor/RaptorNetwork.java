package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.Stop;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable compact timetable structures derived from all available Stage 1 feeds. */
public final class RaptorNetwork {

    private final NavigableMap<String, RaptorFeedContext> feedContexts;
    private final List<String> unavailableFeedIds;
    private final List<Stop> stops;
    private final Map<FeedScopedId, Integer> stopIndexesById;
    private final List<RaptorTripSchedule> trips;
    private final Map<FeedScopedId, Integer> tripIndexesById;
    private final List<RaptorTripPattern> patterns;
    private final int[][] patternIndexesByStop;
    private final RaptorNetworkStats stats;

    RaptorNetwork(
        NavigableMap<String, RaptorFeedContext> feedContexts,
        List<String> unavailableFeedIds,
        List<Stop> stops,
        Map<FeedScopedId, Integer> stopIndexesById,
        List<RaptorTripSchedule> trips,
        Map<FeedScopedId, Integer> tripIndexesById,
        List<RaptorTripPattern> patterns,
        int[][] patternIndexesByStop,
        RaptorNetworkStats stats
    ) {
        this.feedContexts = Collections.unmodifiableNavigableMap(new TreeMap<>(feedContexts));
        this.unavailableFeedIds = List.copyOf(unavailableFeedIds);
        this.stops = List.copyOf(stops);
        this.stopIndexesById = Map.copyOf(stopIndexesById);
        this.trips = List.copyOf(trips);
        this.tripIndexesById = Map.copyOf(tripIndexesById);
        this.patterns = List.copyOf(patterns);
        this.patternIndexesByStop = deepCopy(patternIndexesByStop);
        this.stats = stats;
    }

    public NavigableSet<String> feedIds() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(feedContexts.keySet()));
    }

    public List<String> unavailableFeedIds() {
        return unavailableFeedIds;
    }

    public Optional<RaptorFeedContext> feedContext(String feedId) {
        return Optional.ofNullable(feedContexts.get(feedId));
    }

    public List<Stop> stops() {
        return stops;
    }

    public OptionalInt stopIndex(FeedScopedId stopId) {
        Integer index = stopIndexesById.get(stopId);
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    public Stop stop(int stopIndex) {
        return stops.get(stopIndex);
    }

    public List<RaptorTripSchedule> trips() {
        return trips;
    }

    public OptionalInt tripIndex(FeedScopedId tripId) {
        Integer index = tripIndexesById.get(tripId);
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    public RaptorTripSchedule trip(int tripIndex) {
        return trips.get(tripIndex);
    }

    public List<RaptorTripPattern> patterns() {
        return patterns;
    }

    public RaptorTripPattern pattern(int patternIndex) {
        return patterns.get(patternIndex);
    }

    public List<Integer> patternIndexesForStop(int stopIndex) {
        return Arrays.stream(patternIndexesByStop[stopIndex]).boxed().toList();
    }

    public List<RaptorTripPattern> patternsForStop(FeedScopedId stopId) {
        OptionalInt stopIndex = stopIndex(stopId);
        if (stopIndex.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(patternIndexesByStop[stopIndex.getAsInt()])
            .mapToObj(patterns::get)
            .toList();
    }

    public RaptorNetworkStats stats() {
        return stats;
    }

    private static int[][] deepCopy(int[][] values) {
        int[][] result = new int[values.length][];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index].clone();
        }
        return result;
    }
}
