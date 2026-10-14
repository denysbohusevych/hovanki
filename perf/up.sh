#!/usr/bin/env bash
# The server's performance lab (docs/perf.md): its database, Prometheus and Grafana in Docker.
#   perf/up.sh               the lab; the server then runs on this machine: perf/server.sh, or the IDE with the
#                            Spring profiles `e2e,perf`
#   perf/up.sh --container   the lab and the server in a container with production's limits (2 CPUs, 640 MB): the
#                            jar is built first, the image is server/Dockerfile on the JDK
# Grafana: http://localhost:3000 (the dashboard «Hovanki: сервер»), Prometheus: http://localhost:9090.
set -euo pipefail
cd "$(dirname "$0")/.."

compose=(docker compose -f perf/compose.yaml)
case "${1:-}" in
  "")
    "${compose[@]}" up -d --wait postgres prometheus grafana
    server="perf/server.sh (or the IDE with the Spring profiles e2e,perf)"
    ;;
  --container)
    ./gradlew :server:bootJar
    "${compose[@]}" --profile container up -d --build --wait
    server="in the container hovanki-perf-server-1 on http://localhost:8080 (logs: docker compose -f perf/compose.yaml logs -f server)"
    ;;
  *)
    echo "Usage: perf/up.sh [--container]" >&2
    exit 2
    ;;
esac

echo
echo "Grafana:    http://localhost:3000"
echo "Prometheus: http://localhost:9090"
echo "Database:   localhost:5433, hovanki / hovanki"
echo "Server:     $server"
