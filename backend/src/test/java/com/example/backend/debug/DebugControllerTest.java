package com.example.backend.debug;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.config.GtfsProperties;
import com.example.backend.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.example.backend.service.TransitDataService;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
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
        registry.add("sprintwise.gtfs.mta-path", FIXTURE::toString);
        registry.add("sprintwise.gtfs.feed-id", () -> "synthetic");
    }

    @Test
    void looksUpNamespacedStop() throws Exception {
        mockMvc.perform(get("/debug/stop/{id}", "synthetic:A"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value("synthetic:A"))
            .andExpect(jsonPath("$.name").value("Alpha"))
            .andExpect(jsonPath("$.latitude").value(40.0))
            .andExpect(jsonPath("$.parentStationId").doesNotExist());
    }

    @Test
    void listsPreviousServiceDayDepartureForExplicitTimestamp() throws Exception {
        mockMvc.perform(get("/debug/departures")
                .param("stopId", "synthetic:A")
                .param("at", "2026-08-14T00:04:00-04:00")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].tripId").value("synthetic:NIGHT"))
            .andExpect(jsonPath("$[0].routeId").value("synthetic:DIRECT"))
            .andExpect(jsonPath("$[0].serviceId").value("synthetic:WEEKDAY"))
            .andExpect(jsonPath("$[0].serviceDate").value("2026-08-13"))
            .andExpect(jsonPath("$[0].departureSeconds").value(86_700))
            .andExpect(jsonPath("$[0].departureTime").value("2026-08-14T04:05:00Z"));
    }

    @Test
    void inspectsTripWithOrderedStopTimes() throws Exception {
        mockMvc.perform(get("/debug/trip/{id}", "synthetic:NIGHT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("synthetic:NIGHT"))
            .andExpect(jsonPath("$.routeId").value("synthetic:DIRECT"))
            .andExpect(jsonPath("$.stopTimes", hasSize(2)))
            .andExpect(jsonPath("$.stopTimes[*].stopId", contains("synthetic:A", "synthetic:C")))
            .andExpect(jsonPath("$.stopTimes[*].stopSequence", contains(1, 2)))
            .andExpect(jsonPath("$.stopTimes[*].departureSeconds", contains(86_700, 87_300)));
    }

    @Test
    void inspectsActiveServicesForDate() throws Exception {
        mockMvc.perform(get("/debug/services").param("date", "2026-08-13"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.feedId").value("synthetic"))
            .andExpect(jsonPath("$.date").value("2026-08-13"))
            .andExpect(jsonPath(
                "$.activeServiceIds",
                contains("synthetic:DIRECT_CASE", "synthetic:SPECIAL", "synthetic:WEEKDAY")
            ));
    }

    @Test
    void returnsClearErrorsForUnknownAndMalformedRequests() throws Exception {
        mockMvc.perform(get("/debug/stop/{id}", "synthetic:UNKNOWN"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("not_found"))
            .andExpect(jsonPath("$.detail").value("Unknown stop synthetic:UNKNOWN"));

        mockMvc.perform(get("/debug/trip/{id}", "synthetic:UNKNOWN"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"))
            .andExpect(jsonPath("$.detail").value("Unknown trip synthetic:UNKNOWN"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "synthetic:UNKNOWN")
                .param("at", "2026-08-13T08:00:00-04:00"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("not_found"));

        mockMvc.perform(get("/debug/stop/{id}", "A"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_id"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "synthetic:A")
                .param("at", "2026-08-13T08:00:00"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_timestamp"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "synthetic:A")
                .param("at", "2026-08-13T08:00:00-04:00")
                .param("limit", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_limit"));

        mockMvc.perform(get("/debug/departures").param("stopId", "synthetic:A"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("missing_parameter"));

        mockMvc.perform(get("/debug/services").param("date", "08/13/2026"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("malformed_date"));
    }

    @Test
    void returnsJsonServiceUnavailableWhenConfiguredFeedCannotLoad() throws Exception {
        var properties = new GtfsProperties();
        properties.setFeedId("missing");
        properties.setMtaPath(FIXTURE.resolve("does-not-exist"));
        var unavailable = new TransitDataService(new OneBusAwayGtfsLoader(), properties);
        MockMvc unavailableMvc = MockMvcBuilders
            .standaloneSetup(new DebugController(unavailable))
            .setControllerAdvice(new DebugApiExceptionHandler())
            .build();

        unavailableMvc.perform(get("/debug/stop/{id}", "missing:A"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("feed_unavailable"))
            .andExpect(jsonPath("$.feedId").value("missing"))
            .andExpect(jsonPath("$.source").value(
                FIXTURE.resolve("does-not-exist").toAbsolutePath().normalize().toString()
            ));
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
