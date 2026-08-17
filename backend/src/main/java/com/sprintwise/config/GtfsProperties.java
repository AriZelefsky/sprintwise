package com.sprintwise.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sprintwise.gtfs")
public final class GtfsProperties {

    private List<FeedProperties> feeds = new ArrayList<>(List.of(
        new FeedProperties("mta", Path.of("../data/gtfs/mta"), true),
        new FeedProperties("lirr", Path.of("../data/gtfs/lirr"), true)
    ));

    public List<FeedProperties> getFeeds() {
        return feeds;
    }

    public void setFeeds(List<FeedProperties> feeds) {
        this.feeds = feeds;
    }

    public static final class FeedProperties {

        private String id;
        private Path path;
        private boolean enabled = true;

        public FeedProperties() {}

        public FeedProperties(String id, Path path, boolean enabled) {
            this.id = id;
            this.path = path;
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
