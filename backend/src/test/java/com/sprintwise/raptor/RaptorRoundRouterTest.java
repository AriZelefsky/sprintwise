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
import com.sprintwise.service.TransitFeedCatalog;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
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
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RaptorRoundRouterTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Test
    void reconstructsOneTwoAndThreeTripJourneysInChronologicalOrder() {
        RaptorNetwork network = network(Map.of("toy", roundSemanticsFeed("toy")));
        var router = new RaptorRoundRouter(network);
        var reconstructor = new RaptorJourneyReconstructor();
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorSearchResult directResult = router.route(
            id("toy", "A"), id("toy", "E"), query, 3
        );
        RaptorJourney direct = reconstructor.reconstruct(directResult).orElseThrow();
        assertSame(directResult, direct.searchResult());
        assertEquals(id("toy", "A"), direct.origin());
        assertEquals(id("toy", "E"), direct.destination());
        assertEquals(query, direct.requestedDepartureInstant());
        assertEquals(localInstant(2026, 8, 17, 9, 0), direct.arrivalInstant());
        assertEquals(1, direct.numberOfBoardings());
        RaptorTransitLeg directLeg = direct.legs().getFirst();
        assertEquals(id("toy", "DIRECT_FIVE"), directLeg.tripId());
        assertEquals(id("toy", "DIRECT"), directLeg.routeId());
        assertEquals(id("toy", "ALL"), directLeg.serviceId());
        assertEquals(MONDAY, directLeg.serviceDate());
        assertEquals(id("toy", "A"), directLeg.boardingStopId());
        assertEquals(id("toy", "E"), directLeg.alightingStopId());
        assertEquals(0, directLeg.boardingStopPosition());
        assertEquals(4, directLeg.alightingStopPosition());
        assertEquals(28_800, directLeg.scheduledDepartureSeconds());
        assertEquals(32_400, directLeg.scheduledArrivalSeconds());
        assertEquals(localInstant(2026, 8, 17, 8, 0), directLeg.departureInstant());
        assertEquals(localInstant(2026, 8, 17, 9, 0), directLeg.arrivalInstant());

        RaptorJourney twoTrips = reconstructor.reconstruct(router.route(
            id("toy", "A"), id("toy", "C"), query, 3
        )).orElseThrow();
        assertEquals(
            List.of(id("toy", "LEG_1"), id("toy", "LEG_2")),
            twoTrips.legs().stream().map(RaptorTransitLeg::tripId).toList()
        );
        assertEquals(
            List.of(id("toy", "LOCAL"), id("toy", "LOCAL")),
            twoTrips.legs().stream().map(RaptorTransitLeg::routeId).toList()
        );
        assertEquals(
            twoTrips.legs().get(0).alightingStopId(),
            twoTrips.legs().get(1).boardingStopId()
        );

        RaptorJourney threeTrips = reconstructor.reconstruct(router.route(
            id("toy", "A"), id("toy", "D"), query, 3
        )).orElseThrow();
        assertEquals(
            List.of(id("toy", "LEG_1"), id("toy", "LEG_2"), id("toy", "LEG_3")),
            threeTrips.legs().stream().map(RaptorTransitLeg::tripId).toList()
        );
        assertEquals(3, threeTrips.numberOfBoardings());
        for (int index = 1; index < threeTrips.legs().size(); index++) {
            RaptorTransitLeg previous = threeTrips.legs().get(index - 1);
            RaptorTransitLeg current = threeTrips.legs().get(index);
            assertEquals(previous.alightingStopId(), current.boardingStopId());
            assertFalse(current.departureInstant().isBefore(previous.arrivalInstant()));
        }
    }

    @Test
    void transferWaitingIsTheGapBetweenRideLegsWithoutAnInventedLeg() {
        RaptorNetwork network = network(Map.of("toy", fasterTransferFeed("toy")));
        RaptorJourney journey = new RaptorJourneyReconstructor().reconstruct(
            new RaptorRoundRouter(network).route(
                id("toy", "A"),
                id("toy", "D"),
                localInstant(2026, 8, 17, 7, 59),
                2
            )
        ).orElseThrow();

        assertEquals(2, journey.legs().size());
        assertEquals(
            Duration.ofSeconds(60),
            Duration.between(
                journey.legs().get(0).arrivalInstant(),
                journey.legs().get(1).departureInstant()
            )
        );
        assertEquals(
            journey.legs().get(0).alightingStopId(),
            journey.legs().get(1).boardingStopId()
        );
    }

    @Test
    void reconstructsPreviousServiceDateAndDefinesEmptyJourneyContracts() {
        var reconstructor = new RaptorJourneyReconstructor();
        RaptorNetwork fixture = fixtureNetwork("mta");
        Instant afterMidnight = localInstant(2026, 8, 14, 0, 4);
        RaptorJourney night = reconstructor.reconstruct(
            new RaptorRoundRouter(fixture).route(
                id("mta", "A"), id("mta", "C"), afterMidnight, 1
            )
        ).orElseThrow();

        RaptorTransitLeg nightLeg = night.legs().getFirst();
        assertEquals(id("mta", "NIGHT"), nightLeg.tripId());
        assertEquals(LocalDate.of(2026, 8, 13), nightLeg.serviceDate());
        assertEquals(86_700, nightLeg.scheduledDepartureSeconds());
        assertEquals(87_300, nightLeg.scheduledArrivalSeconds());
        assertEquals(localInstant(2026, 8, 14, 0, 5), nightLeg.departureInstant());
        assertEquals(localInstant(2026, 8, 14, 0, 15), nightLeg.arrivalInstant());

        RaptorNetwork edge = network(Map.of("edge", edgeFeed("edge")));
        var router = new RaptorRoundRouter(edge);
        Instant query = localInstant(2026, 8, 17, 7, 59);
        RaptorSearchResult sameResult = router.route(
            id("edge", "A"), id("edge", "A"), query, 3
        );
        RaptorJourney same = reconstructor.reconstruct(sameResult).orElseThrow();
        assertTrue(same.legs().isEmpty());
        assertEquals(0, same.numberOfBoardings());
        assertEquals(query, same.arrivalInstant());
        assertSame(sameResult, same.searchResult());

        RaptorSearchResult unreachable = router.route(
            id("edge", "ISOLATED"), id("edge", "T"), query, 3
        );
        assertTrue(reconstructor.reconstruct(unreachable).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> night.legs().clear());
    }

    @Test
    void reconstructedLegsPreserveFeedNamespaces() {
        RaptorNetwork network = network(Map.of(
            "mta", simpleFeed("mta"),
            "lirr", simpleFeed("lirr")
        ));
        var router = new RaptorRoundRouter(network);
        var reconstructor = new RaptorJourneyReconstructor();
        Instant query = localInstant(2026, 8, 17, 7, 59);

        for (String feedId : List.of("mta", "lirr")) {
            RaptorJourney journey = reconstructor.reconstruct(router.route(
                id(feedId, "A"), id(feedId, "B"), query, 1
            )).orElseThrow();
            RaptorTransitLeg leg = journey.legs().getFirst();
            assertEquals(feedId, journey.origin().feedId());
            assertEquals(feedId, journey.destination().feedId());
            assertEquals(feedId, leg.tripId().feedId());
            assertEquals(feedId, leg.routeId().feedId());
            assertEquals(feedId, leg.serviceId().feedId());
            assertEquals(feedId, leg.boardingStopId().feedId());
            assertEquals(feedId, leg.alightingStopId().feedId());
        }
    }

    @Test
    void rejectsAMalformedPredecessorChainInsteadOfReturningABogusJourney() {
        RaptorNetwork network = network(Map.of("edge", edgeFeed("edge")));
        FeedScopedId origin = id("edge", "A");
        FeedScopedId destination = id("edge", "T");
        Instant query = localInstant(2026, 8, 17, 7, 59);
        int destinationIndex = network.stopIndex(destination).orElseThrow();
        RaptorLabel wrongRoundZero = RaptorLabel.origin(
            destinationIndex,
            destination,
            query
        );
        var labelsByIndex = new TreeMap<Integer, RaptorLabel>();
        labelsByIndex.put(destinationIndex, wrongRoundZero);
        var labelsById = new TreeMap<FeedScopedId, RaptorLabel>();
        labelsById.put(destination, wrongRoundZero);
        var malformed = new RaptorSearchResult(
            origin,
            destination,
            query,
            3,
            List.of(new RaptorRound(0, labelsByIndex, 0)),
            labelsByIndex,
            labelsById,
            wrongRoundZero
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new RaptorJourneyReconstructor().reconstruct(malformed)
        );
        assertTrue(exception.getMessage().contains("wrong origin stop"));
    }

    @Test
    void oneRideCanCrossFiveStopsWhileTwoAndThreeTripsNeedLaterRounds() {
        RaptorNetwork network = network(Map.of("toy", roundSemanticsFeed("toy")));
        var router = new RaptorRoundRouter(network);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorSearchResult direct = router.route(id("toy", "A"), id("toy", "E"), query, 3);
        RaptorSearchResult twoTrips = router.route(id("toy", "A"), id("toy", "C"), query, 3);
        RaptorSearchResult threeTrips = router.route(id("toy", "A"), id("toy", "D"), query, 3);
        RaptorSearchResult capped = router.route(id("toy", "A"), id("toy", "C"), query, 1);

        assertEquals(1, direct.winningRound().orElseThrow());
        RaptorRide directRide = direct.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow();
        assertEquals(id("toy", "DIRECT_FIVE"), directRide.tripId());
        assertEquals(0, directRide.boardingStopPosition());
        assertEquals(4, directRide.alightingStopPosition());
        assertEquals(2, twoTrips.winningRound().orElseThrow());
        assertEquals(id("toy", "LEG_2"), twoTrips.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow().tripId());
        assertEquals(3, threeTrips.winningRound().orElseThrow());
        RaptorLabel third = threeTrips.bestDestinationLabel().orElseThrow();
        RaptorLabel second = third.predecessor().orElseThrow();
        RaptorLabel first = second.predecessor().orElseThrow();
        RaptorLabel origin = first.predecessor().orElseThrow();
        assertEquals(id("toy", "LEG_3"), third.incomingRide().orElseThrow().tripId());
        assertEquals(id("toy", "LEG_2"), second.incomingRide().orElseThrow().tripId());
        assertEquals(id("toy", "LEG_1"), first.incomingRide().orElseThrow().tripId());
        assertEquals(0, origin.round());
        assertTrue(origin.predecessor().isEmpty());
        assertFalse(capped.destinationReachable());
    }

    @Test
    void aFasterTwoTripArrivalReplacesButDoesNotEraseTheSlowerDirectAnswer() {
        RaptorNetwork network = network(Map.of("toy", fasterTransferFeed("toy")));
        var router = new RaptorRoundRouter(network);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorSearchResult oneRound = router.route(id("toy", "A"), id("toy", "D"), query, 1);
        RaptorSearchResult twoRounds = router.route(id("toy", "A"), id("toy", "D"), query, 2);

        assertEquals(id("toy", "SLOW_DIRECT"), oneRound.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow().tripId());
        assertEquals(localInstant(2026, 8, 17, 9, 0), oneRound.bestDestinationLabel()
            .orElseThrow().arrivalInstant());
        assertEquals(id("toy", "FAST_2"), twoRounds.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow().tripId());
        assertEquals(localInstant(2026, 8, 17, 8, 30), twoRounds.bestDestinationLabel()
            .orElseThrow().arrivalInstant());
        assertEquals(2, twoRounds.winningRound().orElseThrow());
    }

    @Test
    void missesAnEarlyConnectionSelectsALaterOneAndAllowsAnEqualTimeConnection() {
        RaptorNetwork network = network(Map.of("toy", connectionFeed("toy")));
        var router = new RaptorRoundRouter(network);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorLabel later = router.route(id("toy", "A"), id("toy", "C"), query, 2)
            .bestDestinationLabel().orElseThrow();
        RaptorLabel exact = router.route(id("toy", "A"), id("toy", "D"), query, 2)
            .bestDestinationLabel().orElseThrow();

        assertEquals(id("toy", "LATER"), later.incomingRide().orElseThrow().tripId());
        assertEquals(localInstant(2026, 8, 17, 8, 12), later.incomingRide()
            .orElseThrow().departureInstant());
        assertEquals(id("toy", "EXACT"), exact.incomingRide().orElseThrow().tripId());
        assertEquals(exact.predecessor().orElseThrow().arrivalInstant(), exact.incomingRide()
            .orElseThrow().departureInstant());
    }

    @Test
    void stageTwoBServiceAccessAndMissingTimeRulesControlRoundPropagation() {
        RaptorNetwork network = network(Map.of("rules", ruleFeed("rules")));
        RaptorSearchResult result = new RaptorRoundRouter(network).route(
            id("rules", "A"),
            id("rules", "W"),
            localInstant(2026, 8, 17, 7, 59),
            2
        );

        assertTrue(result.destinationReachable());
        assertEquals(id("rules", "MISSING"), result.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow().tripId());
        assertTrue(result.bestLabel(id("rules", "I")).isEmpty(), "inactive service leaked");
        assertTrue(result.bestLabel(id("rules", "P")).isEmpty(), "illegal pickup leaked");
        assertTrue(result.bestLabel(id("rules", "Q")).isEmpty(), "illegal drop-off leaked");
        assertTrue(result.bestLabel(id("rules", "M")).isEmpty(), "missing time was invented");
        assertTrue(result.bestLabel(id("rules", "R")).isPresent(), "pass-through was blocked");
    }

    @Test
    void previousServiceDateExtendedTimeCanProduceARoundLabel() {
        RaptorNetwork network = fixtureNetwork("mta");
        RaptorSearchResult result = new RaptorRoundRouter(network).route(
            id("mta", "A"),
            id("mta", "C"),
            localInstant(2026, 8, 14, 0, 4),
            1
        );

        RaptorRide ride = result.bestDestinationLabel().orElseThrow().incomingRide().orElseThrow();
        assertEquals(id("mta", "NIGHT"), ride.tripId());
        assertEquals(LocalDate.of(2026, 8, 13), ride.serviceDate());
        assertEquals(86_700, ride.departureSeconds());
        assertEquals(localInstant(2026, 8, 14, 0, 5), ride.departureInstant());
    }

    @Test
    void scansEveryOvertakingPartitionAndKeepsTheEarliestArrival() {
        RaptorNetwork network = network(Map.of("overtake", overtakingFeed("overtake")));
        assertEquals(2, network.patterns().size());

        RaptorLabel label = new RaptorRoundRouter(network).route(
            id("overtake", "A"),
            id("overtake", "C"),
            localInstant(2026, 8, 17, 10, 59),
            1
        ).bestDestinationLabel().orElseThrow();

        assertEquals(id("overtake", "FAST"), label.incomingRide().orElseThrow().tripId());
        assertEquals(localInstant(2026, 8, 17, 11, 25), label.arrivalInstant());
    }

    @Test
    void tiesAreDeterministicOnlyMarkedPatternsAreScannedAndSearchStopsEarly() {
        RaptorNetwork network = network(Map.of("edge", edgeFeed("edge")));
        var router = new RaptorRoundRouter(network);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorSearchResult tie = router.route(id("edge", "A"), id("edge", "T"), query, 1);
        RaptorSearchResult tieAgain = router.route(id("edge", "A"), id("edge", "T"), query, 1);
        int patternsAtOrigin = network.patternIndexesForStop(
            network.stopIndex(id("edge", "A")).orElseThrow()
        ).size();

        assertEquals(id("edge", "TIE_A"), tie.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow().tripId());
        assertEquals(
            tie.bestDestinationLabel().orElseThrow().incomingRide().orElseThrow().tripId(),
            tieAgain.bestDestinationLabel().orElseThrow().incomingRide().orElseThrow().tripId()
        );
        assertEquals(patternsAtOrigin, tie.rounds().get(1).patternScanCount());
        assertTrue(patternsAtOrigin < network.patterns().size());

        RaptorSearchResult isolated = router.route(
            id("edge", "ISOLATED"), id("edge", "T"), query, 5
        );
        assertFalse(isolated.destinationReachable());
        assertEquals(1, isolated.completedTransitRounds());
        assertFalse(isolated.rounds().getLast().hasImprovements());

        RaptorSearchResult cycle = router.route(id("edge", "X"), id("edge", "Y"), query, 5);
        assertTrue(cycle.destinationReachable());
        assertTrue(cycle.completedTransitRounds() < 5);
        assertEquals(1, cycle.winningRound().orElseThrow());
    }

    @Test
    void validatesIdsHandlesRoundZeroAndReturnsImmutableUnreachableResults() {
        RaptorNetwork network = network(Map.of("edge", edgeFeed("edge")));
        var router = new RaptorRoundRouter(network);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        IllegalArgumentException unknownOrigin = assertThrows(
            IllegalArgumentException.class,
            () -> router.route(id("edge", "UNKNOWN"), id("edge", "T"), query, 2)
        );
        IllegalArgumentException unknownDestination = assertThrows(
            IllegalArgumentException.class,
            () -> router.route(id("edge", "A"), id("edge", "UNKNOWN"), query, 2)
        );
        assertTrue(unknownOrigin.getMessage().contains("origin"));
        assertTrue(unknownDestination.getMessage().contains("destination"));
        assertThrows(
            IllegalArgumentException.class,
            () -> router.route(id("edge", "A"), id("edge", "T"), query, 0)
        );

        RaptorSearchResult same = router.route(id("edge", "A"), id("edge", "A"), query, 3);
        assertTrue(same.destinationReachable());
        assertEquals(0, same.winningRound().orElseThrow());
        assertEquals(query, same.bestDestinationLabel().orElseThrow().arrivalInstant());
        assertEquals(0, same.completedTransitRounds());
        assertTrue(same.bestDestinationLabel().orElseThrow().incomingRide().isEmpty());

        RaptorSearchResult unreachable = router.route(
            id("edge", "ISOLATED"), id("edge", "T"), query, 2
        );
        assertFalse(unreachable.destinationReachable());
        assertTrue(unreachable.bestDestinationLabel().isEmpty());
        assertTrue(unreachable.winningRound().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> unreachable.rounds().clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> unreachable.bestLabelsByStopIndex().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> unreachable.rounds().getFirst().markedStopIndexes().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> unreachable.rounds().getFirst().improvedLabels().clear()
        );
    }

    @Test
    void oneEngineKeepsMtaAndLirrIdsSeparateAndIgnoresAnUnavailableFeed() {
        RaptorNetwork both = network(Map.of(
            "mta", simpleFeed("mta"),
            "lirr", simpleFeed("lirr")
        ));
        var router = new RaptorRoundRouter(both);
        Instant query = localInstant(2026, 8, 17, 7, 59);

        RaptorSearchResult mta = router.route(id("mta", "A"), id("mta", "B"), query, 1);
        RaptorSearchResult lirr = router.route(id("lirr", "A"), id("lirr", "B"), query, 1);

        assertEquals("mta", mta.bestDestinationLabel().orElseThrow().stopId().feedId());
        assertEquals("lirr", lirr.bestDestinationLabel().orElseThrow().stopId().feedId());
        assertNotEquals(
            mta.bestDestinationLabel().orElseThrow().stopIndex(),
            lirr.bestDestinationLabel().orElseThrow().stopIndex()
        );
        assertFalse(router.route(
            id("mta", "A"), id("lirr", "B"), query, 3
        ).destinationReachable());

        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("mta", FIXTURE, true),
            new FeedProperties("lirr", FIXTURE.resolve("missing"), true)
        ));
        RaptorNetwork partial = new RaptorNetworkBuilder().build(
            new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties)
        );
        RaptorSearchResult available = new RaptorRoundRouter(partial).route(
            id("mta", "A"),
            id("mta", "C"),
            query,
            1
        );
        assertTrue(available.destinationReachable());
        assertEquals(List.of("lirr"), partial.unavailableFeedIds());
    }

    private static GtfsFeed roundSemanticsFeed(String feedId) {
        List<TripSpec> trips = List.of(
            trip("DIRECT_FIVE", "DIRECT", "ALL", "A", 28_800, "P", 29_400,
                "Q", 30_000, "R", 30_600, "E", 32_400),
            trip("LEG_1", "LOCAL", "ALL", "A", 28_800, "B", 29_400),
            trip("LEG_2", "LOCAL", "ALL", "B", 29_400, "C", 30_000),
            trip("LEG_3", "LOCAL", "ALL", "C", 30_000, "D", 30_600)
        );
        return feed(feedId, trips, List.of(allDays(feedId, "ALL")));
    }

    private static GtfsFeed fasterTransferFeed(String feedId) {
        return feed(feedId, List.of(
            trip("SLOW_DIRECT", "DIRECT", "ALL", "A", 28_800, "D", 32_400),
            trip("FAST_1", "LEG1", "ALL", "A", 28_800, "B", 29_400),
            trip("FAST_2", "LEG2", "ALL", "B", 29_460, "D", 30_600)
        ), List.of(allDays(feedId, "ALL")));
    }

    private static GtfsFeed connectionFeed(String feedId) {
        return feed(feedId, List.of(
            trip("FIRST", "FIRST", "ALL", "A", 28_800, "B", 29_400),
            trip("MISSED", "TO_C", "ALL", "B", 29_340, "C", 30_000),
            trip("LATER", "TO_C", "ALL", "B", 29_520, "C", 30_120),
            trip("EXACT", "TO_D", "ALL", "B", 29_400, "D", 29_880)
        ), List.of(allDays(feedId, "ALL")));
    }

    private static GtfsFeed ruleFeed(String feedId) {
        List<Stop> stops = stops(feedId, "A", "B", "I", "P", "Q", "R", "M", "W");
        List<Route> routes = routes(feedId, "BASE", "INACTIVE", "NO_PICKUP", "NO_DROP", "MISSING");
        List<Trip> trips = List.of(
            modelTrip(feedId, "BASE", "BASE", "MONDAY"),
            modelTrip(feedId, "INACTIVE", "INACTIVE", "TUESDAY"),
            modelTrip(feedId, "NO_PICKUP", "NO_PICKUP", "MONDAY"),
            modelTrip(feedId, "NO_DROP", "NO_DROP", "MONDAY"),
            modelTrip(feedId, "MISSING", "MISSING", "MONDAY")
        );
        List<StopTime> stopTimes = List.of(
            stopTime(feedId, "BASE", "A", 1, 28_800),
            stopTime(feedId, "BASE", "B", 2, 29_400),
            stopTime(feedId, "INACTIVE", "B", 1, 29_400),
            stopTime(feedId, "INACTIVE", "I", 2, 30_000),
            accessStopTime(feedId, "NO_PICKUP", "B", 1, 29_400, false, true),
            stopTime(feedId, "NO_PICKUP", "P", 2, 30_000),
            stopTime(feedId, "NO_DROP", "B", 1, 29_400),
            accessStopTime(feedId, "NO_DROP", "Q", 2, 29_700, true, false),
            stopTime(feedId, "NO_DROP", "R", 3, 30_000),
            stopTime(feedId, "MISSING", "B", 1, 29_400),
            new StopTime(id(feedId, "MISSING"), id(feedId, "M"), 2, null, null),
            stopTime(feedId, "MISSING", "W", 3, 30_300)
        );
        return new GtfsFeed(
            feedId,
            NEW_YORK,
            stops,
            routes,
            trips,
            stopTimes,
            List.of(
                new ServiceCalendar(id(feedId, "MONDAY"), Set.of(DayOfWeek.MONDAY), MONDAY, MONDAY),
                new ServiceCalendar(id(feedId, "TUESDAY"), Set.of(DayOfWeek.TUESDAY), MONDAY, MONDAY.plusDays(1))
            ),
            List.of()
        );
    }

    private static GtfsFeed edgeFeed(String feedId) {
        return feed(feedId, List.of(
            trip("TIE_Z", "TIE_Z_ROUTE", "ALL", "A", 28_800, "T", 29_400),
            trip("TIE_A", "TIE_A_ROUTE", "ALL", "A", 28_800, "T", 29_400),
            trip("UNRELATED", "UNRELATED", "ALL", "U", 28_800, "V", 29_400),
            trip("CYCLE", "CYCLE", "ALL", "X", 28_800, "Y", 29_100, "X", 29_400)
        ), List.of(allDays(feedId, "ALL")), List.of("ISOLATED"));
    }

    private static GtfsFeed simpleFeed(String feedId) {
        return feed(feedId, List.of(
            trip("TRIP", "ROUTE", "ALL", "A", 28_800, "B", 29_400)
        ), List.of(allDays(feedId, "ALL")));
    }

    private static GtfsFeed overtakingFeed(String feedId) {
        return feed(feedId, List.of(
            trip("SLOW", "ROUTE", "ALL", "A", 39_600, "B", 40_800, "C", 41_400),
            trip("FAST", "ROUTE", "ALL", "A", 40_200, "B", 40_500, "C", 41_100)
        ), List.of(allDays(feedId, "ALL")));
    }

    private static GtfsFeed feed(
        String feedId,
        List<TripSpec> tripSpecs,
        List<ServiceCalendar> calendars
    ) {
        return feed(feedId, tripSpecs, calendars, List.of());
    }

    private static GtfsFeed feed(
        String feedId,
        List<TripSpec> tripSpecs,
        List<ServiceCalendar> calendars,
        List<String> extraStops
    ) {
        var stopIds = new java.util.TreeSet<String>(extraStops);
        var routeIds = new java.util.TreeSet<String>();
        var trips = new ArrayList<Trip>();
        var stopTimes = new ArrayList<StopTime>();
        for (TripSpec spec : tripSpecs) {
            routeIds.add(spec.routeId());
            trips.add(modelTrip(feedId, spec.tripId(), spec.routeId(), spec.serviceId()));
            for (int index = 0; index < spec.stops().size(); index++) {
                TimedStop stop = spec.stops().get(index);
                stopIds.add(stop.stopId());
                stopTimes.add(stopTime(
                    feedId,
                    spec.tripId(),
                    stop.stopId(),
                    index + 1,
                    stop.seconds()
                ));
            }
        }
        return new GtfsFeed(
            feedId,
            NEW_YORK,
            stops(feedId, stopIds.toArray(String[]::new)),
            routes(feedId, routeIds.toArray(String[]::new)),
            trips,
            stopTimes,
            calendars,
            List.of()
        );
    }

    private static TripSpec trip(
        String tripId,
        String routeId,
        String serviceId,
        Object... stopAndTimePairs
    ) {
        if (stopAndTimePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Stops and times must be paired");
        }
        var stops = new ArrayList<TimedStop>();
        for (int index = 0; index < stopAndTimePairs.length; index += 2) {
            stops.add(new TimedStop(
                (String) stopAndTimePairs[index],
                (Integer) stopAndTimePairs[index + 1]
            ));
        }
        return new TripSpec(tripId, routeId, serviceId, List.copyOf(stops));
    }

    private static ServiceCalendar allDays(String feedId, String serviceId) {
        return new ServiceCalendar(
            id(feedId, serviceId),
            EnumSet.allOf(DayOfWeek.class),
            MONDAY.minusDays(30),
            MONDAY.plusDays(30)
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
            pickupAllowed ? PickupDropOffType.REGULARLY_SCHEDULED : PickupDropOffType.NOT_AVAILABLE,
            dropOffAllowed ? PickupDropOffType.REGULARLY_SCHEDULED : PickupDropOffType.NOT_AVAILABLE
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

    private static Trip modelTrip(
        String feedId,
        String tripId,
        String routeId,
        String serviceId
    ) {
        return new Trip(
            id(feedId, tripId),
            id(feedId, routeId),
            id(feedId, serviceId),
            tripId,
            "0"
        );
    }

    private static List<Route> routes(String feedId, String... routeIds) {
        return Arrays.stream(routeIds)
            .map(routeId -> new Route(id(feedId, routeId), routeId, routeId, 1))
            .toList();
    }

    private static List<Stop> stops(String feedId, String... stopIds) {
        return Arrays.stream(stopIds)
            .map(stopId -> new Stop(id(feedId, stopId), stopId, 40.0, -74.0, 0, null))
            .toList();
    }

    private static RaptorNetwork network(Map<String, GtfsFeed> feeds) {
        GtfsLoader loader = (ignored, feedId) -> feeds.get(feedId);
        var properties = new GtfsProperties();
        properties.setFeeds(feeds.keySet().stream().sorted()
            .map(feedId -> new FeedProperties(feedId, Path.of(feedId), true))
            .toList());
        return new RaptorNetworkBuilder().build(new TransitFeedCatalog(loader, properties));
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

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static Instant localInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            var resource = RaptorRoundRouterTest.class.getClassLoader()
                .getResource("fixtures/synthetic-gtfs");
            if (resource == null) {
                throw new IOException("Missing synthetic GTFS fixture");
            }
            return Path.of(resource.toURI());
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record TimedStop(String stopId, int seconds) {}

    private record TripSpec(
        String tripId,
        String routeId,
        String serviceId,
        List<TimedStop> stops
    ) {}
}
