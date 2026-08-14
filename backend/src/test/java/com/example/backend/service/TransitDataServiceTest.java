package com.example.backend.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.config.GtfsProperties;
import com.example.backend.gtfs.GtfsDiagnosticCode;
import com.example.backend.gtfs.GtfsDiagnosticSeverity;
import com.example.backend.gtfs.GtfsImportDiagnostic;
import com.example.backend.gtfs.GtfsLoadException;
import com.example.backend.gtfs.GtfsLoader;
import com.example.backend.gtfs.onebusaway.OneBusAwayGtfsLoader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransitDataServiceTest {

    @Test
    void loadsAndBuildsExactlyOnceThenReusesImmutableIndex() throws Exception {
        AtomicInteger loadCount = new AtomicInteger();
        GtfsLoader loader = (source, feedId) -> {
            loadCount.incrementAndGet();
            return new OneBusAwayGtfsLoader().load(source, feedId);
        };
        TransitDataService service = new TransitDataService(
            loader,
            properties("synthetic", fixtureDirectory())
        );

        assertTrue(service.isAvailable());
        assertSame(service.index(), service.index());
        assertTrue(loadCount.get() == 1, () -> "Expected one GTFS load, got " + loadCount.get());
    }

    @Test
    void preservesFeedLoadFailureForClearRepeatedReporting() {
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
        TransitDataService service = new TransitDataService(
            loader,
            properties("broken", Path.of("broken-feed"))
        );

        FeedUnavailableException first = assertThrows(FeedUnavailableException.class, service::index);
        FeedUnavailableException second = assertThrows(FeedUnavailableException.class, service::index);
        assertSame(first, second);
        assertSame(failure, first.getCause());
        assertSame(failure.diagnostic(), first.diagnostic().orElseThrow());
    }

    private static GtfsProperties properties(String feedId, Path path) {
        var properties = new GtfsProperties();
        properties.setFeedId(feedId);
        properties.setMtaPath(path);
        return properties;
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = TransitDataServiceTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
