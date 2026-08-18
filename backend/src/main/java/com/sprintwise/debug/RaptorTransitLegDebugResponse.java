package com.sprintwise.debug;

import com.sprintwise.raptor.RaptorTransitLeg;
import java.time.Instant;
import java.time.LocalDate;

/** JSON projection of one exact scheduled transit leg. */
public record RaptorTransitLegDebugResponse(
    String tripId,
    String routeId,
    String serviceId,
    LocalDate serviceDate,
    String boardingStopId,
    String alightingStopId,
    int boardingStopPosition,
    int alightingStopPosition,
    int departureSeconds,
    int arrivalSeconds,
    Instant departureTime,
    Instant arrivalTime
) {

    static RaptorTransitLegDebugResponse from(RaptorTransitLeg leg) {
        return new RaptorTransitLegDebugResponse(
            leg.tripId().toString(),
            leg.routeId().toString(),
            leg.serviceId().toString(),
            leg.serviceDate(),
            leg.boardingStopId().toString(),
            leg.alightingStopId().toString(),
            leg.boardingStopPosition(),
            leg.alightingStopPosition(),
            leg.scheduledDepartureSeconds(),
            leg.scheduledArrivalSeconds(),
            leg.departureInstant(),
            leg.arrivalInstant()
        );
    }
}
