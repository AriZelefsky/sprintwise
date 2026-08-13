package com.example.backend.model;

import java.time.ZoneId;
import java.util.List;

public record GtfsFeed(
    String feedId,
    ZoneId agencyZoneId,
    List<Stop> stops,
    List<Route> routes,
    List<Trip> trips,
    List<StopTime> stopTimes,
    List<ServiceCalendar> serviceCalendars,
    List<ServiceCalendarDate> serviceCalendarDates
) {
    public GtfsFeed {
        stops = List.copyOf(stops);
        routes = List.copyOf(routes);
        trips = List.copyOf(trips);
        stopTimes = List.copyOf(stopTimes);
        serviceCalendars = List.copyOf(serviceCalendars);
        serviceCalendarDates = List.copyOf(serviceCalendarDates);
    }
}
