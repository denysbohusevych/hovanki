#!/usr/bin/env bash
# The lab's server on this machine, in the foreground (docs/perf.md): the jar of the current code with the Spring
# profiles `e2e,perf`, against the lab's database (perf/up.sh first). Ctrl+C stops it.
#   perf/server.sh
#   PERF_JAVA_OPTS="-Xmx2g" perf/server.sh      other JVM options
#   PERF_MAP=tiles perf/server.sh                the real buildings, streets and ground of the tiles perf/tiles.py serves
# By default the JVM is sized like production's container (2 CPUs, a heap of 352 MB, G1 with a pause goal of 20 ms as
# server/Dockerfile names them).
# The server still uses every core of this machine; for production's 2 CPUs use perf/up.sh --container.
set -euo pipefail
cd "$(dirname "$0")/.."

map=()
if [[ "${PERF_MAP:-fake}" == tiles ]]; then
  map=(--hovanki.buildings.source=tiles --hovanki.map.streets=tiles --hovanki.map.terrain=tiles
    "--hovanki.map.tiles-url=${PERF_TILES_URL:-http://localhost:8090/planet}")
fi

./gradlew :server:bootJar
# shellcheck disable=SC2086 # the options are words on purpose
exec java ${PERF_JAVA_OPTS:--Xmx352m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:ActiveProcessorCount=2} \
  -jar server/build/libs/hovanki-server.jar --spring.profiles.active=e2e,perf ${map[@]+"${map[@]}"}
