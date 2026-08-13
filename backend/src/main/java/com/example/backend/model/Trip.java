package com.example.backend.model;

public record Trip(
    FeedScopedId id,
    FeedScopedId routeId,
    FeedScopedId serviceId,
    String headsign,
    String directionId
) {}
