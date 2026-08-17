package com.sprintwise.service;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.GtfsLoadException;
import com.sprintwise.gtfs.GtfsLoader;
import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.GtfsFeed;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads each enabled feed once and exposes independent immutable Stage 1 snapshots. */
public final class TransitFeedCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(TransitFeedCatalog.class);

    private final NavigableMap<String, TransitFeedEntry> entriesByFeedId;
    private final NavigableSet<String> feedIds;

    public TransitFeedCatalog(GtfsLoader loader, GtfsProperties properties) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(properties, "properties");
        List<FeedProperties> configuredFeeds = Objects.requireNonNull(
            properties.getFeeds(),
            "sprintwise.gtfs.feeds"
        );

        var validated = validateConfigurations(configuredFeeds);
        var entries = new TreeMap<String, TransitFeedEntry>();
        for (ConfiguredFeed configuredFeed : validated) {
            if (!configuredFeed.enabled()) {
                continue;
            }
            entries.put(configuredFeed.feedId(), load(loader, configuredFeed));
        }
        this.entriesByFeedId = Collections.unmodifiableNavigableMap(entries);
        this.feedIds = Collections.unmodifiableNavigableSet(new TreeSet<>(entries.keySet()));
    }

    public NavigableSet<String> feedIds() {
        return feedIds;
    }

    public List<TransitFeedEntry> entries() {
        return List.copyOf(entriesByFeedId.values());
    }

    public Optional<TransitFeedEntry> find(String feedId) {
        return Optional.ofNullable(entriesByFeedId.get(feedId));
    }

    public TransitFeedEntry entry(String feedId) {
        TransitFeedEntry entry = entriesByFeedId.get(requireText(feedId, "feedId"));
        if (entry == null) {
            throw new UnknownFeedException(feedId);
        }
        return entry;
    }

    public GtfsFeed feed(String feedId) {
        return entry(feedId).feed();
    }

    public GtfsIndex index(String feedId) {
        return entry(feedId).index();
    }

    private static TransitFeedEntry load(GtfsLoader loader, ConfiguredFeed configuredFeed) {
        Instant loadStarted = Instant.now();
        try {
            GtfsFeed feed = loader.load(configuredFeed.source(), configuredFeed.feedId());
            Duration loadDuration = Duration.between(loadStarted, Instant.now());
            Instant indexStarted = Instant.now();
            GtfsIndex index = new GtfsIndex(feed);
            Duration indexDuration = Duration.between(indexStarted, Instant.now());
            LOG.info(
                "Loaded GTFS feed {} from {}: {} stops, {} routes, {} trips, {} departures",
                configuredFeed.feedId(),
                configuredFeed.source(),
                index.stats().stopCount(),
                index.stats().routeCount(),
                index.stats().tripCount(),
                index.stats().scheduledDepartureCount()
            );
            return TransitFeedEntry.available(
                configuredFeed.feedId(),
                configuredFeed.source(),
                feed,
                index,
                loadDuration,
                indexDuration
            );
        } catch (GtfsLoadException exception) {
            Duration loadDuration = Duration.between(loadStarted, Instant.now());
            var failure = new FeedUnavailableException(
                configuredFeed.feedId(),
                configuredFeed.source(),
                exception
            );
            LOG.error(failure.getMessage());
            return TransitFeedEntry.unavailable(
                configuredFeed.feedId(),
                configuredFeed.source(),
                failure,
                loadDuration
            );
        }
    }

    private static List<ConfiguredFeed> validateConfigurations(List<FeedProperties> feeds) {
        var byId = new TreeMap<String, ConfiguredFeed>();
        for (int index = 0; index < feeds.size(); index++) {
            FeedProperties feed = Objects.requireNonNull(
                feeds.get(index),
                "sprintwise.gtfs.feeds[" + index + "]"
            );
            String propertyPrefix = "sprintwise.gtfs.feeds[" + index + "]";
            String feedId = requireText(feed.getId(), propertyPrefix + ".id");
            Path source = feed.getPath();
            if (feed.isEnabled()) {
                source = Objects.requireNonNull(source, propertyPrefix + ".path")
                    .toAbsolutePath()
                    .normalize();
            } else if (source != null) {
                source = source.toAbsolutePath().normalize();
            }

            var configured = new ConfiguredFeed(feedId, source, feed.isEnabled());
            if (byId.putIfAbsent(feedId, configured) != null) {
                throw new IllegalArgumentException("Duplicate configured GTFS feed ID " + feedId);
            }
        }
        return List.copyOf(byId.values());
    }

    private static String requireText(String value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        return value;
    }

    private record ConfiguredFeed(String feedId, Path source, boolean enabled) {}
}
