#!/bin/bash
# Dumps the Hovanki database (pg_dump custom format, compressed) into backups/ next to this script, i.e.
# /opt/hovanki/backups on the VM:
#   hovanki-backup.sh                 hovanki-<UTC time>.dump; daily from hovanki-backup.timer, the 7 newest kept
#   hovanki-backup.sh before-update   hovanki-<UTC time>-before-update.dump; from hovanki-update.sh before a new server
#                                     image starts and runs its Flyway migrations; the 7 newest kept, none older than
#                                     7 days (counted apart, so a day of pushes to main doesn't push out the daily ones)
# The dump goes to a hidden temporary file, is read back with pg_restore and only then renamed, so a failed run never
# leaves a partial dump among the backups. The dumps hold accounts (emails, password hashes): readable by the owner
# only. Restore: docs/deploy.md.
set -euo pipefail
export LC_ALL=C

keep=7
case "${1:-}" in
  "") suffix="" ;;
  before-update) suffix="-before-update" ;;
  *)
    echo "usage: $0 [before-update]" >&2
    exit 2
    ;;
esac

# compose.yaml and .env are next to this script.
cd "$(dirname "$(readlink -f "$0")")"
backup_dir="$PWD/backups"
umask 077
mkdir -p "$backup_dir"

# One dump at a time: the daily timer and an update may start together.
exec 9>"$backup_dir/.lock"
flock 9
# Left behind by a run that was killed (e.g. a reboot in the middle of a dump); safe to delete under the lock.
rm -f "$backup_dir"/.*.partial

name="hovanki-$(date -u +%Y%m%dT%H%M%SZ)$suffix.dump"
partial="$backup_dir/.$name.partial"
on_exit() {
  local status=$?
  rm -f "$partial"
  if [ "$status" != 0 ]; then
    echo "Backup failed (exit $status), older backups are kept" >&2
  fi
}
trap on_exit EXIT

# Right after a boot the timer may catch up on a missed run before Postgres is up: wait for it up to a minute.
for _ in $(seq 30); do
  if docker compose exec -T postgres pg_isready --host=127.0.0.1 --quiet 2>/dev/null; then
    break
  fi
  sleep 2
done

echo "Dumping the database into $name"
docker compose exec -T postgres pg_dump --username=hovanki --dbname=hovanki --format=custom >"$partial"
# Reads the whole archive back: a dump cut short (e.g. by a full disk) never replaces an older backup.
docker compose exec -T postgres pg_restore --file=/dev/null <"$partial"
mv "$partial" "$backup_dir/$name"
echo "Saved $name ($(du -h "$backup_dir/$name" | cut -f1))"

# The names sort by time, oldest first.
shopt -s nullglob
dumps=("$backup_dir"/hovanki-*Z"$suffix".dump)
if ((${#dumps[@]} > keep)); then
  for old in "${dumps[@]:0:${#dumps[@]}-keep}"; do
    rm -f -- "$old"
    echo "Removed the old backup $(basename "$old")"
  done
fi
# Every run, daily ones too: without pushes to main no new dump before an update would push the old ones out.
find "$backup_dir" -maxdepth 1 -name 'hovanki-*Z-before-update.dump' -mtime +6 -print -delete |
  sed 's#^.*/#Removed the old backup #'
