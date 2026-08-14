package com.example.backend.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sprintwise.gtfs")
public final class GtfsProperties {

    private Path mtaPath = Path.of("../data/gtfs/mta");
    private String feedId = "mta";

    public Path getMtaPath() {
        return mtaPath;
    }

    public void setMtaPath(Path mtaPath) {
        this.mtaPath = mtaPath;
    }

    public String getFeedId() {
        return feedId;
    }

    public void setFeedId(String feedId) {
        this.feedId = feedId;
    }
}
