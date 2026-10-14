#!/usr/bin/env bash
# Fills the lab's database (perf/compose.yaml) like production's after 90 days (docs/perf.md, perf/postgres/seed.sql):
# 100 000 accounts with a known token each, their friends and groups, about a million game results, a sample of
# recordings and saved routes. The schema must be there: start the server once first (perf/server.sh), Flyway
# migrates the database at its start. A second run replaces the first one's data and keeps everything else.
#   perf/seed.sh                                    the defaults (a few minutes)
#   perf/seed.sh -v users=20000 -v results=200000   psql variables, see the top of perf/postgres/seed.sql; the
#                                                   simulations then need -Pperf.seedUsers=20000
set -euo pipefail
cd "$(dirname "$0")/.."

started=$SECONDS
docker compose -f perf/compose.yaml exec -T postgres \
  psql -U hovanki -d hovanki -X -q -v ON_ERROR_STOP=1 "$@" -f - <perf/postgres/seed.sql
echo "Seeded in $((SECONDS - started)) s"
