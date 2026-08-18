package com.sprintwise.raptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.service.TransitFeedCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional fixed-date proof of Stage 2D1 against both frozen production feeds. */
class RealRaptorRoundSearchIT {

    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Path MTA_PATH = configuredPath("mta.gtfs.path", "../data/gtfs/mta");
    private static final Path LIRR_PATH = configuredPath("lirr.gtfs.path", "../data/gtfs/lirr");

    @Test
    void findsKnownDirectMtaAndLirrJourneysWithTheSameRoundEngine() {
        Assumptions.assumeTrue(
            Files.isDirectory(MTA_PATH) && Files.isDirectory(LIRR_PATH),
            () -> "Real round-search proof skipped; expected MTA at " + MTA_PATH
                + " and LIRR at " + LIRR_PATH
        );
        assertTrue(Runtime.getRuntime().maxMemory() <= TWO_GIBIBYTES);

        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("mta", MTA_PATH, true),
            new FeedProperties("lirr", LIRR_PATH, true)
        ));
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            new OneBusAwayGtfsLoader(),
            properties
        );
        RaptorNetwork network = new RaptorNetworkBuilder().build(catalog);
        var router = new RaptorRoundRouter(network);
        Instant query = ZonedDateTime.of(2026, 8, 13, 17, 0, 0, 0, NEW_YORK).toInstant();

        long startedNanos = System.nanoTime();
        RaptorSearchResult mta = router.route(
            id("mta", "A28N"),
            id("mta", "A06N"),
            query,
            1
        );
        RaptorSearchResult lirr = router.route(
            id("lirr", "237"),
            id("lirr", "217"),
            query,
            1
        );
        var reconstructor = new RaptorJourneyReconstructor();
        RaptorJourney mtaJourney = reconstructor.reconstruct(mta).orElseThrow();
        RaptorJourney lirrJourney = reconstructor.reconstruct(lirr).orElseThrow();
        double searchAndReconstructionSeconds =
            (System.nanoTime() - startedNanos) / 1_000_000_000.0;

        assertDirectResult("mta", mta);
        assertDirectResult("lirr", lirr);
        RaptorRide mtaRide = mta.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow();
        assertEquals(
            id("mta", "BSP26GEN-A087-Weekday-00_097700_A..N54R"),
            mtaRide.tripId()
        );
        assertEquals(id("mta", "A"), mtaRide.routeId());
        assertEquals(LocalDate.of(2026, 8, 13), mtaRide.serviceDate());
        assertEquals(Instant.parse("2026-08-13T21:02:30Z"), mtaRide.departureInstant());
        assertEquals(Instant.parse("2026-08-13T21:25:30Z"), mtaRide.arrivalInstant());
        RaptorRide lirrRide = lirr.bestDestinationLabel().orElseThrow()
            .incomingRide().orElseThrow();
        assertEquals(id("lirr", "GO201_26_2772"), lirrRide.tripId());
        assertEquals(id("lirr", "7"), lirrRide.routeId());
        assertEquals(LocalDate.of(2026, 8, 13), lirrRide.serviceDate());
        assertEquals(65_820, lirrRide.departureSeconds());
        assertEquals(68_460, lirrRide.arrivalSeconds());
        assertEquals(Instant.parse("2026-08-13T22:17:00Z"), lirrRide.departureInstant());
        assertEquals(Instant.parse("2026-08-13T23:01:00Z"), lirrRide.arrivalInstant());
        assertFalse(mtaRide.tripId().equals(lirrRide.tripId()));
        assertDirectJourney(mta, mtaJourney, mtaRide);
        assertDirectJourney(lirr, lirrJourney, lirrRide);

        System.out.printf(
            "%nStage 2D1 real journey reconstruction: PASS%n%s%n%s%n"
                + "Combined search/reconstruction: %.3f s; JVM limit: %.1f MiB%n%n",
            description("MTA", mta, mtaJourney),
            description("LIRR", lirr, lirrJourney),
            searchAndReconstructionSeconds,
            Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0
        );
    }

    private static void assertDirectResult(String feedId, RaptorSearchResult result) {
        assertTrue(result.destinationReachable());
        assertEquals(1, result.winningRound().orElseThrow());
        RaptorLabel label = result.bestDestinationLabel().orElseThrow();
        RaptorRide ride = label.incomingRide().orElseThrow();
        assertEquals(feedId, label.stopId().feedId());
        assertEquals(feedId, ride.tripId().feedId());
        assertEquals(feedId, ride.routeId().feedId());
        assertEquals(feedId, ride.serviceId().feedId());
        assertEquals(result.origin(), label.predecessor().orElseThrow().stopId());
        assertEquals(result.destination(), label.stopId());
        assertFalse(ride.departureInstant().isBefore(result.departureInstant()));
        assertFalse(ride.arrivalInstant().isBefore(ride.departureInstant()));
    }

    private static void assertDirectJourney(
        RaptorSearchResult result,
        RaptorJourney journey,
        RaptorRide ride
    ) {
        assertSame(result, journey.searchResult());
        assertEquals(result.origin(), journey.origin());
        assertEquals(result.destination(), journey.destination());
        assertEquals(result.departureInstant(), journey.requestedDepartureInstant());
        assertEquals(ride.arrivalInstant(), journey.arrivalInstant());
        assertEquals(1, journey.numberOfBoardings());
        RaptorTransitLeg leg = journey.legs().getFirst();
        assertEquals(ride.tripId(), leg.tripId());
        assertEquals(ride.routeId(), leg.routeId());
        assertEquals(ride.serviceId(), leg.serviceId());
        assertEquals(ride.serviceDate(), leg.serviceDate());
        assertEquals(result.origin(), leg.boardingStopId());
        assertEquals(result.destination(), leg.alightingStopId());
        assertEquals(ride.boardingStopPosition(), leg.boardingStopPosition());
        assertEquals(ride.alightingStopPosition(), leg.alightingStopPosition());
        assertEquals(ride.departureSeconds(), leg.scheduledDepartureSeconds());
        assertEquals(ride.arrivalSeconds(), leg.scheduledArrivalSeconds());
        assertEquals(ride.departureInstant(), leg.departureInstant());
        assertEquals(ride.arrivalInstant(), leg.arrivalInstant());
    }

    private static String description(
        String label,
        RaptorSearchResult result,
        RaptorJourney journey
    ) {
        RaptorTransitLeg leg = journey.legs().getFirst();
        int markedStops = result.rounds().stream()
            .mapToInt(round -> round.markedStopIndexes().size())
            .sum();
        int scans = result.rounds().stream().mapToInt(RaptorRound::patternScanCount).sum();
        return "%s: %s -> %s, legs=%d, trip=%s route=%s service=%s serviceDate=%s, "
            .formatted(
                label,
                leg.boardingStopId(),
                leg.alightingStopId(),
                journey.legs().size(),
                leg.tripId(),
                leg.routeId(),
                leg.serviceId(),
                leg.serviceDate()
            )
            + "depart=%s, arrive=%s, ".formatted(
                leg.departureInstant(),
                leg.arrivalInstant()
            )
            + "rounds=%d, marked=%d, patternScans=%d".formatted(
                result.completedTransitRounds(),
                markedStops,
                scans
            );
    }

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static Path configuredPath(String property, String fallback) {
        return Path.of(System.getProperty(property, fallback)).toAbsolutePath().normalize();
    }
}
