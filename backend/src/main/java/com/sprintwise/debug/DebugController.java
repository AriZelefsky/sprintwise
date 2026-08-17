package com.sprintwise.debug;

import com.sprintwise.index.GtfsIndex;
import com.sprintwise.model.FeedScopedId;
import com.sprintwise.model.Stop;
import com.sprintwise.model.Trip;
import com.sprintwise.service.TransitFeedCatalog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public final class DebugController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TransitFeedCatalog feeds;

    public DebugController(TransitFeedCatalog feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/stop/{id}")
    public StopDebugResponse stop(@PathVariable String id) {
        FeedScopedId stopId = parseId(id);
        Stop stop = index(stopId.feedId()).stop(stopId).orElseThrow(() ->
            new DebugNotFoundException("Unknown stop " + stopId)
        );
        return StopDebugResponse.from(stop);
    }

    @GetMapping("/departures")
    public List<DepartureDebugResponse> departures(
        @RequestParam String stopId,
        @RequestParam String at,
        @RequestParam(defaultValue = "20") int limit
    ) {
        FeedScopedId parsedStopId = parseId(stopId);
        GtfsIndex index = index(parsedStopId.feedId());
        if (index.stop(parsedStopId).isEmpty()) {
            throw new DebugNotFoundException("Unknown stop " + parsedStopId);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new DebugBadRequestException(
                "invalid_limit",
                "limit must be between 1 and " + MAX_LIMIT + "; received " + limit
            );
        }
        Instant queryInstant = parseTimestamp(at);
        return index.nextDepartures(parsedStopId, queryInstant, limit).stream()
            .map(DepartureDebugResponse::from)
            .toList();
    }

    @GetMapping("/trip/{id}")
    public TripDebugResponse trip(@PathVariable String id) {
        FeedScopedId tripId = parseId(id);
        GtfsIndex index = index(tripId.feedId());
        Trip trip = index.trip(tripId).orElseThrow(() ->
            new DebugNotFoundException("Unknown trip " + tripId)
        );
        return TripDebugResponse.from(trip, index.stopTimesForTrip(tripId));
    }

    @GetMapping("/services")
    public ActiveServicesDebugResponse services(
        @RequestParam String feedId,
        @RequestParam String date
    ) {
        LocalDate serviceDate = parseDate(date);
        GtfsIndex index = index(parseFeedId(feedId));
        return ActiveServicesDebugResponse.from(
            index.feedId(),
            serviceDate,
            index.activeServiceIds(serviceDate)
        );
    }

    private GtfsIndex index(String feedId) {
        return feeds.index(feedId);
    }

    private static FeedScopedId parseId(String value) {
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new DebugBadRequestException(
                "malformed_id",
                "ID must be namespaced as feed:id; received " + value
            );
        }
        try {
            return new FeedScopedId(value.substring(0, separator), value.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new DebugBadRequestException("malformed_id", exception.getMessage());
        }
    }

    private static String parseFeedId(String value) {
        if (value == null || value.isBlank() || value.contains(":")) {
            throw new DebugBadRequestException(
                "malformed_feed_id",
                "feedId must be a nonblank namespace without a colon; received " + value
            );
        }
        return value;
    }

    private static Instant parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw new DebugBadRequestException(
                "malformed_timestamp",
                "at must be an ISO-8601 timestamp with an explicit offset, for example "
                    + "2026-08-13T08:00:00-04:00; received " + value
            );
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new DebugBadRequestException(
                "malformed_date",
                "date must use ISO-8601 YYYY-MM-DD format; received " + value
            );
        }
    }
}
