package com.sprintwise.debug;

import com.sprintwise.model.Stop;

public record StopDebugResponse(
    String id,
    String name,
    double latitude,
    double longitude,
    int locationType,
    String parentStationId
) {
    static StopDebugResponse from(Stop stop) {
        return new StopDebugResponse(
            stop.id().toString(),
            stop.name(),
            stop.latitude(),
            stop.longitude(),
            stop.locationType(),
            stop.parentStationId() == null ? null : stop.parentStationId().toString()
        );
    }
}
