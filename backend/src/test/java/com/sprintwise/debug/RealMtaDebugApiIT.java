package com.sprintwise.debug;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprintwise.service.TransitFeedCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Runs only through the real-mta-index profile and uses the frozen MTA snapshot. */
@SpringBootTest
@AutoConfigureMockMvc
class RealMtaDebugApiIT {

    private static final String KNOWN_TRIP = "mta:L0S1-1-1094-S02_048200_1..S15R";
    private static final Path GTFS_PATH = Path.of(
        System.getProperty("mta.gtfs.path", "../data/gtfs/mta")
    ).toAbsolutePath().normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransitFeedCatalog feeds;

    @DynamicPropertySource
    static void frozenMtaProperties(DynamicPropertyRegistry registry) {
        registry.add("sprintwise.gtfs.feeds[0].id", () -> "mta");
        registry.add("sprintwise.gtfs.feeds[0].path", GTFS_PATH::toString);
        registry.add("sprintwise.gtfs.feeds[0].enabled", () -> "true");
        registry.add("sprintwise.gtfs.feeds[1].id", () -> "lirr");
        registry.add("sprintwise.gtfs.feeds[1].enabled", () -> "false");
    }

    @Test
    void frozenMtaFeedSupportsKnownDebugLookups() throws Exception {
        Assumptions.assumeTrue(
            Files.isDirectory(GTFS_PATH),
            () -> "Frozen MTA GTFS directory is unavailable: " + GTFS_PATH
        );
        assertTrue(feeds.entry("mta").isAvailable());

        mockMvc.perform(get("/debug/stop/{id}", "mta:101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Van Cortlandt Park-242 St"));

        mockMvc.perform(get("/debug/departures")
                .param("stopId", "mta:101S")
                .param("at", "2026-08-13T08:00:00-04:00")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].tripId").value(KNOWN_TRIP))
            .andExpect(jsonPath("$[0].departureSeconds").value(28_920))
            .andExpect(jsonPath("$[0].departureTime").value("2026-08-13T12:02:00Z"));

        mockMvc.perform(get("/debug/trip/{id}", KNOWN_TRIP))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routeId").value("mta:1"))
            .andExpect(jsonPath("$.serviceId").value("mta:Weekday"))
            .andExpect(jsonPath("$.stopTimes", hasSize(38)))
            .andExpect(jsonPath("$.stopTimes[0].stopId").value("mta:101S"));

        mockMvc.perform(get("/debug/services")
                .param("feedId", "mta")
                .param("date", "2026-08-13"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeServiceIds", hasItem("mta:Weekday")));
    }
}
