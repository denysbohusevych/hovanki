#!/bin/sh
# Build phase "Debug: local network" of iosApp (Xcode runs it after Info.plist is processed).
# Debug builds talk to a development server on the local network over plain HTTP (http://<mac>.local:8080,
# http://localhost:8080 from the simulator). Release builds (TestFlight, App Store) keep App Transport Security strict:
# the exceptions below are added to the processed Info.plist of Debug builds only.
# The radio lab's background modes (docs/radar-run.md §5.1, §5.3) are Debug only too: `audio` (a silent audio session,
# `mode.audio`: App Review doesn't like it in a game), `nearby-interaction` (`uwb.ni` with the Live Activity) and
# `NSSupportsLiveActivities` (`mode.live_activity`). Release keeps the background modes of iosApp/Info.plist.
set -eu
[ "$CONFIGURATION" = "Debug" ] || exit 0

plist="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
buddy=/usr/libexec/PlistBuddy
# Incremental builds may keep the processed Info.plist of the previous build: start from a clean state.
"$buddy" -c "Delete :NSAppTransportSecurity" "$plist" 2>/dev/null || true
"$buddy" -c "Delete :NSLocalNetworkUsageDescription" "$plist" 2>/dev/null || true
"$buddy" -c "Delete :NSSupportsLiveActivities" "$plist" 2>/dev/null || true
"$buddy" \
  -c "Add :NSAppTransportSecurity dict" \
  -c "Add :NSAppTransportSecurity:NSAllowsLocalNetworking bool true" \
  -c "Add :NSLocalNetworkUsageDescription string 'Debug build: Hovanki connects to a game server on your local network.'" \
  -c "Add :NSSupportsLiveActivities bool true" \
  "$plist"

# The lab's modes join the array Info.plist has (bluetooth-central, bluetooth-peripheral, location): each is added
# only when it isn't there yet, so a kept Info.plist of the previous build doesn't get it twice.
modes=$("$buddy" -c "Print :UIBackgroundModes" "$plist" 2>/dev/null || true)
[ -n "$modes" ] || "$buddy" -c "Add :UIBackgroundModes array" "$plist"
for mode in audio nearby-interaction; do
  if ! printf '%s\n' "$modes" | grep -qx "[[:space:]]*$mode"; then
    "$buddy" -c "Add :UIBackgroundModes: string $mode" "$plist"
  fi
done
