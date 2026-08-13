package com.example.backend.gtfs.time;

import java.time.LocalDate;
import java.util.Objects;

/** A GTFS service date paired with seconds since that service day's time zero. */
public record ServiceTime(LocalDate serviceDate, int secondsSinceServiceDayStart) {

    public ServiceTime {
        Objects.requireNonNull(serviceDate, "serviceDate");
        if (secondsSinceServiceDayStart < 0) {
            throw new IllegalArgumentException("GTFS seconds must not be negative");
        }
    }
}
