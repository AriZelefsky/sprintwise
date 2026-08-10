#!/bin/bash

# Create data directory if it doesn't exist
mkdir -p data

# Initialize success and failure lists
success_list=()
failure_list=()

# Download OSM extract
if curl -L -o data/new-york-latest.osm.pbf https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf; then
  # Sanity check: Ensure the file is larger than 1MB
  osm_size=$(wc -c < "data/new-york-latest.osm.pbf")
  if [ "$osm_size" -gt 1048576 ]; then
    success_list+=("new-york-latest.osm.pbf")
  else
    echo "Error: OSM file is smaller than expected. Download may have failed."
    exit 1
  fi
else
  failure_list+=("new-york-latest.osm.pbf")
fi

# Download GTFS subway feed
if curl -L -o data/gtfs_subway.zip https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip; then
  # Sanity check: Verify the zip file is valid
  if unzip -t data/gtfs_subway.zip > /dev/null 2>&1; then
    # Unzip into a subfolder and delete the zip file
    mkdir -p data/gtfs_subway
    unzip -q data/gtfs_subway.zip -d data/gtfs_subway
    rm data/gtfs_subway.zip
    success_list+=("gtfs_subway.zip")
  else
    echo "Error: GTFS zip file is invalid."
    exit 1
  fi
else
  failure_list+=("gtfs_subway.zip")
fi

# Print results
if [ ${#success_list[@]} -gt 0 ]; then
  echo "Successfully downloaded: ${success_list[*]}"
fi

if [ ${#failure_list[@]} -gt 0 ]; then
  echo "Failed to download: ${failure_list[*]}"
fi