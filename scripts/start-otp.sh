#!/bin/bash

set -e

JAR="otp/otp-shaded-2.9.0.jar"
DATA_DIR="data"
GRAPH="$DATA_DIR/graph.obj"

if [ ! -f "$JAR" ]; then
  echo "Error: OTP jar not found at $JAR. Run the setup/download script first."
  exit 1
fi

if [ ! -f "$GRAPH" ]; then
  echo "Error: Graph not found at $GRAPH. Run ./scripts/run-otp.sh first to build it."
  exit 1
fi

java -Xmx4G -jar "$JAR" --load --serve "$DATA_DIR"
