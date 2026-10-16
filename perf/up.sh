#!/usr/bin/env bash
# The server's performance lab (docs/perf.md): its database, Prometheus and Grafana in Docker.
#   perf/up.sh               the lab; the server then runs on this machine: perf/server.sh, or the IDE with the
#                            Spring profiles `e2e,perf`
#   perf/up.sh --container   the lab and the server in a container with production's limits (2 CPUs, 640 MB): the
#                            jar is built first, the image is server/Dockerfile on the JDK; Caddy in front of it
#   perf/up.sh --container --tls
#                            the same server with its own TLS on 8443–8446 instead of Caddy (perf/compose.tls.yaml)
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
    if [[ "${2:-}" == "--tls" ]]; then
      docker rm -f hovanki-perf-caddy-1 >/dev/null 2>&1 || true
      "${compose[@]}" -f perf/compose.tls.yaml --profile container up -d --build --wait postgres prometheus grafana server
      server="in the container hovanki-perf-server-1 on https://localhost:8443–8446, its metrics on http://localhost:8080"
    else
      "${compose[@]}" --profile container up -d --build --wait
      server="in the container hovanki-perf-server-1 on http://localhost:8080 (logs: docker compose -f perf/compose.yaml logs -f server)"
    fi
    ;;
  *)
    echo "Usage: perf/up.sh [--container [--tls]]" >&2
    exit 2
    ;;
esac

echo
echo "Grafana:    http://localhost:3000"
echo "Prometheus: http://localhost:9090"
echo "Database:   localhost:5433, hovanki / hovanki"
echo "Server:     $server"
