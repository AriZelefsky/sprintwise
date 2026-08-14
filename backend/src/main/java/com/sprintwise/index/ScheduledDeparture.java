package com.sprintwise.index;

import com.sprintwise.model.FeedScopedId;
import java.util.Objects;

/** A departure as encoded in GTFS, before selecting a concrete service date. */
public record ScheduledDeparture(
    FeedScopedId stopId,
    FeedScopedId tripId,
    FeedScopedId routeId,
    FeedScopedId serviceId,
    int stopSequence,
    int departureSeconds
) {
    public ScheduledDeparture {
        Objects.requireNonNull(stopId, "stopId");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(serviceId, "serviceId");
        if (stopSequence < 0) {
            throw new IllegalArgumentException("stopSequence must not be negative");
        }
        if (departureSeconds < 0) {
            throw new IllegalArgumentException("departureSeconds must not be negative");
        }
    }
}
