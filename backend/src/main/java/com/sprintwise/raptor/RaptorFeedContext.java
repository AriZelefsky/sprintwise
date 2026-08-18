package com.sprintwise.raptor;

import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.GtfsFeed;
import java.time.ZoneId;
import java.util.Objects;

/** References the immutable Stage 1 data and time context retained for one available feed. */
public record RaptorFeedContext(
    String feedId,
    ZoneId agencyZoneId,
    GtfsFeed feed,
    GtfsIndex sourceIndex
) {
    public RaptorFeedContext {
        Objects.requireNonNull(feedId, "feedId");
        Objects.requireNonNull(agencyZoneId, "agencyZoneId");
        Objects.requireNonNull(feed, "feed");
        Objects.requireNonNull(sourceIndex, "sourceIndex");
        if (!feedId.equals(feed.feedId()) || !feedId.equals(sourceIndex.feedId())) {
            throw new IllegalArgumentException(
                "RAPTOR feed context " + feedId + " does not match its Stage 1 feed/index"
            );
        }
    }
}
