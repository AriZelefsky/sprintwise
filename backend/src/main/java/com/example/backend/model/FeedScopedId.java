package com.example.backend.model;

import java.util.Objects;

/** An identifier whose namespace is the GTFS feed, not the GTFS agency. */
public record FeedScopedId(String feedId, String id) implements Comparable<FeedScopedId> {

    public FeedScopedId {
        feedId = requireText(feedId, "feedId");
        id = requireText(id, "id");
    }

    @Override
    public int compareTo(FeedScopedId other) {
        int byFeed = feedId.compareTo(other.feedId);
        return byFeed != 0 ? byFeed : id.compareTo(other.id);
    }

    @Override
    public String toString() {
        return feedId + ":" + id;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
