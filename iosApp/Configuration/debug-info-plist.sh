#!/bin/sh
# Build phase "Debug: local network" of iosApp (Xcode runs it after Info.plist is processed).
# Debug builds talk to a development server on the local network over plain HTTP (http://<mac>.local:8080,
# http://localhost:8080 from the simulator). Release builds (TestFlight, App Store) keep App Transport Security strict:
# the exceptions below are added to the processed Info.plist of Debug builds only.
set -eu
[ "$CONFIGURATION" = "Debug" ] || exit 0

plist="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
buddy=/usr/libexec/PlistBuddy
# Incremental builds may keep the processed Info.plist of the previous build: start from a clean state.
"$buddy" -c "Delete :NSAppTransportSecurity" "$plist" 2>/dev/null || true
"$buddy" -c "Delete :NSLocalNetworkUsageDescription" "$plist" 2>/dev/null || true
"$buddy" \
  -c "Add :NSAppTransportSecurity dict" \
  -c "Add :NSAppTransportSecurity:NSAllowsLocalNetworking bool true" \
  -c "Add :NSLocalNetworkUsageDescription string 'Debug build: Hovanki connects to a game server on your local network.'" \
  "$plist"
