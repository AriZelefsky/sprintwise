#!/bin/bash

set -e  # exit immediately on unexpected errors

# Create directories if they don't exist
mkdir -p data
mkdir -p otp

# Initialize success and failure lists
success_list=()
failure_list=()

# --- OSM extract (NYC + Long Island clip) ---
OSM_OUT="data/nyc-metro.osm.pbf"
OSM_TEMP="data/.new-york-state-temp.osm.pbf"
OSM_URL="https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"
# min_lon,min_lat,max_lon,max_lat
BBOX="-74.05,40.50,-71.85,41.05"

if [ -f "$OSM_OUT" ]; then
  echo "OSM metro extract already exists, skipping download."
  success_list+=("nyc-metro.osm.pbf (already present)")
else
  if ! command -v osmium >/dev/null 2>&1; then
    echo "Error: osmium-tool is required. Install with: brew install osmium-tool"
    exit 1
  fi

  cleanup_temp() {
    rm -f "$OSM_TEMP"
  }
  trap cleanup_temp EXIT

  echo "Downloading NY state OSM (temporary, for clipping)..."
  if curl -L -o "$OSM_TEMP" "$OSM_URL"; then
    temp_size=$(wc -c < "$OSM_TEMP")
    if [ "$temp_size" -le 1048576 ]; then
      echo "Error: OSM download is smaller than expected. Download may have failed."
      exit 1
    fi
  else
    echo "Error: OSM download failed (curl error)."
    failure_list+=("nyc-metro.osm.pbf")
    exit 1
  fi

  echo "Clipping to NYC + Long Island bbox..."
  osmium extract -b "$BBOX" "$OSM_TEMP" -o "$OSM_OUT"
  cleanup_temp
  trap - EXIT

  osm_size=$(wc -c < "$OSM_OUT")
  if [ "$osm_size" -gt 1048576 ]; then
    success_list+=("nyc-metro.osm.pbf")
  else
    echo "Error: Clipped OSM file is smaller than expected. Clip may have failed."
    rm -f "$OSM_OUT"
    exit 1
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
  echo "Successfully set up:"
  for item in "${success_list[@]}"; do
    printf "\t%s\n" "$item"
  done
fi

if [ ${#failure_list[@]} -gt 0 ]; then
  echo "Failed to download:"
  for item in "${failure_list[@]}"; do
    printf "\t%s\n" "$item"
  done
  exit 1
fi