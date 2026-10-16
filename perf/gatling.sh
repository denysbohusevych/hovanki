#!/usr/bin/env bash
# A Gatling simulation inside the lab's Docker network (docs/perf.md, «Гатлинг в сети стенда»): for runs of more
# connections than this machine's own network takes (macOS: about 16 000 ephemeral ports to one address and port, and
# Docker Desktop's port forwarding in between). The stand runs already (perf/up.sh --container); the players go
# straight to the server, http://server:8080, unless -Pperf.baseUrl says otherwise (Caddy: https://caddy:8443,…,8446).
#   perf/gatling.sh app.hovanki.perf.Mix -Pperf.players=30000
#   PERF_GATLING_HEAP=3g PERF_GATLING_CPUSET=4-9 perf/gatling.sh app.hovanki.perf.OrdinaryGames -Pperf.games=100
#   perf/gatling.sh UdpGames -Pperf.games=100   # the live channel v2's UDP: a program of its own, not Gatling
# Takes the same -Pperf.<name>=<value> as ./gradlew :perf:gatlingRun; reports land in perf/build/reports/gatling/.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -lt 1 || "$1" == -* ]]; then
  echo "Usage: perf/gatling.sh <simulation class> [-Pperf.<name>=<value>…]" >&2
  exit 2
fi
simulation="$1"
shift
[[ "$simulation" == *.* ]] || simulation="app.hovanki.perf.$simulation"

properties=()
for arg in "$@"; do
  case "$arg" in
    -Pperf.*=*) properties+=("-D${arg#-P}") ;;
    *)
      echo "Not a -Pperf.<name>=<value>: $arg" >&2
      exit 2
      ;;
  esac
done
# Inside the network the server is `server`; the run's admin always goes to it.
[[ " $* " == *" -Pperf.baseUrl="* ]] || properties+=("-Dperf.baseUrl=http://server:8080")
[[ " $* " == *" -Pperf.adminUrl="* ]] || properties+=("-Dperf.adminUrl=http://server:8080")

./gradlew -q :perf:gatlingClasspath

# UdpGames is no Gatling simulation (Gatling has no UDP): its own main, its report in perf/build/reports/udp.
if [[ "$simulation" == app.hovanki.perf.UdpGames ]]; then
  main=(app.hovanki.perf.UdpGames)
  properties+=("-Dhovanki.perf.reports=$PWD/perf/build/reports/udp")
else
  main=(io.gatling.app.Gatling -s "$simulation" -rf "$PWD/perf/build/reports/gatling")
fi

export PERF_REPO="$PWD"
export PERF_GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
# shellcheck disable=SC2086 # the options are words on purpose
exec docker compose -f perf/compose.yaml --profile load run --rm --no-deps gatling \
  java -Xmx"${PERF_GATLING_HEAP:-3g}" -Xss512k -XX:+UseParallelGC --add-opens=java.base/java.lang=ALL-UNNAMED ${PERF_GATLING_JAVA_OPTS:-} \
  "${properties[@]}" @perf/build/gatling-classpath.txt "${main[@]}"
