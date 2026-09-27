#!/bin/bash
# Updates the Hovanki server to the newest image of HOVANKI_TAG (.env; `main` follows every push to main). Started by
# hovanki-update.timer every 2 minutes through hovanki-update.service. Only when the pulled image is not the one the
# server container runs: recreates the server and removes the old images. A new server may run Flyway migrations on
# startup; the "New server image" line below dates the update, and RDS point-in-time recovery can bring the database
# back to any moment before it (docs/deploy.md). Only the server: Caddy is updated by hand.
set -euo pipefail

# compose.yaml and .env are next to this script.
cd "$(dirname "$(readlink -f "$0")")"

docker compose pull --quiet server

# The server's image as compose.yaml names it after .env (python3 comes with Ubuntu).
image=$(docker compose config --format json |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["server"]["image"])')
pulled=$(docker image inspect --format '{{.Id}}' "$image")
# Compared with the server container rather than with the image before the pull, so the next run retries an update
# that stopped half way. A container stopped by hand counts too and stays stopped.
container=$(docker compose ps --all --quiet server | head -n 1)
current=""
if [ -n "$container" ]; then
  current=$(docker inspect --format '{{.Image}}' "$container")
fi
if [ "$pulled" = "$current" ]; then
  exit 0
fi

echo "New server image $image (${pulled:7:12}) at $(date -u +%Y-%m-%dT%H:%M:%SZ), restarting the server"
docker compose up --detach server
# Old server images, left without a tag after the update.
docker image prune --force
echo "Server updated"
