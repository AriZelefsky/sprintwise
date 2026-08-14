package com.sprintwise.model;

import java.util.Arrays;
import java.util.Optional;

/** GTFS pickup/drop-off rules preserved without exposing parser-specific values. */
public enum PickupDropOffType {
    REGULARLY_SCHEDULED(0, true),
    NOT_AVAILABLE(1, false),
    MUST_PHONE_AGENCY(2, false),
    MUST_COORDINATE_WITH_DRIVER(3, false);

    private final int gtfsValue;
    private final boolean ordinaryUseAllowed;

    PickupDropOffType(int gtfsValue, boolean ordinaryUseAllowed) {
        this.gtfsValue = gtfsValue;
        this.ordinaryUseAllowed = ordinaryUseAllowed;
    }

    public int gtfsValue() {
        return gtfsValue;
    }

    /** True only when no advance arrangement is needed. */
    public boolean allowsOrdinaryUse() {
        return ordinaryUseAllowed;
    }

    public static Optional<PickupDropOffType> fromGtfsValue(int value) {
        return Arrays.stream(values())
            .filter(type -> type.gtfsValue == value)
            .findFirst();
    }
}
