package com.sprintwise.raptor;

/** Counts the principal composite RAPTOR structures and overtaking partitions. */
public record RaptorNetworkStats(
    int feedCount,
    int unavailableFeedCount,
    int stopCount,
    int tripCount,
    int structuralPatternCount,
    int patternCount,
    int overtakingStructuralPatternCount,
    int additionalPatternsFromOvertaking,
    long patternStopPositionCount,
    long tripStopPositionCount
) {}
