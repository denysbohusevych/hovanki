#!/bin/bash
# Installs lego, the ACME client of hovanki-cert.sh, into /usr/local/bin: one pinned version from its GitHub release,
# checked against the checksum written here. Run as root once on a new VM (docs/deploy.md, «Без Caddy»); to update
# lego, change both lines and run it again.
set -euo pipefail

version=5.5.2
sha256=2a35505089e7772c92e1e9ac144df91151ef2eca8568630db0ff91fca06d9bef

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/lego.tar.gz" \
  "https://github.com/go-acme/lego/releases/download/v$version/lego_v${version}_linux_amd64.tar.gz"
echo "$sha256  $work/lego.tar.gz" | sha256sum --check --quiet
tar -xzf "$work/lego.tar.gz" -C "$work" lego
install -m 0755 -o root -g root "$work/lego" /usr/local/bin/lego
lego --version
