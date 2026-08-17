package com.sprintwise.debug;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprintwise.config.GtfsProperties;
import com.sprintwise.config.GtfsProperties.FeedProperties;
import com.sprintwise.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.sprintwise.service.TransitFeedCatalog;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
@AutoConfigureMockMvc
class DebugControllerTest {

    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void syntheticGtfsProperties(DynamicPropertyRegistry registry) {
        registry.add("sprintwise.gtfs.feeds[0].id", () -> "mta");
        registry.add("sprintwise.gtfs.feeds[0].path", FIXTURE::toString);
        registry.add("sprintwise.gtfs.feeds[0].enabled", () -> "true");
        registry.add("sprintwise.gtfs.feeds[1].id", () -> "lirr");
        registry.add("sprintwise.gtfs.feeds[1].path", FIXTURE::toString);
        registry.add("sprintwise.gtfs.feeds[1].enabled", () -> "true");
    }

    @Test
    void looksUpNamespacedStop() throws Exception {
        mockMvc.perform(get("/debug/stop/{id}", "mta:A"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value("mta:A"))
            .andExpect(jsonPath("$.name").value("Alpha"))
            .andExpect(jsonPath("$.latitude").value(40.0))
            .andExpect(jsonPath("$.parentStationId").doesNotExist());
    }

    @Test
    void dispatchesIdenticalRawIdsToIndependentFeeds() throws Exception {
        mockMvc.perform(get("/debug/stop/{id}", "mta:A"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("mta:A"));

        mockMvc.perform(get("/debug/stop/{id}", "lirr:A"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("lirr:A"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "lirr:A")
                .param("at", "2026-08-14T00:04:00-04:00")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tripId").value("lirr:NIGHT"));
    }

    @Test
    void listsPreviousServiceDayDepartureForExplicitTimestamp() throws Exception {
        mockMvc.perform(get("/debug/departures")
                .param("stopId", "mta:A")
                .param("at", "2026-08-14T00:04:00-04:00")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].tripId").value("mta:NIGHT"))
            .andExpect(jsonPath("$[0].routeId").value("mta:DIRECT"))
            .andExpect(jsonPath("$[0].serviceId").value("mta:WEEKDAY"))
            .andExpect(jsonPath("$[0].serviceDate").value("2026-08-13"))
            .andExpect(jsonPath("$[0].departureSeconds").value(86_700))
            .andExpect(jsonPath("$[0].departureTime").value("2026-08-14T04:05:00Z"));
    }

    @Test
    void inspectsTripWithOrderedStopTimes() throws Exception {
        mockMvc.perform(get("/debug/trip/{id}", "mta:NIGHT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("mta:NIGHT"))
            .andExpect(jsonPath("$.routeId").value("mta:DIRECT"))
            .andExpect(jsonPath("$.stopTimes", hasSize(2)))
            .andExpect(jsonPath("$.stopTimes[*].stopId", contains("mta:A", "mta:C")))
            .andExpect(jsonPath("$.stopTimes[*].stopSequence", contains(1, 2)))
            .andExpect(jsonPath("$.stopTimes[*].departureSeconds", contains(86_700, 87_300)));
    }

    @Test
    void inspectsActiveServicesForDate() throws Exception {
        mockMvc.perform(get("/debug/services")
                .param("feedId", "lirr")
                .param("date", "2026-08-13"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.feedId").value("lirr"))
            .andExpect(jsonPath("$.date").value("2026-08-13"))
            .andExpect(jsonPath(
                "$.activeServiceIds",
                contains("lirr:DIRECT_CASE", "lirr:SPECIAL", "lirr:WEEKDAY")
            ));
    }

    @Test
    void returnsClearErrorsForUnknownAndMalformedRequests() throws Exception {
        mockMvc.perform(get("/debug/stop/{id}", "mta:UNKNOWN"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("not_found"))
            .andExpect(jsonPath("$.detail").value("Unknown stop mta:UNKNOWN"));

        mockMvc.perform(get("/debug/trip/{id}", "mta:UNKNOWN"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"))
            .andExpect(jsonPath("$.detail").value("Unknown trip mta:UNKNOWN"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "mta:UNKNOWN")
                .param("at", "2026-08-13T08:00:00-04:00"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));

        mockMvc.perform(get("/debug/stop/{id}", "A"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_id"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "mta:A")
                .param("at", "2026-08-13T08:00:00"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_timestamp"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "mta:A")
                .param("at", "2026-08-13T08:00:00-04:00")
                .param("limit", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_limit"));

        mockMvc.perform(get("/debug/departures").param("stopId", "mta:A"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("missing_parameter"));

        mockMvc.perform(get("/debug/services")
                .param("feedId", "mta")
                .param("date", "08/13/2026"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_date"));

        mockMvc.perform(get("/debug/stop/{id}", "unknown:A"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"))
            .andExpect(jsonPath("$.feedId").value("unknown"));

        mockMvc.perform(get("/debug/services")
                .param("feedId", "bad:id")
                .param("date", "2026-08-13"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_feed_id"));

        mockMvc.perform(get("/debug/services").param("date", "2026-08-13"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("missing_parameter"));
    }

    @Test
    void returnsJsonServiceUnavailableWhenConfiguredFeedCannotLoad() throws Exception {
        var properties = new GtfsProperties();
        properties.setFeeds(List.of(
            new FeedProperties("available", FIXTURE, true),
            new FeedProperties("missing", FIXTURE.resolve("does-not-exist"), true),
            new FeedProperties("disabled", null, false)
        ));
        var catalog = new TransitFeedCatalog(new OneBusAwayGtfsLoader(), properties);
        MockMvc unavailableMvc = MockMvcBuilders
            .standaloneSetup(new DebugController(catalog))
            .setControllerAdvice(new DebugApiExceptionHandler())
            .build();

        unavailableMvc.perform(get("/debug/stop/{id}", "missing:A"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("feed_unavailable"))
            .andExpect(jsonPath("$.feedId").value("missing"))
            .andExpect(jsonPath("$.diagnosticCode").value("source_missing"))
            .andExpect(jsonPath("$.diagnosticSeverity").value("fatal"))
            .andExpect(jsonPath("$.sourceFile").value("unspecified"))
            .andExpect(jsonPath("$.entityType").value("feed"))
            .andExpect(jsonPath("$.entityId").value("missing"))
            .andExpect(jsonPath("$.field").value("source"))
            .andExpect(jsonPath("$.referencedId").value("unspecified"))
            .andExpect(jsonPath("$.source").value(
                FIXTURE.resolve("does-not-exist").toAbsolutePath().normalize().toString()
            ));

        unavailableMvc.perform(get("/debug/stop/{id}", "available:A"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("available:A"));

        unavailableMvc.perform(get("/debug/stop/{id}", "disabled:A"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            return fixtureDirectory();
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = DebugControllerTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
