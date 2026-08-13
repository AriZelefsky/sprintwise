package com.example.backend.model;

import java.time.LocalDate;

public record ServiceCalendarDate(
    FeedScopedId serviceId,
    LocalDate date,
    ExceptionType exceptionType
) {
    public enum ExceptionType {
        ADDED,
        REMOVED
    }
}
