#!/usr/bin/env bash
# The MacBook as a second phone for the radar (docs/e2e-local.md, «Ноутбук вместо второго телефона»):
#
#   e2e/mac-beacon/run.sh --join <game code> [--name MacBook] [--at <lat,lon>] [--server https://...]
#
# Builds the Bluetooth helper (beacon.swift, with Info.plist linked in so macOS asks for Bluetooth) and the e2e
# command line, then joins the game as one more player. Run it in Terminal itself, not through Gradle: macOS gives
# Bluetooth to the app that started the process (Terminal asks once; System Settings → Privacy & Security →
# Bluetooth). The server is the app's (hovanki.serverUrl in gradle.properties) unless --server says otherwise.
set -euo pipefail

cd "$(dirname "$0")/../.."
[[ "$(uname)" == "Darwin" ]] || { echo "macOS only: the helper uses the Mac's CoreBluetooth" >&2; exit 2; }
command -v swiftc > /dev/null || { echo "No swiftc: install Xcode or run xcode-select --install" >&2; exit 2; }

out=e2e/build/mac-beacon
mkdir -p "$out"
if [[ ! -x "$out/beacon" || e2e/mac-beacon/beacon.swift -nt "$out/beacon" || e2e/mac-beacon/Info.plist -nt "$out/beacon" ]]; then
  echo "Building the Bluetooth helper…"
  swiftc -O e2e/mac-beacon/beacon.swift -o "$out/beacon" \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker e2e/mac-beacon/Info.plist
fi

echo "Building the e2e command line…"
./gradlew -q :e2e:installDist

args=("$@")
if [[ " $* " != *" --server "* ]]; then
  server=$(grep -E '^hovanki.serverUrl=' gradle.properties | cut -d= -f2-)
  args+=(--server "$server")
fi
exec e2e/build/install/e2e/bin/e2e beacon --helper "$out/beacon" "${args[@]}"
