package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.Trip;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One complete Stage 1 trip represented as compact per-pattern timetable arrays.
 * Missing intermediate times use a private sentinel and are never interpolated.
 */
public final class RaptorTripSchedule {

    static final int MISSING_TIME = -1;

    private final int index;
    private final int patternIndex;
    private final Trip trip;
    private final int[] stopSequences;
    private final int[] arrivalSeconds;
    private final int[] departureSeconds;

    RaptorTripSchedule(
        int index,
        int patternIndex,
        Trip trip,
        int[] stopSequences,
        int[] arrivalSeconds,
        int[] departureSeconds
    ) {
        this.index = index;
        this.patternIndex = patternIndex;
        this.trip = Objects.requireNonNull(trip, "trip");
        this.stopSequences = stopSequences.clone();
        this.arrivalSeconds = arrivalSeconds.clone();
        this.departureSeconds = departureSeconds.clone();
        if (
            this.stopSequences.length != this.arrivalSeconds.length
                || this.stopSequences.length != this.departureSeconds.length
        ) {
            throw new IllegalArgumentException("Trip timetable arrays must have equal lengths");
        }
    }

    public int index() {
        return index;
    }

    public int patternIndex() {
        return patternIndex;
    }

    public Trip trip() {
        return trip;
    }

    public FeedScopedId id() {
        return trip.id();
    }

    public FeedScopedId routeId() {
        return trip.routeId();
    }

    public FeedScopedId serviceId() {
        return trip.serviceId();
    }

    public String feedId() {
        return trip.id().feedId();
    }

    public int stopCount() {
        return stopSequences.length;
    }

    public int stopSequenceAt(int stopPosition) {
        return stopSequences[checkedPosition(stopPosition)];
    }

    public OptionalInt arrivalSecondsAt(int stopPosition) {
        return optionalTime(arrivalSeconds[checkedPosition(stopPosition)]);
    }

    public OptionalInt departureSecondsAt(int stopPosition) {
        return optionalTime(departureSeconds[checkedPosition(stopPosition)]);
    }

    public boolean hasScheduledTimeAt(int stopPosition) {
        int position = checkedPosition(stopPosition);
        return arrivalSeconds[position] != MISSING_TIME && departureSeconds[position] != MISSING_TIME;
    }

    int rawArrivalSecondsAt(int stopPosition) {
        return arrivalSeconds[stopPosition];
    }

    int rawDepartureSecondsAt(int stopPosition) {
        return departureSeconds[stopPosition];
    }

    private int checkedPosition(int stopPosition) {
        if (stopPosition < 0 || stopPosition >= stopSequences.length) {
            throw new IndexOutOfBoundsException("Unknown trip stop position " + stopPosition);
        }
        return stopPosition;
    }

    private static OptionalInt optionalTime(int value) {
        return value == MISSING_TIME ? OptionalInt.empty() : OptionalInt.of(value);
    }
}
