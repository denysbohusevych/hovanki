#!/usr/bin/env bash
# The lab's runs (docs/perf.md, «Отчёт прогона»), made by perf/run.sh:
#   perf/report.sh list                     every run, newest last: the outcome and the cost of a sync
#   perf/report.sh show [run] [--json]      a run's digest (summary.md), or its whole report (run.json)
#   perf/report.sh compare <run> <run>...   side by side, the change of the second against the first
#   perf/report.sh collect <run>            builds a run's report again (Prometheus keeps 14 days)
#   perf/report.sh import [gatling report]  reports a run made by ./gradlew :perf:gatlingRun directly
# A run is its id, a unique prefix of it, its label, latest or latest~N.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 perf/report.py "$@"
