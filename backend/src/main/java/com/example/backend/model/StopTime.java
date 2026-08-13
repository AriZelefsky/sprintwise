package com.example.backend.model;

/** GTFS times are integer offsets from service-day midnight and may exceed 86,400. */
public record StopTime(
    FeedScopedId tripId,
    FeedScopedId stopId,
    int stopSequence,
    Integer arrivalSeconds,
    Integer departureSeconds
) {}
