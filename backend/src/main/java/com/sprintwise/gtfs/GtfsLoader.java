package com.sprintwise.gtfs;

import com.sprintwise.model.GtfsFeed;
import java.nio.file.Path;

public interface GtfsLoader {

    GtfsFeed load(Path source, String feedId);
}
