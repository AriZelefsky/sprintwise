package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An immutable, reachable transit-only journey reconstructed from a RAPTOR result. */
public final class RaptorJourney {

    private final RaptorSearchResult searchResult;
    private final List<RaptorTransitLeg> legs;

    RaptorJourney(RaptorSearchResult searchResult, List<RaptorTransitLeg> legs) {
        this.searchResult = Objects.requireNonNull(searchResult, "searchResult");
        this.legs = List.copyOf(legs);
        validate();
    }

    /** The complete search result is retained for round and label diagnostics. */
    public RaptorSearchResult searchResult() {
        return searchResult;
    }

    public FeedScopedId origin() {
        return searchResult.origin();
    }

    public FeedScopedId destination() {
        return searchResult.destination();
    }

    public Instant requestedDepartureInstant() {
        return searchResult.departureInstant();
    }

    public Instant arrivalInstant() {
        return searchResult.bestDestinationLabel().orElseThrow().arrivalInstant();
    }

    public int numberOfBoardings() {
        return legs.size();
    }

    public List<RaptorTransitLeg> legs() {
        return legs;
    }

    private void validate() {
        if (!searchResult.destinationReachable()) {
            throw new IllegalArgumentException(
                "Cannot construct a journey from an unreachable RAPTOR result"
            );
        }
        int winningRound = searchResult.winningRound().orElseThrow();
        if (legs.size() != winningRound) {
            throw new IllegalArgumentException(
                "Journey leg count must equal the winning RAPTOR round"
            );
        }

        Instant destinationArrival = searchResult.bestDestinationLabel()
            .orElseThrow()
            .arrivalInstant();
        if (legs.isEmpty()) {
            if (!origin().equals(destination())
                || winningRound != 0
                || !destinationArrival.equals(requestedDepartureInstant())) {
                throw new IllegalArgumentException(
                    "A zero-leg journey must be a round-zero origin-to-origin result"
                );
            }
            return;
        }

        RaptorTransitLeg first = legs.getFirst();
        if (!first.boardingStopId().equals(origin())) {
            throw new IllegalArgumentException("The first transit leg must board at the origin");
        }
        if (first.departureInstant().isBefore(requestedDepartureInstant())) {
            throw new IllegalArgumentException(
                "The first transit leg cannot depart before the requested time"
            );
        }

        for (int index = 1; index < legs.size(); index++) {
            RaptorTransitLeg previous = legs.get(index - 1);
            RaptorTransitLeg current = legs.get(index);
            if (!previous.alightingStopId().equals(current.boardingStopId())) {
                throw new IllegalArgumentException(
                    "Consecutive transit legs must connect at the same exact stop"
                );
            }
            if (current.departureInstant().isBefore(previous.arrivalInstant())) {
                throw new IllegalArgumentException(
                    "A transit leg cannot depart before the preceding leg arrives"
                );
            }
        }

        RaptorTransitLeg last = legs.getLast();
        if (!last.alightingStopId().equals(destination())) {
            throw new IllegalArgumentException(
                "The final transit leg must alight at the destination"
            );
        }
        if (!last.arrivalInstant().equals(destinationArrival)) {
            throw new IllegalArgumentException(
                "The final transit leg must arrive at the winning label's time"
            );
        }
    }
}
