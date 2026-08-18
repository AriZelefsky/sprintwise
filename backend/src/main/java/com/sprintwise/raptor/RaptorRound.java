package com.sprintwise.raptor;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;

/**
 * One immutable marked-stop round.
 *
 * <p>The map contains labels created with exactly {@link #number()} boarded
 * trips that strictly improved the best arrival from all earlier rounds. It is
 * therefore an improvement delta, not a copy of every at-most-N best label.</p>
 */
public final class RaptorRound {

    private final int number;
    private final NavigableMap<Integer, RaptorLabel> improvedLabels;
    private final int patternScanCount;

    RaptorRound(
        int number,
        NavigableMap<Integer, RaptorLabel> improvedLabels,
        int patternScanCount
    ) {
        if (number < 0) {
            throw new IllegalArgumentException("RAPTOR round number must not be negative");
        }
        if (patternScanCount < 0 || (number == 0 && patternScanCount != 0)) {
            throw new IllegalArgumentException("Invalid RAPTOR pattern scan count");
        }
        this.number = number;
        improvedLabels.forEach((stopIndex, label) -> {
            if (stopIndex != label.stopIndex()) {
                throw new IllegalArgumentException("Round label key must equal its stop index");
            }
            if (label.round() != number) {
                throw new IllegalArgumentException("Round contains a label from another round");
            }
        });
        this.improvedLabels = Collections.unmodifiableNavigableMap(
            new TreeMap<>(improvedLabels)
        );
        this.patternScanCount = patternScanCount;
    }

    public int number() {
        return number;
    }

    public NavigableMap<Integer, RaptorLabel> improvedLabels() {
        return improvedLabels;
    }

    public NavigableSet<Integer> markedStopIndexes() {
        return improvedLabels.navigableKeySet();
    }

    public int patternScanCount() {
        return patternScanCount;
    }

    public boolean hasImprovements() {
        return !improvedLabels.isEmpty();
    }
}
