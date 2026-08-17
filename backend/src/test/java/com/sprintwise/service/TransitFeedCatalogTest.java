package com.sprintwise.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.GtfsDiagnosticCode;
import com.sprintwise.gtfs.GtfsDiagnosticSeverity;
import com.sprintwise.gtfs.GtfsImportDiagnostic;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.model.FeedScopedId;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransitFeedCatalogTest {

    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Test
    void loadsTwoIndependentFeedsOnceWithDeterministicImmutableCatalogState() {
        AtomicInteger loadCount = new AtomicInteger();
        GtfsLoader loader = (source, feedId) -> {
            loadCount.incrementAndGet();
            return new OneBusAwayGtfsLoader().load(source, feedId);
        };
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            loader,
            properties(
                feed("mta", FIXTURE, true),
                feed("lirr", FIXTURE, true)
            )
        );

        assertEquals(2, loadCount.get());
        assertEquals(List.of("lirr", "mta"), catalog.entries().stream()
            .map(TransitFeedEntry::feedId)
            .toList());
        assertEquals(List.of("lirr", "mta"), List.copyOf(catalog.feedIds()));
        assertTrue(catalog.entries().stream().allMatch(TransitFeedEntry::isAvailable));
        assertNotSame(catalog.feed("mta"), catalog.feed("lirr"));
        assertNotSame(catalog.index("mta"), catalog.index("lirr"));
        assertSame(catalog.index("mta"), catalog.index("mta"));
        assertSame(catalog.feed("lirr"), catalog.entry("lirr").feed());

        assertEquals("mta:A", catalog.index("mta").stop(id("mta", "A")).orElseThrow().id().toString());
        assertEquals("lirr:A", catalog.index("lirr").stop(id("lirr", "A")).orElseThrow().id().toString());
        assertTrue(catalog.index("mta").route(id("mta", "RED")).isPresent());
        assertTrue(catalog.index("lirr").route(id("lirr", "RED")).isPresent());
        assertTrue(catalog.index("mta").trip(id("mta", "NIGHT")).isPresent());
        assertTrue(catalog.index("lirr").trip(id("lirr", "NIGHT")).isPresent());
        assertFalse(catalog.index("mta").stop(id("lirr", "A")).isPresent());
        assertFalse(catalog.index("lirr").stop(id("mta", "A")).isPresent());
        assertFalse(catalog.index("mta").route(id("lirr", "RED")).isPresent());
        assertFalse(catalog.index("lirr").trip(id("mta", "NIGHT")).isPresent());
        assertEquals(catalog.feed("mta").agencyZoneId(), catalog.feed("lirr").agencyZoneId());

        assertTrue(catalog.index("mta").activeServiceIds(LocalDate.of(2026, 8, 13))
            .contains(id("mta", "WEEKDAY")));
        assertTrue(catalog.index("lirr").activeServiceIds(LocalDate.of(2026, 8, 13))
            .contains(id("lirr", "WEEKDAY")));
        assertEquals(
            "mta:NIGHT",
            catalog.index("mta").nextDepartures(
                id("mta", "A"),
                Instant.parse("2026-08-14T04:04:00Z"),
                1
            ).getFirst().tripId().toString()
        );
        assertEquals(
            "lirr:NIGHT",
            catalog.index("lirr").nextDepartures(
                id("lirr", "A"),
                Instant.parse("2026-08-14T04:04:00Z"),
                1
            ).getFirst().tripId().toString()
        );

        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.feedIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.feed("mta").trips().clear());
    }

    @Test
    void retainsOneStructuredFailureWhileTheOtherFeedRemainsAvailable() {
        Path missingPath = FIXTURE.resolve("does-not-exist");
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            new OneBusAwayGtfsLoader(),
            properties(
                feed("mta", FIXTURE, true),
                feed("lirr", missingPath, true)
            )
        );

        assertTrue(catalog.entry("mta").isAvailable());
        assertFalse(catalog.entry("lirr").isAvailable());
        assertTrue(catalog.index("mta").stop(id("mta", "A")).isPresent());

        FeedUnavailableException retained = catalog.entry("lirr").failure().orElseThrow();
        FeedUnavailableException first = assertThrows(
            FeedUnavailableException.class,
            () -> catalog.index("lirr")
        );
        FeedUnavailableException second = assertThrows(
            FeedUnavailableException.class,
            () -> catalog.feed("lirr")
        );
        assertSame(retained, first);
        assertSame(retained, second);
        assertEquals(GtfsDiagnosticCode.SOURCE_MISSING, retained.diagnostic().orElseThrow().code());
    }

    @Test
    void preservesTheOriginalStructuredFailureObject() {
        GtfsLoadException failure = new GtfsLoadException(new GtfsImportDiagnostic(
            GtfsDiagnosticSeverity.FATAL,
            GtfsDiagnosticCode.MISSING_REQUIRED_REFERENCE,
            "broken",
            Path.of("broken-feed"),
            "trips.txt",
            "trip",
            "BROKEN_TRIP",
            "route_id",
            "MISSING_ROUTE",
            "bad required reference"
        ));
        GtfsLoader loader = (source, feedId) -> {
            throw failure;
        };
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            loader,
            properties(feed("broken", Path.of("broken-feed"), true))
        );

        FeedUnavailableException unavailable = catalog.entry("broken").failure().orElseThrow();
        assertSame(failure, unavailable.getCause());
        assertSame(failure.diagnostic(), unavailable.diagnostic().orElseThrow());
    }

    @Test
    void disabledAndUnknownFeedsAreAbsent() {
        TransitFeedCatalog catalog = new TransitFeedCatalog(
            new OneBusAwayGtfsLoader(),
            properties(
                feed("mta", FIXTURE, true),
                feed("lirr", null, false)
            )
        );

        assertEquals(List.of("mta"), List.copyOf(catalog.feedIds()));
        assertTrue(catalog.find("lirr").isEmpty());
        assertThrows(UnknownFeedException.class, () -> catalog.index("lirr"));
        assertThrows(UnknownFeedException.class, () -> catalog.index("unknown"));
    }

    @Test
    void rejectsInvalidConfigurationAndDoesNotHideProgrammingFailures() {
        var duplicate = properties(
            feed("mta", FIXTURE, true),
            feed("mta", FIXTURE, false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TransitFeedCatalog(new OneBusAwayGtfsLoader(), duplicate)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new TransitFeedCatalog(
                new OneBusAwayGtfsLoader(),
                properties(feed(" ", FIXTURE, true))
            )
        );
        assertThrows(
            NullPointerException.class,
            () -> new TransitFeedCatalog(
                new OneBusAwayGtfsLoader(),
                properties(feed("mta", null, true))
            )
        );

        IllegalStateException programmingFailure = new IllegalStateException("programming failure");
        GtfsLoader brokenLoader = (source, feedId) -> {
            throw programmingFailure;
        };
        assertSame(
            programmingFailure,
            assertThrows(
                IllegalStateException.class,
                () -> new TransitFeedCatalog(
                    brokenLoader,
                    properties(feed("mta", FIXTURE, true))
                )
            )
        );
    }

    private static FeedScopedId id(String feedId, String rawId) {
        return new FeedScopedId(feedId, rawId);
    }

    private static FeedProperties feed(String id, Path path, boolean enabled) {
        return new FeedProperties(id, path, enabled);
    }

    private static GtfsProperties properties(FeedProperties... feeds) {
        var properties = new GtfsProperties();
        properties.setFeeds(List.of(feeds));
        return properties;
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            return fixtureDirectory();
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = TransitFeedCatalogTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
