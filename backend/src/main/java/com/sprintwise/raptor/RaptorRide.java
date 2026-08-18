package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** One legal, uninterrupted ride on one scheduled trip occurrence. */
public record RaptorRide(
    int patternIndex,
    int tripIndex,
    FeedScopedId tripId,
    FeedScopedId routeId,
    FeedScopedId serviceId,
    LocalDate serviceDate,
    int boardingStopIndex,
    int boardingStopPosition,
    int alightingStopIndex,
    int alightingStopPosition,
    int departureSeconds,
    int arrivalSeconds,
    Instant departureInstant,
    Instant arrivalInstant
) {

    public RaptorRide {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(serviceDate, "serviceDate");
        Objects.requireNonNull(departureInstant, "departureInstant");
        Objects.requireNonNull(arrivalInstant, "arrivalInstant");
        if (patternIndex < 0 || tripIndex < 0 || boardingStopIndex < 0 || alightingStopIndex < 0) {
            throw new IllegalArgumentException("RAPTOR indexes must not be negative");
        }
        if (boardingStopPosition < 0 || alightingStopPosition <= boardingStopPosition) {
            throw new IllegalArgumentException(
                "Alighting position must be downstream of the boarding position"
            );
        }
        if (departureSeconds < 0 || arrivalSeconds < 0) {
            throw new IllegalArgumentException("GTFS service-day seconds must not be negative");
        }
        if (arrivalInstant.isBefore(departureInstant)) {
            throw new IllegalArgumentException("Ride arrival must not precede departure");
        }
    }
}
