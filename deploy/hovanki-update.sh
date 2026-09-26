#!/bin/bash
# Updates the Hovanki server to the newest image of HOVANKI_TAG (.env; `main` follows every push to main). Started by
# hovanki-update.timer every 2 minutes through hovanki-update.service. Only when the pulled image is not the one the
# server container runs: dumps the database first (the new server may run Flyway migrations on startup; no dump, no
# update), then recreates the server and removes the old images. Only the server: Caddy and Postgres are updated by
# hand. See docs/deploy.md.
set -euo pipefail

# compose.yaml and .env are next to this script.
cd "$(dirname "$(readlink -f "$0")")"

docker compose pull --quiet server

# Not `config --images server`: that lists the images of its dependencies (postgres) too. python3 comes with Ubuntu.
image=$(docker compose config --format json |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["server"]["image"])')
pulled=$(docker image inspect --format '{{.Id}}' "$image")
# Compared with the server container rather than with the image before the pull, so the next run retries an update
# that stopped half way (e.g. at the dump). A container stopped by hand counts too and stays stopped.
container=$(docker compose ps --all --quiet server | head -n 1)
current=""
if [ -n "$container" ]; then
  current=$(docker inspect --format '{{.Image}}' "$container")
fi
if [ "$pulled" = "$current" ]; then
  exit 0
fi

echo "New server image $image (${pulled:7:12}), dumping the database first"
bash ./hovanki-backup.sh before-update
docker compose up --detach server
# Old server images, left without a tag after the update.
docker image prune --force
echo "Server updated"
