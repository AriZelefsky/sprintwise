package com.example.backend.service;

import java.nio.file.Path;

public final class FeedUnavailableException extends RuntimeException {

    private final String feedId;
    private final Path source;

    FeedUnavailableException(String feedId, Path source, Throwable cause) {
        super("GTFS feed " + feedId + " is unavailable from " + source + ": " + cause.getMessage(), cause);
        this.feedId = feedId;
        this.source = source;
    }

    public String feedId() {
        return feedId;
    }

    public Path source() {
        return source;
    }
}
