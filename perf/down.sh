#!/usr/bin/env bash
# Stops the performance lab (docs/perf.md), the server's container too.
#   perf/down.sh           the database and the metrics stay for the next time
#   perf/down.sh --wipe    deletes them: the accounts of the past runs, Prometheus' history, Grafana's changes
set -euo pipefail
cd "$(dirname "$0")/.."

compose=(docker compose -f perf/compose.yaml --profile container)
case "${1:-}" in
  "") "${compose[@]}" down ;;
  --wipe) "${compose[@]}" down --volumes ;;
  *)
    echo "Usage: perf/down.sh [--wipe]" >&2
    exit 2
    ;;
esac
