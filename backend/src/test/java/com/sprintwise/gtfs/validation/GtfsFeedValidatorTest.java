package com.sprintwise.gtfs.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.GtfsFeed;
import com.sprintwise.model.Route;
import com.sprintwise.model.ServiceCalendar;
import com.sprintwise.model.Stop;
import com.sprintwise.model.StopTime;
import com.sprintwise.model.Trip;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GtfsFeedValidatorTest {

    private static final String FEED_ID = "owned";

    @Test
    void acceptsAValidProgrammaticFeedAndIndex() {
        GtfsFeed feed = validFeed();

        assertDoesNotThrow(() -> GtfsFeedValidator.validate(feed));
        assertDoesNotThrow(() -> new GtfsIndex(feed));
    }

    @Test
    void indexRejectsAProgrammaticTripWithAnUnknownRouteThroughSharedValidation() {
        GtfsFeed valid = validFeed();
        GtfsFeed invalid = copyWith(
            valid,
            List.of(new Trip(id("T"), id("MISSING_ROUTE"), id("S"), "Trip", "0")),
            valid.stopTimes()
        );

        GtfsFeedValidationException exception = assertThrows(
            GtfsFeedValidationException.class,
            () -> new GtfsIndex(invalid)
        );

        assertEquals(GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE, exception.code());
        assertEquals("trips.txt", exception.sourceFile());
        assertEquals("trip", exception.entityType());
        assertEquals("T", exception.entityId());
        assertEquals("route_id", exception.field());
        assertEquals("MISSING_ROUTE", exception.referencedId());
    }

    @Test
    void rejectsProgrammaticServiceAndStopReferencesWithSpecificContext() {
        GtfsFeed valid = validFeed();
        GtfsFeed missingService = copyWith(
            valid,
            List.of(new Trip(id("T"), id("R"), id("MISSING_SERVICE"), "Trip", "0")),
            valid.stopTimes()
        );
        GtfsFeedValidationException serviceFailure = assertThrows(
            GtfsFeedValidationException.class,
            () -> GtfsFeedValidator.validate(missingService)
        );
        assertEquals("service_id", serviceFailure.field());
        assertEquals("MISSING_SERVICE", serviceFailure.referencedId());

        GtfsFeed missingStop = copyWith(
            valid,
            valid.trips(),
            List.of(
                new StopTime(id("T"), id("A"), 1, 28_800, 28_800),
                new StopTime(id("T"), id("MISSING_STOP"), 2, 29_100, 29_100)
            )
        );
        GtfsFeedValidationException stopFailure = assertThrows(
            GtfsFeedValidationException.class,
            () -> GtfsFeedValidator.validate(missingStop)
        );
        assertEquals("stop_times.txt", stopFailure.sourceFile());
        assertEquals("trip_id=T,stop_sequence=2", stopFailure.entityId());
        assertEquals("stop_id", stopFailure.field());
        assertEquals("MISSING_STOP", stopFailure.referencedId());
    }

    @Test
    void rejectsProgrammaticNamespaceAndTimetableViolations() {
        assertThrows(
            IllegalArgumentException.class,
            () -> GtfsFeedValidator.requireValidFeedId("ambiguous:namespace")
        );

        GtfsFeed valid = validFeed();
        GtfsFeed wrongNamespace = new GtfsFeed(
            valid.feedId(),
            valid.agencyZoneId(),
            List.of(new Stop(new FeedScopedId("other", "A"), "A", 0, 0, 0, null)),
            valid.routes(),
            valid.trips(),
            valid.stopTimes(),
            valid.serviceCalendars(),
            valid.serviceCalendarDates()
        );
        GtfsFeedValidationException namespaceFailure = assertThrows(
            GtfsFeedValidationException.class,
            () -> GtfsFeedValidator.validate(wrongNamespace)
        );
        assertEquals(GtfsDiagnosticCode.INVALID_FEED_NAMESPACE, namespaceFailure.code());
        assertEquals("stop_id", namespaceFailure.field());

        GtfsFeed duplicateSequence = copyWith(
            valid,
            valid.trips(),
            List.of(
                new StopTime(id("T"), id("A"), 1, 28_800, 28_800),
                new StopTime(id("T"), id("B"), 1, 29_100, 29_100)
            )
        );
        GtfsFeedValidationException timetableFailure = assertThrows(
            GtfsFeedValidationException.class,
            () -> GtfsFeedValidator.validate(duplicateSequence)
        );
        assertEquals(GtfsDiagnosticCode.INVALID_STOP_TIME, timetableFailure.code());
        assertEquals("stop_sequence", timetableFailure.field());
    }

    private static GtfsFeed validFeed() {
        return new GtfsFeed(
            FEED_ID,
            ZoneId.of("America/New_York"),
            List.of(
                new Stop(id("A"), "A", 0, 0, 0, null),
                new Stop(id("B"), "B", 0, 0, 0, null)
            ),
            List.of(new Route(id("R"), "R", "Route", 1)),
            List.of(new Trip(id("T"), id("R"), id("S"), "Trip", "0")),
            List.of(
                new StopTime(id("T"), id("A"), 1, 28_800, 28_800),
                new StopTime(id("T"), id("B"), 2, 29_100, 29_100)
            ),
            List.of(new ServiceCalendar(
                id("S"),
                Set.of(DayOfWeek.MONDAY),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)
            )),
            List.of()
        );
    }

    private static GtfsFeed copyWith(
        GtfsFeed source,
        List<Trip> trips,
        List<StopTime> stopTimes
    ) {
        return new GtfsFeed(
            source.feedId(),
            source.agencyZoneId(),
            source.stops(),
            source.routes(),
            trips,
            stopTimes,
            source.serviceCalendars(),
            source.serviceCalendarDates()
        );
    }

    private static FeedScopedId id(String id) {
        return new FeedScopedId(FEED_ID, id);
    }
}
