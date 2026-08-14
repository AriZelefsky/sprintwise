package com.sprintwise.gtfs.time;

import com.sprintwise.model.GtfsFeed;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/** Converts GTFS service times using one explicit agency timezone. */
public final class ServiceTimeResolver {

    private static final LocalTime LOCAL_NOON = LocalTime.NOON;
    private static final Duration TWELVE_HOURS = Duration.ofHours(12);

    private final ZoneId agencyZoneId;
    private final int maximumScheduledTimeSeconds;
    private final int serviceDateLookbackDays;

    public ServiceTimeResolver(ZoneId agencyZoneId) {
        this(agencyZoneId, 0);
    }

    public ServiceTimeResolver(ZoneId agencyZoneId, int maximumScheduledTimeSeconds) {
        this.agencyZoneId = Objects.requireNonNull(agencyZoneId, "agencyZoneId");
        if (maximumScheduledTimeSeconds < 0) {
            throw new IllegalArgumentException("maximumScheduledTimeSeconds must not be negative");
        }
        this.maximumScheduledTimeSeconds = maximumScheduledTimeSeconds;
        this.serviceDateLookbackDays = maximumScheduledTimeSeconds / (24 * 60 * 60);
    }

    public static ServiceTimeResolver forFeed(GtfsFeed feed) {
        Objects.requireNonNull(feed, "feed");
        int maximumScheduledTimeSeconds = feed.stopTimes().stream()
            .flatMapToInt(stopTime -> IntStream.of(
                stopTime.arrivalSeconds() == null ? 0 : stopTime.arrivalSeconds(),
                stopTime.departureSeconds() == null ? 0 : stopTime.departureSeconds()
            ))
            .max()
            .orElse(0);
        return new ServiceTimeResolver(feed.agencyZoneId(), maximumScheduledTimeSeconds);
    }

    public ZoneId agencyZoneId() {
        return agencyZoneId;
    }

    public int maximumScheduledTimeSeconds() {
        return maximumScheduledTimeSeconds;
    }

    public int serviceDateLookbackDays() {
        return serviceDateLookbackDays;
    }

    /**
     * Resolves according to the GTFS Time definition: time zero is local noon on
     * the service date minus twelve elapsed hours. This preserves a monotonic
     * timeline through daylight-saving gaps and overlaps.
     */
    public Instant toInstant(ServiceTime serviceTime) {
        Objects.requireNonNull(serviceTime, "serviceTime");
        Instant serviceDayTimeZero = serviceTime.serviceDate()
            .atTime(LOCAL_NOON)
            .atZone(agencyZoneId)
            .toInstant()
            .minus(TWELVE_HOURS);
        return serviceDayTimeZero.plusSeconds(serviceTime.secondsSinceServiceDayStart());
    }

    public ZonedDateTime toAgencyZonedDateTime(ServiceTime serviceTime) {
        return toInstant(serviceTime).atZone(agencyZoneId);
    }

    public LocalDate civilDate(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return instant.atZone(agencyZoneId).toLocalDate();
    }

    /**
     * Returns every service date whose feed-derived service-time interval
     * contains the query instant. The scan stops as soon as the maximum time
     * on an older date precedes the query, rather than using a fixed historical
     * window. Starting one civil date ahead also covers the GTFS noon-minus-12
     * rule on a spring daylight-saving transition.
     */
    public List<LocalDate> serviceDateCandidates(Instant queryInstant) {
        LocalDate civilDate = civilDate(queryInstant);
        var candidates = new ArrayList<LocalDate>();
        LocalDate candidate = civilDate.plusDays(1);
        while (true) {
            Instant serviceStart = toInstant(new ServiceTime(candidate, 0));
            Instant serviceEnd = toInstant(new ServiceTime(candidate, maximumScheduledTimeSeconds));
            boolean currentCivilDate = candidate.equals(civilDate);
            if ((currentCivilDate || !serviceStart.isAfter(queryInstant))
                && !serviceEnd.isBefore(queryInstant)) {
                candidates.add(candidate);
            }
            if (serviceEnd.isBefore(queryInstant)) {
                break;
            }
            candidate = candidate.minusDays(1);
        }
        return List.copyOf(candidates);
    }
}
