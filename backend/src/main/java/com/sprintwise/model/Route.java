package com.sprintwise.model;

public record Route(
    FeedScopedId id,
    String shortName,
    String longName,
    int type
) {}
