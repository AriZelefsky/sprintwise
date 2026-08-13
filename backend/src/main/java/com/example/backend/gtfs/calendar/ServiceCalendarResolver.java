package com.example.backend.gtfs.calendar;

import com.example.backend.model.FeedScopedId;
import com.example.backend.model.GtfsFeed;
import com.example.backend.model.ServiceCalendar;
import com.example.backend.model.ServiceCalendarDate;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Resolves the GTFS services active on a service date. */
public final class ServiceCalendarResolver {

    private final String feedId;
    private final Map<FeedScopedId, ServiceCalendar> calendarsByServiceId;
    private final Map<LocalDate, Map<FeedScopedId, ServiceCalendarDate.ExceptionType>> exceptionsByDate;

    public ServiceCalendarResolver(GtfsFeed feed) {
        Objects.requireNonNull(feed, "feed");
        this.feedId = feed.feedId();
        this.calendarsByServiceId = calendarsByServiceId(feed.serviceCalendars());
        this.exceptionsByDate = exceptionsByDate(feed.serviceCalendarDates());
        validateNamespaces();
    }

    public String feedId() {
        return feedId;
    }

    /**
     * Returns a sorted, immutable set. A calendar-date exception takes precedence
     * over the recurring weekly calendar for the same service and date.
     */
    public Set<FeedScopedId> activeServiceIds(LocalDate serviceDate) {
        Objects.requireNonNull(serviceDate, "serviceDate");
        var active = new TreeSet<FeedScopedId>();

        calendarsByServiceId.values().stream()
            .filter(calendar -> isActiveByCalendar(calendar, serviceDate))
            .map(ServiceCalendar::serviceId)
            .forEach(active::add);

        exceptionsByDate.getOrDefault(serviceDate, Map.of()).forEach((serviceId, exceptionType) -> {
            if (exceptionType == ServiceCalendarDate.ExceptionType.ADDED) {
                active.add(serviceId);
            } else {
                active.remove(serviceId);
            }
        });

        return Collections.unmodifiableSet(active);
    }

    public boolean isActive(FeedScopedId serviceId, LocalDate serviceDate) {
        requireFeedNamespace(serviceId);
        return activeServiceIds(serviceDate).contains(serviceId);
    }

    private static boolean isActiveByCalendar(ServiceCalendar calendar, LocalDate date) {
        return !date.isBefore(calendar.startDate())
            && !date.isAfter(calendar.endDate())
            && calendar.activeDays().contains(date.getDayOfWeek());
    }

    private static Map<FeedScopedId, ServiceCalendar> calendarsByServiceId(
        List<ServiceCalendar> calendars
    ) {
        var result = new HashMap<FeedScopedId, ServiceCalendar>();
        for (ServiceCalendar calendar : calendars) {
            Objects.requireNonNull(calendar, "service calendar");
            if (calendar.startDate().isAfter(calendar.endDate())) {
                throw new IllegalArgumentException(
                    "Service calendar " + calendar.serviceId() + " starts after it ends"
                );
            }
            ServiceCalendar previous = result.putIfAbsent(calendar.serviceId(), calendar);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Duplicate service calendar for " + calendar.serviceId()
                );
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, Map<FeedScopedId, ServiceCalendarDate.ExceptionType>> exceptionsByDate(
        List<ServiceCalendarDate> exceptions
    ) {
        var mutable = new HashMap<LocalDate, Map<FeedScopedId, ServiceCalendarDate.ExceptionType>>();
        for (ServiceCalendarDate exception : exceptions) {
            Objects.requireNonNull(exception, "service calendar date");
            var onDate = mutable.computeIfAbsent(exception.date(), ignored -> new HashMap<>());
            var previous = onDate.putIfAbsent(exception.serviceId(), exception.exceptionType());
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Duplicate calendar-date exception for " + exception.serviceId()
                        + " on " + exception.date()
                );
            }
        }

        var immutable = new HashMap<LocalDate, Map<FeedScopedId, ServiceCalendarDate.ExceptionType>>();
        mutable.forEach((date, values) -> immutable.put(date, Map.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private void validateNamespaces() {
        calendarsByServiceId.keySet().forEach(this::requireFeedNamespace);
        exceptionsByDate.values().forEach(exceptions ->
            exceptions.keySet().forEach(this::requireFeedNamespace)
        );
    }

    private void requireFeedNamespace(FeedScopedId serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        if (!feedId.equals(serviceId.feedId())) {
            throw new IllegalArgumentException(
                "Service ID " + serviceId + " is outside feed namespace " + feedId
            );
        }
    }
}
