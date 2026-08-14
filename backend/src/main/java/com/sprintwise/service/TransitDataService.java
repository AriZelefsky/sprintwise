package com.sprintwise.service;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.index.GtfsIndex;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads one immutable feed/index snapshot and never reloads it per request. */
public final class TransitDataService {

    private static final Logger LOG = LoggerFactory.getLogger(TransitDataService.class);

    private final String feedId;
    private final Path source;
    private final GtfsIndex index;
    private final FeedUnavailableException loadFailure;

    public TransitDataService(GtfsLoader loader, GtfsProperties properties) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(properties, "properties");
        this.feedId = requireText(properties.getFeedId(), "sprintwise.gtfs.feed-id");
        this.source = Objects.requireNonNull(
            properties.getMtaPath(),
            "sprintwise.gtfs.mta-path"
        ).toAbsolutePath().normalize();

        GtfsIndex loadedIndex = null;
        FeedUnavailableException failure = null;
        try {
            loadedIndex = new GtfsIndex(loader.load(source, feedId));
            LOG.info(
                "Loaded GTFS feed {} from {}: {} stops, {} routes, {} trips, {} departures",
                feedId,
                source,
                loadedIndex.stats().stopCount(),
                loadedIndex.stats().routeCount(),
                loadedIndex.stats().tripCount(),
                loadedIndex.stats().scheduledDepartureCount()
            );
        } catch (RuntimeException exception) {
            failure = new FeedUnavailableException(feedId, source, exception);
            LOG.error(failure.getMessage());
        }
        this.index = loadedIndex;
        this.loadFailure = failure;
    }

    public String feedId() {
        return feedId;
    }

    public Path source() {
        return source;
    }

    public GtfsIndex index() {
        if (loadFailure != null) {
            throw loadFailure;
        }
        return index;
    }

    public boolean isAvailable() {
        return loadFailure == null;
    }

    private static String requireText(String value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        return value;
    }
}
