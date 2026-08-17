package com.sprintwise.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.gtfs.time.ServiceTimeResolver;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Standalone Stage 1 compatibility proof for the frozen LIRR snapshot.
 * Runs only through the real-lirr-stage1 Maven profile in its own 2 GiB JVM.
 */
class RealLirrStage1CompatibilityIT {

    private static final String FEED_ID = "lirr";
    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;
    private static final LocalDate PROOF_SERVICE_DATE = LocalDate.of(2026, 8, 13);
    private static final FeedScopedId PROOF_TRIP = id("GO201_26_617");
    private static final FeedScopedId PROOF_ROUTE = id("10");
    private static final FeedScopedId PROOF_SERVICE = id("EB24D2DC");
    private static final FeedScopedId PROOF_STOP = id("102");
    private static final int PROOF_DEPARTURE_SECONDS = 8 * 3_600 + 5 * 60;
    private static final Instant PROOF_QUERY = Instant.parse("2026-08-13T12:04:00Z");
    private static final Instant PROOF_DEPARTURE_INSTANT = Instant.parse("2026-08-13T12:05:00Z");
    private static final FeedScopedId EXTENDED_TIME_TRIP = id("GO201_26_21");
    private static final FeedScopedId EXTENDED_TIME_STOP = id("157");
    private static final LocalDate EXTENDED_TIME_SERVICE_DATE = LocalDate.of(2026, 8, 14);
    private static final int EXTENDED_TIME_SECONDS = 24 * 3_600 + 4 * 60;
    private static final Instant EXTENDED_TIME_QUERY = Instant.parse("2026-08-15T04:03:00Z");
    private static final Instant EXTENDED_TIME_INSTANT = Instant.parse("2026-08-15T04:04:00Z");
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "agency.txt",
        "calendar_dates.txt",
        "feed_info.txt",
        "routes.txt",
        "shapes.txt",
        "stop_times.txt",
        "stops.txt",
        "transfers.txt",
        "trips.txt"
    );

    @Test
    void frozenLirrFeedTraversesTheOrdinaryStageOnePath() throws Exception {
        Path gtfsPath = Path.of(System.getProperty("lirr.gtfs.path", "../data/gtfs/lirr"))
            .toAbsolutePath()
            .normalize();
        Assumptions.assumeTrue(
            Files.isDirectory(gtfsPath),
            () -> "Frozen LIRR GTFS directory is unavailable; optional proof skipped: " + gtfsPath
        );

        long maxHeap = Runtime.getRuntime().maxMemory();
        assertTrue(
            maxHeap <= TWO_GIBIBYTES,
            () -> "Compatibility JVM exceeds the required -Xmx2G limit: " + bytes(maxHeap)
        );

        Set<String> tables = tableNames(gtfsPath);
        assertEquals(EXPECTED_TABLES, tables);
        assertFalse(tables.contains("calendar.txt"));
        assertTrue(tables.containsAll(Set.of("shapes.txt", "transfers.txt")));

        forceGc();
        long baselineHeap = usedHeap();
        long gtfsBytes = directoryBytes(gtfsPath);

        Instant loadStarted = Instant.now();
        GtfsFeed feed = new OneBusAwayGtfsLoader().load(gtfsPath, FEED_ID);
        Duration loadDuration = Duration.between(loadStarted, Instant.now());
        long heapAfterLoad = usedHeap();

        assertEquals(FEED_ID, feed.feedId());
        assertEquals(ZoneId.of("America/New_York"), feed.agencyZoneId());
        ZoneId feedZoneId = feed.agencyZoneId();
        assertEquals(127, feed.stops().size());
        assertEquals(13, feed.routes().size());
        assertEquals(2_143, feed.trips().size());
        assertEquals(23_185, feed.stopTimes().size());
        assertEquals(0, feed.serviceCalendars().size());
        assertEquals(571, feed.serviceCalendarDates().size());
        assertFalse(feed.stops().isEmpty());
        assertFalse(feed.routes().isEmpty());
        assertFalse(feed.trips().isEmpty());
        assertFalse(feed.stopTimes().isEmpty());

        assertNamespaces(feed);
        assertReferences(feed);

        var timeResolver = ServiceTimeResolver.forFeed(feed);
        assertEquals(25 * 3_600 + 21 * 60, timeResolver.maximumScheduledTimeSeconds());
        long extendedTimeRows = feed.stopTimes().stream()
            .filter(RealLirrStage1CompatibilityIT::hasTimeAboveTwentyFourHours)
            .count();
        assertTrue(extendedTimeRows > 0, "The frozen snapshot is expected to contain 24:xx/25:xx times");

        long missingBothTimes = feed.stopTimes().stream()
            .filter(stopTime -> stopTime.arrivalSeconds() == null && stopTime.departureSeconds() == null)
            .count();
        long missingOnlyOneTime = feed.stopTimes().stream()
            .filter(stopTime -> (stopTime.arrivalSeconds() == null)
                != (stopTime.departureSeconds() == null))
            .count();
        assertEquals(0, missingOnlyOneTime);
        assertEquals(0, missingBothTimes);

        var pickupCounts = accessCounts(feed, true);
        var dropOffCounts = accessCounts(feed, false);
        assertEquals(22_923L, pickupCounts.get(PickupDropOffType.REGULARLY_SCHEDULED));
        assertEquals(262L, pickupCounts.get(PickupDropOffType.NOT_AVAILABLE));
        assertEquals(22_923L, dropOffCounts.get(PickupDropOffType.REGULARLY_SCHEDULED));
        assertEquals(262L, dropOffCounts.get(PickupDropOffType.NOT_AVAILABLE));

        Instant indexStarted = Instant.now();
        GtfsIndex index = new GtfsIndex(feed);
        Duration indexDuration = Duration.between(indexStarted, Instant.now());

        assertEquals(FEED_ID, index.feedId());
        assertEquals(feed.stops().size(), index.stats().stopCount());
        assertEquals(feed.routes().size(), index.stats().routeCount());
        assertEquals(feed.trips().size(), index.stats().tripCount());
        assertEquals(feed.stopTimes().size(), index.stats().stopTimeReferenceCount());
        assertEquals(feed.stopTimes().size(), index.stats().scheduledDepartureCount());

        Trip proofTrip = index.trip(PROOF_TRIP).orElseThrow();
        assertEquals(PROOF_ROUTE, proofTrip.routeId());
        assertEquals(PROOF_SERVICE, proofTrip.serviceId());
        List<StopTime> proofStopTimes = index.stopTimesForTrip(PROOF_TRIP);
        assertEquals(11, proofStopTimes.size());
        assertEquals(
            List.of("164", "14", "193", "202", "111", "153", "78", "91", "102", "90", "118"),
            proofStopTimes.stream().map(stopTime -> stopTime.stopId().id()).toList()
        );
        for (int position = 1; position < proofStopTimes.size(); position++) {
            assertTrue(
                proofStopTimes.get(position - 1).stopSequence()
                    < proofStopTimes.get(position).stopSequence()
            );
        }

        Set<FeedScopedId> activeServices = index.activeServiceIds(PROOF_SERVICE_DATE);
        assertFalse(activeServices.isEmpty());
        assertTrue(activeServices.contains(PROOF_SERVICE));

        List<TimetableDeparture> proofDepartures = index.nextDepartures(PROOF_STOP, PROOF_QUERY, 100);
        TimetableDeparture proofDeparture = proofDepartures
            .stream()
            .filter(departure -> departure.tripId().equals(PROOF_TRIP))
            .findFirst()
            .orElseThrow();
        assertEquals(PROOF_STOP, proofDeparture.stopId());
        assertEquals(PROOF_TRIP, proofDeparture.tripId());
        assertEquals(PROOF_ROUTE, proofDeparture.routeId());
        assertEquals(PROOF_SERVICE, proofDeparture.serviceId());
        assertEquals(PROOF_SERVICE_DATE, proofDeparture.serviceTime().serviceDate());
        assertEquals(PROOF_DEPARTURE_SECONDS, proofDeparture.serviceTime().secondsSinceServiceDayStart());
        assertEquals(PROOF_DEPARTURE_INSTANT, proofDeparture.departureInstant());
        assertEquals(timeResolver.toInstant(proofDeparture.serviceTime()), proofDeparture.departureInstant());

        TimetableDeparture extendedTimeDeparture = index.nextDepartures(
            EXTENDED_TIME_STOP,
            EXTENDED_TIME_QUERY,
            100
        ).stream()
            .filter(departure -> departure.tripId().equals(EXTENDED_TIME_TRIP))
            .findFirst()
            .orElseThrow();
        assertEquals(EXTENDED_TIME_SERVICE_DATE, extendedTimeDeparture.serviceTime().serviceDate());
        assertEquals(EXTENDED_TIME_SECONDS, extendedTimeDeparture.serviceTime().secondsSinceServiceDayStart());
        assertEquals(EXTENDED_TIME_INSTANT, extendedTimeDeparture.departureInstant());
        assertEquals(timeResolver.toInstant(extendedTimeDeparture.serviceTime()), EXTENDED_TIME_INSTANT);

        List<?> immutableFeedStops = feed.stops();
        assertThrows(UnsupportedOperationException.class, immutableFeedStops::clear);
        assertThrows(UnsupportedOperationException.class, () -> index.stops().clear());
        assertThrows(UnsupportedOperationException.class, () -> proofStopTimes.clear());
        assertThrows(UnsupportedOperationException.class, () -> activeServices.clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> index.scheduledDeparturesAtStop(PROOF_STOP).clear()
        );
        assertThrows(UnsupportedOperationException.class, proofDepartures::clear);

        int stopTimeCount = feed.stopTimes().size();
        GtfsIndexStats stats = index.stats();
        feed = null;
        forceGc();
        long retainedHeap = usedHeap();

        System.out.printf(
            "%nStandalone frozen LIRR Stage 1 compatibility: PASS%n"
                + "GTFS path: %s%n"
                + "GTFS input size: %,d bytes (%s)%n"
                + "Tables present: %s%n"
                + "Tables absent: [calendar.txt]%n"
                + "Present but deliberately absent from the Stage 1 model: "
                + "[feed_info.txt, shapes.txt, transfers.txt]%n"
                + "Feed timezone: %s%n"
                + "Entities: %,d stops, %,d routes, %,d trips, %,d stop times, "
                + "%,d calendars, %,d calendar dates%n"
                + "Maximum GTFS time: %d seconds (25:21:00); rows above 24:00: %,d%n"
                + "Pickup counts: %s; drop-off counts: %s%n"
                + "Missing-time rows: %,d both absent, %,d only one absent%n"
                + "Proof trip: %s, route %s, %,d ordered stops%n"
                + "Proof service date: %s with service %s active%n"
                + "Proof departure: stop %s, trip %s, route %s, %d GTFS seconds, %s%n"
                + "Extended-time proof: trip %s on service date %s departs stop %s at "
                + "%d GTFS seconds / %s%n"
                + "Load time: %s; index construction time: %s%n"
                + "Heap: %s baseline, %s after load, %s retained after index (approximate)%n"
                + "Heap limit: %s%n",
            gtfsPath,
            gtfsBytes,
            bytes(gtfsBytes),
            tables,
            feedZoneId,
            stats.stopCount(),
            stats.routeCount(),
            stats.tripCount(),
            stopTimeCount,
            0,
            571,
            timeResolver.maximumScheduledTimeSeconds(),
            extendedTimeRows,
            pickupCounts,
            dropOffCounts,
            missingBothTimes,
            missingOnlyOneTime,
            PROOF_TRIP,
            PROOF_ROUTE,
            proofStopTimes.size(),
            PROOF_SERVICE_DATE,
            PROOF_SERVICE,
            PROOF_STOP,
            PROOF_TRIP,
            PROOF_ROUTE,
            PROOF_DEPARTURE_SECONDS,
            PROOF_DEPARTURE_INSTANT,
            EXTENDED_TIME_TRIP,
            EXTENDED_TIME_SERVICE_DATE,
            EXTENDED_TIME_STOP,
            EXTENDED_TIME_SECONDS,
            EXTENDED_TIME_INSTANT,
            duration(loadDuration),
            duration(indexDuration),
            bytes(baselineHeap),
            bytes(heapAfterLoad),
            bytes(retainedHeap),
            bytes(maxHeap)
        );
        System.out.printf(
            "Likely largest structures: %,d StopTime records, %,d derived scheduled-departure "
                + "records, and %,d grouped stop-time index references.%n%n",
            stopTimeCount,
            stats.scheduledDepartureCount(),
            stats.stopTimeReferenceCount()
        );
    }

    private static void assertNamespaces(GtfsFeed feed) {
        Stream<FeedScopedId> applicationIds = Stream.of(
            feed.stops().stream().flatMap(stop -> Stream.concat(
                Stream.of(stop.id()),
                stop.parentStationId() == null ? Stream.empty() : Stream.of(stop.parentStationId())
            )),
            feed.routes().stream().map(route -> route.id()),
            feed.trips().stream().flatMap(trip -> Stream.of(
                trip.id(), trip.routeId(), trip.serviceId()
            )),
            feed.stopTimes().stream().flatMap(stopTime -> Stream.of(
                stopTime.tripId(), stopTime.stopId()
            )),
            feed.serviceCalendars().stream().map(calendar -> calendar.serviceId()),
            feed.serviceCalendarDates().stream().map(calendarDate -> calendarDate.serviceId())
        ).flatMap(ids -> ids);

        assertTrue(applicationIds.allMatch(id -> FEED_ID.equals(id.feedId())));
    }

    private static void assertReferences(GtfsFeed feed) {
        Set<FeedScopedId> stopIds = feed.stops().stream()
            .map(stop -> stop.id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<FeedScopedId> routeIds = feed.routes().stream()
            .map(route -> route.id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<FeedScopedId> tripIds = feed.trips().stream()
            .map(trip -> trip.id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<FeedScopedId> serviceIds = new HashSet<>();
        feed.serviceCalendars().forEach(calendar -> serviceIds.add(calendar.serviceId()));
        feed.serviceCalendarDates().forEach(calendarDate -> serviceIds.add(calendarDate.serviceId()));

        assertTrue(feed.stops().stream().allMatch(stop ->
            stop.parentStationId() == null || stopIds.contains(stop.parentStationId())
        ));
        assertTrue(feed.trips().stream().allMatch(trip ->
            routeIds.contains(trip.routeId()) && serviceIds.contains(trip.serviceId())
        ));
        assertTrue(feed.stopTimes().stream().allMatch(stopTime ->
            tripIds.contains(stopTime.tripId()) && stopIds.contains(stopTime.stopId())
        ));
    }

    private static EnumMap<PickupDropOffType, Long> accessCounts(GtfsFeed feed, boolean pickup) {
        var counts = new EnumMap<PickupDropOffType, Long>(PickupDropOffType.class);
        for (StopTime stopTime : feed.stopTimes()) {
            PickupDropOffType type = pickup ? stopTime.pickupType() : stopTime.dropOffType();
            assertNotNull(type);
            counts.merge(type, 1L, Long::sum);
        }
        return counts;
    }

    private static boolean hasTimeAboveTwentyFourHours(StopTime stopTime) {
        return (stopTime.arrivalSeconds() != null && stopTime.arrivalSeconds() >= 24 * 3_600)
            || (stopTime.departureSeconds() != null && stopTime.departureSeconds() >= 24 * 3_600);
    }

    private static Set<String> tableNames(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".txt"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
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

    private static FeedScopedId id(String rawId) {
        return new FeedScopedId(FEED_ID, rawId);
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

    private static final class DirectorySizeException extends RuntimeException {
        private DirectorySizeException(IOException cause) {
            super(cause);
        }
    }
}
