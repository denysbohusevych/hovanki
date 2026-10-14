#!/usr/bin/env bash
# A load run with its report (docs/perf.md, «Отчёт прогона»): runs the simulation like ./gradlew :perf:gatlingRun and
# gathers the server's metrics, the containers' CPU, memory and network, the database's top and Gatling's numbers
# into perf/runs/<id>/run.json and a short summary.md. The lab and the server must be up (perf/up.sh [--container]).
#   perf/run.sh OrdinaryGames -Pperf.transport=socket -Pperf.games=20 -Pperf.steps=6
#   perf/run.sh --label "без копий в правилах" --baseline latest OrdinaryGames -Pperf.games=40
# Reading the runs: perf/report.sh list | show [run] | compare <run> <run>.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 perf/report.py run "$@"
