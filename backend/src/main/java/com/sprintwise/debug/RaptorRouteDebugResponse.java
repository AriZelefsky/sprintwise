package com.sprintwise.debug;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sprintwise.raptor.RaptorJourney;
import com.sprintwise.raptor.RaptorSearchResult;
import com.sprintwise.service.RaptorRoutingOutcome;
import java.time.Instant;
import java.util.List;

/** Stable JSON projection that deliberately excludes internal RAPTOR labels and rounds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaptorRouteDebugResponse(
    String fromStopId,
    String toStopId,
    Instant departAt,
    boolean reachable,
    Instant arrivalAt,
    Integer winningRound,
    int numberOfBoardedTrips,
    int roundsAttempted,
    List<RaptorTransitLegDebugResponse> legs
) {

    static RaptorRouteDebugResponse from(RaptorRoutingOutcome outcome) {
        RaptorSearchResult result = outcome.searchResult();
        RaptorJourney journey = outcome.journey().orElse(null);
        List<RaptorTransitLegDebugResponse> legs = journey == null
            ? List.of()
            : journey.legs().stream().map(RaptorTransitLegDebugResponse::from).toList();
        return new RaptorRouteDebugResponse(
            result.origin().toString(),
            result.destination().toString(),
            result.departureInstant(),
            result.destinationReachable(),
            journey == null ? null : journey.arrivalInstant(),
            result.winningRound().isEmpty() ? null : result.winningRound().getAsInt(),
            legs.size(),
            result.completedTransitRounds(),
            legs
        );
    }

    public RaptorRouteDebugResponse {
        legs = List.copyOf(legs);
        if (reachable != (arrivalAt != null && winningRound != null)) {
            throw new IllegalArgumentException(
                "Reachable RAPTOR JSON must include arrivalAt and winningRound"
            );
        }
        if (numberOfBoardedTrips != legs.size()) {
            throw new IllegalArgumentException(
                "RAPTOR JSON boarded-trip count must equal its leg count"
            );
        }
    }
}
