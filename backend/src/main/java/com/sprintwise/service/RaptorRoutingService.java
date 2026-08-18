package com.sprintwise.service;

import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.raptor.RaptorJourneyReconstructor;
import com.sprintwise.raptor.RaptorNetwork;
import com.sprintwise.raptor.RaptorRoundRouter;
import com.sprintwise.raptor.RaptorSearchResult;
import java.time.Instant;
import java.util.Objects;

/** Coordinates exact-stop, transit-only RAPTOR search and journey reconstruction. */
public final class RaptorRoutingService {

    private final TransitFeedCatalog feeds;
    private final RaptorRoundRouter router;
    private final RaptorJourneyReconstructor reconstructor;

    public RaptorRoutingService(TransitFeedCatalog feeds, RaptorNetwork network) {
        this.feeds = Objects.requireNonNull(feeds, "feeds");
        this.router = new RaptorRoundRouter(Objects.requireNonNull(network, "network"));
        this.reconstructor = new RaptorJourneyReconstructor();
    }

    public RaptorRoutingOutcome route(
        FeedScopedId origin,
        FeedScopedId destination,
        Instant departureInstant,
        int maxRounds
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departureInstant, "departureInstant");
        requireStop(origin, "origin");
        requireStop(destination, "destination");

        RaptorSearchResult result = router.route(
            origin,
            destination,
            departureInstant,
            maxRounds
        );
        return new RaptorRoutingOutcome(result, reconstructor.reconstruct(result));
    }

    private void requireStop(FeedScopedId stopId, String role) {
        GtfsIndex index = feeds.index(stopId.feedId());
        if (index.stop(stopId).isEmpty()) {
            throw new UnknownTransitStopException(stopId, role);
        }
    }
}
