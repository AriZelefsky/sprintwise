#!/bin/bash

# NOTE: This is a ONE-TIME (or "whenever data changes") step — it builds the
# routable graph from raw OSM + GTFS data, which is slow and CPU/memory heavy.
# Day-to-day, we'll merely LOAD the already-built graph.obj (via a separate
# start-otp.sh using --load) because the expensive build step doesn't need to
# be repeated every time you just want to start the server.

set -e

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/require-java-25.sh"
require_java_25

JAR="otp/otp-shaded-2.9.0.jar"
DATA_DIR="data"

if [ ! -f "$JAR" ]; then
  echo "Error: OTP jar not found at $JAR. Run the setup/download script first."
  exit 1
fi

if [ ! -f "$DATA_DIR/nyc-metro.osm.pbf" ]; then
  echo "Error: OSM data not found in $DATA_DIR. Run the setup/download script first."
  exit 1
fi

java -Xmx4G -jar "$JAR" --build --save "$DATA_DIR"

echo "Graph generation completed successfully."
