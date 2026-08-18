package com.sprintwise.raptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.gtfs.time.ServiceTime;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.service.TransitFeedCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional real MTA/LIRR proof for the Stage 2B single-pattern scanner. */
class RealPatternScannerIT {

    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;
    private static final Path MTA_PATH = configuredPath("mta.gtfs.path", "../data/gtfs/mta");
    private static final Path LIRR_PATH = configuredPath("lirr.gtfs.path", "../data/gtfs/lirr");

    @Test
    void scansOneRealPatternFromEachFrozenFeed() {
        Assumptions.assumeTrue(
            Files.isDirectory(MTA_PATH) && Files.isDirectory(LIRR_PATH),
            () -> "Real pattern-scanner proof skipped; expected MTA at " + MTA_PATH
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
        var scanner = new RaptorPatternScanner(network);

        Instant started = Instant.now();
        RaptorRide mta = findRealRide("mta", network, scanner);
        RaptorRide lirr = findRealRide("lirr", network, scanner);
        Duration scanTime = Duration.between(started, Instant.now());

        assertRideBelongsToFeed("mta", mta, network);
        assertRideBelongsToFeed("lirr", lirr, network);
        assertFalse(mta.tripId().equals(lirr.tripId()));

        System.out.printf(
            "%nStage 2B real single-pattern scans: PASS%n%s%n%s%n"
                + "Proof search and scans: %.3f s; JVM limit: %.1f MiB%n%n",
            description("MTA", mta, network),
            description("LIRR", lirr, network),
            scanTime.toNanos() / 1_000_000_000.0,
            Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0
        );
    }

    private static RaptorRide findRealRide(
        String feedId,
        RaptorNetwork network,
        RaptorPatternScanner scanner
    ) {
        RaptorFeedContext context = network.feedContext(feedId).orElseThrow();
        Map<FeedScopedId, LocalDate> datesByService = firstActiveDates(context);

        for (RaptorTripPattern pattern : network.patterns()) {
            if (!pattern.routeId().feedId().equals(feedId)) {
                continue;
            }
            for (int tripPosition = 0; tripPosition < pattern.tripCount(); tripPosition++) {
                RaptorTripSchedule trip = network.trip(pattern.tripIndexAt(tripPosition));
                LocalDate serviceDate = datesByService.get(trip.serviceId());
                if (serviceDate == null) {
                    continue;
                }
                for (int boardingPosition = 0; boardingPosition < pattern.stopCount() - 1; boardingPosition++) {
                    if (!pattern.pickupTypeAt(boardingPosition).allowsOrdinaryUse()
                        || trip.departureSecondsAt(boardingPosition).isEmpty()) {
                        continue;
                    }
                    int departureSeconds = trip.departureSecondsAt(boardingPosition).getAsInt();
                    Instant query = context.sourceIndex().resolveServiceTime(
                        new ServiceTime(serviceDate, departureSeconds)
                    ).minusSeconds(1);
                    List<RaptorRide> rides = scanner.scan(
                        pattern.index(),
                        pattern.stopIndexAt(boardingPosition),
                        query
                    );
                    if (!rides.isEmpty()) {
                        return rides.getFirst();
                    }
                }
            }
        }
        throw new AssertionError("No scannable real pattern found for feed " + feedId);
    }

    private static Map<FeedScopedId, LocalDate> firstActiveDates(RaptorFeedContext context) {
        var candidateDates = new TreeSet<LocalDate>();
        var calendars = new ArrayList<>(context.feed().serviceCalendars());
        calendars.sort(Comparator.comparing(ServiceCalendar::serviceId));
        for (ServiceCalendar calendar : calendars) {
            LocalDate date = calendar.startDate();
            while (!date.isAfter(calendar.endDate())) {
                candidateDates.add(date);
                date = date.plusDays(1);
            }
        }
        context.feed().serviceCalendarDates().forEach(exception ->
            candidateDates.add(exception.date())
        );

        var result = new TreeMap<FeedScopedId, LocalDate>();
        for (LocalDate date : candidateDates) {
            context.sourceIndex().activeServiceIds(date).forEach(serviceId ->
                result.putIfAbsent(serviceId, date)
            );
        }
        return Map.copyOf(result);
    }

    private static void assertRideBelongsToFeed(
        String feedId,
        RaptorRide ride,
        RaptorNetwork network
    ) {
        assertEquals(feedId, ride.tripId().feedId());
        assertEquals(feedId, ride.routeId().feedId());
        assertEquals(feedId, ride.serviceId().feedId());
        assertEquals(feedId, network.stop(ride.boardingStopIndex()).id().feedId());
        assertEquals(feedId, network.stop(ride.alightingStopIndex()).id().feedId());
        assertTrue(ride.alightingStopPosition() > ride.boardingStopPosition());
        assertFalse(ride.departureInstant().isAfter(ride.arrivalInstant()));
        assertTrue(network.feedContext(feedId).orElseThrow().sourceIndex()
            .activeServiceIds(ride.serviceDate()).contains(ride.serviceId()));
    }

    private static String description(
        String label,
        RaptorRide ride,
        RaptorNetwork network
    ) {
        return "%s: trip=%s route=%s service=%s serviceDate=%s board=%s[%d] at %d (%s) "
            .formatted(
                label,
                ride.tripId(),
                ride.routeId(),
                ride.serviceId(),
                ride.serviceDate(),
                network.stop(ride.boardingStopIndex()).id(),
                ride.boardingStopPosition(),
                ride.departureSeconds(),
                ride.departureInstant()
            )
            + "alight=%s[%d] at %d (%s)".formatted(
                network.stop(ride.alightingStopIndex()).id(),
                ride.alightingStopPosition(),
                ride.arrivalSeconds(),
                ride.arrivalInstant()
            );
    }

    private static Path configuredPath(String property, String fallback) {
        return Path.of(System.getProperty(property, fallback)).toAbsolutePath().normalize();
    }
}
