#!/usr/bin/env bash
# The MacBook as a second phone for the radar (docs/e2e-local.md, «Ноутбук вместо второго телефона»):
#
#   e2e/mac-beacon/run.sh --join <game code> [--name MacBook] [--at <lat,lon>] [--server https://...]
#   e2e/mac-beacon/run.sh --lab --auto [--label mac] [--out <folder>]
#   e2e/mac-beacon/run.sh --lab [--label mac] [--advertise <token> | --ibeacon <token>] [--sniff] [--out <folder>]
#
# --lab: the radio lab (docs/radio-lab.md §6) without a game: every reading into a JSONL log on the server's clock.
# --auto: leave it running; it follows the phone's automatic radio run by itself (docs/radio-lab-tests.md).
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

# --lab and --sniff are switches here; the command line takes a value after every option.
command=beacon
args=()
for arg in "$@"; do
  case "$arg" in
    --lab) command=beacon-lab ;;
    --sniff) args+=(--sniff on) ;;
    --auto) command=beacon-lab; args+=(--auto on) ;;
    *) args+=("$arg") ;;
  esac
done
if [[ " $* " != *" --server "* ]]; then
  server=$(grep -E '^hovanki.serverUrl=' gradle.properties | cut -d= -f2-)
  args+=(--server "$server")
fi
if [[ "$command" == beacon-lab ]]; then
  args+=(--commit "$(git rev-parse --short HEAD 2> /dev/null || echo unknown)")
fi
exec e2e/build/install/e2e/bin/e2e "$command" --helper "$out/beacon" "${args[@]}"
