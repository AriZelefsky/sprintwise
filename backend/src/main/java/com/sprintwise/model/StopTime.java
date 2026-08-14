package com.sprintwise.model;

/** GTFS times are integer offsets from service-day midnight and may exceed 86,400. */
public record StopTime(
    FeedScopedId tripId,
    FeedScopedId stopId,
    int stopSequence,
    Integer arrivalSeconds,
    Integer departureSeconds,
    PickupDropOffType pickupType,
    PickupDropOffType dropOffType
) {
    public StopTime {
        if (pickupType == null || dropOffType == null) {
            throw new IllegalArgumentException("pickupType and dropOffType are required");
        }
    }

    /** Convenience for owned/programmatic feeds; omitted GTFS values default to regular service. */
    public StopTime(
        FeedScopedId tripId,
        FeedScopedId stopId,
        int stopSequence,
        Integer arrivalSeconds,
        Integer departureSeconds
    ) {
        this(
            tripId,
            stopId,
            stopSequence,
            arrivalSeconds,
            departureSeconds,
            PickupDropOffType.REGULARLY_SCHEDULED,
            PickupDropOffType.REGULARLY_SCHEDULED
        );
    }

    public boolean allowsOrdinaryBoarding() {
        return pickupType.allowsOrdinaryUse();
    }

    public boolean allowsOrdinaryAlighting() {
        return dropOffType.allowsOrdinaryUse();
    }
}
