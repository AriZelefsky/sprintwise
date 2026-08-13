package com.example.backend.index;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.gtfs.onebusaway.OneBusAwayGtfsLoader;
import com.example.backend.model.GtfsFeed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Runs only through the real-mta-index Maven profile, in its own 2 GiB JVM. */
class RealMtaIndexSizeIT {

    private static final long TWO_GIBIBYTES = 2L * 1024 * 1024 * 1024;

    @Test
    void completeFrozenMtaIndexFitsInConfiguredHeap() throws Exception {
        Path gtfsPath = Path.of(System.getProperty("mta.gtfs.path", "../data/gtfs/mta"))
            .toAbsolutePath()
            .normalize();
        Assumptions.assumeTrue(
            Files.isDirectory(gtfsPath),
            () -> "Frozen MTA GTFS directory is unavailable: " + gtfsPath
        );

        long maxHeap = Runtime.getRuntime().maxMemory();
        assertTrue(
            maxHeap <= TWO_GIBIBYTES,
            () -> "Integration JVM exceeds the required -Xmx2G limit: " + bytes(maxHeap)
        );

        forceGc();
        long baselineHeap = usedHeap();
        long gtfsBytes = directoryBytes(gtfsPath);

        Instant loadStarted = Instant.now();
        GtfsFeed feed = new OneBusAwayGtfsLoader().load(gtfsPath, "mta");
        Duration loadDuration = Duration.between(loadStarted, Instant.now());
        long heapAfterLoad = usedHeap();

        int stopTimes = feed.stopTimes().size();
        Instant indexStarted = Instant.now();
        GtfsIndex index = new GtfsIndex(feed);
        Duration indexDuration = Duration.between(indexStarted, Instant.now());
        GtfsIndexStats stats = index.stats();

        feed = null;
        forceGc();
        long retainedHeap = usedHeap();

        System.out.printf(
            "%nFrozen MTA timetable index measurement%n"
                + "GTFS files: %s%n"
                + "Entities: %,d stops, %,d routes, %,d trips, %,d stop times%n"
                + "Load time: %s; index time: %s%n"
                + "Heap: %s baseline, %s after load, %s retained after index (approximate)%n"
                + "Heap limit: %s%n",
            bytes(gtfsBytes),
            stats.stopCount(),
            stats.routeCount(),
            stats.tripCount(),
            stopTimes,
            duration(loadDuration),
            duration(indexDuration),
            bytes(baselineHeap),
            bytes(heapAfterLoad),
            bytes(retainedHeap),
            bytes(maxHeap)
        );
        System.out.printf(
            "Likely largest structures: %,d derived scheduled-departure records; "
                + "%,d stop-time model records plus %,d grouped index references.%n%n",
            stats.scheduledDepartureCount(),
            stopTimes,
            stats.stopTimeReferenceCount()
        );

        assertTrue(index.stop(new com.example.backend.model.FeedScopedId("mta", "101")).isPresent());
    }

    private static long directoryBytes(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    throw new DirectorySizeException(exception);
                }
            }).sum();
        } catch (DirectorySizeException exception) {
            throw (IOException) exception.getCause();
        }
    }

    private static void forceGc() {
        System.gc();
        System.gc();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String bytes(long value) {
        return String.format("%.1f MiB", value / 1024.0 / 1024.0);
    }

    private static String duration(Duration value) {
        return String.format("%.3f s", value.toNanos() / 1_000_000_000.0);
    }

    private static final class DirectorySizeException extends RuntimeException {
        private DirectorySizeException(IOException cause) {
            super(cause);
        }
    }
}
