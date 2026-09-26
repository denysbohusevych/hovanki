#!/usr/bin/env bash
# Device e2e (docs/e2e.md): the server with the Spring profile e2e, N Android emulators and/or M iOS simulators with
# the debug app, headless bots, Maestro for the UI. Report: e2e/build/reports/devices/ (index.md, <scenario>/index.html).
#
#   e2e/run-devices.sh --android 2 --bots 3 --scenario full-round
#   e2e/run-devices.sh --ios 2 --bots 3 --scenario all            # macOS with Xcode
#   e2e/run-devices.sh --android-serials emulator-5554 --skip-build  # emulators you already started
#
# Needs: JDK 21; Android: SDK (local.properties sdk.dir or ANDROID_HOME) with cmdline-tools, KVM on Linux;
# iOS: macOS with Xcode 26.4+; Maestro (curl -fsSL https://get.maestro.mobile.dev | bash).
set -euo pipefail
# Empty arrays are expanded as ${a[@]+"${a[@]}"}: bash 3.2 (macOS) treats "${a[@]}" of an empty array as unset.

ANDROID=0
IOS=0
BOTS=3
SCENARIO=full-round
PORT=${HOVANKI_E2E_PORT:-8080}
KEEP=0
FAIL_FAST=0
SKIP_BUILD=0
REPORT=e2e/build/reports/devices
ANDROID_SERIALS=()
IOS_UDIDS=()
# Automated Test Device image: made for headless CI, without SystemUI, Settings and bundled apps, with the Google APIs
# (Play services for FusedLocationProvider). ATD images exist for API 30-33.
API_LEVEL=${HOVANKI_E2E_API_LEVEL:-33}
IMAGE_TAG=${HOVANKI_E2E_IMAGE_TAG:-google_atd}
EMULATOR_GPU=${HOVANKI_E2E_EMULATOR_GPU:-swiftshader_indirect}

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'USAGE'
Options:
  --android N              start N Android emulators (AVDs hovanki-e2e-N are created if missing)
  --ios M                  start M iOS simulators (macOS)
  --android-serials a,b    use running emulators instead (adb serials)
  --ios-udids a,b          use booted simulators instead
  --bots K                 headless bots in the game (default 3)
  --scenario NAME          full-round | restart | all (default full-round)
  --port P                 server port on this machine (default 8080)
  --skip-build             reuse the server jar, APK/app and e2e CLI from the last build
  --keep                   leave emulators/simulators running
  --fail-fast              skip the remaining scenarios after a failed one
USAGE
}

while (($#)); do
  case "$1" in
    --android) ANDROID=$2; shift 2 ;;
    --ios) IOS=$2; shift 2 ;;
    --android-serials) IFS=, read -ra ANDROID_SERIALS <<<"$2"; shift 2 ;;
    --ios-udids) IFS=, read -ra IOS_UDIDS <<<"$2"; shift 2 ;;
    --bots) BOTS=$2; shift 2 ;;
    --scenario) SCENARIO=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --keep) KEEP=1; shift ;;
    --fail-fast) FAIL_FAST=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Unknown option $1" >&2; usage; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."
mkdir -p "$REPORT/logs"
log() { echo "[run-devices] $*"; }

WANT_ANDROID=$(( ANDROID > 0 || ${#ANDROID_SERIALS[@]} > 0 ))
WANT_IOS=$(( IOS > 0 || ${#IOS_UDIDS[@]} > 0 ))
if (( !WANT_ANDROID && !WANT_IOS )); then
  echo "Pass --android N and/or --ios M" >&2
  exit 2
fi

STARTED_EMULATORS=()
CREATED_SIMULATORS=()
SERVER_PID=
cleanup() {
  [[ -n $SERVER_PID ]] && kill "$SERVER_PID" 2>/dev/null || true
  if ((KEEP)); then return; fi
  for serial in ${STARTED_EMULATORS[@]+"${STARTED_EMULATORS[@]}"}; do adb -s "$serial" emu kill >/dev/null 2>&1 || true; done
  for udid in ${CREATED_SIMULATORS[@]+"${CREATED_SIMULATORS[@]}"}; do
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

# ---- Tools ----
if ((WANT_ANDROID)); then
  # Like AGP and :e2e:devices: sdk.dir from local.properties (Android Studio writes it), then ANDROID_HOME, then
  # where Android Studio installs the SDK by default.
  from_properties=
  if [[ -f local.properties ]]; then
    from_properties=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*[=:][[:space:]]*//p' local.properties | tail -1 \
      | sed 's/\\\(.\)/\1/g')
  fi
  SDK=
  for candidate in "$from_properties" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n $candidate && -d $candidate ]]; then SDK=$candidate; break; fi
  done
  if [[ -z $SDK ]]; then
    echo "Android SDK not found: open the project in Android Studio once (it writes sdk.dir to local.properties)" \
      "or export ANDROID_HOME=<path to the SDK>" >&2
    exit 2
  fi
  log "Android SDK: $SDK"
  # The Gradle build of the APK below must use the same SDK.
  export ANDROID_HOME=$SDK
  export PATH="$SDK/emulator:$SDK/platform-tools:$SDK/cmdline-tools/latest/bin:$PATH"
  # Android Studio does not install them by default; avdmanager and sdkmanager create the AVDs.
  if ((ANDROID > 0)) && ! command -v avdmanager >/dev/null; then
    echo "avdmanager not found: install Android SDK Command-line Tools (Android Studio → Settings → Languages &" \
      "Frameworks → Android SDK → SDK Tools → Android SDK Command-line Tools (latest))" >&2
    exit 2
  fi
fi
command -v maestro >/dev/null || export PATH="$HOME/.maestro/bin:$PATH"
command -v maestro >/dev/null || { echo "Maestro not found: curl -fsSL https://get.maestro.mobile.dev | bash" >&2; exit 2; }
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

# ---- Build ----
IOS_APP=build/ios/Build/Products/Debug-iphonesimulator/Hovanki.app
if ((!SKIP_BUILD)); then
  tasks=(:server:bootJar :e2e:installDist)
  ((WANT_ANDROID)) && tasks+=(:androidApp:assembleDebug)
  log "building ${tasks[*]}"
  ./gradlew "${tasks[@]}"
  if ((WANT_IOS)); then
    log "building the iOS app for the simulator"
    # Signed to run locally (ad hoc, no team), like Xcode does for the simulator: an unsigned app has no
    # entitlements, and the Keychain refuses it (errSecMissingEntitlement), so the saved session would be lost.
    xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator \
      -destination 'generic/platform=iOS Simulator' -derivedDataPath build/ios \
      CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= build \
      >"$REPORT/logs/xcodebuild.log" 2>&1 || { tail -50 "$REPORT/logs/xcodebuild.log"; exit 1; }
  fi
fi
if [[ -n ${CI:-} ]]; then
  # CI runners are small (macOS: 7 GB): the idle Gradle and Kotlin daemons would keep several GB the emulators and
  # simulators need. Not done locally, where they are the developer's.
  ./gradlew --stop >/dev/null
  pkill -f KotlinCompileDaemon || true
fi
export E2E_OPTS=${E2E_OPTS:--Xmx768m}
# The Maestro CLI and MCP server take up to a quarter of the RAM by default.
export MAESTRO_OPTS=${MAESTRO_OPTS:--Xmx1g}

# ---- Server ----
log "starting the server on :$PORT (profile e2e)"
# The access log (request line, status, time; no headers, so no tokens) shows which device requests reached the server.
java -Xmx512m -jar server/build/libs/hovanki-server.jar --spring.profiles.active=e2e --server.port="$PORT" \
  --server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.directory="$PWD/$REPORT/logs" \
  --server.tomcat.accesslog.prefix=access --server.tomcat.accesslog.suffix=.log \
  --server.tomcat.accesslog.pattern='%t %a "%r" %s %{ms}Tms' >"$REPORT/logs/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 90); do
  curl -sf "http://localhost:$PORT/actuator/health" >/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -50 "$REPORT/logs/server.log"; exit 1; }
  sleep 1
done
curl -sf "http://localhost:$PORT/actuator/health" >/dev/null || { echo "Server did not start" >&2; exit 1; }

# ---- Android emulators ----
boot_failed() {
  local serial=$1 index=$2 reason=$3
  echo "$serial: $reason" >&2
  ls -l /dev/kvm >&2 || true
  adb devices -l >&2 || true
  echo "--- tail of $REPORT/logs/emulator-$index.log" >&2
  tail -60 "$REPORT/logs/emulator-$index.log" >&2 || true
  return 1
}

# $3: pid of the emulator process we started, empty for emulators started by someone else.
wait_for_boot() {
  local serial=$1 index=$2 pid=${3:-}
  for second in $(seq 1 600); do
    [[ $(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && return 0
    if [[ -n $pid ]] && ! kill -0 "$pid" 2>/dev/null; then
      boot_failed "$serial" "$index" "the emulator process exited"
      return 1
    fi
    ((second % 60 == 0)) && log "$serial still booting (${second}s): $(adb devices | grep "$serial" || echo 'not listed by adb')"
    sleep 1
  done
  boot_failed "$serial" "$index" "did not boot within 10 minutes"
}

if ((ANDROID > 0)); then
  # avdmanager and the emulator must look for AVDs in the same place (newer cmdline-tools may pick another default).
  export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
  mkdir -p "$ANDROID_AVD_HOME"
  case "$(uname -m)" in arm64 | aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
  IMAGE="system-images;android-$API_LEVEL;$IMAGE_TAG;$ABI"
  if [[ ! -d "$SDK/system-images/android-$API_LEVEL/$IMAGE_TAG/$ABI" || ! -x "$SDK/emulator/emulator" ]]; then
    log "installing $IMAGE"
    # Licenses are confirmed through process substitution: with pipefail, `yes | ...` fails on SIGPIPE.
    sdkmanager --install "$IMAGE" emulator platform-tools < <(yes) >/dev/null
  fi
  for i in $(seq 1 "$ANDROID"); do
    avd=hovanki-e2e-$i
    if ! avdmanager list avd -c 2>/dev/null | grep -qx "$avd"; then
      log "creating AVD $avd"
      echo no | avdmanager create avd -n "$avd" -k "$IMAGE" -d pixel_6 --force
      # Pixel 6 layout (411x914 dp) at 720x1600 instead of 1080x2400: the GPU is emulated in software, and fewer
      # pixels leave the CPU to the apps.
      config="$ANDROID_AVD_HOME/$avd.avd/config.ini"
      sed -i.orig -e '/^hw\.lcd\.width=/d' -e '/^hw\.lcd\.height=/d' -e '/^hw\.lcd\.density=/d' \
        -e '/^skin\.name=/d' -e '/^skin\.path=/d' "$config"
      printf '%s\n' hw.lcd.width=720 hw.lcd.height=1600 hw.lcd.density=280 skin.name=720x1600 skin.path=_no_skin \
        >>"$config"
    fi
    port=$((5552 + 2 * i))
    log "starting $avd as emulator-$port"
    emulator -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$EMULATOR_GPU" \
      -memory "${HOVANKI_E2E_EMULATOR_MEMORY:-2048}" -cores 2 >"$REPORT/logs/emulator-$i.log" 2>&1 &
    eval "EMULATOR_PID_$port=$!"
    STARTED_EMULATORS+=("emulator-$port")
    ANDROID_SERIALS+=("emulator-$port")
  done
fi

index=0
for serial in ${ANDROID_SERIALS[@]+"${ANDROID_SERIALS[@]}"}; do
  index=$((index + 1))
  log "waiting for $serial"
  pid_var="EMULATOR_PID_${serial#emulator-}"
  wait_for_boot "$serial" "$index" "${!pid_var:-}"
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb -s "$serial" shell settings put global "$setting" 0
  done
  # "... isn't responding" dialogs of a busy emulator must not cover the app under test.
  adb -s "$serial" shell settings put global hide_error_dialogs 1
  adb -s "$serial" shell cmd location set-location-enabled true || true
  log "installing the debug app on $serial"
  # -g grants the runtime permissions (location, notifications) up front.
  adb -s "$serial" install -r -g androidApp/build/outputs/apk/debug/androidApp-debug.apk >/dev/null
done

# ---- iOS simulators ----
if ((IOS > 0)); then
  RUNTIME=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("isAvailable") and r["name"].startswith("iOS")]
print(runtimes[-1]["identifier"])')
  DEVICE_TYPE=$(xcrun simctl list devicetypes -j | python3 -c '
import json, sys
types = [t for t in json.load(sys.stdin)["devicetypes"] if t.get("productFamily") == "iPhone"]
print(max(types, key=lambda t: t.get("minRuntimeVersion", 0))["identifier"])')
  for i in $(seq 1 "$IOS"); do
    udid=$(xcrun simctl create "hovanki-e2e-$i" "$DEVICE_TYPE" "$RUNTIME")
    CREATED_SIMULATORS+=("$udid")
    IOS_UDIDS+=("$udid")
  done
fi

# One simulator at a time: booting several at once on a small machine makes all of them crawl.
for udid in ${IOS_UDIDS[@]+"${IOS_UDIDS[@]}"}; do
  log "booting simulator $udid"
  xcrun simctl boot "$udid" 2>/dev/null || true # already booted when passed with --ios-udids
  xcrun simctl bootstatus "$udid" -b >/dev/null
  log "installing the debug app on $udid"
  xcrun simctl install "$udid" "$IOS_APP"
  # The first launch on a fresh simulator is by far the slowest: take it here, not inside a scenario.
  xcrun simctl launch "$udid" app.hovanki.ios >/dev/null
  sleep 5
  xcrun simctl terminate "$udid" app.hovanki.ios || true
  log "$udid ready"
done

# ---- Scenarios ----
args=(devices --port "$PORT" --bots "$BOTS" --scenario "$SCENARIO" --report "$REPORT" --flows e2e/maestro)
((FAIL_FAST)) && args+=(--fail-fast true)
((${#ANDROID_SERIALS[@]})) && args+=(--android "$(IFS=,; echo "${ANDROID_SERIALS[*]}")")
((${#IOS_UDIDS[@]})) && args+=(--ios "$(IFS=,; echo "${IOS_UDIDS[*]}")")
log "running: e2e ${args[*]}"
set +e
e2e/build/install/e2e/bin/e2e "${args[@]}"
status=$?
set -e
log "report: $REPORT/index.md"
# The timelines also go to the console: CI logs show what happened without downloading the artifact.
for report in "$REPORT"/*/report.md; do
  [[ -f $report ]] && { echo "===== $report"; cat "$report"; }
done
if ((status != 0)); then
  # Who answers on the server port: the devices must reach this server and nothing else.
  echo "===== listeners on :$PORT"
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || netstat -an 2>/dev/null | grep "[.:]$PORT " || true
  for host in 127.0.0.1 '[::1]'; do
    echo "--- http://$host:$PORT/actuator/health: $(curl -sS -m 5 "http://$host:$PORT/actuator/health" 2>&1 | head -c 200)"
  done
  echo "===== requests other than the observer's (access log)"
  grep -hv "/api/v1/debug/\|/actuator/" "$REPORT"/logs/access*.log 2>/dev/null | tail -40 || true
  echo "===== memory"
  if [[ $(uname) == Darwin ]]; then sysctl vm.swapusage; memory_pressure 2>/dev/null | tail -3; else free -m; fi || true
  echo "===== tail of $REPORT/logs/server.log"
  tail -40 "$REPORT/logs/server.log" 2>/dev/null || true
  echo "===== last commands ($REPORT/commands.log)"
  tail -40 "$REPORT/commands.log" 2>/dev/null || true
  # Crashes of the app or the device's system (system_server, low memory) in the device logs.
  for device_log in "$REPORT"/*/logs/*.log; do
    [[ -f $device_log ]] || continue
    if [[ $(basename "$device_log") == iOS-* ]]; then
      # The app's process log without the XCTest/accessibility chatter of the UI driver, then its console.
      echo "===== $device_log: location and app messages"
      grep -E "^[0-9]{4}-" "$device_log" \
        | grep -vE "com\.apple\.dt\.|com\.apple\.accessibility|AXRuntime|UIAccessibility|KeyboardArbiter" \
        | grep -E "CFNetwork.*(sent request|received response|finished)|[Ll]ocation|[Aa]uthoriz|[Ee]rror|[Ff]ault" \
        | tail -80 || true
      sed -n '/===== app console/,$p' "$device_log" | tail -40
      continue
    fi
    echo "===== crash signatures in $device_log"
    grep -E "FATAL EXCEPTION|ANR in|beginning of crash|Fatal signal|WATCHDOG KILLING|lowmemorykiller|Out of memory|AndroidRuntime" "$device_log" \
      | tail -40 || true
  done
fi
exit "$status"
