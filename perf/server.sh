#!/usr/bin/env bash
# The lab's server on this machine, in the foreground (docs/perf.md): the jar of the current code with the Spring
# profiles `e2e,perf`, against the lab's database (perf/up.sh first). Ctrl+C stops it.
#   perf/server.sh
#   PERF_JAVA_OPTS="-Xmx2g" perf/server.sh      other JVM options
# By default the JVM is sized like production's container (2 CPUs, a heap of 480 MB): the JVM picks the Serial GC
# there, as it does on any machine with under 2 CPUs or 1792 MB, and this one is likely bigger. The server still uses
# every core of this machine; for production's 2 CPUs use perf/up.sh --container.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :server:bootJar
# shellcheck disable=SC2086 # the options are words on purpose
exec java ${PERF_JAVA_OPTS:--Xmx480m -XX:+UseSerialGC -XX:ActiveProcessorCount=2} \
  -jar server/build/libs/hovanki-server.jar --spring.profiles.active=e2e,perf
