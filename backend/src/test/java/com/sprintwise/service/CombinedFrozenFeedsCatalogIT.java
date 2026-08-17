package com.sprintwise.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.index.TimetableDeparture;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional simultaneous MTA/LIRR Stage 1 proof, isolated in a 2 GiB JVM. */
class CombinedFrozenFeedsCatalogIT {

    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;
    private static final Path MTA_PATH = configuredPath("mta.gtfs.path", "../data/gtfs/mta");
    private static final Path LIRR_PATH = configuredPath("lirr.gtfs.path", "../data/gtfs/lirr");

    @Test
    void frozenMtaAndLirrCoexistAsIndependentCatalogEntries() throws Exception {
        Assumptions.assumeTrue(
            Files.isDirectory(MTA_PATH) && Files.isDirectory(LIRR_PATH),
            () -> "Combined frozen-feed proof skipped; expected MTA at " + MTA_PATH
                + " and LIRR at " + LIRR_PATH
        );
        long maxHeap = Runtime.getRuntime().maxMemory();
        assertTrue(maxHeap <= TWO_GIBIBYTES);

        long mtaInputBytes = directoryBytes(MTA_PATH);
        long lirrInputBytes = directoryBytes(LIRR_PATH);
        forceGc();
        long baselineHeap = usedHeap();

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
        long retainedHeap = usedHeap();

        assertEquals(List.of("lirr", "mta"), List.copyOf(catalog.feedIds()));
        assertTrue(catalog.entries().stream().allMatch(TransitFeedEntry::isAvailable));
        TransitFeedEntry mta = catalog.entry("mta");
        TransitFeedEntry lirr = catalog.entry("lirr");

        assertTrue(mta.index().stop(id("mta", "101")).isPresent());
        assertTrue(lirr.index().stop(id("lirr", "101")).isPresent());
        assertFalse(mta.index().stop(id("lirr", "101")).isPresent());
        assertFalse(lirr.index().stop(id("mta", "101")).isPresent());
        assertAllEntitiesUseNamespace(mta.feed(), "mta");
        assertAllEntitiesUseNamespace(lirr.feed(), "lirr");

        FeedScopedId mtaTrip = id("mta", "L0S1-1-1094-S02_048200_1..S15R");
        assertTrue(mta.index().trip(mtaTrip).isPresent());
        TimetableDeparture mtaDeparture = mta.index().nextDepartures(
            id("mta", "101S"),
            Instant.parse("2026-08-13T12:00:00Z"),
            1
        ).getFirst();
        assertEquals(mtaTrip, mtaDeparture.tripId());
        assertEquals(28_920, mtaDeparture.serviceTime().secondsSinceServiceDayStart());

        FeedScopedId lirrTrip = id("lirr", "GO201_26_617");
        assertEquals(11, lirr.index().stopTimesForTrip(lirrTrip).size());
        assertTrue(lirr.index().activeServiceIds(LocalDate.of(2026, 8, 13))
            .contains(id("lirr", "EB24D2DC")));
        TimetableDeparture lirrDeparture = lirr.index().nextDepartures(
            id("lirr", "102"),
            Instant.parse("2026-08-13T12:04:00Z"),
            100
        ).stream()
            .filter(departure -> departure.tripId().equals(lirrTrip))
            .findFirst()
            .orElseThrow();
        assertEquals(id("lirr", "10"), lirrDeparture.routeId());
        assertEquals(id("lirr", "EB24D2DC"), lirrDeparture.serviceId());
        assertEquals(29_100, lirrDeparture.serviceTime().secondsSinceServiceDayStart());
        assertEquals(Instant.parse("2026-08-13T12:05:00Z"), lirrDeparture.departureInstant());

        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.feedIds().clear());

        long combinedStops = (long) mta.index().stats().stopCount() + lirr.index().stats().stopCount();
        long combinedRoutes = (long) mta.index().stats().routeCount() + lirr.index().stats().routeCount();
        long combinedTrips = (long) mta.index().stats().tripCount() + lirr.index().stats().tripCount();
        long combinedStopTimes = mta.index().stats().stopTimeReferenceCount()
            + lirr.index().stats().stopTimeReferenceCount();

        System.out.printf(
            "%nCombined frozen MTA/LIRR Stage 1 catalog: PASS%n"
                + "MTA: %,d input bytes; %,d stops, %,d routes, %,d trips, %,d stop times; "
                + "load %s, index %s%n"
                + "LIRR: %,d input bytes; %,d stops, %,d routes, %,d trips, %,d stop times; "
                + "load %s, index %s%n"
                + "Combined: %,d input bytes; %,d stops, %,d routes, %,d trips, %,d stop times%n"
                + "Approximate heap: %s baseline, %s retained; JVM limit %s%n%n",
            mtaInputBytes,
            mta.index().stats().stopCount(),
            mta.index().stats().routeCount(),
            mta.index().stats().tripCount(),
            mta.index().stats().stopTimeReferenceCount(),
            duration(mta.loadDuration()),
            duration(mta.indexDuration()),
            lirrInputBytes,
            lirr.index().stats().stopCount(),
            lirr.index().stats().routeCount(),
            lirr.index().stats().tripCount(),
            lirr.index().stats().stopTimeReferenceCount(),
            duration(lirr.loadDuration()),
            duration(lirr.indexDuration()),
            mtaInputBytes + lirrInputBytes,
            combinedStops,
            combinedRoutes,
            combinedTrips,
            combinedStopTimes,
            bytes(baselineHeap),
            bytes(retainedHeap),
            bytes(maxHeap)
        );
    }

    private static Path configuredPath(String property, String fallback) {
        return Path.of(System.getProperty(property, fallback)).toAbsolutePath().normalize();
    }

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static void assertAllEntitiesUseNamespace(GtfsFeed feed, String feedId) {
        assertEquals(feedId, feed.feedId());
        assertTrue(feed.stops().stream().allMatch(stop -> stop.id().feedId().equals(feedId)));
        assertTrue(feed.routes().stream().allMatch(route -> route.id().feedId().equals(feedId)));
        assertTrue(feed.trips().stream().allMatch(trip ->
            trip.id().feedId().equals(feedId)
                && trip.routeId().feedId().equals(feedId)
                && trip.serviceId().feedId().equals(feedId)
        ));
        assertTrue(feed.stopTimes().stream().allMatch(stopTime ->
            stopTime.tripId().feedId().equals(feedId)
                && stopTime.stopId().feedId().equals(feedId)
        ));
        assertTrue(feed.serviceCalendars().stream().allMatch(calendar ->
            calendar.serviceId().feedId().equals(feedId)
        ));
        assertTrue(feed.serviceCalendarDates().stream().allMatch(calendarDate ->
            calendarDate.serviceId().feedId().equals(feedId)
        ));
    }

    private static long directoryBytes(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    throw new DirectorySizeException(exception);
                }
            }).sum();
        } catch (DirectorySizeException exception) {
            throw (IOException) exception.getCause();
        }
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

    private static String duration(java.time.Duration value) {
        return String.format("%.3f s", value.toNanos() / 1_000_000_000.0);
    }

    private static final class DirectorySizeException extends RuntimeException {
        private DirectorySizeException(IOException cause) {
            super(cause);
        }
    }
}
