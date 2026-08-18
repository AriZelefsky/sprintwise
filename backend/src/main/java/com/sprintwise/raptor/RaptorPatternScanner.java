package com.sprintwise.raptor;

import com.sprintwise.gtfs.time.ServiceTime;
import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Selects legal trip occurrences within one pattern and propagates their rides downstream.
 * This is a single-pattern primitive: it performs no rounds, transfers, or network search.
 */
public final class RaptorPatternScanner {

    private static final Comparator<RaptorRide> RIDE_ORDER =
        Comparator.comparing(RaptorRide::arrivalInstant)
            .thenComparing(RaptorRide::departureInstant)
            .thenComparing(RaptorRide::tripId)
            .thenComparing(RaptorRide::serviceDate)
            .thenComparingInt(RaptorRide::alightingStopPosition)
            .thenComparingInt(RaptorRide::boardingStopPosition);

    private final RaptorNetwork network;

    public RaptorPatternScanner(RaptorNetwork network) {
        this.network = Objects.requireNonNull(network, "network");
    }

    /**
     * Returns at most one earliest legal ride for each downstream pattern position.
     *
     * <p>The boarding stop may occur more than once; every matching, pickup-enabled
     * position is considered. A valid compact stop that is absent from the pattern
     * returns an immutable empty list. Invalid compact indexes are argument errors.</p>
     *
     * <p>For {@code B} matching boarding positions, {@code T} pattern trips,
     * {@code D} overlapping service dates, and {@code L} pattern stops, the worst-case
     * scan is {@code O(B*T*D*L)} and never scans trips outside this pattern.</p>
     */
    public List<RaptorRide> scan(
        int patternIndex,
        int boardingStopIndex,
        Instant earliestBoardingInstant
    ) {
        Objects.requireNonNull(earliestBoardingInstant, "earliestBoardingInstant");
        requireIndex(patternIndex, network.patterns().size(), "pattern");
        requireIndex(boardingStopIndex, network.stops().size(), "stop");

        RaptorTripPattern pattern = network.pattern(patternIndex);
        List<Integer> boardingPositions = boardingPositions(pattern, boardingStopIndex);
        if (boardingPositions.isEmpty()) {
            return List.of();
        }

        RaptorRide[] bestByAlightingPosition = new RaptorRide[pattern.stopCount()];
        var datesByFeed = new HashMap<String, List<LocalDate>>();
        var servicesByFeedAndDate = new HashMap<FeedDate, Set<FeedScopedId>>();

        for (int timetablePosition = 0; timetablePosition < pattern.tripCount(); timetablePosition++) {
            RaptorTripSchedule trip = network.trip(pattern.tripIndexAt(timetablePosition));
            RaptorFeedContext feedContext = network.feedContext(trip.feedId()).orElseThrow(() ->
                new IllegalStateException("Missing RAPTOR feed context for " + trip.feedId())
            );
            GtfsIndex sourceIndex = feedContext.sourceIndex();
            List<LocalDate> serviceDates = datesByFeed.computeIfAbsent(
                trip.feedId(),
                ignored -> sourceIndex.serviceDateCandidates(earliestBoardingInstant)
            );

            for (LocalDate serviceDate : serviceDates) {
                Set<FeedScopedId> activeServices = servicesByFeedAndDate.computeIfAbsent(
                    new FeedDate(trip.feedId(), serviceDate),
                    ignored -> sourceIndex.activeServiceIds(serviceDate)
                );
                if (!activeServices.contains(trip.serviceId())) {
                    continue;
                }
                scanTripOccurrence(
                    pattern,
                    trip,
                    boardingPositions,
                    serviceDate,
                    earliestBoardingInstant,
                    sourceIndex,
                    bestByAlightingPosition
                );
            }
        }

        var results = new ArrayList<RaptorRide>();
        for (RaptorRide ride : bestByAlightingPosition) {
            if (ride != null) {
                results.add(ride);
            }
        }
        results.sort(RIDE_ORDER);
        return List.copyOf(results);
    }

    private static void scanTripOccurrence(
        RaptorTripPattern pattern,
        RaptorTripSchedule trip,
        List<Integer> boardingPositions,
        LocalDate serviceDate,
        Instant earliestBoardingInstant,
        GtfsIndex sourceIndex,
        RaptorRide[] bestByAlightingPosition
    ) {
        for (int boardingPosition : boardingPositions) {
            int departureSeconds = trip.rawDepartureSecondsAt(boardingPosition);
            if (departureSeconds == RaptorTripSchedule.MISSING_TIME) {
                continue;
            }
            Instant departureInstant = sourceIndex.resolveServiceTime(
                new ServiceTime(serviceDate, departureSeconds)
            );
            if (departureInstant.isBefore(earliestBoardingInstant)) {
                continue;
            }

            for (
                int alightingPosition = boardingPosition + 1;
                alightingPosition < pattern.stopCount();
                alightingPosition++
            ) {
                if (!pattern.dropOffTypeAt(alightingPosition).allowsOrdinaryUse()) {
                    continue;
                }
                int arrivalSeconds = trip.rawArrivalSecondsAt(alightingPosition);
                if (arrivalSeconds == RaptorTripSchedule.MISSING_TIME) {
                    continue;
                }
                Instant arrivalInstant = sourceIndex.resolveServiceTime(
                    new ServiceTime(serviceDate, arrivalSeconds)
                );
                var candidate = new RaptorRide(
                    pattern.index(),
                    trip.index(),
                    trip.id(),
                    trip.routeId(),
                    trip.serviceId(),
                    serviceDate,
                    pattern.stopIndexAt(boardingPosition),
                    boardingPosition,
                    pattern.stopIndexAt(alightingPosition),
                    alightingPosition,
                    departureSeconds,
                    arrivalSeconds,
                    departureInstant,
                    arrivalInstant
                );
                RaptorRide current = bestByAlightingPosition[alightingPosition];
                if (current == null || RIDE_ORDER.compare(candidate, current) < 0) {
                    bestByAlightingPosition[alightingPosition] = candidate;
                }
            }
        }
    }

    private static List<Integer> boardingPositions(
        RaptorTripPattern pattern,
        int boardingStopIndex
    ) {
        var positions = new ArrayList<Integer>();
        for (int position = 0; position < pattern.stopCount(); position++) {
            if (pattern.stopIndexAt(position) == boardingStopIndex
                && pattern.pickupTypeAt(position).allowsOrdinaryUse()) {
                positions.add(position);
            }
        }
        return List.copyOf(positions);
    }

    private static void requireIndex(int index, int size, String type) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("Unknown RAPTOR " + type + " index " + index);
        }
    }

    private record FeedDate(String feedId, LocalDate serviceDate) {}
}
