package com.example.backend.debug;

import com.example.backend.model.StopTime;

public record StopTimeDebugResponse(
    String stopId,
    int stopSequence,
    Integer arrivalSeconds,
    Integer departureSeconds
) {
    static StopTimeDebugResponse from(StopTime stopTime) {
        return new StopTimeDebugResponse(
            stopTime.stopId().toString(),
            stopTime.stopSequence(),
            stopTime.arrivalSeconds(),
            stopTime.departureSeconds()
        );
    }
}
