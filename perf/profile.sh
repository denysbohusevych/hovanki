#!/usr/bin/env bash
# A profile of the lab's server while a simulation runs (docs/perf.md), wherever it runs: on this machine
# (perf/server.sh, the IDE) or in the lab's container (perf/up.sh --container).
#   perf/profile.sh cpu [seconds]     where the CPU time goes                          (async-profiler, flame graph)
#   perf/profile.sh alloc [seconds]   who allocates the memory the GC then collects    (async-profiler, flame graph)
#   perf/profile.sh lock [seconds]    who waits for a lock, and for how long           (async-profiler, flame graph)
#   perf/profile.sh wall [seconds]    every thread, running or waiting: the database   (async-profiler, flame graph)
#   perf/profile.sh jfr [seconds]     a Java Flight Recorder recording for JDK Mission Control: GC, locks, threads
# 60 seconds by default. The result goes to perf/build/profiles/<time>-<kind>.html (.jfr). The agent is loaded into
# the running JVM with jcmd; nothing is restarted. Only ever on the lab's server: a profile or a recording of a real
# server would carry its players' data.
set -euo pipefail
cd "$(dirname "$0")/.."

kind=${1:-cpu}
seconds=${2:-60}
case "$kind" in
  cpu | alloc | lock | wall | jfr) ;;
  *)
    echo "Usage: perf/profile.sh cpu|alloc|lock|wall|jfr [seconds]" >&2
    exit 2
    ;;
esac

out_dir=perf/build/profiles
mkdir -p "$out_dir"
extension=$([ "$kind" = jfr ] && echo jfr || echo html)
name="$(date +%Y%m%d-%H%M%S)-$kind.$extension"
compose=(docker compose -f perf/compose.yaml)

# Where the server runs: the lab's container, else a JVM of this machine running its jar or its main class.
if "${compose[@]}" ps --status running --services 2>/dev/null | grep -qx server; then
  where=container
  pid=1
  jcmd_() { "${compose[@]}" exec -T server jcmd "$@"; }
  file="/tmp/$name"
else
  where=host
  pid=$(jcmd -l 2>/dev/null | awk '/hovanki-server\.jar|HovankiServerApplication/ { print $1; exit }')
  if [ -z "$pid" ]; then
    echo "No lab server running: perf/server.sh, or perf/up.sh --container" >&2
    exit 1
  fi
  jcmd_() { jcmd "$@"; }
  file="$PWD/$out_dir/$name"
fi

# async-profiler's agent with [options]: jcmd reads them as one quoted argument; the agent's own messages go to the
# server's output, its answer is jcmd's return code.
agent_load() {
  local answer
  answer=$(jcmd_ "$pid" JVMTI.agent_load "$agent" "\"$1\"" 2>&1)
  if ! grep -q "return code: 0" <<<"$answer"; then
    echo "$answer" >&2
    echo "async-profiler refused '$1': its reason is in the server's output" >&2
    exit 1
  fi
}

if [ "$kind" = jfr ]; then
  answer=$(jcmd_ "$pid" JFR.start name=perf settings=profile duration="${seconds}s" filename="$file" 2>&1)
  if ! grep -q "Started recording" <<<"$answer"; then
    echo "$answer" >&2
    exit 1
  fi
else
  # The agent for the JVM's platform, from async-profiler's jar on Maven Central (:perf:asyncProfiler).
  if [ "$where" = container ]; then
    platform=$([ "$("${compose[@]}" exec -T server uname -m)" = aarch64 ] && echo linux-arm64 || echo linux-x64)
  else
    case "$(uname -s)-$(uname -m)" in
      Darwin-*) platform=macos ;;
      Linux-aarch64 | Linux-arm64) platform=linux-arm64 ;;
      *) platform=linux-x64 ;;
    esac
  fi
  agent="perf/build/async-profiler/$platform/libasyncProfiler.so"
  [ -f "$agent" ] || ./gradlew -q :perf:asyncProfiler
  if [ "$where" = container ]; then
    "${compose[@]}" cp "$agent" server:/tmp/libasyncProfiler.so >/dev/null 2>&1
    agent=/tmp/libasyncProfiler.so
  else
    agent="$PWD/$agent"
  fi
  # CPU time: the kernel's perf events on Linux; macOS has none, and Docker's seccomp keeps them from a container, so
  # there the interval timer.
  event=$kind
  if [ "$kind" = cpu ] && { [ "$where" = container ] || [ "$platform" = macos ]; }; then event=itimer; fi
  agent_load "start,event=$event,file=$file"
fi

echo "Profiling ($kind) the server ($where) for $seconds s..."
sleep "$seconds"

if [ "$kind" = jfr ]; then
  # The recording is written when its duration is over.
  sleep 2
else
  agent_load "stop,file=$file"
fi
if [ "$where" = container ]; then
  "${compose[@]}" cp "server:$file" "$out_dir/$name" >/dev/null 2>&1
fi
echo "$out_dir/$name"
