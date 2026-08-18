package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The earliest known arrival at one compact stop.
 *
 * <p>Round zero has no incoming ride or predecessor. Every later label retains
 * both so Stage 2D can reconstruct a journey without changing the search
 * state introduced in Stage 2C.</p>
 */
public final class RaptorLabel {

    private final int stopIndex;
    private final FeedScopedId stopId;
    private final Instant arrivalInstant;
    private final int round;
    private final RaptorRide incomingRide;
    private final RaptorLabel predecessor;

    private RaptorLabel(
        int stopIndex,
        FeedScopedId stopId,
        Instant arrivalInstant,
        int round,
        RaptorRide incomingRide,
        RaptorLabel predecessor
    ) {
        if (stopIndex < 0) {
            throw new IllegalArgumentException("RAPTOR stop index must not be negative");
        }
        if (round < 0) {
            throw new IllegalArgumentException("RAPTOR round must not be negative");
        }
        this.stopIndex = stopIndex;
        this.stopId = Objects.requireNonNull(stopId, "stopId");
        this.arrivalInstant = Objects.requireNonNull(arrivalInstant, "arrivalInstant");
        this.round = round;
        this.incomingRide = incomingRide;
        this.predecessor = predecessor;

        if (round == 0 && (incomingRide != null || predecessor != null)) {
            throw new IllegalArgumentException("Round-zero labels cannot have a ride or predecessor");
        }
        if (round > 0 && (incomingRide == null || predecessor == null)) {
            throw new IllegalArgumentException("Transit labels require a ride and predecessor");
        }
        if (incomingRide != null) {
            if (incomingRide.alightingStopIndex() != stopIndex) {
                throw new IllegalArgumentException("Incoming ride must alight at the label stop");
            }
            if (!incomingRide.arrivalInstant().equals(arrivalInstant)) {
                throw new IllegalArgumentException("Incoming ride arrival must equal label arrival");
            }
            if (predecessor.round() + 1 != round) {
                throw new IllegalArgumentException("A ride label must follow the preceding round");
            }
            if (incomingRide.boardingStopIndex() != predecessor.stopIndex()) {
                throw new IllegalArgumentException("Incoming ride must board at the predecessor stop");
            }
            if (incomingRide.departureInstant().isBefore(predecessor.arrivalInstant())) {
                throw new IllegalArgumentException("Incoming ride departs before its predecessor arrives");
            }
        }
    }

    static RaptorLabel origin(int stopIndex, FeedScopedId stopId, Instant departureInstant) {
        return new RaptorLabel(stopIndex, stopId, departureInstant, 0, null, null);
    }

    static RaptorLabel reached(
        int stopIndex,
        FeedScopedId stopId,
        int round,
        RaptorRide incomingRide,
        RaptorLabel predecessor
    ) {
        return new RaptorLabel(
            stopIndex,
            stopId,
            incomingRide.arrivalInstant(),
            round,
            incomingRide,
            predecessor
        );
    }

    public int stopIndex() {
        return stopIndex;
    }

    public FeedScopedId stopId() {
        return stopId;
    }

    public Instant arrivalInstant() {
        return arrivalInstant;
    }

    public int round() {
        return round;
    }

    public Optional<RaptorRide> incomingRide() {
        return Optional.ofNullable(incomingRide);
    }

    public Optional<RaptorLabel> predecessor() {
        return Optional.ofNullable(predecessor);
    }

    RaptorRide incomingRideOrNull() {
        return incomingRide;
    }

    RaptorLabel predecessorOrNull() {
        return predecessor;
    }
}
