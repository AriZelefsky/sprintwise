package com.sprintwise.debug;

import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.util.List;

public record TripDebugResponse(
    String id,
    String routeId,
    String serviceId,
    String headsign,
    String directionId,
    List<StopTimeDebugResponse> stopTimes
) {
    static TripDebugResponse from(Trip trip, List<StopTime> stopTimes) {
        return new TripDebugResponse(
            trip.id().toString(),
            trip.routeId().toString(),
            trip.serviceId().toString(),
            trip.headsign(),
            trip.directionId(),
            stopTimes.stream().map(StopTimeDebugResponse::from).toList()
        );
    }

    public TripDebugResponse {
        stopTimes = List.copyOf(stopTimes);
    }
}
