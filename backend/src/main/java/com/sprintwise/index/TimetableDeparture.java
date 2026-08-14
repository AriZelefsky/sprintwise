package com.sprintwise.index;

import com.sprintwise.gtfs.time.ServiceTime;
import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.util.Objects;

/** A scheduled departure resolved onto a concrete service date and instant. */
public record TimetableDeparture(
    FeedScopedId stopId,
    FeedScopedId tripId,
    FeedScopedId routeId,
    FeedScopedId serviceId,
    int stopSequence,
    ServiceTime serviceTime,
    Instant departureInstant
) {
    public TimetableDeparture {
        Objects.requireNonNull(stopId, "stopId");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(serviceTime, "serviceTime");
        Objects.requireNonNull(departureInstant, "departureInstant");
    }
}
