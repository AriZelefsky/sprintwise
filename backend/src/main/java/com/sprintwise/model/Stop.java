package com.sprintwise.model;

public record Stop(
    FeedScopedId id,
    String name,
    double latitude,
    double longitude,
    int locationType,
    FeedScopedId parentStationId
) {}
