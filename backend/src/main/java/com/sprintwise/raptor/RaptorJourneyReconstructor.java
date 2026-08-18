package com.sprintwise.raptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Converts a winning RAPTOR predecessor chain into chronological transit legs. */
public final class RaptorJourneyReconstructor {

    public Optional<RaptorJourney> reconstruct(RaptorSearchResult searchResult) {
        Objects.requireNonNull(searchResult, "searchResult");
        if (!searchResult.destinationReachable()) {
            return Optional.empty();
        }

        RaptorLabel cursor = searchResult.bestDestinationLabel().orElseThrow();
        int expectedRound = searchResult.winningRound().orElseThrow();
        var reversedLegs = new ArrayList<RaptorTransitLeg>(expectedRound);
        Set<RaptorLabel> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        while (expectedRound > 0) {
            if (!visited.add(cursor)) {
                throw malformed("cycle in the predecessor chain");
            }
            if (cursor.round() != expectedRound) {
                throw malformed(
                    "expected round " + expectedRound + " but found " + cursor.round()
                );
            }

            RaptorRide ride = cursor.incomingRide().orElse(null);
            if (ride == null) {
                throw malformed("round " + cursor.round() + " has no incoming ride");
            }
            RaptorLabel predecessor = cursor.predecessor().orElse(null);
            if (predecessor == null) {
                throw malformed("round " + cursor.round() + " has no predecessor");
            }
            validateLink(predecessor, cursor, ride);
            reversedLegs.add(RaptorTransitLeg.from(
                ride,
                predecessor.stopId(),
                cursor.stopId()
            ));
            cursor = predecessor;
            expectedRound--;
        }

        if (!visited.add(cursor)) {
            throw malformed("cycle in the predecessor chain");
        }
        validateOrigin(searchResult, cursor);
        Collections.reverse(reversedLegs);
        return Optional.of(new RaptorJourney(searchResult, reversedLegs));
    }

    private static void validateLink(
        RaptorLabel predecessor,
        RaptorLabel reached,
        RaptorRide ride
    ) {
        if (predecessor.round() + 1 != reached.round()) {
            throw malformed("predecessor rounds are not consecutive");
        }
        if (ride.boardingStopIndex() != predecessor.stopIndex()) {
            throw malformed("incoming ride does not board at its predecessor stop");
        }
        if (ride.alightingStopIndex() != reached.stopIndex()) {
            throw malformed("incoming ride does not alight at its reached stop");
        }
        if (!ride.arrivalInstant().equals(reached.arrivalInstant())) {
            throw malformed("incoming ride arrival differs from its reached label");
        }
        if (ride.departureInstant().isBefore(predecessor.arrivalInstant())) {
            throw malformed("incoming ride departs before its predecessor arrival");
        }
    }

    private static void validateOrigin(
        RaptorSearchResult searchResult,
        RaptorLabel originLabel
    ) {
        if (originLabel.round() != 0) {
            throw malformed("predecessor chain does not terminate at round zero");
        }
        if (!originLabel.stopId().equals(searchResult.origin())) {
            throw malformed("predecessor chain terminates at the wrong origin stop");
        }
        if (!originLabel.arrivalInstant().equals(searchResult.departureInstant())) {
            throw malformed("round-zero label does not match the requested departure time");
        }
        if (originLabel.incomingRide().isPresent() || originLabel.predecessor().isPresent()) {
            throw malformed("round-zero label contains an incoming edge");
        }
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Malformed RAPTOR predecessor chain: " + detail);
    }
}
