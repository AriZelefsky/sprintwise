package com.example.backend.debug;

import com.example.backend.index.TimetableDeparture;
import java.time.Instant;
import java.time.LocalDate;

public record DepartureDebugResponse(
    String stopId,
    String tripId,
    String routeId,
    String serviceId,
    int stopSequence,
    LocalDate serviceDate,
    int departureSeconds,
    Instant departureTime
) {
    static DepartureDebugResponse from(TimetableDeparture departure) {
        return new DepartureDebugResponse(
            departure.stopId().toString(),
            departure.tripId().toString(),
            departure.routeId().toString(),
            departure.serviceId().toString(),
            departure.stopSequence(),
            departure.serviceTime().serviceDate(),
            departure.serviceTime().secondsSinceServiceDayStart(),
            departure.departureInstant()
        );
    }
}
