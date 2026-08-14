package com.sprintwise.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GtfsIndexTest {

    private static final String FEED_ID = "synthetic";
    private static GtfsFeed feed;
    private static GtfsIndex index;

    @BeforeAll
    static void buildIndex() throws Exception {
        feed = new OneBusAwayGtfsLoader().load(fixtureDirectory(), FEED_ID);
        index = new GtfsIndex(feed);
    }

    @Test
    void exposesStopsRoutesAndTripsInExactFeedScopedIdOrder() {
        assertEquals(
            List.of("A", "B_BLUE", "B_RED", "B_STATION", "C"),
            index.stops().stream().map(stop -> stop.id().id()).toList()
        );
        assertEquals(
            List.of("BLUE", "DIRECT", "RED"),
            index.routes().stream().map(route -> route.id().id()).toList()
        );
        assertEquals(
            List.of(
                "BLUE_EARLY",
                "BLUE_LATE",
                "DIRECT_SLOW",
                "NIGHT",
                "RED_EARLY",
                "RED_LATE",
                "SPECIAL_ONLY",
                "WEEKEND_ONLY"
            ),
            index.trips().stream().map(trip -> trip.id().id()).toList()
        );

        assertEquals(
            List.of(1, 2),
            index.stopTimesForTrip(id("NIGHT")).stream().map(StopTime::stopSequence).toList()
        );
        assertEquals(
            List.of(
                "DIRECT_SLOW",
                "NIGHT",
                "RED_EARLY",
                "RED_LATE",
                "SPECIAL_ONLY",
                "WEEKEND_ONLY"
            ),
            index.tripsServingStop(id("A")).stream().map(trip -> trip.id().id()).toList()
        );
    }

    @Test
    void findsNormalDaytimeDeparturesAndFiltersInactiveServices() {
        List<TimetableDeparture> departures = index.nextDepartures(
            id("A"),
            at(2026, 8, 12, 7, 59),
            10
        );

        assertEquals(
            List.of("RED_EARLY", "RED_LATE", "NIGHT"),
            departures.stream().map(departure -> departure.tripId().id()).toList()
        );
        assertEquals(
            List.of(28_800, 29_400, 86_700),
            departures.stream()
                .map(departure -> departure.serviceTime().secondsSinceServiceDayStart())
                .toList()
        );
        assertFalse(departures.stream().anyMatch(
            departure -> departure.tripId().id().equals("SPECIAL_ONLY")
        ));
        assertFalse(departures.stream().anyMatch(
            departure -> departure.tripId().id().equals("WEEKEND_ONLY")
        ));
    }

    @Test
    void resolvesAfterMidnightDepartureFromPreviousServiceDate() {
        List<TimetableDeparture> departures = index.nextDepartures(
            id("A"),
            at(2026, 8, 14, 0, 4),
            1
        );

        assertEquals(1, departures.size());
        TimetableDeparture departure = departures.getFirst();
        assertEquals("NIGHT", departure.tripId().id());
        assertEquals(LocalDate.of(2026, 8, 13), departure.serviceTime().serviceDate());
        assertEquals(86_700, departure.serviceTime().secondsSinceServiceDayStart());
        assertEquals(at(2026, 8, 14, 0, 5), departure.departureInstant());
    }

    @Test
    void inactiveExceptionOnlyServiceDoesNotLeakToAnotherDate() {
        List<TimetableDeparture> departures = index.nextDepartures(
            id("A"),
            at(2026, 8, 14, 11, 59),
            10
        );

        assertEquals(
            List.of("NIGHT"),
            departures.stream().map(departure -> departure.tripId().id()).toList()
        );
    }

    @Test
    void findsFortyNineHourDepartureFromTwoServiceDatesEarlier() {
        GtfsIndex longSpanIndex = longSpanIndex();

        List<TimetableDeparture> departures = longSpanIndex.nextDepartures(
            id("A"),
            at(2026, 8, 15, 1, 4),
            1
        );

        assertEquals(1, departures.size());
        assertEquals("LONG_WEEKDAY", departures.getFirst().tripId().id());
        assertEquals(LocalDate.of(2026, 8, 13), departures.getFirst().serviceTime().serviceDate());
        assertEquals(49 * 3_600 + 5 * 60, departures.getFirst().serviceTime().secondsSinceServiceDayStart());
        assertEquals(at(2026, 8, 15, 1, 5), departures.getFirst().departureInstant());
    }

    @Test
    void filtersInactiveServicesAcrossEveryDerivedCandidateDate() {
        List<TimetableDeparture> departures = longSpanIndex().nextDepartures(
            id("A"),
            at(2026, 8, 16, 1, 4),
            10
        );

        assertFalse(departures.stream().anyMatch(
            departure -> departure.tripId().id().equals("LONG_SPECIAL")
        ));
    }

    @Test
    void equalTimeDeparturesUseTripIdAsStableTieBreaker() {
        var trips = new ArrayList<>(feed.trips());
        trips.add(new Trip(id("A_TIE"), id("RED"), id("WEEKDAY"), "Tie", "0"));

        var stopTimes = new ArrayList<>(feed.stopTimes());
        stopTimes.add(new StopTime(id("A_TIE"), id("A"), 1, 28_800, 28_800));
        stopTimes.add(new StopTime(id("A_TIE"), id("B_RED"), 2, 29_100, 29_100));

        GtfsIndex augmented = new GtfsIndex(new GtfsFeed(
            feed.feedId(),
            feed.agencyZoneId(),
            feed.stops(),
            feed.routes(),
            trips,
            stopTimes,
            feed.serviceCalendars(),
            feed.serviceCalendarDates()
        ));

        List<TimetableDeparture> departures = augmented.nextDepartures(
            id("A"),
            at(2026, 8, 12, 7, 59),
            3
        );

        assertEquals(
            List.of("A_TIE", "RED_EARLY", "RED_LATE"),
            departures.stream().map(departure -> departure.tripId().id()).toList()
        );
        assertEquals(
            departures.get(0).departureInstant(),
            departures.get(1).departureInstant()
        );
    }

    @Test
    void unknownIdsHaveAnExplicitEmptyContractAndResultsAreReadOnly() {
        FeedScopedId unknown = id("UNKNOWN");

        assertFalse(index.stop(unknown).isPresent());
        assertEquals(List.of(), index.stopTimesForTrip(unknown));
        assertEquals(List.of(), index.tripsServingStop(unknown));
        assertEquals(List.of(), index.nextDepartures(unknown, at(2026, 8, 12, 7, 59), 5));
        assertThrows(UnsupportedOperationException.class, () -> index.stops().clear());
    }

    private static FeedScopedId id(String rawId) {
        return new FeedScopedId(FEED_ID, rawId);
    }

    private static GtfsIndex longSpanIndex() {
        var trips = new ArrayList<>(feed.trips());
        trips.add(new Trip(id("LONG_WEEKDAY"), id("DIRECT"), id("WEEKDAY"), "Long", "0"));
        trips.add(new Trip(id("LONG_SPECIAL"), id("DIRECT"), id("SPECIAL"), "Long", "0"));

        var stopTimes = new ArrayList<>(feed.stopTimes());
        int departure = 49 * 3_600 + 5 * 60;
        int arrival = 49 * 3_600 + 15 * 60;
        stopTimes.add(new StopTime(id("LONG_WEEKDAY"), id("A"), 1, departure, departure));
        stopTimes.add(new StopTime(id("LONG_WEEKDAY"), id("C"), 2, arrival, arrival));
        stopTimes.add(new StopTime(id("LONG_SPECIAL"), id("A"), 1, departure + 60, departure + 60));
        stopTimes.add(new StopTime(id("LONG_SPECIAL"), id("C"), 2, arrival + 60, arrival + 60));

        return new GtfsIndex(new GtfsFeed(
            feed.feedId(),
            feed.agencyZoneId(),
            feed.stops(),
            feed.routes(),
            trips,
            stopTimes,
            feed.serviceCalendars(),
            feed.serviceCalendarDates()
        ));
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(
            year,
            month,
            day,
            hour,
            minute,
            0,
            0,
            feed.agencyZoneId()
        ).toInstant();
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = GtfsIndexTest.class.getClassLoader().getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
