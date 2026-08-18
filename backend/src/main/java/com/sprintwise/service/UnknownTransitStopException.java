package com.sprintwise.service;

import com.sprintwise.model.FeedScopedId;
import java.util.Objects;

/** A namespaced origin or destination is absent from its available GTFS feed. */
public final class UnknownTransitStopException extends RuntimeException {

    private final FeedScopedId stopId;
    private final String role;

    UnknownTransitStopException(FeedScopedId stopId, String role) {
        super("Unknown RAPTOR " + role + " stop " + stopId);
        this.stopId = Objects.requireNonNull(stopId, "stopId");
        this.role = Objects.requireNonNull(role, "role");
    }

    public FeedScopedId stopId() {
        return stopId;
    }

    public String role() {
        return role;
    }
}
