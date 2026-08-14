package com.sprintwise.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GoldenFixtureIntegrityTest {

    private static final String FIXTURE = "fixtures/synthetic-gtfs/";

    @Test
    void syntheticFeedContainsTheExpectedNetwork() throws Exception {
        assertEquals(
            Set.of("A", "B_STATION", "B_RED", "B_BLUE", "C"),
            values(FIXTURE + "stops.txt", "stop_id")
        );
        assertEquals(
            Set.of(
                "RED_EARLY",
                "RED_LATE",
                "BLUE_EARLY",
                "BLUE_LATE",
                "DIRECT_SLOW",
                "NIGHT",
                "SPECIAL_ONLY",
                "WEEKEND_ONLY"
            ),
            values(FIXTURE + "trips.txt", "trip_id")
        );
    }

    @Test
    void syntheticFeedContainsAfterMidnightAndExceptionOnlyService() throws Exception {
        String stopTimes = Files.readString(resource(FIXTURE + "stop_times.txt"));
        String calendarDates = Files.readString(resource(FIXTURE + "calendar_dates.txt"));

        assertTrue(stopTimes.contains("NIGHT,24:05:00,24:05:00,A,1"));
        assertTrue(stopTimes.contains("NIGHT,24:15:00,24:15:00,C,2"));
        assertTrue(calendarDates.contains("SPECIAL,20260813,1"));
        assertTrue(calendarDates.contains("DIRECT_CASE,20260813,1"));
        assertTrue(calendarDates.contains("WEEKDAY,20260818,2"));
    }

    @Test
    void mockFootpathsProduceTheDocumentedExactDurations() throws Exception {
        List<String> rows = Files.readAllLines(resource(FIXTURE + "mock-graphhopper-footpaths.csv"));

        assertTrue(rows.contains("START,A,240,ACCESS"));
        assertTrue(rows.contains("B_RED,B_BLUE,120,TRANSFER"));
        assertTrue(rows.contains("C,END,180,EGRESS"));

        assertEquals(240, secondsAtSpeed(240, 1));
        assertEquals(80, secondsAtSpeed(240, 3));
        assertEquals(120, secondsAtSpeed(120, 1));
        assertEquals(40, secondsAtSpeed(120, 3));
        assertEquals(180, secondsAtSpeed(180, 1));
        assertEquals(60, secondsAtSpeed(180, 3));
    }

    @Test
    void normalizedRealBaselineContainsAllStableCaseIds() throws Exception {
        String baseline = Files.readString(resource("golden/otp-real-baseline.json"));

        for (int i = 1; i <= 10; i++) {
            assertTrue(baseline.contains(String.format("\"id\": \"NYC-%02d\"", i)));
        }
    }

    private static int secondsAtSpeed(int meters, int metersPerSecond) {
        return meters / metersPerSecond;
    }

    private static Set<String> values(String file, String column) throws Exception {
        List<String> lines = Files.readAllLines(resource(file));
        List<String> header = Arrays.asList(lines.getFirst().split(",", -1));
        int columnIndex = header.indexOf(column);
        assertTrue(columnIndex >= 0, () -> "Missing column " + column + " in " + file);

        return lines.stream()
            .skip(1)
            .filter(line -> !line.isBlank())
            .map(line -> line.split(",", -1)[columnIndex])
            .collect(Collectors.toSet());
    }

    private static Path resource(String name) throws URISyntaxException, IOException {
        var resource = GoldenFixtureIntegrityTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Missing test resource: " + name);
        }
        return Path.of(resource.toURI());
    }
}
