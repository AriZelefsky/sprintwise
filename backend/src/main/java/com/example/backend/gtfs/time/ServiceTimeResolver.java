package com.example.backend.gtfs.time;

import com.example.backend.model.GtfsFeed;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/** Converts GTFS service times using one explicit agency timezone. */
public final class ServiceTimeResolver {

    private static final LocalTime LOCAL_NOON = LocalTime.NOON;
    private static final Duration TWELVE_HOURS = Duration.ofHours(12);

    private final ZoneId agencyZoneId;

    public ServiceTimeResolver(ZoneId agencyZoneId) {
        this.agencyZoneId = Objects.requireNonNull(agencyZoneId, "agencyZoneId");
    }

    public static ServiceTimeResolver forFeed(GtfsFeed feed) {
        Objects.requireNonNull(feed, "feed");
        return new ServiceTimeResolver(feed.agencyZoneId());
    }

    public ZoneId agencyZoneId() {
        return agencyZoneId;
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
     * Stage 1 timetable lookups must inspect both dates near civil midnight:
     * departures encoded as 24:xx belong to the previous service date.
     */
    public List<LocalDate> serviceDateCandidates(Instant queryInstant) {
        LocalDate civilDate = civilDate(queryInstant);
        return List.of(civilDate, civilDate.minusDays(1));
    }
}
