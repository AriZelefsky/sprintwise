package com.example.backend.gtfs.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.example.backend.model.FeedScopedId;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServiceCalendarResolverTest {

    private static final String FEED_ID = "synthetic";
    private static ServiceCalendarResolver resolver;

    @BeforeAll
    static void loadFixtureCalendars() throws Exception {
        var feed = new OneBusAwayGtfsLoader().load(fixtureDirectory(), FEED_ID);
        resolver = new ServiceCalendarResolver(feed);
    }

    @Test
    void resolvesNormalWeekdayService() {
        assertEquals(Set.of(id("WEEKDAY")), resolver.activeServiceIds(LocalDate.of(2026, 8, 12)));
    }

    @Test
    void resolvesWeekendService() {
        assertEquals(Set.of(id("WEEKEND")), resolver.activeServiceIds(LocalDate.of(2026, 8, 15)));
    }

    @Test
    void addsCalendarDateOnlyServices() {
        Set<FeedScopedId> active = resolver.activeServiceIds(LocalDate.of(2026, 8, 13));

        assertEquals(Set.of(id("WEEKDAY"), id("SPECIAL"), id("DIRECT_CASE")), active);
        assertTrue(resolver.isActive(id("SPECIAL"), LocalDate.of(2026, 8, 13)));
    }

    @Test
    void removalOverridesAnOtherwiseActiveWeekday() {
        LocalDate removedTuesday = LocalDate.of(2026, 8, 18);

        assertFalse(resolver.isActive(id("WEEKDAY"), removedTuesday));
        assertEquals(Set.of(), resolver.activeServiceIds(removedTuesday));
    }

    private static FeedScopedId id(String serviceId) {
        return new FeedScopedId(FEED_ID, serviceId);
    }

    private static Path fixtureDirectory() throws URISyntaxException, IOException {
        var resource = ServiceCalendarResolverTest.class.getClassLoader()
            .getResource("fixtures/synthetic-gtfs");
        if (resource == null) {
            throw new IOException("Missing synthetic GTFS fixture");
        }
        return Path.of(resource.toURI());
    }
}
