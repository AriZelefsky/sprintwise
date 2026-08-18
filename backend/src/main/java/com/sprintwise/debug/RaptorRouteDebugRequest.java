package com.sprintwise.debug;

/** Raw HTTP request fields for one exact-stop, transit-only RAPTOR query. */
public record RaptorRouteDebugRequest(
    String fromStopId,
    String toStopId,
    String departAt,
    Integer maxRounds
) {}
