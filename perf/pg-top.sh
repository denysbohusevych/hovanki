#!/usr/bin/env bash
# The statements the lab's database spent the most time on (docs/perf.md), from pg_stat_statements.
#   perf/pg-top.sh            the top 15 by total time since the last reset
#   perf/pg-top.sh 30         the top 30
#   perf/pg-top.sh --reset    forgets the numbers: before a run, so the top is that run's
set -euo pipefail
cd "$(dirname "$0")/.."

psql_=(docker compose -f perf/compose.yaml exec -T postgres psql -U hovanki -d hovanki -P pager=off)
if [ "${1:-}" = "--reset" ]; then
  "${psql_[@]}" -qc "SELECT pg_stat_statements_reset()" >/dev/null
  echo "pg_stat_statements: reset"
  exit 0
fi

"${psql_[@]}" -c "
  SELECT calls,
         round(total_exec_time)::bigint AS total_ms,
         round(mean_exec_time::numeric, 2) AS mean_ms,
         round(max_exec_time::numeric, 2) AS max_ms,
         rows,
         shared_blks_read AS disk_blocks,
         left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
    FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
   ORDER BY total_exec_time DESC
   LIMIT ${1:-15}"
