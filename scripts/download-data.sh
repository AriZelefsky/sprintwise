#!/bin/bash

set -e  # exit immediately on unexpected errors

# Create directories if they don't exist
mkdir -p data
mkdir -p otp

# Initialize success and failure lists
success_list=()
failure_list=()

# --- OSM extract ---
if [ -f "data/new-york-latest.osm.pbf" ]; then
  echo "OSM file already exists, skipping download."
  success_list+=("new-york-latest.osm.pbf (already present)")
else
  if curl -L -o data/new-york-latest.osm.pbf https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf; then
    osm_size=$(wc -c < "data/new-york-latest.osm.pbf")
    if [ "$osm_size" -gt 1048576 ]; then
      success_list+=("new-york-latest.osm.pbf")
    else
      echo "Error: OSM file is smaller than expected. Download may have failed."
      rm -f data/new-york-latest.osm.pbf
      exit 1
    fi
  else
    echo "Error: OSM download failed (curl error)."
    failure_list+=("new-york-latest.osm.pbf")
  fi
fi

# --- GTFS subway feed ---
if [ -d "data/gtfs_subway" ] && [ "$(ls -A data/gtfs_subway 2>/dev/null)" ]; then
  echo "GTFS subway data already exists, skipping download."
  success_list+=("gtfs_subway (already present)")
else
  if curl -L -o data/gtfs_subway.zip https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip; then
    if unzip -t data/gtfs_subway.zip > /dev/null 2>&1; then
      mkdir -p data/gtfs_subway
      unzip -q data/gtfs_subway.zip -d data/gtfs_subway
      rm data/gtfs_subway.zip
      success_list+=("gtfs_subway.zip")
    else
      echo "Error: GTFS zip file is invalid."
      rm -f data/gtfs_subway.zip
      exit 1
    fi
  else
    echo "Error: GTFS download failed (curl error)."
    failure_list+=("gtfs_subway.zip")
  fi
fi

# --- OTP jar ---
OTP_VERSION="2.9.0"
OTP_JAR="otp-shaded-${OTP_VERSION}.jar"
OTP_URL="https://github.com/opentripplanner/OpenTripPlanner/releases/download/v${OTP_VERSION}/${OTP_JAR}"

if [ -f "otp/${OTP_JAR}" ]; then
  echo "OTP jar already exists, skipping download."
  success_list+=("${OTP_JAR} (already present)")
else
  if curl -L -o "otp/${OTP_JAR}" "$OTP_URL"; then
    otp_size=$(wc -c < "otp/${OTP_JAR}")
    if [ "$otp_size" -gt 50000000 ]; then  # sanity check: expect >50MB, real file is ~175MB
      success_list+=("${OTP_JAR}")
    else
      echo "Error: OTP jar is smaller than expected. Download may have failed."
      rm -f "otp/${OTP_JAR}"
      exit 1
    fi
  else
    echo "Error: OTP jar download failed (curl error)."
    failure_list+=("${OTP_JAR}")
  fi
fi

# --- Print results ---
echo ""
if [ ${#success_list[@]} -gt 0 ]; then
  echo "Successfully set up: ${success_list[*]}"
fi

if [ ${#failure_list[@]} -gt 0 ]; then
  echo "Failed to download: ${failure_list[*]}"
  exit 1
fi