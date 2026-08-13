package com.example.backend.model;

public record Stop(
    FeedScopedId id,
    String name,
    double latitude,
    double longitude,
    int locationType,
    FeedScopedId parentStationId
) {}
