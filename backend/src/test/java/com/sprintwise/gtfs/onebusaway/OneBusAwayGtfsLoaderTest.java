package com.sprintwise.gtfs.onebusaway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.gtfs.GtfsDiagnosticSeverity;
import com.sprintwise.gtfs.GtfsImportDiagnostic;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.validation.GtfsFeedValidationException;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.PickupDropOffType;
import com.sprintwise.model.ServiceCalendarDate;
import com.sprintwise.model.StopTime;
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
        assertEquals(3, feed.serviceCalendarDates().size());

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
    void defaultsBlankPickupAndDropOffRulesToRegularService() {
        assertTrue(feed.stopTimes().stream().allMatch(stopTime ->
            stopTime.pickupType() == PickupDropOffType.REGULARLY_SCHEDULED
                && stopTime.dropOffType() == PickupDropOffType.REGULARLY_SCHEDULED
                && stopTime.allowsOrdinaryBoarding()
                && stopTime.allowsOrdinaryAlighting()
        ));
    }

    @Test
    void preservesAllPickupAndDropOffValuesAndTheirOrdinaryUseSemantics(
        @TempDir Path temporaryFeed
    ) throws Exception {
        copyFixtureTo(temporaryFeed);
        Path stopTimes = temporaryFeed.resolve("stop_times.txt");
        List<String> lines = Files.readAllLines(stopTimes);
        lines.set(0, lines.getFirst() + ",pickup_type,drop_off_type");
        for (int index = 1; index < lines.size(); index++) {
            int value = Math.min(index - 1, 3);
            lines.set(index, lines.get(index) + "," + value + "," + value);
        }
        Files.write(stopTimes, lines);

        GtfsFeed accessFeed = new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID);
        List<StopTime> firstFour = accessFeed.stopTimes().stream()
            .filter(stopTime -> stopTime.tripId().id().equals("RED_EARLY")
                || stopTime.tripId().id().equals("RED_LATE"))
            .sorted(Comparator.comparing(StopTime::tripId).thenComparingInt(StopTime::stopSequence))
            .toList();

        assertEquals(
            List.of(
                PickupDropOffType.REGULARLY_SCHEDULED,
                PickupDropOffType.NOT_AVAILABLE,
                PickupDropOffType.MUST_PHONE_AGENCY,
                PickupDropOffType.MUST_COORDINATE_WITH_DRIVER
            ),
            firstFour.stream().map(StopTime::pickupType).toList()
        );
        assertEquals(
            List.of(
                PickupDropOffType.REGULARLY_SCHEDULED,
                PickupDropOffType.NOT_AVAILABLE,
                PickupDropOffType.MUST_PHONE_AGENCY,
                PickupDropOffType.MUST_COORDINATE_WITH_DRIVER
            ),
            firstFour.stream().map(StopTime::dropOffType).toList()
        );
        assertTrue(firstFour.getFirst().allowsOrdinaryBoarding());
        assertFalse(firstFour.get(1).allowsOrdinaryBoarding());
        assertFalse(firstFour.get(1).allowsOrdinaryAlighting());
        assertFalse(firstFour.get(2).allowsOrdinaryAlighting());
        assertFalse(firstFour.get(3).allowsOrdinaryAlighting());
    }

    @Test
    void rejectsInvalidPickupTypeWithStructuredContext(@TempDir Path temporaryFeed)
        throws Exception {
        copyFixtureTo(temporaryFeed);
        Path stopTimes = temporaryFeed.resolve("stop_times.txt");
        List<String> lines = Files.readAllLines(stopTimes);
        lines.set(0, lines.getFirst() + ",pickup_type");
        for (int index = 1; index < lines.size(); index++) {
            lines.set(index, lines.get(index) + (index == 1 ? ",9" : ","));
        }
        Files.write(stopTimes, lines);

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );
        GtfsImportDiagnostic diagnostic = exception.diagnostic();

        assertEquals(GtfsDiagnosticCode.INVALID_PICKUP_DROP_OFF_TYPE, diagnostic.code());
        assertEquals("stop_times.txt", diagnostic.sourceFile());
        assertEquals("stop_time", diagnostic.entityType());
        assertEquals("trip_id=RED_EARLY,stop_sequence=1", diagnostic.entityId());
        assertEquals("pickup_type", diagnostic.field());
        assertEquals("9", diagnostic.referencedId());
    }

    @Test
    void keepsFiveStopsTogetherAsOneCompleteTripAndAllowsUntimedIntermediateStop(
        @TempDir Path temporaryFeed
    ) throws Exception {
        copyFixtureTo(temporaryFeed);
        appendFiveStopTrip(temporaryFeed, ",,,");

        GtfsFeed multiStopFeed = new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID);
        List<StopTime> run = multiStopFeed.stopTimes().stream()
            .filter(stopTime -> stopTime.tripId().id().equals("FIVE_STOP_RUN"))
            .toList();

        assertEquals(1, multiStopFeed.trips().stream()
            .filter(trip -> trip.id().id().equals("FIVE_STOP_RUN"))
            .count());
        assertEquals(5, run.size());
        assertEquals(List.of(1, 2, 3, 4, 5), run.stream().map(StopTime::stopSequence).toList());
        assertEquals(null, run.get(2).arrivalSeconds());
        assertEquals(null, run.get(2).departureSeconds());
    }

    @Test
    void rejectsTripWithFewerThanTwoStopTimes(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace("RED_EARLY,08:05:00,08:05:00,B_RED,2\n", ""),
            "trip",
            "RED_EARLY",
            "trip_id"
        );
    }

    @Test
    void rejectsDuplicateStopSequence(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace(
                "RED_EARLY,08:05:00,08:05:00,B_RED,2",
                "RED_EARLY,08:05:00,08:05:00,B_RED,1"
            ),
            "stop_time",
            "trip_id=RED_EARLY,stop_sequence=1",
            "stop_sequence"
        );
    }

    @Test
    void rejectsInvalidNegativeStopSequence(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace("RED_EARLY,08:00:00,08:00:00,A,1", "RED_EARLY,08:00:00,08:00:00,A,-1"),
            "stop_time",
            "trip_id=RED_EARLY,stop_sequence=-1",
            "stop_sequence"
        );
    }

    @Test
    void rejectsDepartureBeforeArrival(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace("RED_EARLY,08:00:00,08:00:00,A,1", "RED_EARLY,08:00:00,07:59:00,A,1"),
            "stop_time",
            "trip_id=RED_EARLY,stop_sequence=1",
            "departure_time"
        );
    }

    @Test
    void rejectsTimesThatMoveBackwardAcrossStops(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace("RED_EARLY,08:05:00,08:05:00,B_RED,2", "RED_EARLY,07:59:00,07:59:00,B_RED,2"),
            "stop_time",
            "trip_id=RED_EARLY,stop_sequence=2",
            "arrival_time"
        );
    }

    @Test
    void rejectsMissingRequiredEndpointTime(@TempDir Path temporaryFeed) throws Exception {
        assertInvalidStopTime(
            temporaryFeed,
            text -> text.replace("RED_EARLY,08:00:00,08:00:00,A,1", "RED_EARLY,,08:00:00,A,1"),
            "stop_time",
            "trip_id=RED_EARLY,stop_sequence=1",
            "arrival_time"
        );
    }

    @Test
    void rejectsIntermediateStopWithOnlyOneTime(@TempDir Path temporaryFeed) throws Exception {
        copyFixtureTo(temporaryFeed);
        appendFiveStopTrip(temporaryFeed, ",09:10:00,,");

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );
        GtfsImportDiagnostic diagnostic = exception.diagnostic();

        assertEquals(GtfsDiagnosticCode.INVALID_STOP_TIME, diagnostic.code());
        assertEquals("stop_times.txt", diagnostic.sourceFile());
        assertEquals("trip_id=FIVE_STOP_RUN,stop_sequence=3", diagnostic.entityId());
        assertEquals("departure_time", diagnostic.field());
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

        var diagnostic = exception.diagnostic();
        assertEquals(GtfsDiagnosticSeverity.FATAL, diagnostic.severity());
        assertEquals(GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE, diagnostic.code());
        assertEquals(FEED_ID, diagnostic.feedId());
        assertEquals("trips.txt", diagnostic.sourceFile());
        assertEquals("trip", diagnostic.entityType());
        assertEquals(GtfsImportDiagnostic.UNSPECIFIED, diagnostic.entityId());
        assertEquals("route_id", diagnostic.field());
        assertEquals("MISSING_ROUTE", diagnostic.referencedId());
        assertNotNull(exception.getCause());
    }

    @Test
    void identifiesEntityAndFieldForPostParseReferenceFailure(@TempDir Path temporaryFeed)
        throws Exception {
        copyFixtureTo(temporaryFeed);
        Path stops = temporaryFeed.resolve("stops.txt");
        Files.writeString(
            stops,
            Files.readString(stops).replace(
                "B_RED,Beta Red Platform,40.0010,-74.0000,0,B_STATION",
                "B_RED,Beta Red Platform,40.0010,-74.0000,0,MISSING_PARENT"
            )
        );

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );
        GtfsImportDiagnostic diagnostic = exception.diagnostic();

        assertEquals(GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE, diagnostic.code());
        assertEquals("stops.txt", diagnostic.sourceFile());
        assertEquals("stop", diagnostic.entityType());
        assertEquals("B_RED", diagnostic.entityId());
        assertEquals("parent_station", diagnostic.field());
        assertEquals("MISSING_PARENT", diagnostic.referencedId());
        assertInstanceOf(GtfsFeedValidationException.class, exception.getCause());
    }

    @Test
    void identifiesMissingServiceReferenceFromOneBusAway(@TempDir Path temporaryFeed)
        throws Exception {
        copyFixtureTo(temporaryFeed);
        Path trips = temporaryFeed.resolve("trips.txt");
        Files.writeString(
            trips,
            Files.readString(trips).replace(
                "RED,WEEKDAY,RED_EARLY,Beta,0",
                "RED,MISSING_SERVICE,RED_EARLY,Beta,0"
            )
        );

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );

        assertEquals(GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE, exception.diagnostic().code());
        assertEquals("trips.txt", exception.diagnostic().sourceFile());
        assertEquals("service_id", exception.diagnostic().field());
        assertEquals("MISSING_SERVICE", exception.diagnostic().referencedId());
        assertNotNull(exception.getCause());
    }

    @Test
    void reportsMissingSourceAsStructuredDiagnostic(@TempDir Path temporaryFeed) {
        Path missing = temporaryFeed.resolve("does-not-exist");

        GtfsImportDiagnostic diagnostic = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(missing, FEED_ID)
        ).diagnostic();

        assertEquals(GtfsDiagnosticCode.SOURCE_MISSING, diagnostic.code());
        assertEquals(FEED_ID, diagnostic.feedId());
        assertEquals(missing.toAbsolutePath().normalize(), diagnostic.feedSource());
        assertEquals("feed", diagnostic.entityType());
        assertEquals("source", diagnostic.field());
    }

    @Test
    void reportsInvalidAgencyTimezoneWithSourceContext(@TempDir Path temporaryFeed)
        throws Exception {
        copyFixtureTo(temporaryFeed);
        Path agency = temporaryFeed.resolve("agency.txt");
        Files.writeString(
            agency,
            Files.readString(agency).replace("America/New_York", "Mars/Olympus")
        );

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );
        GtfsImportDiagnostic diagnostic = exception.diagnostic();

        assertEquals(GtfsDiagnosticCode.INVALID_AGENCY_TIMEZONE, diagnostic.code());
        assertEquals("agency.txt", diagnostic.sourceFile());
        assertEquals("agency", diagnostic.entityType());
        assertEquals("SYN", diagnostic.entityId());
        assertEquals("agency_timezone", diagnostic.field());
        assertNotNull(exception.getCause());
    }

    @Test
    void preservesCauseAndFallbackContextForGenericOneBusAwayReadFailure(
        @TempDir Path temporaryFeed
    ) throws Exception {
        copyFixtureTo(temporaryFeed);
        Path stops = temporaryFeed.resolve("stops.txt");
        Files.delete(stops);
        Files.createDirectory(stops);

        GtfsLoadException exception = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        );
        GtfsImportDiagnostic diagnostic = exception.diagnostic();

        assertEquals(GtfsDiagnosticCode.READ_FAILURE, diagnostic.code());
        assertEquals(GtfsImportDiagnostic.UNSPECIFIED, diagnostic.sourceFile());
        assertEquals(GtfsImportDiagnostic.UNSPECIFIED, diagnostic.entityType());
        assertEquals(GtfsImportDiagnostic.UNSPECIFIED, diagnostic.entityId());
        assertEquals(GtfsImportDiagnostic.UNSPECIFIED, diagnostic.field());
        assertInstanceOf(IOException.class, exception.getCause());
        assertTrue(exception.getMessage().contains("read_failure"), exception::getMessage);
    }

    private static void copyFixtureTo(Path destination) throws Exception {
        try (var files = Files.list(fixtureDirectory())) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Files.copy(source, destination.resolve(source.getFileName()));
            }
        }
    }

    private static void appendFiveStopTrip(Path feedDirectory, String middleTimes) throws IOException {
        Path trips = feedDirectory.resolve("trips.txt");
        Files.writeString(trips, Files.readString(trips) + "DIRECT,WEEKDAY,FIVE_STOP_RUN,Loop,0\n");

        Path stopTimes = feedDirectory.resolve("stop_times.txt");
        Files.writeString(stopTimes, Files.readString(stopTimes)
            + "FIVE_STOP_RUN,09:00:00,09:00:00,A,1\n"
            + "FIVE_STOP_RUN,09:05:00,09:05:00,B_RED,2\n"
            + "FIVE_STOP_RUN" + middleTimes + "B_BLUE,3\n"
            + "FIVE_STOP_RUN,09:15:00,09:15:00,C,4\n"
            + "FIVE_STOP_RUN,09:25:00,09:25:00,A,5\n");
    }

    private static void assertInvalidStopTime(
        Path temporaryFeed,
        java.util.function.UnaryOperator<String> change,
        String entityType,
        String entityId,
        String field
    ) throws Exception {
        copyFixtureTo(temporaryFeed);
        Path stopTimes = temporaryFeed.resolve("stop_times.txt");
        Files.writeString(stopTimes, change.apply(Files.readString(stopTimes)));

        GtfsImportDiagnostic diagnostic = assertThrows(
            GtfsLoadException.class,
            () -> new OneBusAwayGtfsLoader().load(temporaryFeed, FEED_ID)
        ).diagnostic();
        assertEquals(GtfsDiagnosticCode.INVALID_STOP_TIME, diagnostic.code());
        assertEquals("stop_times.txt", diagnostic.sourceFile());
        assertEquals(entityType, diagnostic.entityType());
        assertEquals(entityId, diagnostic.entityId());
        assertEquals(field, diagnostic.field());
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
