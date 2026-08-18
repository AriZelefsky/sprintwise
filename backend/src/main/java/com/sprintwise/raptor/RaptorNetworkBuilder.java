package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import com.sprintwise.service.TransitFeedCatalog;
import com.sprintwise.service.TransitFeedEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds one deterministic RAPTOR index from every available Stage 1 catalog entry. */
public final class RaptorNetworkBuilder {

    private static final Comparator<ScheduleDraft> TIMETABLE_ORDER =
        Comparator.comparingInt(ScheduleDraft::firstDepartureSeconds)
            .thenComparing(draft -> draft.trip().id());

    public RaptorNetwork build(TransitFeedCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");

        var contexts = new TreeMap<String, RaptorFeedContext>();
        var unavailableFeedIds = new ArrayList<String>();
        for (TransitFeedEntry entry : catalog.entries()) {
            if (!entry.isAvailable()) {
                unavailableFeedIds.add(entry.feedId());
                continue;
            }
            contexts.put(entry.feedId(), new RaptorFeedContext(
                entry.feedId(),
                entry.feed().agencyZoneId(),
                entry.feed(),
                entry.index()
            ));
        }

        var stopsById = new TreeMap<FeedScopedId, Stop>();
        var tripsById = new TreeMap<FeedScopedId, Trip>();
        for (RaptorFeedContext context : contexts.values()) {
            context.sourceIndex().stops().forEach(stop -> stopsById.put(stop.id(), stop));
            context.sourceIndex().trips().forEach(trip -> tripsById.put(trip.id(), trip));
        }

        List<Stop> stops = List.copyOf(stopsById.values());
        Map<FeedScopedId, Integer> stopIndexes = indexes(stops.stream().map(Stop::id).toList());
        List<Trip> stageOneTrips = List.copyOf(tripsById.values());
        Map<FeedScopedId, Integer> tripIndexes = indexes(
            stageOneTrips.stream().map(Trip::id).toList()
        );

        NavigableMap<PatternKey, List<ScheduleDraft>> draftsByPattern = new TreeMap<>();
        for (Trip trip : stageOneTrips) {
            RaptorFeedContext context = contexts.get(trip.id().feedId());
            List<StopTime> stopTimes = context.sourceIndex().stopTimesForTrip(trip.id());
            ScheduleDraft draft = scheduleDraft(tripIndexes.get(trip.id()), trip, stopTimes);
            draftsByPattern.computeIfAbsent(patternKey(trip, stopTimes), ignored -> new ArrayList<>())
                .add(draft);
        }

        var patterns = new ArrayList<RaptorTripPattern>();
        var schedulesByTripIndex = new RaptorTripSchedule[stageOneTrips.size()];
        int overtakingStructuralPatterns = 0;
        int additionalPatternsFromOvertaking = 0;
        long patternStopPositions = 0;

        for (Map.Entry<PatternKey, List<ScheduleDraft>> entry : draftsByPattern.entrySet()) {
            PatternKey key = entry.getKey();
            List<ScheduleDraft> sortedDrafts = entry.getValue().stream().sorted(TIMETABLE_ORDER).toList();
            List<List<ScheduleDraft>> timetableGroups = nonOvertakingGroups(sortedDrafts);
            if (timetableGroups.size() > 1) {
                overtakingStructuralPatterns++;
                additionalPatternsFromOvertaking += timetableGroups.size() - 1;
            }

            for (int groupIndex = 0; groupIndex < timetableGroups.size(); groupIndex++) {
                List<ScheduleDraft> timetableGroup = timetableGroups.get(groupIndex);
                int patternIndex = patterns.size();
                int[] patternStopIndexes = key.stopIds().stream()
                    .mapToInt(stopId -> stopIndexes.get(stopId))
                    .toArray();
                int[] patternTripIndexes = timetableGroup.stream()
                    .mapToInt(ScheduleDraft::tripIndex)
                    .toArray();
                patterns.add(new RaptorTripPattern(
                    patternIndex,
                    key.routeId(),
                    key.directionId(),
                    patternStopIndexes,
                    key.pickupTypes().toArray(PickupDropOffType[]::new),
                    key.dropOffTypes().toArray(PickupDropOffType[]::new),
                    patternTripIndexes,
                    groupIndex,
                    timetableGroups.size()
                ));
                patternStopPositions += patternStopIndexes.length;

                for (ScheduleDraft draft : timetableGroup) {
                    schedulesByTripIndex[draft.tripIndex()] = new RaptorTripSchedule(
                        draft.tripIndex(),
                        patternIndex,
                        draft.trip(),
                        draft.stopSequences(),
                        draft.arrivalSeconds(),
                        draft.departureSeconds()
                    );
                }
            }
        }

        int[][] patternIndexesByStop = patternsByStop(stops.size(), patterns);
        long tripStopPositions = Arrays.stream(schedulesByTripIndex)
            .mapToLong(RaptorTripSchedule::stopCount)
            .sum();
        var stats = new RaptorNetworkStats(
            contexts.size(),
            unavailableFeedIds.size(),
            stops.size(),
            schedulesByTripIndex.length,
            draftsByPattern.size(),
            patterns.size(),
            overtakingStructuralPatterns,
            additionalPatternsFromOvertaking,
            patternStopPositions,
            tripStopPositions
        );

        return new RaptorNetwork(
            contexts,
            unavailableFeedIds,
            stops,
            stopIndexes,
            List.of(schedulesByTripIndex),
            tripIndexes,
            patterns,
            patternIndexesByStop,
            stats
        );
    }

    private static PatternKey patternKey(Trip trip, List<StopTime> stopTimes) {
        return new PatternKey(
            trip.routeId(),
            trip.directionId(),
            stopTimes.stream().map(StopTime::stopId).toList(),
            stopTimes.stream().map(StopTime::pickupType).toList(),
            stopTimes.stream().map(StopTime::dropOffType).toList()
        );
    }

    private static ScheduleDraft scheduleDraft(
        int tripIndex,
        Trip trip,
        List<StopTime> stopTimes
    ) {
        int[] stopSequences = new int[stopTimes.size()];
        int[] arrivals = new int[stopTimes.size()];
        int[] departures = new int[stopTimes.size()];
        for (int index = 0; index < stopTimes.size(); index++) {
            StopTime stopTime = stopTimes.get(index);
            stopSequences[index] = stopTime.stopSequence();
            arrivals[index] = timeOrMissing(stopTime.arrivalSeconds());
            departures[index] = timeOrMissing(stopTime.departureSeconds());
        }
        return new ScheduleDraft(tripIndex, trip, stopSequences, arrivals, departures);
    }

    private static int timeOrMissing(Integer seconds) {
        return seconds == null ? RaptorTripSchedule.MISSING_TIME : seconds;
    }

    /** Deterministic greedy chain partition; every resulting timetable is non-overtaking. */
    private static List<List<ScheduleDraft>> nonOvertakingGroups(List<ScheduleDraft> schedules) {
        var groups = new ArrayList<List<ScheduleDraft>>();
        for (ScheduleDraft schedule : schedules) {
            List<ScheduleDraft> compatibleGroup = null;
            for (List<ScheduleDraft> group : groups) {
                if (doesNotOvertake(group.getLast(), schedule)) {
                    compatibleGroup = group;
                    break;
                }
            }
            if (compatibleGroup == null) {
                compatibleGroup = new ArrayList<>();
                groups.add(compatibleGroup);
            }
            compatibleGroup.add(schedule);
        }
        return groups.stream().map(List::copyOf).toList();
    }

    private static boolean doesNotOvertake(ScheduleDraft earlier, ScheduleDraft later) {
        for (int position = 0; position < earlier.arrivalSeconds().length; position++) {
            if (movesBefore(earlier.arrivalSeconds()[position], later.arrivalSeconds()[position])) {
                return false;
            }
            if (movesBefore(earlier.departureSeconds()[position], later.departureSeconds()[position])) {
                return false;
            }
        }
        return true;
    }

    private static boolean movesBefore(int earlier, int later) {
        return earlier != RaptorTripSchedule.MISSING_TIME
            && later != RaptorTripSchedule.MISSING_TIME
            && later < earlier;
    }

    private static int[][] patternsByStop(int stopCount, List<RaptorTripPattern> patterns) {
        var mutable = new ArrayList<TreeSet<Integer>>(stopCount);
        for (int stopIndex = 0; stopIndex < stopCount; stopIndex++) {
            mutable.add(new TreeSet<>());
        }
        for (RaptorTripPattern pattern : patterns) {
            for (int stopIndex : pattern.stopIndexes()) {
                mutable.get(stopIndex).add(pattern.index());
            }
        }
        int[][] result = new int[stopCount][];
        for (int stopIndex = 0; stopIndex < stopCount; stopIndex++) {
            result[stopIndex] = mutable.get(stopIndex).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    private static Map<FeedScopedId, Integer> indexes(List<FeedScopedId> ids) {
        var result = new HashMap<FeedScopedId, Integer>();
        for (int index = 0; index < ids.size(); index++) {
            result.put(ids.get(index), index);
        }
        return Map.copyOf(result);
    }

    private record ScheduleDraft(
        int tripIndex,
        Trip trip,
        int[] stopSequences,
        int[] arrivalSeconds,
        int[] departureSeconds
    ) {
        private int firstDepartureSeconds() {
            return departureSeconds[0];
        }
    }

    /** Route/direction are retained so branded services and future route policies stay distinct. */
    private record PatternKey(
        FeedScopedId routeId,
        String directionId,
        List<FeedScopedId> stopIds,
        List<PickupDropOffType> pickupTypes,
        List<PickupDropOffType> dropOffTypes
    ) implements Comparable<PatternKey> {

        private static final Comparator<String> DIRECTION_ORDER =
            Comparator.nullsFirst(Comparator.naturalOrder());

        private PatternKey {
            stopIds = List.copyOf(stopIds);
            pickupTypes = List.copyOf(pickupTypes);
            dropOffTypes = List.copyOf(dropOffTypes);
        }

        @Override
        public int compareTo(PatternKey other) {
            int comparison = routeId.compareTo(other.routeId);
            if (comparison != 0) {
                return comparison;
            }
            comparison = DIRECTION_ORDER.compare(directionId, other.directionId);
            if (comparison != 0) {
                return comparison;
            }
            comparison = compareLists(stopIds, other.stopIds, FeedScopedId::compareTo);
            if (comparison != 0) {
                return comparison;
            }
            comparison = compareLists(pickupTypes, other.pickupTypes, Enum::compareTo);
            return comparison != 0
                ? comparison
                : compareLists(dropOffTypes, other.dropOffTypes, Enum::compareTo);
        }

        private static <T> int compareLists(
            List<T> left,
            List<T> right,
            Comparator<T> comparator
        ) {
            int commonSize = Math.min(left.size(), right.size());
            for (int index = 0; index < commonSize; index++) {
                int comparison = comparator.compare(left.get(index), right.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(left.size(), right.size());
        }
    }
}
