package com.sprintwise.raptor;

import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.PickupDropOffType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** A shared stop/access structure with one non-overtaking group of individual trips. */
public final class RaptorTripPattern {

    private final int index;
    private final FeedScopedId routeId;
    private final String directionId;
    private final int[] stopIndexes;
    private final PickupDropOffType[] pickupTypes;
    private final PickupDropOffType[] dropOffTypes;
    private final int[] tripIndexes;
    private final int overtakingGroupCount;

    RaptorTripPattern(
        int index,
        FeedScopedId routeId,
        String directionId,
        int[] stopIndexes,
        PickupDropOffType[] pickupTypes,
        PickupDropOffType[] dropOffTypes,
        int[] tripIndexes,
        int overtakingGroupCount
    ) {
        this.index = index;
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.directionId = directionId;
        this.stopIndexes = stopIndexes.clone();
        this.pickupTypes = pickupTypes.clone();
        this.dropOffTypes = dropOffTypes.clone();
        this.tripIndexes = tripIndexes.clone();
        this.overtakingGroupCount = overtakingGroupCount;
        if (
            this.stopIndexes.length != this.pickupTypes.length
                || this.stopIndexes.length != this.dropOffTypes.length
        ) {
            throw new IllegalArgumentException("Pattern stop/access arrays must have equal lengths");
        }
    }

    public int index() {
        return index;
    }

    public FeedScopedId routeId() {
        return routeId;
    }

    public String directionId() {
        return directionId;
    }

    public int stopCount() {
        return stopIndexes.length;
    }

    public int stopIndexAt(int stopPosition) {
        return stopIndexes[checkedStopPosition(stopPosition)];
    }

    public List<Integer> stopIndexes() {
        return Arrays.stream(stopIndexes).boxed().toList();
    }

    public PickupDropOffType pickupTypeAt(int stopPosition) {
        return pickupTypes[checkedStopPosition(stopPosition)];
    }

    public PickupDropOffType dropOffTypeAt(int stopPosition) {
        return dropOffTypes[checkedStopPosition(stopPosition)];
    }

    public int tripCount() {
        return tripIndexes.length;
    }

    public int tripIndexAt(int timetablePosition) {
        if (timetablePosition < 0 || timetablePosition >= tripIndexes.length) {
            throw new IndexOutOfBoundsException("Unknown pattern trip position " + timetablePosition);
        }
        return tripIndexes[timetablePosition];
    }

    public List<Integer> tripIndexes() {
        return Arrays.stream(tripIndexes).boxed().toList();
    }

    public boolean wasSplitForOvertaking() {
        return overtakingGroupCount > 1;
    }

    public int overtakingGroupCount() {
        return overtakingGroupCount;
    }

    private int checkedStopPosition(int stopPosition) {
        if (stopPosition < 0 || stopPosition >= stopIndexes.length) {
            throw new IndexOutOfBoundsException("Unknown pattern stop position " + stopPosition);
        }
        return stopPosition;
    }
}
