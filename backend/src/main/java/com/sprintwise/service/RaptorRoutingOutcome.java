package com.sprintwise.service;

import com.sprintwise.raptor.RaptorJourney;
import com.sprintwise.raptor.RaptorSearchResult;
import java.util.Objects;
import java.util.Optional;

/** Immutable application-layer pairing of a RAPTOR search and its optional journey. */
public record RaptorRoutingOutcome(
    RaptorSearchResult searchResult,
    Optional<RaptorJourney> journey
) {

    public RaptorRoutingOutcome {
        Objects.requireNonNull(searchResult, "searchResult");
        journey = Objects.requireNonNull(journey, "journey");
        if (searchResult.destinationReachable() != journey.isPresent()) {
            throw new IllegalArgumentException(
                "A reachable RAPTOR result must have exactly one reconstructed journey"
            );
        }
        journey.ifPresent(value -> {
            if (value.searchResult() != searchResult) {
                throw new IllegalArgumentException(
                    "The reconstructed journey must retain the same RAPTOR search result"
                );
            }
        });
    }
}
