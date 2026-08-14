package com.sprintwise.gtfs.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sprintwise.gtfs.calendar.ServiceCalendarResolver;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServiceTimeResolverTest {

    private static final String FEED_ID = "synthetic";
    private static final FeedScopedId WEEKDAY = new FeedScopedId(FEED_ID, "WEEKDAY");

    private static GtfsFeed feed;
    private static ServiceCalendarResolver calendars;
    private static ServiceTimeResolver times;

    @BeforeAll
    static void loadFixture() throws Exception {
        feed = new OneBusAwayGtfsLoader().load(fixtureDirectory(), FEED_ID);
        calendars = new ServiceCalendarResolver(feed);
        times = ServiceTimeResolver.forFeed(feed);
    }

    @Test
    void findsTwentyFourHourDepartureFromPreviousServiceDateAfterCivilMidnight() {
        Instant query = ZonedDateTime.of(
            2026, 8, 14, 0, 4, 0, 0, feed.agencyZoneId()
        ).toInstant();

        ServiceTime found = times.serviceDateCandidates(query).stream()
            .filter(serviceDate -> calendars.isActive(WEEKDAY, serviceDate))
            .map(serviceDate -> new ServiceTime(serviceDate, 86_700))
            .filter(departure -> !times.toInstant(departure).isBefore(query))
            .min(Comparator.comparing(times::toInstant))
            .orElseThrow();

        assertEquals(LocalDate.of(2026, 8, 13), found.serviceDate());
        assertEquals(86_700, found.secondsSinceServiceDayStart());
        assertEquals(
            ZonedDateTime.of(2026, 8, 14, 0, 5, 0, 0, feed.agencyZoneId()),
            times.toAgencyZonedDateTime(found)
        );
    }

    @Test
    void usesAgencyTimezoneRatherThanUtcOrMachineDefaults() {
        Instant lateNewYorkEvening = Instant.parse("2026-08-14T03:30:00Z");

        assertEquals(ZoneId.of("America/New_York"), times.agencyZoneId());
        assertEquals(
            List.of(LocalDate.of(2026, 8, 13)),
            times.serviceDateCandidates(lateNewYorkEvening)
        );
    }

    @Test
    void springDstGapUsesNoonMinusTwelveElapsedHours() {
        ServiceTime start = new ServiceTime(LocalDate.of(2026, 3, 8), 0);
        ServiceTime threeHoursLater = new ServiceTime(LocalDate.of(2026, 3, 8), 10_800);

        ZonedDateTime resolvedStart = times.toAgencyZonedDateTime(start);
        ZonedDateTime resolvedLater = times.toAgencyZonedDateTime(threeHoursLater);

        assertEquals(ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, feed.agencyZoneId()), resolvedStart);
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, feed.agencyZoneId()), resolvedLater);
        assertEquals(Duration.ofHours(3), Duration.between(times.toInstant(start), times.toInstant(threeHoursLater)));
        assertEquals(ZoneOffset.ofHours(-5), resolvedStart.getOffset());
        assertEquals(ZoneOffset.ofHours(-4), resolvedLater.getOffset());
    }

    @Test
    void fallDstOverlapAlsoKeepsElapsedGtfsSecondsMonotonic() {
        ServiceTime start = new ServiceTime(LocalDate.of(2026, 11, 1), 0);
        ServiceTime twoHoursLater = new ServiceTime(LocalDate.of(2026, 11, 1), 7_200);

        ZonedDateTime resolvedStart = times.toAgencyZonedDateTime(start);
        ZonedDateTime resolvedLater = times.toAgencyZonedDateTime(twoHoursLater);

        assertEquals(1, resolvedStart.getHour());
        assertEquals(ZoneOffset.ofHours(-4), resolvedStart.getOffset());
        assertEquals(2, resolvedLater.getHour());
        assertEquals(ZoneOffset.ofHours(-5), resolvedLater.getOffset());
        assertEquals(Duration.ofHours(2), Duration.between(times.toInstant(start), times.toInstant(twoHoursLater)));
        assertFalse(times.toInstant(twoHoursLater).isBefore(times.toInstant(start)));
    }

    @Test
    void derivesAServiceDateWindowFromTheFeedsMaximumScheduledTime() {
        var resolver = new ServiceTimeResolver(feed.agencyZoneId(), 49 * 3_600 + 15 * 60);
        Instant query = ZonedDateTime.of(
            2026, 8, 15, 1, 4, 0, 0, feed.agencyZoneId()
        ).toInstant();

        assertEquals(177_300, resolver.maximumScheduledTimeSeconds());
        assertEquals(2, resolver.serviceDateLookbackDays());
        assertEquals(
            List.of(
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 8, 13)
            ),
            resolver.serviceDateCandidates(query)
        );
    }

    @Test
    void multiDayCandidateWindowRetainsNoonMinusTwelveDstSemantics() {
        var resolver = new ServiceTimeResolver(feed.agencyZoneId(), 50 * 3_600 + 5 * 60);
        ServiceTime departure = new ServiceTime(LocalDate.of(2026, 3, 6), 50 * 3_600 + 5 * 60);
        Instant justBeforeDeparture = resolver.toInstant(departure).minusSeconds(60);

        assertEquals(
            List.of(
                LocalDate.of(2026, 3, 8),
                LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 6)
            ),
            resolver.serviceDateCandidates(justBeforeDeparture)
        );
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 3, 5, 0, 0, feed.agencyZoneId()),
            resolver.toAgencyZonedDateTime(departure)
        );
    }

    @Test
    void fallDstQueryBeforeServiceTimeZeroStillConsidersTheCivilServiceDate() {
        var resolver = new ServiceTimeResolver(feed.agencyZoneId(), 30 * 60);
        Instant query = ZonedDateTime.of(
            2026, 11, 1, 0, 30, 0, 0, feed.agencyZoneId()
        ).toInstant();

        assertEquals(
            List.of(LocalDate.of(2026, 11, 1)),
            resolver.serviceDateCandidates(query)
        );
        assertEquals(
            ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, feed.agencyZoneId()),
            resolver.toAgencyZonedDateTime(new ServiceTime(LocalDate.of(2026, 11, 1), 1_800))
        );
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = ServiceTimeResolverTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
