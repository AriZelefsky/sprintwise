package com.sprintwise.raptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.Route;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import com.sprintwise.service.FeedUnavailableException;
import com.sprintwise.service.TransitFeedCatalog;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RaptorNetworkTest {

    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Test
    void buildsOneDeterministicCompositeNetworkFromTwoOrdinaryStageOneFeeds() {
        RaptorNetwork first = new RaptorNetworkBuilder().build(syntheticCatalog("mta", "lirr"));
        RaptorNetwork second = new RaptorNetworkBuilder().build(syntheticCatalog("lirr", "mta"));

        assertEquals(List.of("lirr", "mta"), List.copyOf(first.feedIds()));
        assertEquals(10, first.stats().stopCount());
        assertEquals(16, first.stats().tripCount());
        assertEquals(6, first.stats().structuralPatternCount());
        assertEquals(6, first.stats().patternCount());

        int lirrA = first.stopIndex(id("lirr", "A")).orElseThrow();
        int mtaA = first.stopIndex(id("mta", "A")).orElseThrow();
        assertNotEquals(lirrA, mtaA);
        assertEquals("lirr:A", first.stop(lirrA).id().toString());
        assertEquals("mta:A", first.stop(mtaA).id().toString());

        RaptorTripSchedule mtaRedEarly = trip(first, "mta", "RED_EARLY");
        RaptorTripSchedule mtaRedLate = trip(first, "mta", "RED_LATE");
        RaptorTripSchedule lirrRedEarly = trip(first, "lirr", "RED_EARLY");
        assertEquals(mtaRedEarly.patternIndex(), mtaRedLate.patternIndex());
        assertNotEquals(mtaRedEarly.index(), mtaRedLate.index());
        assertNotEquals(mtaRedEarly.patternIndex(), lirrRedEarly.patternIndex());
        assertEquals("mta:RED", mtaRedEarly.routeId().toString());
        assertEquals("mta:WEEKDAY", mtaRedEarly.serviceId().toString());

        RaptorTripPattern redPattern = first.pattern(mtaRedEarly.patternIndex());
        assertEquals(
            List.of("mta:A", "mta:B_RED"),
            redPattern.stopIndexes().stream().map(first::stop).map(stop -> stop.id().toString()).toList()
        );
        assertEquals(2, redPattern.tripCount());
        assertEquals(1, first.patternsForStop(id("mta", "B_RED")).size());
        assertEquals(2, first.patternsForStop(id("mta", "A")).size());

        RaptorTripSchedule night = trip(first, "mta", "NIGHT");
        assertEquals(86_700, night.departureSecondsAt(0).orElseThrow());
        assertEquals(87_300, night.arrivalSecondsAt(1).orElseThrow());

        assertEquals(signature(first), signature(second));
        assertThrows(UnsupportedOperationException.class, () -> first.stops().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.trips().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.patterns().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.feedIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> redPattern.stopIndexes().clear());
        assertThrows(UnsupportedOperationException.class, () -> redPattern.tripIndexes().clear());
    }

    @Test
    void derivesPatternsWithoutChangingCompleteTripsOrInventingMissingTimes() {
        GtfsFeed feed = patternCasesFeed("synthetic");
        RaptorNetwork network = buildProgrammatic(Map.of("synthetic", feed));

        RaptorTripSchedule first = trip(network, "synthetic", "SAME_1");
        RaptorTripSchedule second = trip(network, "synthetic", "SAME_2");
        RaptorTripSchedule missing = trip(network, "synthetic", "MISSING");
        RaptorTripSchedule differentOrder = trip(network, "synthetic", "DIFFERENT_ORDER");
        RaptorTripSchedule differentAccess = trip(network, "synthetic", "DIFFERENT_ACCESS");
        RaptorTripSchedule differentDropOff = trip(network, "synthetic", "DIFFERENT_DROPOFF");
        RaptorTripSchedule differentRoute = trip(network, "synthetic", "DIFFERENT_ROUTE");
        RaptorTripSchedule differentDirection = trip(network, "synthetic", "DIFFERENT_DIRECTION");

        assertEquals(first.patternIndex(), second.patternIndex());
        assertEquals(first.patternIndex(), missing.patternIndex());
        assertNotEquals(first.patternIndex(), differentOrder.patternIndex());
        assertNotEquals(first.patternIndex(), differentAccess.patternIndex());
        assertNotEquals(first.patternIndex(), differentDropOff.patternIndex());
        assertNotEquals(first.patternIndex(), differentRoute.patternIndex());
        assertNotEquals(first.patternIndex(), differentDirection.patternIndex());

        assertEquals(5, first.stopCount());
        assertEquals(List.of(10, 20, 30, 40, 50), stopSequences(first));
        assertEquals(5, network.pattern(first.patternIndex()).stopCount());
        assertEquals(8, network.stats().tripCount());

        assertFalse(missing.hasScheduledTimeAt(2));
        assertTrue(missing.arrivalSecondsAt(2).isEmpty());
        assertTrue(missing.departureSecondsAt(2).isEmpty());
        assertEquals(4_980, missing.arrivalSecondsAt(3).orElseThrow());
        assertEquals(
            PickupDropOffType.NOT_AVAILABLE,
            network.pattern(differentAccess.patternIndex()).pickupTypeAt(1)
        );
        assertEquals(
            PickupDropOffType.NOT_AVAILABLE,
            network.pattern(differentDropOff.patternIndex()).dropOffTypeAt(3)
        );
    }

    @Test
    void partitionsOvertakingTripsIntoDeterministicNonOvertakingTimetables() {
        RaptorNetwork network = buildProgrammatic(Map.of("synthetic", overtakingFeed("synthetic")));

        RaptorTripSchedule early = trip(network, "synthetic", "EARLY");
        RaptorTripSchedule fast = trip(network, "synthetic", "FAST");
        RaptorTripSchedule later = trip(network, "synthetic", "LATER");

        assertEquals(1, network.stats().structuralPatternCount());
        assertEquals(2, network.stats().patternCount());
        assertEquals(1, network.stats().overtakingStructuralPatternCount());
        assertEquals(1, network.stats().additionalPatternsFromOvertaking());
        assertEquals(early.patternIndex(), later.patternIndex());
        assertNotEquals(early.patternIndex(), fast.patternIndex());

        for (RaptorTripPattern pattern : network.patterns()) {
            assertTrue(pattern.wasSplitForOvertaking());
            assertEquals(2, pattern.overtakingGroupCount());
            assertNonOvertaking(network, pattern);
        }
    }

    @Test
    void excludesButDoesNotEraseAnUnavailableCatalogFeed() {
        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("mta", FIXTURE, true),
            new FeedProperties("lirr", FIXTURE.resolve("missing"), true)
        ));
        TransitFeedCatalog catalog = new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties);
        FeedUnavailableException retainedFailure = catalog.entry("lirr").failure().orElseThrow();

        RaptorNetwork network = new RaptorNetworkBuilder().build(catalog);

        assertEquals(List.of("mta"), List.copyOf(network.feedIds()));
        assertEquals(List.of("lirr"), network.unavailableFeedIds());
        assertEquals(1, network.stats().feedCount());
        assertEquals(1, network.stats().unavailableFeedCount());
        assertTrue(network.stopIndex(id("mta", "A")).isPresent());
        assertTrue(network.stopIndex(id("lirr", "A")).isEmpty());
        assertSame(retainedFailure, catalog.entry("lirr").failure().orElseThrow());
        assertSame(retainedFailure, assertThrows(
            FeedUnavailableException.class,
            () -> catalog.index("lirr")
        ));
    }

    private static RaptorTripSchedule trip(RaptorNetwork network, String feedId, String tripId) {
        return network.trip(network.tripIndex(id(feedId, tripId)).orElseThrow());
    }

    private static List<Integer> stopSequences(RaptorTripSchedule trip) {
        var values = new ArrayList<Integer>();
        for (int position = 0; position < trip.stopCount(); position++) {
            values.add(trip.stopSequenceAt(position));
        }
        return List.copyOf(values);
    }

    private static void assertNonOvertaking(RaptorNetwork network, RaptorTripPattern pattern) {
        for (int tripPosition = 1; tripPosition < pattern.tripCount(); tripPosition++) {
            RaptorTripSchedule earlier = network.trip(pattern.tripIndexAt(tripPosition - 1));
            RaptorTripSchedule later = network.trip(pattern.tripIndexAt(tripPosition));
            for (int stopPosition = 0; stopPosition < pattern.stopCount(); stopPosition++) {
                if (earlier.arrivalSecondsAt(stopPosition).isPresent()
                    && later.arrivalSecondsAt(stopPosition).isPresent()) {
                    assertTrue(
                        earlier.arrivalSecondsAt(stopPosition).getAsInt()
                            <= later.arrivalSecondsAt(stopPosition).getAsInt()
                    );
                }
                if (earlier.departureSecondsAt(stopPosition).isPresent()
                    && later.departureSecondsAt(stopPosition).isPresent()) {
                    assertTrue(
                        earlier.departureSecondsAt(stopPosition).getAsInt()
                            <= later.departureSecondsAt(stopPosition).getAsInt()
                    );
                }
            }
        }
    }

    private static List<String> signature(RaptorNetwork network) {
        var signature = new ArrayList<String>();
        network.stops().forEach(stop -> signature.add("stop:" + stop.id()));
        network.trips().forEach(trip -> signature.add(
            "trip:" + trip.index() + ":" + trip.id() + ":" + trip.patternIndex()
        ));
        network.patterns().forEach(pattern -> signature.add(
            "pattern:" + pattern.index() + ":" + pattern.routeId() + ":"
                + pattern.stopIndexes() + ":" + pattern.tripIndexes()
        ));
        return List.copyOf(signature);
    }

    private static TransitFeedCatalog syntheticCatalog(String... feedIds) {
        var properties = new GtfsProperties();
        properties.setFeeds(Arrays.stream(feedIds)
            .map(feedId -> new FeedProperties(feedId, FIXTURE, true))
            .toList());
        return new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties);
    }

    private static RaptorNetwork buildProgrammatic(Map<String, GtfsFeed> feeds) {
        GtfsLoader loader = (ignored, feedId) -> feeds.get(feedId);
        var properties = new GtfsProperties();
        properties.setFeeds(feeds.keySet().stream().sorted()
            .map(feedId -> new FeedProperties(feedId, Path.of(feedId), true))
            .toList());
        return new RaptorNetworkBuilder().build(new TransitFeedCatalog(loader, properties));
    }

    private static GtfsFeed patternCasesFeed(String feedId) {
        List<Stop> stops = stops(feedId, "A", "B", "C", "D", "E");
        List<Route> routes = List.of(
            new Route(id(feedId, "R1"), "R1", "Route 1", 1),
            new Route(id(feedId, "R2"), "R2", "Route 2", 1)
        );
        List<Trip> trips = List.of(
            new Trip(id(feedId, "SAME_1"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "SAME_2"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "MISSING"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "DIFFERENT_ORDER"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "DIFFERENT_ACCESS"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "DIFFERENT_DROPOFF"), id(feedId, "R1"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "DIFFERENT_ROUTE"), id(feedId, "R2"), id(feedId, "S"), "E", "0"),
            new Trip(id(feedId, "DIFFERENT_DIRECTION"), id(feedId, "R1"), id(feedId, "S"), "E", "1")
        );
        var stopTimes = new ArrayList<StopTime>();
        addFiveStops(stopTimes, feedId, "SAME_1", List.of("A", "B", "C", "D", "E"), 3_600, false, false, false);
        addFiveStops(stopTimes, feedId, "SAME_2", List.of("A", "B", "C", "D", "E"), 4_200, false, false, false);
        addFiveStops(stopTimes, feedId, "MISSING", List.of("A", "B", "C", "D", "E"), 4_800, true, false, false);
        addFiveStops(stopTimes, feedId, "DIFFERENT_ORDER", List.of("A", "C", "B", "D", "E"), 5_400, false, false, false);
        addFiveStops(stopTimes, feedId, "DIFFERENT_ACCESS", List.of("A", "B", "C", "D", "E"), 6_000, false, true, false);
        addFiveStops(stopTimes, feedId, "DIFFERENT_DROPOFF", List.of("A", "B", "C", "D", "E"), 6_300, false, false, true);
        addFiveStops(stopTimes, feedId, "DIFFERENT_ROUTE", List.of("A", "B", "C", "D", "E"), 6_600, false, false, false);
        addFiveStops(stopTimes, feedId, "DIFFERENT_DIRECTION", List.of("A", "B", "C", "D", "E"), 7_200, false, false, false);
        return feed(feedId, stops, routes, trips, stopTimes);
    }

    private static void addFiveStops(
        List<StopTime> target,
        String feedId,
        String tripId,
        List<String> stopIds,
        int firstTime,
        boolean missingMiddle,
        boolean restrictedPickup,
        boolean restrictedDropOff
    ) {
        for (int position = 0; position < stopIds.size(); position++) {
            Integer time = missingMiddle && position == 2 ? null : firstTime + position * 60;
            PickupDropOffType pickup = restrictedPickup && position == 1
                ? PickupDropOffType.NOT_AVAILABLE
                : PickupDropOffType.REGULARLY_SCHEDULED;
            PickupDropOffType dropOff = restrictedDropOff && position == 3
                ? PickupDropOffType.NOT_AVAILABLE
                : PickupDropOffType.REGULARLY_SCHEDULED;
            target.add(new StopTime(
                id(feedId, tripId),
                id(feedId, stopIds.get(position)),
                (position + 1) * 10,
                time,
                time,
                pickup,
                dropOff
            ));
        }
    }

    private static GtfsFeed overtakingFeed(String feedId) {
        List<Stop> stops = stops(feedId, "A", "B", "C");
        List<Route> routes = List.of(new Route(id(feedId, "R"), "R", "Route", 1));
        List<Trip> trips = List.of(
            new Trip(id(feedId, "EARLY"), id(feedId, "R"), id(feedId, "S"), "C", "0"),
            new Trip(id(feedId, "FAST"), id(feedId, "R"), id(feedId, "S"), "C", "0"),
            new Trip(id(feedId, "LATER"), id(feedId, "R"), id(feedId, "S"), "C", "0")
        );
        List<StopTime> stopTimes = List.of(
            stopTime(feedId, "EARLY", "A", 1, 0),
            stopTime(feedId, "EARLY", "B", 2, 1_200),
            stopTime(feedId, "EARLY", "C", 3, 1_800),
            stopTime(feedId, "FAST", "A", 1, 600),
            stopTime(feedId, "FAST", "B", 2, 900),
            stopTime(feedId, "FAST", "C", 3, 1_500),
            stopTime(feedId, "LATER", "A", 1, 1_200),
            stopTime(feedId, "LATER", "B", 2, 1_800),
            stopTime(feedId, "LATER", "C", 3, 2_400)
        );
        return feed(feedId, stops, routes, trips, stopTimes);
    }

    private static StopTime stopTime(
        String feedId,
        String tripId,
        String stopId,
        int sequence,
        int time
    ) {
        return new StopTime(id(feedId, tripId), id(feedId, stopId), sequence, time, time);
    }

    private static List<Stop> stops(String feedId, String... stopIds) {
        return Arrays.stream(stopIds)
            .map(stopId -> new Stop(id(feedId, stopId), stopId, 40.0, -74.0, 0, null))
            .toList();
    }

    private static GtfsFeed feed(
        String feedId,
        List<Stop> stops,
        List<Route> routes,
        List<Trip> trips,
        List<StopTime> stopTimes
    ) {
        return new GtfsFeed(
            feedId,
            ZoneId.of("America/New_York"),
            stops,
            routes,
            trips,
            stopTimes,
            List.of(new ServiceCalendar(
                id(feedId, "S"),
                Set.of(DayOfWeek.MONDAY),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)
            )),
            List.of()
        );
    }

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            return fixtureDirectory();
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = RaptorNetworkTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
