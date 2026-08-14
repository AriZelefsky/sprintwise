package com.sprintwise.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

public record ServiceCalendar(
    FeedScopedId serviceId,
    Set<DayOfWeek> activeDays,
    LocalDate startDate,
    LocalDate endDate
) {
    public ServiceCalendar {
        activeDays = Set.copyOf(activeDays);
    }
}
