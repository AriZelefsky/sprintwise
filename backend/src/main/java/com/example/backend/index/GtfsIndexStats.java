package com.example.backend.index;

/** Structural counts useful for diagnostics without exposing mutable index internals. */
public record GtfsIndexStats(
    int stopCount,
    int routeCount,
    int tripCount,
    long stopTimeReferenceCount,
    long scheduledDepartureCount
) {}
