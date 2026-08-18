package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/** Immutable output of one time-only, fixed-stop RAPTOR search. */
public final class RaptorSearchResult {

    private final FeedScopedId origin;
    private final FeedScopedId destination;
    private final Instant departureInstant;
    private final int maxRounds;
    private final List<RaptorRound> rounds;
    private final NavigableMap<Integer, RaptorLabel> bestLabelsByStopIndex;
    private final NavigableMap<FeedScopedId, RaptorLabel> bestLabelsByStopId;
    private final RaptorLabel bestDestinationLabel;

    RaptorSearchResult(
        FeedScopedId origin,
        FeedScopedId destination,
        Instant departureInstant,
        int maxRounds,
        List<RaptorRound> rounds,
        NavigableMap<Integer, RaptorLabel> bestLabelsByStopIndex,
        NavigableMap<FeedScopedId, RaptorLabel> bestLabelsByStopId,
        RaptorLabel bestDestinationLabel
    ) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.departureInstant = Objects.requireNonNull(departureInstant, "departureInstant");
        this.maxRounds = maxRounds;
        this.rounds = List.copyOf(rounds);
        this.bestLabelsByStopIndex = Collections.unmodifiableNavigableMap(
            new TreeMap<>(bestLabelsByStopIndex)
        );
        this.bestLabelsByStopId = Collections.unmodifiableNavigableMap(
            new TreeMap<>(bestLabelsByStopId)
        );
        this.bestDestinationLabel = bestDestinationLabel;
    }

    public FeedScopedId origin() {
        return origin;
    }

    public FeedScopedId destination() {
        return destination;
    }

    public Instant departureInstant() {
        return departureInstant;
    }

    public int maxRounds() {
        return maxRounds;
    }

    /** Includes round zero and every transit round actually attempted. */
    public List<RaptorRound> rounds() {
        return rounds;
    }

    /** Number of transit rounds actually attempted; round zero is not counted. */
    public int completedTransitRounds() {
        return rounds.size() - 1;
    }

    public NavigableMap<Integer, RaptorLabel> bestLabelsByStopIndex() {
        return bestLabelsByStopIndex;
    }

    public Optional<RaptorLabel> bestLabel(int stopIndex) {
        return Optional.ofNullable(bestLabelsByStopIndex.get(stopIndex));
    }

    public Optional<RaptorLabel> bestLabel(FeedScopedId stopId) {
        return Optional.ofNullable(bestLabelsByStopId.get(stopId));
    }

    public Optional<RaptorLabel> bestDestinationLabel() {
        return Optional.ofNullable(bestDestinationLabel);
    }

    public boolean destinationReachable() {
        return bestDestinationLabel != null;
    }

    public OptionalInt winningRound() {
        return bestDestinationLabel == null
            ? OptionalInt.empty()
            : OptionalInt.of(bestDestinationLabel.round());
    }
}
