package com.sprintwise.service;

/** A requested feed is not configured as an enabled Stage 1 feed. */
public final class UnknownFeedException extends RuntimeException {

    private final String feedId;

    UnknownFeedException(String feedId) {
        super("Unknown or disabled feed " + feedId);
        this.feedId = feedId;
    }

    public String feedId() {
        return feedId;
    }
}
