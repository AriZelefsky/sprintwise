package com.sprintwise.debug;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Full production-path HTTP proof over the dedicated exact-stop RAPTOR GTFS fixture. */
@SpringBootTest
@AutoConfigureMockMvc
class RaptorDebugControllerTest {

    private static final Path FIXTURE = fixtureDirectoryUnchecked();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void syntheticRaptorProperties(DynamicPropertyRegistry registry) {
        registry.add("sprintwise.gtfs.feeds[0].id", () -> "toy");
        registry.add("sprintwise.gtfs.feeds[0].path", FIXTURE::toString);
        registry.add("sprintwise.gtfs.feeds[0].enabled", () -> "true");
        registry.add("sprintwise.gtfs.feeds[1].id", () -> "unused");
        registry.add("sprintwise.gtfs.feeds[1].enabled", () -> "false");
    }

    @Test
    void returnsOneFiveStopTripAsOneTransitLeg() throws Exception {
        mockMvc.perform(routeRequest("toy:A", "toy:E", "2026-08-17T07:59:00-04:00", 4))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.reachable").value(true))
            .andExpect(jsonPath("$.winningRound").value(1))
            .andExpect(jsonPath("$.numberOfBoardedTrips").value(1))
            .andExpect(jsonPath("$.legs", hasSize(1)))
            .andExpect(jsonPath("$.legs[0].tripId").value("toy:DIRECT_FIVE"))
            .andExpect(jsonPath("$.legs[0].boardingStopId").value("toy:A"))
            .andExpect(jsonPath("$.legs[0].alightingStopId").value("toy:E"))
            .andExpect(jsonPath("$.legs[0].boardingStopPosition").value(0))
            .andExpect(jsonPath("$.legs[0].alightingStopPosition").value(4));
    }

    @Test
    void returnsTwoAndThreeDistinctSameRouteTripsInOrder() throws Exception {
        mockMvc.perform(routeRequest("toy:A", "toy:C", "2026-08-17T07:59:00-04:00", 2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.winningRound").value(2))
            .andExpect(jsonPath("$.numberOfBoardedTrips").value(2))
            .andExpect(jsonPath(
                "$.legs[*].tripId",
                contains("toy:LEG_1", "toy:LEG_2")
            ))
            .andExpect(jsonPath(
                "$.legs[*].routeId",
                contains("toy:LOCAL", "toy:LOCAL")
            ))
            .andExpect(jsonPath("$.legs[0].alightingStopId").value("toy:B"))
            .andExpect(jsonPath("$.legs[0].arrivalTime").value("2026-08-17T12:10:00Z"))
            .andExpect(jsonPath("$.legs[1].boardingStopId").value("toy:B"))
            .andExpect(jsonPath("$.legs[1].departureTime").value("2026-08-17T12:11:00Z"));

        mockMvc.perform(routeRequest("toy:A", "toy:D", "2026-08-17T07:59:00-04:00", 3))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.winningRound").value(3))
            .andExpect(jsonPath("$.numberOfBoardedTrips").value(3))
            .andExpect(jsonPath("$.legs", hasSize(3)))
            .andExpect(jsonPath(
                "$.legs[*].tripId",
                contains("toy:LEG_1", "toy:LEG_2", "toy:LEG_3")
            ))
            .andExpect(jsonPath("$.legs[1].alightingStopId").value("toy:C"))
            .andExpect(jsonPath("$.legs[2].boardingStopId").value("toy:C"));
    }

    @Test
    void handlesPreviousServiceDateZeroLegAndUnreachableResults() throws Exception {
        mockMvc.perform(routeRequest("toy:A", "toy:C", "2026-08-18T00:04:00-04:00", 1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.legs[0].tripId").value("toy:NIGHT"))
            .andExpect(jsonPath("$.legs[0].serviceDate").value("2026-08-17"))
            .andExpect(jsonPath("$.legs[0].departureSeconds").value(86_700))
            .andExpect(jsonPath("$.legs[0].arrivalSeconds").value(87_300));

        mockMvc.perform(routeRequest("toy:A", "toy:A", "2026-08-17T07:59:00-04:00", 4))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reachable").value(true))
            .andExpect(jsonPath("$.winningRound").value(0))
            .andExpect(jsonPath("$.numberOfBoardedTrips").value(0))
            .andExpect(jsonPath("$.legs", empty()));

        mockMvc.perform(routeRequest(
                "toy:ISOLATED",
                "toy:E",
                "2026-08-17T07:59:00-04:00",
                4
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.arrivalAt").doesNotExist())
            .andExpect(jsonPath("$.winningRound").doesNotExist())
            .andExpect(jsonPath("$.legs", empty()));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
        routeRequest(String from, String to, String departAt, int maxRounds) {
        return post("/debug/raptor")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "fromStopId": "%s",
                  "toStopId": "%s",
                  "departAt": "%s",
                  "maxRounds": %d
                }
                """.formatted(from, to, departAt, maxRounds));
    }

    private static Path fixtureDirectoryUnchecked() {
        try {
            var resource = RaptorDebugControllerTest.class.getClassLoader()
                .getResource("fixtures/synthetic-raptor-gtfs");
            if (resource == null) {
                throw new IOException("Missing synthetic RAPTOR GTFS fixture");
            }
            return Path.of(resource.toURI());
        } catch (IOException | URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
