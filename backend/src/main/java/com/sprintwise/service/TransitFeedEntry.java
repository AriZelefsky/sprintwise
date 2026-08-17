package com.sprintwise.service;

import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.GtfsFeed;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable catalog state for one enabled feed, available or failed. */
public final class TransitFeedEntry {

    private final String feedId;
    private final Path source;
    private final GtfsFeed feed;
    private final GtfsIndex index;
    private final FeedUnavailableException failure;
    private final Duration loadDuration;
    private final Duration indexDuration;

    private TransitFeedEntry(
        String feedId,
        Path source,
        GtfsFeed feed,
        GtfsIndex index,
        FeedUnavailableException failure,
        Duration loadDuration,
        Duration indexDuration
    ) {
        this.feedId = Objects.requireNonNull(feedId, "feedId");
        this.source = Objects.requireNonNull(source, "source");
        this.feed = feed;
        this.index = index;
        this.failure = failure;
        this.loadDuration = Objects.requireNonNull(loadDuration, "loadDuration");
        this.indexDuration = Objects.requireNonNull(indexDuration, "indexDuration");
    }

    static TransitFeedEntry available(
        String feedId,
        Path source,
        GtfsFeed feed,
        GtfsIndex index,
        Duration loadDuration,
        Duration indexDuration
    ) {
        return new TransitFeedEntry(
            feedId,
            source,
            Objects.requireNonNull(feed, "feed"),
            Objects.requireNonNull(index, "index"),
            null,
            loadDuration,
            indexDuration
        );
    }

    static TransitFeedEntry unavailable(
        String feedId,
        Path source,
        FeedUnavailableException failure,
        Duration loadDuration
    ) {
        return new TransitFeedEntry(
            feedId,
            source,
            null,
            null,
            Objects.requireNonNull(failure, "failure"),
            loadDuration,
            Duration.ZERO
        );
    }

    public String feedId() {
        return feedId;
    }

    public Path source() {
        return source;
    }

    public boolean isAvailable() {
        return failure == null;
    }

    public GtfsFeed feed() {
        requireAvailable();
        return feed;
    }

    public GtfsIndex index() {
        requireAvailable();
        return index;
    }

    public Optional<FeedUnavailableException> failure() {
        return Optional.ofNullable(failure);
    }

    public Duration loadDuration() {
        return loadDuration;
    }

    public Duration indexDuration() {
        return indexDuration;
    }

    private void requireAvailable() {
        if (failure != null) {
            throw failure;
        }
    }
}
