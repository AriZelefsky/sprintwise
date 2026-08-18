package com.sprintwise.raptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.sprintwise.service.TransitFeedCatalog;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RaptorPatternScannerTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Test
    void selectsTheEarliestBoardableWeekdayTripWithoutScanningOtherPatterns() {
        RaptorNetwork network = fixtureNetwork("mta");
        RaptorTripSchedule redEarly = trip(network, "mta", "RED_EARLY");
        int stopA = stopIndex(network, "mta", "A");
        var scanner = new RaptorPatternScanner(network);

        RaptorRide first = onlyRide(scanner.scan(
            redEarly.patternIndex(),
            stopA,
            localInstant(2026, 8, 17, 7, 59)
        ));
        RaptorRide afterFirstDeparture = onlyRide(scanner.scan(
            redEarly.patternIndex(),
            stopA,
            localInstant(2026, 8, 17, 8, 1)
        ));

        assertEquals(id("mta", "RED_EARLY"), first.tripId());
        assertEquals(28_800, first.departureSeconds());
        assertEquals(29_100, first.arrivalSeconds());
        assertEquals(id("mta", "RED_LATE"), afterFirstDeparture.tripId());
        assertEquals(29_700, afterFirstDeparture.arrivalSeconds());
    }

    @Test
    void appliesWeekendAddRemoveAndPreviousServiceDateCalendarRules() {
        RaptorNetwork network = fixtureNetwork("mta");
        RaptorTripSchedule direct = trip(network, "mta", "NIGHT");
        int stopA = stopIndex(network, "mta", "A");
        var scanner = new RaptorPatternScanner(network);

        RaptorRide weekend = onlyRide(scanner.scan(
            direct.patternIndex(),
            stopA,
            localInstant(2026, 8, 15, 9, 0)
        ));
        RaptorRide addition = onlyRide(scanner.scan(
            direct.patternIndex(),
            stopA,
            localInstant(2026, 8, 13, 11, 59)
        ));
        List<RaptorRide> removal = scanner.scan(
            direct.patternIndex(),
            stopA,
            localInstant(2026, 8, 18, 7, 0)
        );
        RaptorRide afterMidnight = onlyRide(scanner.scan(
            direct.patternIndex(),
            stopA,
            localInstant(2026, 8, 14, 0, 4)
        ));

        assertEquals(id("mta", "WEEKEND_ONLY"), weekend.tripId());
        assertEquals(LocalDate.of(2026, 8, 15), weekend.serviceDate());
        assertEquals(id("mta", "SPECIAL_ONLY"), addition.tripId());
        assertEquals(LocalDate.of(2026, 8, 13), addition.serviceDate());
        assertTrue(removal.isEmpty());
        assertEquals(id("mta", "NIGHT"), afterMidnight.tripId());
        assertEquals(LocalDate.of(2026, 8, 13), afterMidnight.serviceDate());
        assertEquals(86_700, afterMidnight.departureSeconds());
        assertEquals(localInstant(2026, 8, 14, 0, 5), afterMidnight.departureInstant());
    }

    @Test
    void usesALaterTripWhenTheEarlierTripsServiceIsInactive() {
        String feedId = "synthetic";
        List<Trip> trips = List.of(
            trip(feedId, "EARLY", "R", "MONDAY"),
            trip(feedId, "LATE", "R", "TUESDAY")
        );
        List<StopTime> stopTimes = List.of(
            stopTime(feedId, "EARLY", "A", 1, 28_800),
            stopTime(feedId, "EARLY", "B", 2, 29_400),
            stopTime(feedId, "LATE", "A", 1, 29_100),
            stopTime(feedId, "LATE", "B", 2, 29_700)
        );
        LocalDate start = LocalDate.of(2026, 8, 17);
        GtfsFeed feed = feed(
            feedId,
            stops(feedId, "A", "B"),
            List.of(route(feedId, "R")),
            trips,
            stopTimes,
            List.of(
                new ServiceCalendar(id(feedId, "MONDAY"), Set.of(DayOfWeek.MONDAY), start, start.plusDays(7)),
                new ServiceCalendar(id(feedId, "TUESDAY"), Set.of(DayOfWeek.TUESDAY), start, start.plusDays(7))
            )
        );
        RaptorNetwork network = programmaticNetwork(Map.of(feedId, feed));
        RaptorTripSchedule early = trip(network, feedId, "EARLY");

        RaptorRide ride = onlyRide(new RaptorPatternScanner(network).scan(
            early.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 18, 7, 59)
        ));

        assertEquals(id(feedId, "LATE"), ride.tripId());
        assertEquals(id(feedId, "TUESDAY"), ride.serviceId());
    }

    @Test
    void preservesFiveStopTripsMissingTimesAndPickupDropOffRules() {
        String feedId = "rules";
        List<Stop> stops = stops(feedId, "A", "B", "C", "D", "E");
        List<Route> routes = List.of(
            route(feedId, "MISSING_ROUTE"),
            route(feedId, "PICKUP_ROUTE"),
            route(feedId, "DROPOFF_ROUTE")
        );
        List<Trip> trips = List.of(
            trip(feedId, "MISSING", "MISSING_ROUTE", "ALL"),
            trip(feedId, "NO_PICKUP", "PICKUP_ROUTE", "ALL"),
            trip(feedId, "NO_DROPOFF", "DROPOFF_ROUTE", "ALL")
        );
        List<StopTime> stopTimes = new ArrayList<>(List.of(
            stopTime(feedId, "MISSING", "A", 1, 28_800),
            stopTime(feedId, "MISSING", "B", 2, 28_860),
            new StopTime(id(feedId, "MISSING"), id(feedId, "C"), 3, null, null),
            stopTime(feedId, "MISSING", "D", 4, 28_980),
            stopTime(feedId, "MISSING", "E", 5, 29_040),
            accessStopTime(feedId, "NO_PICKUP", "A", 1, 29_400, false, true),
            stopTime(feedId, "NO_PICKUP", "B", 2, 29_460),
            stopTime(feedId, "NO_DROPOFF", "A", 1, 30_000),
            accessStopTime(feedId, "NO_DROPOFF", "B", 2, 30_060, true, false),
            stopTime(feedId, "NO_DROPOFF", "C", 3, 30_120)
        ));
        GtfsFeed feed = feedWithAllDays(feedId, stops, routes, trips, stopTimes);
        RaptorNetwork network = programmaticNetwork(Map.of(feedId, feed));
        var scanner = new RaptorPatternScanner(network);

        RaptorTripSchedule missing = trip(network, feedId, "MISSING");
        List<RaptorRide> missingResults = scanner.scan(
            missing.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 7, 59)
        );
        RaptorTripSchedule noPickup = trip(network, feedId, "NO_PICKUP");
        RaptorTripSchedule noDropOff = trip(network, feedId, "NO_DROPOFF");
        List<RaptorRide> dropOffResults = scanner.scan(
            noDropOff.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 8, 19)
        );

        assertEquals(5, missing.stopCount());
        assertEquals(List.of("B", "D", "E"), rawAlightingStopIds(network, missingResults));
        assertTrue(missingResults.stream().allMatch(ride -> ride.tripId().equals(missing.id())));
        assertTrue(scanner.scan(
            noPickup.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 8, 9)
        ).isEmpty());
        assertEquals(List.of("C"), rawAlightingStopIds(network, dropOffResults));
    }

    @Test
    void handlesRepeatedStopsEqualTimesAndOvertakingPatternsDeterministically() {
        String feedId = "edge";
        GtfsFeed feed = edgeCasesFeed(feedId);
        RaptorNetwork network = programmaticNetwork(Map.of(feedId, feed));
        var scanner = new RaptorPatternScanner(network);

        RaptorTripSchedule loop = trip(network, feedId, "LOOP");
        RaptorRide loopRide = onlyRide(scanner.scan(
            loop.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 9, 1)
        ));
        RaptorTripSchedule tieA = trip(network, feedId, "TIE_A");
        RaptorRide tieRide = onlyRide(scanner.scan(
            tieA.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 9, 59)
        ));
        RaptorTripSchedule slow = trip(network, feedId, "SLOW");
        RaptorTripSchedule fast = trip(network, feedId, "FAST");
        RaptorRide slowResult = scanner.scan(
            slow.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 10, 59)
        ).stream().filter(ride -> rawAlightingStopId(network, ride).equals("C")).findFirst().orElseThrow();
        RaptorRide fastResult = scanner.scan(
            fast.patternIndex(),
            stopIndex(network, feedId, "A"),
            localInstant(2026, 8, 17, 10, 59)
        ).stream().filter(ride -> rawAlightingStopId(network, ride).equals("C")).findFirst().orElseThrow();

        assertEquals(2, loopRide.boardingStopPosition());
        assertEquals("C", rawAlightingStopId(network, loopRide));
        assertEquals(id(feedId, "TIE_A"), tieRide.tripId());
        assertTrue(network.pattern(slow.patternIndex()).wasSplitForOvertaking());
        assertTrue(network.pattern(fast.patternIndex()).wasSplitForOvertaking());
        assertEquals(id(feedId, "SLOW"), slowResult.tripId());
        assertEquals(id(feedId, "FAST"), fastResult.tripId());
        assertTrue(fastResult.arrivalInstant().isBefore(slowResult.arrivalInstant()));
    }

    @Test
    void scansBothNamespacesAndAnAvailableFeedWhenAnotherFeedFailed() {
        RaptorNetwork both = fixtureNetwork("mta", "lirr");
        var scanner = new RaptorPatternScanner(both);

        RaptorTripSchedule mtaTrip = trip(both, "mta", "RED_EARLY");
        RaptorTripSchedule lirrTrip = trip(both, "lirr", "RED_EARLY");
        RaptorRide mtaRide = onlyRide(scanner.scan(
            mtaTrip.patternIndex(),
            stopIndex(both, "mta", "A"),
            localInstant(2026, 8, 17, 7, 59)
        ));
        RaptorRide lirrRide = onlyRide(scanner.scan(
            lirrTrip.patternIndex(),
            stopIndex(both, "lirr", "A"),
            localInstant(2026, 8, 17, 7, 59)
        ));

        assertEquals(id("mta", "RED_EARLY"), mtaRide.tripId());
        assertEquals(id("lirr", "RED_EARLY"), lirrRide.tripId());

        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("mta", FIXTURE, true),
            new FeedProperties("lirr", FIXTURE.resolve("missing"), true)
        ));
        RaptorNetwork partial = new RaptorNetworkBuilder().build(
            new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties)
        );
        RaptorTripSchedule available = trip(partial, "mta", "RED_EARLY");
        assertEquals(id("mta", "RED_EARLY"), onlyRide(new RaptorPatternScanner(partial).scan(
            available.patternIndex(),
            stopIndex(partial, "mta", "A"),
            localInstant(2026, 8, 17, 7, 59)
        )).tripId());
    }

    @Test
    void documentsEmptyInvalidAndImmutableApiResults() {
        RaptorNetwork network = fixtureNetwork("mta");
        RaptorTripSchedule red = trip(network, "mta", "RED_EARLY");
        var scanner = new RaptorPatternScanner(network);

        List<RaptorRide> absent = scanner.scan(
            red.patternIndex(),
            stopIndex(network, "mta", "C"),
            localInstant(2026, 8, 17, 7, 59)
        );

        assertTrue(absent.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> absent.add(null));
        List<RaptorRide> rides = scanner.scan(
            red.patternIndex(),
            stopIndex(network, "mta", "A"),
            localInstant(2026, 8, 17, 7, 59)
        );
        assertThrows(UnsupportedOperationException.class, rides::clear);
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(
            -1,
            stopIndex(network, "mta", "A"),
            Instant.EPOCH
        ));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(
            red.patternIndex(),
            network.stops().size(),
            Instant.EPOCH
        ));
        assertThrows(NullPointerException.class, () -> scanner.scan(
            red.patternIndex(),
            stopIndex(network, "mta", "A"),
            null
        ));
    }

    private static GtfsFeed edgeCasesFeed(String feedId) {
        List<Stop> stops = stops(feedId, "A", "B", "C");
        List<Route> routes = List.of(
            route(feedId, "LOOP_ROUTE"),
            route(feedId, "TIE_ROUTE"),
            route(feedId, "OVERTAKE_ROUTE")
        );
        List<Trip> trips = List.of(
            trip(feedId, "LOOP", "LOOP_ROUTE", "ALL"),
            trip(feedId, "TIE_A", "TIE_ROUTE", "ALL"),
            trip(feedId, "TIE_Z", "TIE_ROUTE", "ALL"),
            trip(feedId, "SLOW", "OVERTAKE_ROUTE", "ALL"),
            trip(feedId, "FAST", "OVERTAKE_ROUTE", "ALL"),
            trip(feedId, "LATER", "OVERTAKE_ROUTE", "ALL")
        );
        List<StopTime> stopTimes = List.of(
            stopTime(feedId, "LOOP", "A", 1, 32_400),
            stopTime(feedId, "LOOP", "B", 2, 32_460),
            stopTime(feedId, "LOOP", "A", 3, 32_520),
            stopTime(feedId, "LOOP", "C", 4, 32_580),
            stopTime(feedId, "TIE_A", "A", 1, 36_000),
            stopTime(feedId, "TIE_A", "B", 2, 36_300),
            stopTime(feedId, "TIE_Z", "A", 1, 36_000),
            stopTime(feedId, "TIE_Z", "B", 2, 36_300),
            stopTime(feedId, "SLOW", "A", 1, 39_600),
            stopTime(feedId, "SLOW", "B", 2, 40_800),
            stopTime(feedId, "SLOW", "C", 3, 41_400),
            stopTime(feedId, "FAST", "A", 1, 40_200),
            stopTime(feedId, "FAST", "B", 2, 40_500),
            stopTime(feedId, "FAST", "C", 3, 41_100),
            stopTime(feedId, "LATER", "A", 1, 40_800),
            stopTime(feedId, "LATER", "B", 2, 41_400),
            stopTime(feedId, "LATER", "C", 3, 42_000)
        );
        return feedWithAllDays(feedId, stops, routes, trips, stopTimes);
    }

    private static GtfsFeed feedWithAllDays(
        String feedId,
        List<Stop> stops,
        List<Route> routes,
        List<Trip> trips,
        List<StopTime> stopTimes
    ) {
        LocalDate start = LocalDate.of(2026, 8, 1);
        return feed(
            feedId,
            stops,
            routes,
            trips,
            stopTimes,
            List.of(new ServiceCalendar(
                id(feedId, "ALL"),
                EnumSet.allOf(DayOfWeek.class),
                start,
                start.plusMonths(1)
            ))
        );
    }

    private static GtfsFeed feed(
        String feedId,
        List<Stop> stops,
        List<Route> routes,
        List<Trip> trips,
        List<StopTime> stopTimes,
        List<ServiceCalendar> calendars
    ) {
        return new GtfsFeed(
            feedId,
            NEW_YORK,
            stops,
            routes,
            trips,
            stopTimes,
            calendars,
            List.of()
        );
    }

    private static StopTime accessStopTime(
        String feedId,
        String tripId,
        String stopId,
        int sequence,
        int time,
        boolean pickupAllowed,
        boolean dropOffAllowed
    ) {
        return new StopTime(
            id(feedId, tripId),
            id(feedId, stopId),
            sequence,
            time,
            time,
            pickupAllowed
                ? PickupDropOffType.REGULARLY_SCHEDULED
                : PickupDropOffType.NOT_AVAILABLE,
            dropOffAllowed
                ? PickupDropOffType.REGULARLY_SCHEDULED
                : PickupDropOffType.NOT_AVAILABLE
        );
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

    private static Trip trip(String feedId, String tripId, String routeId, String serviceId) {
        return new Trip(
            id(feedId, tripId),
            id(feedId, routeId),
            id(feedId, serviceId),
            tripId,
            "0"
        );
    }

    private static Route route(String feedId, String routeId) {
        return new Route(id(feedId, routeId), routeId, routeId, 1);
    }

    private static List<Stop> stops(String feedId, String... stopIds) {
        return Arrays.stream(stopIds)
            .map(stopId -> new Stop(id(feedId, stopId), stopId, 40.0, -74.0, 0, null))
            .toList();
    }

    private static List<String> rawAlightingStopIds(
        RaptorNetwork network,
        List<RaptorRide> rides
    ) {
        return rides.stream().map(ride -> rawAlightingStopId(network, ride)).sorted().toList();
    }

    private static String rawAlightingStopId(RaptorNetwork network, RaptorRide ride) {
        return network.stop(ride.alightingStopIndex()).id().id();
    }

    private static RaptorRide onlyRide(List<RaptorRide> rides) {
        assertEquals(1, rides.size());
        return rides.getFirst();
    }

    private static RaptorTripSchedule trip(RaptorNetwork network, String feedId, String tripId) {
        return network.trip(network.tripIndex(id(feedId, tripId)).orElseThrow());
    }

    private static int stopIndex(RaptorNetwork network, String feedId, String stopId) {
        return network.stopIndex(id(feedId, stopId)).orElseThrow();
    }

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static Instant localInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }

    private static RaptorNetwork fixtureNetwork(String... feedIds) {
        var properties = new GtfsProperties();
        properties.setFeeds(Arrays.stream(feedIds)
            .map(feedId -> new FeedProperties(feedId, FIXTURE, true))
            .toList());
        return new RaptorNetworkBuilder().build(
            new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties)
        );
    }

    private static RaptorNetwork programmaticNetwork(Map<String, GtfsFeed> feeds) {
        GtfsLoader loader = (ignored, feedId) -> feeds.get(feedId);
        var properties = new GtfsProperties();
        properties.setFeeds(feeds.keySet().stream().sorted()
            .map(feedId -> new FeedProperties(feedId, Path.of(feedId), true))
            .toList());
        return new RaptorNetworkBuilder().build(new TransitFeedCatalog(loader, properties));
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            var resource = RaptorPatternScannerTest.class.getClassLoader()
                .getResource("fixtures/synthetic-gtfs");
            if (resource == null) {
                throw new IOException("Missing synthetic GTFS fixture");
            }
            return Path.of(resource.toURI());
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
