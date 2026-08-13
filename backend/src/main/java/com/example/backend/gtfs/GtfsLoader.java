package com.example.backend.gtfs;

import com.example.backend.model.GtfsFeed;
import java.nio.file.Path;

public interface GtfsLoader {

    GtfsFeed load(Path source, String feedId);
}
