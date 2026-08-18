package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Time-only RAPTOR rounds over exact GTFS stops.
 *
 * <p>Only a stop improved in round N can seed a ride in round N+1. Boarding
 * another trip is currently allowed only at that exact feed-scoped stop, with
 * zero added transfer time; a departure equal to arrival is catchable. There
 * are no parent-station, nearby-stop, {@code transfers.txt}, walking, or
 * cross-feed edges in Stage 2C.</p>
 */
public final class RaptorRoundRouter {

    private static final Comparator<RaptorLabel> LABEL_ORDER =
        Comparator.comparing(RaptorLabel::arrivalInstant)
            .thenComparingInt(RaptorLabel::round)
            .thenComparing(
                RaptorRoundRouter::incomingDeparture,
                Comparator.nullsFirst(Comparator.naturalOrder())
            )
            .thenComparing(
                RaptorRoundRouter::incomingTripId,
                Comparator.nullsFirst(Comparator.naturalOrder())
            )
            .thenComparing(
                RaptorRoundRouter::previousStopId,
                Comparator.nullsFirst(Comparator.naturalOrder())
            )
            .thenComparing(
                RaptorRoundRouter::serviceDate,
                Comparator.nullsFirst(Comparator.naturalOrder())
            )
            .thenComparingInt(RaptorRoundRouter::patternIndex)
            .thenComparingInt(RaptorRoundRouter::boardingStopPosition)
            .thenComparingInt(RaptorRoundRouter::alightingStopPosition)
            .thenComparingInt(RaptorLabel::stopIndex);

    private final RaptorNetwork network;
    private final RaptorPatternScanner patternScanner;

    public RaptorRoundRouter(RaptorNetwork network) {
        this.network = Objects.requireNonNull(network, "network");
        this.patternScanner = new RaptorPatternScanner(network);
    }

    /**
     * Finds earliest arrivals using at most {@code maxRounds} boarded trips.
     *
     * <p>Round snapshots contain only strict improvements produced with exactly
     * that many boardings. The result's best-label map combines them into the
     * best known arrival with at most the requested number of boardings.</p>
     */
    public RaptorSearchResult route(
        FeedScopedId origin,
        FeedScopedId destination,
        Instant departureInstant,
        int maxRounds
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departureInstant, "departureInstant");
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds must be positive");
        }

        int originIndex = requireStopIndex(origin, "origin");
        int destinationIndex = requireStopIndex(destination, "destination");
        RaptorLabel originLabel = RaptorLabel.origin(originIndex, origin, departureInstant);

        NavigableMap<Integer, RaptorLabel> currentImprovements = new TreeMap<>();
        currentImprovements.put(originIndex, originLabel);
        var bestByStop = new TreeMap<Integer, RaptorLabel>();
        bestByStop.put(originIndex, originLabel);
        var rounds = new ArrayList<RaptorRound>();
        rounds.add(new RaptorRound(0, currentImprovements, 0));

        if (originIndex != destinationIndex) {
            for (int roundNumber = 1; roundNumber <= maxRounds; roundNumber++) {
                RoundScan scan = scanRound(roundNumber, currentImprovements, bestByStop);
                currentImprovements = scan.improvements();
                rounds.add(new RaptorRound(
                    roundNumber,
                    currentImprovements,
                    scan.patternScanCount()
                ));
                currentImprovements.forEach(bestByStop::put);
                if (currentImprovements.isEmpty()) {
                    break;
                }
            }
        }

        var bestById = new TreeMap<FeedScopedId, RaptorLabel>();
        bestByStop.values().forEach(label -> bestById.put(label.stopId(), label));
        return new RaptorSearchResult(
            origin,
            destination,
            departureInstant,
            maxRounds,
            rounds,
            bestByStop,
            bestById,
            bestByStop.get(destinationIndex)
        );
    }

    private RoundScan scanRound(
        int roundNumber,
        NavigableMap<Integer, RaptorLabel> previousImprovements,
        Map<Integer, RaptorLabel> bestByStop
    ) {
        var candidates = new TreeMap<Integer, RaptorLabel>();
        var scannedStates = new HashSet<ScanState>();
        int scanCount = 0;

        for (RaptorLabel predecessor : previousImprovements.values()) {
            List<Integer> patternIndexes = network.patternIndexesForStop(predecessor.stopIndex());
            for (int patternIndex : patternIndexes) {
                var state = new ScanState(
                    patternIndex,
                    predecessor.stopIndex(),
                    predecessor.arrivalInstant()
                );
                if (!scannedStates.add(state)) {
                    continue;
                }
                scanCount++;
                for (RaptorRide ride : patternScanner.scan(
                    patternIndex,
                    predecessor.stopIndex(),
                    predecessor.arrivalInstant()
                )) {
                    int reachedStopIndex = ride.alightingStopIndex();
                    var candidate = RaptorLabel.reached(
                        reachedStopIndex,
                        network.stop(reachedStopIndex).id(),
                        roundNumber,
                        ride,
                        predecessor
                    );
                    RaptorLabel previousBest = bestByStop.get(reachedStopIndex);
                    if (previousBest != null
                        && !candidate.arrivalInstant().isBefore(previousBest.arrivalInstant())) {
                        continue;
                    }
                    RaptorLabel sameRound = candidates.get(reachedStopIndex);
                    if (sameRound == null || LABEL_ORDER.compare(candidate, sameRound) < 0) {
                        candidates.put(reachedStopIndex, candidate);
                    }
                }
            }
        }
        return new RoundScan(candidates, scanCount);
    }

    private int requireStopIndex(FeedScopedId stopId, String role) {
        OptionalInt index = network.stopIndex(stopId);
        if (index.isEmpty()) {
            throw new IllegalArgumentException("Unknown RAPTOR " + role + " stop " + stopId);
        }
        return index.getAsInt();
    }

    private static Instant incomingDeparture(RaptorLabel label) {
        return label.incomingRideOrNull() == null
            ? null
            : label.incomingRideOrNull().departureInstant();
    }

    private static FeedScopedId incomingTripId(RaptorLabel label) {
        return label.incomingRideOrNull() == null ? null : label.incomingRideOrNull().tripId();
    }

    private static FeedScopedId previousStopId(RaptorLabel label) {
        return label.predecessorOrNull() == null ? null : label.predecessorOrNull().stopId();
    }

    private static LocalDate serviceDate(RaptorLabel label) {
        return label.incomingRideOrNull() == null ? null : label.incomingRideOrNull().serviceDate();
    }

    private static int patternIndex(RaptorLabel label) {
        return label.incomingRideOrNull() == null ? -1 : label.incomingRideOrNull().patternIndex();
    }

    private static int boardingStopPosition(RaptorLabel label) {
        return label.incomingRideOrNull() == null
            ? -1
            : label.incomingRideOrNull().boardingStopPosition();
    }

    private static int alightingStopPosition(RaptorLabel label) {
        return label.incomingRideOrNull() == null
            ? -1
            : label.incomingRideOrNull().alightingStopPosition();
    }

    private record ScanState(int patternIndex, int stopIndex, Instant arrivalInstant) {}

    private record RoundScan(
        NavigableMap<Integer, RaptorLabel> improvements,
        int patternScanCount
    ) {}
}
