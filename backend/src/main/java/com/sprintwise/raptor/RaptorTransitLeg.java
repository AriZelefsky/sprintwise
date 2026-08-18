package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** One user-readable transit leg on one exact scheduled trip occurrence. */
public record RaptorTransitLeg(
    FeedScopedId tripId,
    FeedScopedId routeId,
    FeedScopedId serviceId,
    LocalDate serviceDate,
    FeedScopedId boardingStopId,
    FeedScopedId alightingStopId,
    int boardingStopPosition,
    int alightingStopPosition,
    int scheduledDepartureSeconds,
    int scheduledArrivalSeconds,
    Instant departureInstant,
    Instant arrivalInstant
) {

    public RaptorTransitLeg {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(serviceDate, "serviceDate");
        Objects.requireNonNull(boardingStopId, "boardingStopId");
        Objects.requireNonNull(alightingStopId, "alightingStopId");
        Objects.requireNonNull(departureInstant, "departureInstant");
        Objects.requireNonNull(arrivalInstant, "arrivalInstant");

        String feedId = tripId.feedId();
        if (!feedId.equals(routeId.feedId())
            || !feedId.equals(serviceId.feedId())
            || !feedId.equals(boardingStopId.feedId())
            || !feedId.equals(alightingStopId.feedId())) {
            throw new IllegalArgumentException(
                "A transit leg's trip, route, service, and stops must share one feed namespace"
            );
        }
        if (boardingStopPosition < 0 || alightingStopPosition <= boardingStopPosition) {
            throw new IllegalArgumentException(
                "A transit leg must alight at a position after its boarding position"
            );
        }
        if (scheduledDepartureSeconds < 0 || scheduledArrivalSeconds < 0) {
            throw new IllegalArgumentException(
                "Scheduled service-day seconds must not be negative"
            );
        }
        if (scheduledArrivalSeconds < scheduledDepartureSeconds) {
            throw new IllegalArgumentException(
                "Scheduled transit arrival must not precede departure"
            );
        }
        if (arrivalInstant.isBefore(departureInstant)) {
            throw new IllegalArgumentException(
                "Resolved transit arrival must not precede departure"
            );
        }
    }

    static RaptorTransitLeg from(
        RaptorRide ride,
        FeedScopedId boardingStopId,
        FeedScopedId alightingStopId
    ) {
        Objects.requireNonNull(ride, "ride");
        return new RaptorTransitLeg(
            ride.tripId(),
            ride.routeId(),
            ride.serviceId(),
            ride.serviceDate(),
            boardingStopId,
            alightingStopId,
            ride.boardingStopPosition(),
            ride.alightingStopPosition(),
            ride.departureSeconds(),
            ride.arrivalSeconds(),
            ride.departureInstant(),
            ride.arrivalInstant()
        );
    }
}
