package com.example.backend.gtfs.onebusaway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.gtfs.GtfsLoadException;
import com.example.backend.model.FeedScopedId;
import com.example.backend.model.GtfsFeed;
import com.example.backend.model.ServiceCalendarDate;
import com.example.backend.model.StopTime;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneBusAwayGtfsLoaderTest {

    private static final String FEED_ID = "synthetic";
    private static GtfsFeed feed;

    @BeforeAll
    static void loadFixture() throws Exception {
        feed = new OneBusAwayGtfsLoader().load(fixtureDirectory(), FEED_ID);
    }

    @Test
    void loadsExpectedEntitiesIntoSprintWiseModels() {
        assertEquals(FEED_ID, feed.feedId());
        assertEquals(ZoneId.of("America/New_York"), feed.agencyZoneId());
        assertEquals(5, feed.stops().size());
        assertEquals(3, feed.routes().size());
        assertEquals(8, feed.trips().size());
        assertEquals(16, feed.stopTimes().size());
        assertEquals(2, feed.serviceCalendars().size());
        assertEquals(2, feed.serviceCalendarDates().size());

        assertTrue(feed.stops().stream().allMatch(stop -> stop.id().feedId().equals(FEED_ID)));
        assertTrue(feed.routes().stream().allMatch(route -> route.id().feedId().equals(FEED_ID)));
        assertTrue(feed.trips().stream().allMatch(trip -> trip.id().feedId().equals(FEED_ID)));
    }

    @Test
    void preservesRequiredRelationshipsUsingFeedScopedIds() {
        Set<FeedScopedId> stopIds = feed.stops().stream().map(stop -> stop.id()).collect(Collectors.toSet());
        Set<FeedScopedId> routeIds = feed.routes().stream().map(route -> route.id()).collect(Collectors.toSet());
        Set<FeedScopedId> tripIds = feed.trips().stream().map(trip -> trip.id()).collect(Collectors.toSet());
        Set<FeedScopedId> serviceIds = feed.serviceCalendars().stream()
            .map(calendar -> calendar.serviceId())
            .collect(Collectors.toSet());
        serviceIds.addAll(feed.serviceCalendarDates().stream()
            .map(calendarDate -> calendarDate.serviceId())
            .toList());

        assertTrue(feed.stops().stream()
            .filter(stop -> stop.parentStationId() != null)
            .allMatch(stop -> stopIds.contains(stop.parentStationId())));
        assertTrue(feed.trips().stream().allMatch(trip -> routeIds.contains(trip.routeId())));
        assertTrue(feed.trips().stream().allMatch(trip -> serviceIds.contains(trip.serviceId())));
        assertTrue(feed.stopTimes().stream().allMatch(stopTime -> tripIds.contains(stopTime.tripId())));
        assertTrue(feed.stopTimes().stream().allMatch(stopTime -> stopIds.contains(stopTime.stopId())));

        var weekday = feed.serviceCalendars().stream()
            .filter(calendar -> calendar.serviceId().id().equals("WEEKDAY"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            weekday.activeDays()
        );
        assertEquals(LocalDate.of(2026, 8, 1), weekday.startDate());
        assertEquals(LocalDate.of(2026, 8, 31), weekday.endDate());

        var special = feed.serviceCalendarDates().stream()
            .filter(calendarDate -> calendarDate.serviceId().id().equals("SPECIAL"))
            .findFirst()
            .orElseThrow();
        assertEquals(LocalDate.of(2026, 8, 13), special.date());
        assertEquals(ServiceCalendarDate.ExceptionType.ADDED, special.exceptionType());
    }

    @Test
    void keepsStopTimesOrderedWithinEveryTrip() {
        feed.stopTimes().stream()
            .collect(Collectors.groupingBy(StopTime::tripId))
            .forEach((tripId, stopTimes) -> {
                List<Integer> actual = stopTimes.stream().map(StopTime::stopSequence).toList();
                List<Integer> sorted = actual.stream().sorted().toList();
                assertEquals(sorted, actual, () -> "Stop times out of order for " + tripId);
            });
    }

    @Test
    void preservesTimesAboveTwentyFourHoursAsIntegerSeconds() {
        List<StopTime> night = feed.stopTimes().stream()
            .filter(stopTime -> stopTime.tripId().id().equals("NIGHT"))
            .sorted(Comparator.comparingInt(StopTime::stopSequence))
            .toList();

        assertEquals(86_700, night.getFirst().arrivalSeconds());
        assertEquals(86_700, night.getFirst().departureSeconds());
        assertEquals(87_300, night.getLast().arrivalSeconds());
        assertEquals(Integer.class, night.getFirst().arrivalSeconds().getClass());
    }

    @Test
    void rejectsMissingRequiredReferencesWithContext(@TempDir Path temporaryFeed) throws Exception {
        copyFixtureTo(temporaryFeed);
        Path trips = temporaryFeed.resolve("trips.txt");
        Files.writeString(
            trips,
            Files.readString(trips).replace(
                "RED,WEEKDAY,RED_EARLY,Beta,0",
                "MISSING_ROUTE,WEEKDAY,RED_EARLY,Beta,0"
            )
        );

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );

        assertTrue(exception.getMessage().contains("MISSING_ROUTE"), exception::getMessage);
        assertTrue(exception.getMessage().toLowerCase().contains("route"), exception::getMessage);
    }

    private static void copyFixtureTo(Path destination) throws Exception {
        try (var files = Files.list(fixtureDirectory())) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Files.copy(source, destination.resolve(source.getFileName()));
            }
        }
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = OneBusAwayGtfsLoaderTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
