#!/bin/bash
# SessionStart hook for Claude Code on the web (.claude/settings.json). The cloud container runs as root, where the
# embedded PostgreSQL of the server and e2e tests can't start (initdb refuses root). So this starts the container's own
# PostgreSQL, if one is installed, makes sure role `hovanki` (password `hovanki`, CREATEDB: the tests create a fresh
# database per run) and database `hovanki` exist, and points the tests at it with HOVANKI_TEST_DATABASE_URL.
# Elsewhere it does nothing: locally and in CI the tests use the embedded PostgreSQL. Idempotent, takes a few seconds,
# and never fails the session: without PostgreSQL it only says so.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

skip() {
  echo "session-start: $1; the server and e2e tests fall back to the embedded PostgreSQL, which can't start as root"
  exit 0
}

if [ -n "${HOVANKI_TEST_DATABASE_URL:-}" ]; then
  echo "session-start: HOVANKI_TEST_DATABASE_URL is already set, leaving it as it is"
  exit 0
fi
command -v pg_lsclusters >/dev/null 2>&1 || skip "no local PostgreSQL (pg_lsclusters not found)"

# "16 main 5432 online postgres /var/lib/postgresql/16/main ...": the cluster on 5432 if there is one, else the first.
clusters=$(pg_lsclusters --no-header 2>/dev/null)
[ -n "$clusters" ] || skip "PostgreSQL is installed but has no cluster"
cluster_line=$(awk '$3 == 5432' <<<"$clusters" | head -n 1)
[ -n "$cluster_line" ] || cluster_line=$(head -n 1 <<<"$clusters")
read -r version cluster port status _ <<<"$cluster_line"

case "$status" in
  online*) ;;
  *) pg_ctlcluster "$version" "$cluster" start || skip "could not start PostgreSQL $version/$cluster" ;;
esac
for _ in $(seq 20); do
  pg_isready --quiet --host=localhost --port="$port" && break
  sleep 0.5
done

# Peer authentication as the postgres OS user; from / because it can't read root's working directory.
as_postgres() {
  if [ "$(id -u)" = 0 ]; then
    (cd / && runuser -u postgres -- "$@")
  else
    (cd / && sudo -n -u postgres -- "$@")
  fi
}
setup_sql="
SELECT 'CREATE ROLE hovanki' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hovanki') \gexec
ALTER ROLE hovanki LOGIN CREATEDB PASSWORD 'hovanki';
SELECT 'CREATE DATABASE hovanki OWNER hovanki'
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'hovanki') \gexec
"
as_postgres psql --quiet --no-psqlrc --port="$port" --dbname=postgres -v ON_ERROR_STOP=1 <<<"$setup_sql" >/dev/null ||
  skip "could not create role and database hovanki in PostgreSQL $version/$cluster"

# The same way the tests connect: password over TCP.
PGPASSWORD=hovanki psql --no-psqlrc --host=localhost --port="$port" --username=hovanki --dbname=hovanki \
  --tuples-only --command='SELECT 1' >/dev/null 2>&1 ||
  skip "PostgreSQL $version/$cluster does not accept hovanki/hovanki on localhost:$port"

url="jdbc:postgresql://localhost:$port/hovanki?user=hovanki&password=hovanki"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  export_line="export HOVANKI_TEST_DATABASE_URL='$url'"
  grep --quiet --fixed-strings --line-regexp "$export_line" "$CLAUDE_ENV_FILE" 2>/dev/null ||
    echo "$export_line" >>"$CLAUDE_ENV_FILE"
  echo "session-start: PostgreSQL $version/$cluster is up on localhost:$port with role and database hovanki;" \
    "HOVANKI_TEST_DATABASE_URL is set for the server and e2e tests"
else
  echo "session-start: PostgreSQL $version/$cluster is up on localhost:$port, but CLAUDE_ENV_FILE is not set:" \
    "export HOVANKI_TEST_DATABASE_URL='$url' before running the server or e2e tests"
fi
exit 0
