#!/bin/bash

require_java_25() {
  if ! command -v java >/dev/null 2>&1; then
    echo "Error: JDK 25 is required, but java is not on PATH."
    return 1
  fi

  local sprintwise_java_version
  sprintwise_java_version="$(
    java -XshowSettings:properties -version 2>&1 |
      awk -F= '/^[[:space:]]*java\.specification\.version[[:space:]]*=/ { gsub(/[[:space:]]/, "", $2); print $2; exit }'
  )"

  if [ "$sprintwise_java_version" != "25" ]; then
    echo "Error: SprintWise and OTP 2.9 require Java 25; detected Java ${sprintwise_java_version:-unknown}."
    echo "Set JAVA_HOME and PATH to a JDK 25 installation, then try again."
    return 1
  fi
}
