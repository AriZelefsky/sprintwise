package com.sprintwise.raptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.service.TransitFeedCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional composite Stage 2A construction and memory proof in an isolated 2 GiB JVM. */
class CompositeRaptorNetworkIT {

    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;
    private static final Path MTA_PATH = configuredPath("mta.gtfs.path", "../data/gtfs/mta");
    private static final Path LIRR_PATH = configuredPath("lirr.gtfs.path", "../data/gtfs/lirr");

    @Test
    void buildsOneCompositeNetworkWithoutLosingAnyStageOneTrip() throws Exception {
        Assumptions.assumeTrue(
            Files.isDirectory(MTA_PATH) && Files.isDirectory(LIRR_PATH),
            () -> "Composite RAPTOR network proof skipped; expected MTA at " + MTA_PATH
                + " and LIRR at " + LIRR_PATH
        );
        long maxHeap = Runtime.getRuntime().maxMemory();
        assertTrue(maxHeap <= TWO_GIBIBYTES);

        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("mta", MTA_PATH, true),
            new FeedProperties("lirr", LIRR_PATH, true)
        ));
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            new OneBusAwayGtfsLoader(),
            properties
        );
        forceGc();
        long beforeNetworkHeap = usedHeap();

        Instant constructionStarted = Instant.now();
        RaptorNetwork network = new RaptorNetworkBuilder().build(catalog);
        Duration constructionTime = Duration.between(constructionStarted, Instant.now());
        forceGc();
        long afterNetworkHeap = usedHeap();

        int expectedStops = catalog.entries().stream()
            .mapToInt(entry -> entry.index().stats().stopCount())
            .sum();
        int expectedTrips = catalog.entries().stream()
            .mapToInt(entry -> entry.index().stats().tripCount())
            .sum();
        assertEquals(List.of("lirr", "mta"), List.copyOf(network.feedIds()));
        assertEquals(expectedStops, network.stats().stopCount());
        assertEquals(expectedTrips, network.stats().tripCount());
        assertEquals(expectedTrips, network.trips().size());
        for (var entry : catalog.entries()) {
            for (var trip : entry.feed().trips()) {
                int tripIndex = network.tripIndex(trip.id()).orElseThrow();
                assertEquals(trip, network.trip(tripIndex).trip());
            }
        }

        Map<String, Long> patternsByFeed = network.patterns().stream().collect(Collectors.groupingBy(
            pattern -> pattern.routeId().feedId(),
            TreeMap::new,
            Collectors.counting()
        ));
        Map<Integer, Long> patternSizeDistribution = network.patterns().stream().collect(
            Collectors.groupingBy(
                RaptorTripPattern::tripCount,
                TreeMap::new,
                Collectors.counting()
            )
        );
        long incrementalHeap = Math.max(0, afterNetworkHeap - beforeNetworkHeap);

        System.out.printf(
            "%nComposite Stage 2A RAPTOR network: PASS%n"
                + "Feeds: %s%n"
                + "Stops: %,d; trips: %,d; structural patterns: %,d; final patterns: %,d%n"
                + "Patterns by feed: %s%n"
                + "Pattern trip-count distribution (trips -> patterns): %s%n"
                + "Overtaking: %,d structural patterns split, %,d additional safe patterns%n"
                + "Positions: %,d shared pattern stops; %,d trip timetable stops%n"
                + "Construction time: %s%n"
                + "Approximate heap: %s before RAPTOR, %s after, %s incremental; JVM limit %s%n"
                + "Retained references/duplication: Stage 1 feed/index references, compact ID maps, "
                + "one stop/access array set per pattern, three integer arrays per trip, and "
                + "stop-to-pattern integer arrays.%n%n",
            network.feedIds(),
            network.stats().stopCount(),
            network.stats().tripCount(),
            network.stats().structuralPatternCount(),
            network.stats().patternCount(),
            patternsByFeed,
            patternSizeDistribution,
            network.stats().overtakingStructuralPatternCount(),
            network.stats().additionalPatternsFromOvertaking(),
            network.stats().patternStopPositionCount(),
            network.stats().tripStopPositionCount(),
            duration(constructionTime),
            bytes(beforeNetworkHeap),
            bytes(afterNetworkHeap),
            bytes(incrementalHeap),
            bytes(maxHeap)
        );
    }

    private static Path configuredPath(String property, String fallback) {
        return Path.of(System.getProperty(property, fallback)).toAbsolutePath().normalize();
    }

    private static void forceGc() {
        System.gc();
        System.gc();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String bytes(long value) {
        return String.format("%.1f MiB", value / 1024.0 / 1024.0);
    }

    private static String duration(Duration value) {
        return String.format("%.3f s", value.toNanos() / 1_000_000_000.0);
    }
}
