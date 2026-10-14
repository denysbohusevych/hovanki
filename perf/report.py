#!/usr/bin/env python3
"""The lab's run report (docs/perf.md, «Отчёт прогона»).

Runs a Gatling simulation and gathers, by itself, what Grafana and Gatling show about it: one `run.json` per run
(machine-readable, the same fields every time) and a short `summary.md` (the digest), so a run can be read and two runs
compared without screenshots.

    perf/run.sh OrdinaryGames -Pperf.transport=socket -Pperf.steps=6 --label "baseline" [--baseline <run>]
    perf/report.sh list | show [run] | compare <run> <run>... | collect <run> | import [gatling report dir]

Where the numbers come from:
- the server's metrics in the lab's Prometheus (the same queries as the Grafana dashboard), per ladder step and per
  timeline window;
- every container of the lab (server, postgres, caddy, ... whatever runs) through the Docker API every 5 s: CPU in vCPU,
  memory (cgroup working set, anonymous memory = the process's RSS, peak), network bytes, throttling, OOMKilled and
  exit codes; the Gatling JVM and a server on this machine by their process;
- Gatling's console (the ladder's levels and its stop line, the errors) and its report (every request's percentiles,
  the client's latency per second);
- PostgreSQL's pg_stat_statements (reset at the start) and pg_stat_database / pg_stat_user_tables deltas.

A run lives in perf/runs/<id>/ (git-ignored): run.json, summary.md, gatling.log (the console, every line with its
time), samples.jsonl (the 5 s samples). perf/runs/index.jsonl has one line per run. PERF_RUNS_DIR moves the runs,
PERF_RUNS_EXPORT also copies run.json and summary.md of every run to a second place (a shared folder).

Python 3.9+, the standard library only: macOS has it with Xcode's command line tools.
"""

from __future__ import annotations

import argparse
import datetime
import http.client
import json
import math
import os
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS = Path(os.environ.get("PERF_RUNS_DIR") or ROOT / "perf" / "runs")
EXPORT = Path(os.environ["PERF_RUNS_EXPORT"]) if os.environ.get("PERF_RUNS_EXPORT") else None
PROMETHEUS = os.environ.get("PERF_PROMETHEUS", "http://localhost:9090").rstrip("/")
GRAFANA = os.environ.get("PERF_GRAFANA", "http://localhost:3000").rstrip("/")
COMPOSE_PROJECT = "hovanki-perf"
SCHEMA = "hovanki.perf.run/1"
SAMPLE_SECONDS = 5
# Rates settle this long after a level's players came in before a step's numbers count.
SETTLE_SECONDS = 15
SIMULATION_PACKAGE = "app.hovanki.perf."

# The simulations' defaults (their documentation), for a run that doesn't name them.
DEFAULTS = {
    "OrdinaryGames": {
        "games": 10,
        "players": 30,
        "rampSeconds": 60,
        "minutes": 5,
        "steps": 1,
        "targetMs": 50,
        "transport": "poll",
    },
    "BigGame": {"players": 300, "rampSeconds": 60, "minutes": 5, "transport": "poll"},
}

# Local services only: never through the session's HTTP proxy.
_LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------------------------------------------------------------
# Small helpers


def now() -> float:
    return time.time()


def iso(ts: float | None) -> str | None:
    if ts is None:
        return None
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(cmd: list[str], timeout: float = 60, cwd: Path | None = None) -> tuple[int, str]:
    try:
        done = subprocess.run(
            cmd, cwd=cwd or ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout
        )
        return done.returncode, done.stdout
    except (OSError, subprocess.TimeoutExpired) as e:
        return -1, str(e)


def rnd(value: float | None, digits: int = 1) -> float | int | None:
    """A number for the report: None stays None, NaN and infinities become None, whole numbers lose the point."""
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    r = round(value, digits)
    return int(r) if r == int(r) and abs(r) < 1e15 else r


def pct_change(before: float | None, after: float | None) -> float | None:
    if before is None or after is None or before == 0:
        return None
    return (after - before) / abs(before) * 100


def warn(message: str) -> None:
    print(f"[report] {message}", file=sys.stderr)


# ---------------------------------------------------------------------------------------------------------------------
# Docker: the lab's containers through the Engine API (the CLI can't print cumulative CPU or network counters)


class _UnixConnection(http.client.HTTPConnection):
    def __init__(self, path: str, timeout: float):
        super().__init__("localhost", timeout=timeout)
        self.unix_path = path

    def connect(self) -> None:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(self.timeout)
        sock.connect(self.unix_path)
        self.sock = sock


class Docker:
    def __init__(self) -> None:
        self.socket_path: str | None = None
        host = os.environ.get("DOCKER_HOST")
        if not host:
            rc, out = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], timeout=10)
            host = out.strip() if rc == 0 else "unix:///var/run/docker.sock"
        if host.startswith("unix://"):
            self.socket_path = host[len("unix://") :]

    @property
    def available(self) -> bool:
        return self.socket_path is not None and os.path.exists(self.socket_path)

    def get(self, path: str, timeout: float = 10) -> object | None:
        if not self.available:
            return None
        try:
            conn = _UnixConnection(self.socket_path, timeout)
            conn.request("GET", path)
            response = conn.getresponse()
            body = response.read()
            conn.close()
            if response.status != 200:
                return None
            return json.loads(body)
        except (OSError, ValueError, http.client.HTTPException):
            return None

    def lab_containers(self) -> list[dict]:
        """The compose project's containers, running or not: name, service, id, state."""
        filters = urllib.parse.quote(json.dumps({"label": [f"com.docker.compose.project={COMPOSE_PROJECT}"]}))
        found = self.get(f"/containers/json?all=true&filters={filters}") or []
        return [
            {
                "id": c["Id"],
                "name": (c.get("Names") or ["?"])[0].lstrip("/"),
                "service": c.get("Labels", {}).get("com.docker.compose.service", "?"),
                "state": c.get("State"),
                "image": c.get("Image"),
            }
            for c in found
        ]

    def stats(self, container_id: str) -> dict | None:
        return self.get(f"/containers/{container_id}/stats?stream=false&one-shot=true")

    def inspect(self, container_id: str) -> dict | None:
        return self.get(f"/containers/{container_id}/json")

    def info(self) -> dict | None:
        return self.get("/info")


def container_sample(stats: dict) -> dict:
    """One container's counters: CPU (ns, cumulative), throttling, memory (bytes), network (bytes, cumulative)."""
    cpu = stats.get("cpu_stats") or {}
    memory = stats.get("memory_stats") or {}
    mstats = memory.get("stats") or {}
    usage = memory.get("usage")
    inactive = mstats.get("inactive_file", mstats.get("total_inactive_file", 0)) or 0
    # The process's own memory: anonymous pages (cgroup v2 `anon`, v1 `rss`); the working set is docker stats' number.
    anon = mstats.get("anon", mstats.get("total_rss", mstats.get("rss")))
    networks = stats.get("networks") or {}
    throttling = cpu.get("throttling_data") or {}
    return {
        "cpu_ns": (cpu.get("cpu_usage") or {}).get("total_usage"),
        "periods": throttling.get("periods"),
        "throttled": throttling.get("throttled_periods"),
        "mem": (usage - inactive) if usage is not None else None,
        "anon": anon,
        "peak": memory.get("max_usage"),
        "limit": memory.get("limit"),
        "rx": sum(n.get("rx_bytes", 0) for n in networks.values()) if networks else None,
        "tx": sum(n.get("tx_bytes", 0) for n in networks.values()) if networks else None,
        "pids": (stats.get("pids_stats") or {}).get("current"),
    }


# ---------------------------------------------------------------------------------------------------------------------
# Processes on this machine: the Gatling JVM and a server started by perf/server.sh or the IDE


def find_java(pattern: str) -> int | None:
    """A JVM whose command line matches [pattern]: not the shells and wrappers that carry the same words."""
    rc, out = run(["pgrep", "-f", pattern], timeout=5)
    if rc != 0:
        return None
    for pid in (int(p) for p in out.split() if p.isdigit() and int(p) != os.getpid()):
        rc, comm = run(["ps", "-o", "comm=", "-p", str(pid)], timeout=5)
        if rc == 0 and comm.strip().rsplit("/", 1)[-1] == "java":
            return pid
    return None


_CLOCK_TICKS = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") and "SC_CLK_TCK" in os.sysconf_names else 100


def process_sample(pid: int) -> dict | None:
    """CPU time (ns, cumulative) and RSS (bytes) of a process: /proc on Linux, ps elsewhere."""
    stat = Path(f"/proc/{pid}/stat")
    if stat.exists():
        try:
            fields = stat.read_text().rsplit(")", 1)[1].split()
            cpu_ns = (int(fields[11]) + int(fields[12])) * 1_000_000_000 // _CLOCK_TICKS
            rss = int(fields[21]) * os.sysconf("SC_PAGE_SIZE")
            return {"cpu_ns": cpu_ns, "anon": rss}
        except (OSError, IndexError, ValueError):
            return None
    rc, out = run(["ps", "-o", "time=,rss=", "-p", str(pid)], timeout=5)
    if rc != 0 or not out.strip():
        return None
    cputime, rss_kb = out.split()[:2]
    seconds = 0.0
    days, _, clock = cputime.rpartition("-")
    for part in clock.split(":"):
        seconds = seconds * 60 + float(part)
    seconds += int(days or 0) * 86400
    return {"cpu_ns": int(seconds * 1e9), "anon": int(rss_kb) * 1024}


class MachineCpu:
    """The whole machine's busy share since the last call: /proc/stat on Linux, the sum of ps elsewhere."""

    def __init__(self) -> None:
        self.last: tuple[int, int] | None = None

    def sample(self) -> float | None:
        stat = Path("/proc/stat")
        if stat.exists():
            try:
                values = [int(v) for v in stat.read_text().splitlines()[0].split()[1:]]
            except (OSError, ValueError):
                return None
            idle = values[3] + (values[4] if len(values) > 4 else 0)
            total = sum(values[:8])
            previous, self.last = self.last, (total, idle)
            if previous is None or total == previous[0]:
                return None
            return 1 - (idle - previous[1]) / (total - previous[0])
        rc, out = run(["ps", "-A", "-o", "%cpu="], timeout=5)
        if rc != 0:
            return None
        busy = sum(float(v) for v in out.split() if re.match(r"^[\d.]+$", v))
        return min(1.0, busy / 100 / (os.cpu_count() or 1))


# ---------------------------------------------------------------------------------------------------------------------
# The sampler: every 5 s while the run goes


class Sampler(threading.Thread):
    def __init__(self, docker: Docker, out: Path):
        super().__init__(daemon=True)
        self.docker = docker
        self.out = out
        self.stopping = threading.Event()
        self.machine = MachineCpu()

    def run(self) -> None:
        with self.out.open("a") as f:
            while not self.stopping.is_set():
                started = now()
                try:
                    for row in self.sample():
                        f.write(json.dumps(row) + "\n")
                    f.flush()
                except Exception as e:  # noqa: BLE001 - a lost sample must not end the run
                    warn(f"sampler: {e}")
                self.stopping.wait(max(0.5, SAMPLE_SECONDS - (now() - started)))

    def sample(self) -> list[dict]:
        t = round(now(), 3)
        rows = []
        for c in self.docker.lab_containers():
            if c["state"] != "running":
                rows.append({"t": t, "who": c["service"], "name": c["name"], "state": c["state"]})
                continue
            stats = self.docker.stats(c["id"])
            if stats:
                rows.append({"t": t, "who": c["service"], "name": c["name"], **container_sample(stats)})
        gatling = find_java("io.gatling.app.Gatling")
        if gatling:
            sample = process_sample(gatling)
            if sample:
                rows.append({"t": t, "who": "gatling", "pid": gatling, **sample})
        # perf/server.sh's jar, or the IDE's run of the application class.
        host_server = find_java("hovanki-server.jar|HovankiServerApplication")
        if host_server:
            sample = process_sample(host_server)
            if sample:
                rows.append({"t": t, "who": "server-host", "pid": host_server, **sample})
        busy = self.machine.sample()
        if busy is not None:
            rows.append({"t": t, "who": "machine", "cpu_busy": round(busy, 4)})
        return rows

    def stop(self) -> None:
        self.stopping.set()
        self.join(timeout=15)


def load_samples(path: Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text().splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            pass
    return rows


def samples_in(rows: list[dict], start: float, end: float) -> dict:
    """Every container's and process's numbers over [start, end]: CPU in vCPU, memory peaks in MB, network in KB/s."""
    by_who: dict[str, list[dict]] = {}
    for r in rows:
        if start - SAMPLE_SECONDS / 2 <= r["t"] <= end + SAMPLE_SECONDS / 2:
            by_who.setdefault(r["who"], []).append(r)
    result = {}
    for who, rs in sorted(by_who.items()):
        if who == "machine":
            busy = [r["cpu_busy"] for r in rs if r.get("cpu_busy") is not None]
            if busy:
                result["machine"] = {"cpu_pct": rnd(sum(busy) / len(busy) * 100), "cpu_pct_max": rnd(max(busy) * 100)}
            continue
        live = [r for r in rs if r.get("cpu_ns") is not None]
        entry: dict = {}
        if len(live) >= 2:
            first, last = live[0], live[-1]
            span = last["t"] - first["t"]
            if span > 0:
                entry["cpu_vcpu"] = rnd((last["cpu_ns"] - first["cpu_ns"]) / span / 1e9, 2)
                if last.get("rx") is not None and first.get("rx") is not None:
                    entry["net_in_kb_s"] = rnd((last["rx"] - first["rx"]) / span / 1024)
                    entry["net_out_kb_s"] = rnd((last["tx"] - first["tx"]) / span / 1024)
                if last.get("periods") and first.get("periods") is not None and last["periods"] > first["periods"]:
                    entry["throttled_pct"] = rnd(
                        (last["throttled"] - first["throttled"]) / (last["periods"] - first["periods"]) * 100
                    )
        mems = [r["mem"] for r in live if r.get("mem") is not None]
        anons = [r["anon"] for r in live if r.get("anon") is not None]
        peaks = [r["peak"] for r in live if r.get("peak")]
        if mems:
            entry["mem_mb_max"] = rnd(max(mems) / 1048576)
        if anons:
            entry["rss_mb_max"] = rnd(max(anons) / 1048576)
        if peaks:
            entry["cgroup_peak_mb"] = rnd(max(peaks) / 1048576)
        limits = [r["limit"] for r in live if r.get("limit")]
        if limits and limits[-1] < 1 << 50 and limits[-1] < (os_ram_bytes() or 1 << 62):
            entry["mem_limit_mb"] = rnd(limits[-1] / 1048576)
        states = [r["state"] for r in rs if r.get("state")]
        if states:
            entry["state"] = states[-1]
        if entry:
            result[who] = entry
    return result


def os_ram_bytes() -> int | None:
    meminfo = Path("/proc/meminfo")
    if meminfo.exists():
        m = re.search(r"MemTotal:\s+(\d+) kB", meminfo.read_text())
        return int(m.group(1)) * 1024 if m else None
    rc, out = run(["sysctl", "-n", "hw.memsize"], timeout=5)
    return int(out.strip()) if rc == 0 and out.strip().isdigit() else None


# ---------------------------------------------------------------------------------------------------------------------
# Prometheus: the server's metrics, the same queries as perf/grafana/dashboards/hovanki.json


def prom(path: str, params: dict) -> list[dict] | None:
    url = f"{PROMETHEUS}/api/v1/{path}?{urllib.parse.urlencode(params)}"
    try:
        with _LOCAL.open(url, timeout=30) as response:
            data = json.load(response)
    except (OSError, ValueError) as e:
        warn(f"prometheus: {e}")
        return None
    if data.get("status") != "success":
        return None
    return data["data"]["result"]


def prom_value(result: list[dict] | None) -> float | None:
    """One number from an instant query: the sum of its series (most expressions are aggregated to one)."""
    if not result:
        return None
    values = [float(r["value"][1]) for r in result]
    values = [v for v in values if not math.isnan(v) and not math.isinf(v)]
    return sum(values) if values else None


def server_job(at: float) -> str | None:
    """The lab's Prometheus job that scrapes the server at [at] (hovanki-container or hovanki-host); else the one that
    did last in the 10 minutes before (a server that died)."""
    result = prom("query", {"query": 'up{job=~"hovanki-.*"} == 1', "time": at}) or []
    if not result:
        last_up = 'max_over_time(timestamp(up{job=~"hovanki-.*"} == 1)[10m:5s])'
        result = prom("query", {"query": last_up, "time": at}) or []
        result.sort(key=lambda r: float(r["value"][1]), reverse=True)
    return result[0]["metric"].get("job") if result else None


# One number per window: JOB is the job's selector, WIN the window. Units are in the key.
WINDOW_METRICS = [
    # The players of every game in the server's memory: games of earlier runs too, until they end and expire. The run's
    # own players are Gatling's (`players` of a step, `client.users_max`).
    ("players_in_memory", "max_over_time(hovanki_players{JOB}[WIN])"),
    ("sockets", "max_over_time(hovanki_sockets{JOB}[WIN])"),
    ("games_in_memory", "max_over_time(hovanki_games{JOB}[WIN])"),
    ("syncs_per_s", "sum(rate(hovanki_game_sync_seconds_count{JOB}[WIN]))"),
    ("syncs_per_s_poll", 'sum(rate(hovanki_game_sync_seconds_count{JOB,transport="poll"}[WIN]))'),
    ("syncs_per_s_socket", 'sum(rate(hovanki_game_sync_seconds_count{JOB,transport="socket"}[WIN]))'),
    ("sync_p50_ms", "histogram_quantile(0.5, sum by (le) (rate(hovanki_game_sync_seconds_bucket{JOB}[WIN]))) * 1000"),
    ("sync_p95_ms", "histogram_quantile(0.95, sum by (le) (rate(hovanki_game_sync_seconds_bucket{JOB}[WIN]))) * 1000"),
    ("sync_p99_ms", "histogram_quantile(0.99, sum by (le) (rate(hovanki_game_sync_seconds_bucket{JOB}[WIN]))) * 1000"),
    ("sync_max_ms", "max(max_over_time(hovanki_game_sync_seconds_max{JOB}[WIN])) * 1000"),
    ("http_per_s", "sum(rate(http_server_requests_seconds_count{JOB}[WIN]))"),
    ("http_5xx_per_s", 'sum(rate(http_server_requests_seconds_count{JOB,status=~"5.."}[WIN]))'),
    ("http_429_per_s", 'sum(rate(http_server_requests_seconds_count{JOB,status="429"}[WIN]))'),
    ("cpu_vcpu", "sum(rate(process_cpu_time_ns_total{JOB}[WIN])) / 1e9"),
    ("cpu_vcpu_gauge", "avg_over_time(process_cpu_usage{JOB}[WIN]) * max(system_cpu_count{JOB})"),
    ("heap_used_mb", 'max_over_time(sum(jvm_memory_used_bytes{JOB,area="heap"})[WIN:5s]) / 1048576'),
    ("heap_after_gc_mb", "max(max_over_time(jvm_gc_live_data_size_bytes{JOB}[WIN])) / 1048576"),
    ("nonheap_mb", 'max_over_time(sum(jvm_memory_used_bytes{JOB,area="nonheap"})[WIN:5s]) / 1048576'),
    ("direct_buffers_mb", "max_over_time(sum(jvm_buffer_memory_used_bytes{JOB})[WIN:5s]) / 1048576"),
    ("alloc_mb_per_s", "sum(rate(jvm_gc_memory_allocated_bytes_total{JOB}[WIN])) / 1e6"),
    ("promoted_mb_per_s", "sum(rate(jvm_gc_memory_promoted_bytes_total{JOB}[WIN])) / 1e6"),
    ("gc_pause_pct", "sum(rate(jvm_gc_pause_seconds_sum{JOB}[WIN])) * 100"),
    ("gc_pause_ms_total", "sum(increase(jvm_gc_pause_seconds_sum{JOB}[WIN])) * 1000"),
    # The longest pause: the mean pause of each scrape interval with a GC (one GC in 5 s is the usual case). The
    # pause's own max gauge is Micrometer's 2-minute decaying max: it would carry a pause into the next steps.
    (
        "gc_max_pause_ms",
        "max_over_time((sum(increase(jvm_gc_pause_seconds_sum{JOB}[10s])) / "
        "sum(increase(jvm_gc_pause_seconds_count{JOB}[10s])))[WIN:5s]) * 1000",
    ),
    (
        "gc_major_max_pause_ms",
        'max_over_time((sum(increase(jvm_gc_pause_seconds_sum{JOB,action=~".*major.*"}[10s])) '
        '/ sum(increase(jvm_gc_pause_seconds_count{JOB,action=~".*major.*"}[10s])))[WIN:5s]) '
        "* 1000",
    ),
    ("gc_minor", 'sum(increase(jvm_gc_pause_seconds_count{JOB,action=~".*minor.*"}[WIN]))'),
    ("gc_major", 'sum(increase(jvm_gc_pause_seconds_count{JOB,action=~".*major.*"}[WIN]))'),
    ("threads", "max(max_over_time(jvm_threads_live_threads{JOB}[WIN]))"),
    ("tomcat_busy_threads", "max(max_over_time(tomcat_threads_busy_threads{JOB}[WIN]))"),
    ("tomcat_connections", "max(max_over_time(tomcat_connections_current_connections{JOB}[WIN]))"),
    ("db_active", "max(max_over_time(hikaricp_connections_active{JOB}[WIN]))"),
    ("db_pending", "max(max_over_time(hikaricp_connections_pending{JOB}[WIN]))"),
    (
        "db_acquire_ms",
        "sum(rate(hikaricp_connections_acquire_seconds_sum{JOB}[WIN])) / "
        "sum(rate(hikaricp_connections_acquire_seconds_count{JOB}[WIN])) * 1000",
    ),
    (
        "db_usage_ms",
        "sum(rate(hikaricp_connections_usage_seconds_sum{JOB}[WIN])) / "
        "sum(rate(hikaricp_connections_usage_seconds_count{JOB}[WIN])) * 1000",
    ),
    ("db_timeouts", "sum(increase(hikaricp_connections_timeout_total{JOB}[WIN]))"),
    ("open_files", "max(max_over_time(process_files_open_files{JOB}[WIN]))"),
]

# Counts by a label over the window.
LABELLED_METRICS = [
    ("socket_closes", "sum by (code) (increase(hovanki_socket_close_total{JOB}[WIN]))", ("code",)),
    (
        "http_errors",
        'sum by (status, method, uri) (increase(http_server_requests_seconds_count{JOB,status=~"4..|5.."}[WIN]))',
        ("status", "method", "uri"),
    ),
]

# The server's limits, once.
STATIC_METRICS = [
    ("heap_max_mb", 'sum(jvm_memory_max_bytes{JOB,area="heap"} > 0) / 1048576'),
    ("old_gen_max_mb", "max(jvm_gc_max_data_size_bytes{JOB}) / 1048576"),
    ("jvm_cpus", "max(system_cpu_count{JOB})"),
    ("tomcat_max_threads", "max(tomcat_threads_config_max_threads{JOB})"),
    ("tomcat_max_connections", "max(tomcat_connections_config_max_connections{JOB})"),
    ("db_pool_max", "max(hikaricp_connections_max{JOB})"),
    ("max_open_files", "max(process_files_max_files{JOB})"),
]

DIGITS = {
    "gc_minor": 0,
    "gc_major": 0,
    "db_timeouts": 0,
    "cpu_vcpu": 2,
    "cpu_vcpu_gauge": 2,
    "sync_p50_ms": 2,
    "sync_p95_ms": 2,
    "sync_p99_ms": 2,
    "sync_max_ms": 1,
    "gc_pause_pct": 2,
    "http_5xx_per_s": 2,
    "http_429_per_s": 2,
    "db_acquire_ms": 2,
    "db_usage_ms": 2,
}


def expr(template: str, job: str, window: int) -> str:
    return (
        template.replace("{JOB}", f'{{job="{job}"}}').replace("{JOB,", f'{{job="{job}",').replace("WIN", f"{window}s")
    )


def prom_window(job: str, start: float, end: float) -> dict:
    """The server's numbers over [start, end]: instant queries at the end, the window as their range."""
    window = max(SAMPLE_SECONDS * 3, int(round(end - start)))
    out: dict = {}
    for key, template in WINDOW_METRICS:
        value = prom_value(prom("query", {"query": expr(template, job, window), "time": end}))
        out[key] = rnd(value, DIGITS.get(key, 1))
    if out.get("cpu_vcpu") is None:
        out["cpu_vcpu"] = out.get("cpu_vcpu_gauge")
    out.pop("cpu_vcpu_gauge", None)
    for key, template, labels in LABELLED_METRICS:
        result = prom("query", {"query": expr(template, job, window), "time": end}) or []
        counts = {}
        for r in result:
            v = float(r["value"][1])
            if math.isnan(v) or round(v) <= 0:
                continue
            counts[" ".join(r["metric"].get(label, "?") for label in labels)] = int(round(v))
        if counts:
            out[key] = dict(sorted(counts.items(), key=lambda kv: -kv[1]))
    return out


GC_NAMES = {
    ("Copy", "MarkSweepCompact"): "Serial",
    ("Copy",): "Serial",
    ("MarkSweepCompact",): "Serial",
    ("G1 Concurrent GC", "G1 Old Generation", "G1 Young Generation"): "G1",
    ("G1 Old Generation", "G1 Young Generation"): "G1",
    ("G1 Young Generation",): "G1",
    ("G1 Concurrent GC", "G1 Young Generation"): "G1",
    ("PS MarkSweep", "PS Scavenge"): "Parallel",
    ("ZGC Cycles", "ZGC Pauses"): "ZGC",
}


def prom_static(job: str, at: float) -> dict:
    out = {}
    for key, template in STATIC_METRICS:
        out[key] = rnd(prom_value(prom("query", {"query": expr(template, job, 60), "time": at})))
    result = prom("query", {"query": f'jvm_info{{job="{job}"}}', "time": at}) or []
    if result:
        m = result[0]["metric"]
        out["jvm"] = " ".join(v for v in (m.get("vendor"), m.get("runtime"), m.get("version")) if v)
    result = prom("query", {"query": f'count by (gc) (jvm_gc_pause_seconds_count{{job="{job}"}})', "time": at}) or []
    gcs = sorted({r["metric"].get("gc", "?") for r in result})
    if gcs:
        out["gc"] = GC_NAMES.get(tuple(gcs), ", ".join(gcs))
    return out


def prom_timeline(job: str, start: float, end: float, step: int) -> dict:
    """The timeline: one value per [step] seconds for the keys worth a chart."""
    keys = [
        "players_in_memory",
        "sockets",
        "syncs_per_s",
        "sync_p95_ms",
        "sync_p99_ms",
        "cpu_vcpu",
        "heap_used_mb",
        "heap_after_gc_mb",
        "alloc_mb_per_s",
        "gc_pause_pct",
        "gc_max_pause_ms",
        "tomcat_connections",
        "http_5xx_per_s",
        "db_pending",
    ]
    templates = dict(WINDOW_METRICS)
    times = list(range(int(start) + step, int(end) + 1, step))
    series: dict = {"t_s": [t - int(start) for t in times]}
    for key in keys:
        result = (
            prom(
                "query_range",
                {
                    "query": expr(templates[key], job, step),
                    "start": times[0] if times else start,
                    "end": times[-1] if times else end,
                    "step": step,
                },
            )
            or []
        )
        values: dict[int, float] = {}
        for r in result:
            for ts, v in r["values"]:
                v = float(v)
                if not math.isnan(v):
                    values[int(float(ts))] = values.get(int(float(ts)), 0) + v
        series[key] = [rnd(values.get(t), DIGITS.get(key, 1)) for t in times]
    return series


# ---------------------------------------------------------------------------------------------------------------------
# PostgreSQL: pg_stat_statements, pg_stat_database, pg_stat_user_tables


def postgres_container(docker: Docker) -> str | None:
    for c in docker.lab_containers():
        if c["service"] == "postgres" and c["state"] == "running":
            return c["name"]
    return None


def psql(container: str, sql: str) -> object | None:
    rc, out = run(
        ["docker", "exec", container, "psql", "-U", "hovanki", "-d", "hovanki", "-AtX", "-c", sql], timeout=60
    )
    if rc != 0:
        warn(f"psql: {out.strip()[:200]}")
        return None
    out = out.strip()
    try:
        return json.loads(out) if out else None
    except ValueError:
        return None


DB_SNAPSHOT_SQL = """
SELECT json_build_object(
  'database', (SELECT row_to_json(d) FROM (
      SELECT xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched, tup_inserted, tup_updated,
             tup_deleted, temp_bytes, deadlocks
        FROM pg_stat_database WHERE datname = current_database()) d),
  'tables', (SELECT coalesce(json_object_agg(relname, json_build_array(n_tup_ins, n_tup_upd, n_tup_del, seq_scan,
                                                                       coalesce(idx_scan, 0))), '{}')
               FROM pg_stat_user_tables),
  'size_mb', pg_database_size(current_database()) / 1048576.0)
"""

DB_TOP_SQL = """
SELECT coalesce(json_agg(t), '[]') FROM (
  SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms,
         round(max_exec_time::numeric, 1) AS max_ms, rows, shared_blks_read AS disk_blocks,
         left(regexp_replace(query, '\\s+', ' ', 'g'), 300) AS query
    FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
     AND query NOT ILIKE '%pg_stat_%'
   ORDER BY total_exec_time DESC
   LIMIT 10) t
"""

DB_TOTAL_SQL = """
SELECT json_build_object('statements', count(*), 'calls', coalesce(sum(calls), 0),
                         'total_ms', round(coalesce(sum(total_exec_time), 0)::numeric, 1))
  FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
"""


def db_reset(container: str) -> bool:
    rc, out = run(
        [
            "docker",
            "exec",
            container,
            "psql",
            "-U",
            "hovanki",
            "-d",
            "hovanki",
            "-AtXc",
            "SELECT pg_stat_statements_reset()",
        ],
        timeout=30,
    )
    return rc == 0


def db_report(container: str | None, before: dict | None, reset: bool) -> dict | None:
    if not container:
        return None
    after = psql(container, DB_SNAPSHOT_SQL)
    report: dict = {"statements_reset_at_start": reset}
    if reset:
        report["top"] = psql(container, DB_TOP_SQL) or []
        report["totals"] = psql(container, DB_TOTAL_SQL)
    if after:
        report["size_mb"] = rnd(after.get("size_mb"))
        if before and before.get("database") and after.get("database"):
            report["delta"] = {k: after["database"][k] - before["database"].get(k, 0) for k in after["database"]}
        if before and after.get("tables") is not None:
            writes = []
            for table, (ins, upd, dele, seq, idx) in after["tables"].items():
                b = (before.get("tables") or {}).get(table, [0, 0, 0, 0, 0])
                row = {
                    "table": table,
                    "inserted": ins - b[0],
                    "updated": upd - b[1],
                    "deleted": dele - b[2],
                    "seq_scans": seq - b[3],
                    "idx_scans": idx - b[4],
                }
                if any(row[k] for k in ("inserted", "updated", "deleted", "seq_scans", "idx_scans")):
                    writes.append(row)
            report["tables"] = sorted(writes, key=lambda r: -(r["inserted"] + r["updated"] + r["deleted"]))[:15]
    return report


# ---------------------------------------------------------------------------------------------------------------------
# Gatling: the console (timed by the wrapper) and the report


def read_console(path: Path) -> list[tuple[float, str]]:
    """gatling.log: every line is `<epoch seconds> <the console's line>`."""
    lines = []
    if not path.exists():
        return lines
    for raw in path.read_text(errors="replace").splitlines():
        ts, _, text = raw.partition(" ")
        try:
            lines.append((float(ts), text))
        except ValueError:
            continue
    return lines


def parse_console(lines: list[tuple[float, str]]) -> dict:
    """What the simulation and Gatling said: the start, the ladder's plan, levels and stop, the summary, the errors."""
    out: dict = {"levels": [], "errors": []}
    in_errors = False
    for ts, text in lines:
        if "started..." in text and text.lstrip().startswith("Simulation ") and "start" not in out:
            out["start"] = ts
        m = re.search(r"(\d+) games of (\d+) over (\w+)(?:, (\d+) levels of (\d+) games, (\d+)s each)?", text)
        if m and "plan" not in out:
            out["plan"] = {
                "games": int(m.group(1)),
                "players": int(m.group(2)),
                "transport": m.group(3),
                "levels": int(m.group(4) or 1),
                "games_per_level": int(m.group(5) or m.group(1)),
                "level_seconds": int(m.group(6)) if m.group(6) else None,
                "at": ts,
            }
        m = re.search(r"Level (\d+) of (\d+): (\d+) players", text)
        if m:
            out["levels"].append({"level": int(m.group(1)), "players": int(m.group(3)), "at": ts})
        if "The ladder stops" in text and "stop" not in out:
            out["stop"] = {"at": ts, "reason": text.strip()}
            m = re.search(r"at (\d+) players \((\d+) syncs/s\)", text)
            if m:
                out["stop"]["players"] = int(m.group(1))
                out["stop"]["syncs_per_s"] = int(m.group(2))
        m = re.search(r"Simulation \S+ completed in (\d+) seconds", text)
        if m:
            out["completed"] = ts
            out["completed_seconds"] = int(m.group(1))
        m = re.search(r"open the following file: (?:file://)?(\S+index\.html)", text, re.IGNORECASE)
        if m:
            out["report"] = m.group(1)
        m = re.search(r"Global: .*(true|false)", text)
        if "assertion" in text.lower() and m:
            out.setdefault("assertions", []).append(text.strip())
        if re.match(r"^-+ Errors -+", text.strip()):
            # Gatling prints the errors in every status block: the last one is the run's.
            in_errors = True
            out["errors"] = []
            continue
        if in_errors:
            stripped = text.strip()
            if re.match(r"^=+$", stripped) or re.match(r"^-+ .* -+$", stripped):
                in_errors = False
                continue
            m = re.match(r"^>\s*(.+?)\s+(\d+)\s+\(\s*([\d.,]+)%\)$", stripped)
            if m:
                out["errors"].append(
                    {
                        "message": m.group(1),
                        "count": int(m.group(2)),
                        "pct_of_errors": float(m.group(3).replace(",", ".")),
                    }
                )
            elif out["errors"] and stripped and not stripped.startswith(">"):
                out["errors"][-1]["message"] += " " + stripped
    if "start" not in out and out.get("plan"):
        out["start"] = out["plan"]["at"]
    return out


class ClientLog:
    """Gatling's simulation.log, read whole: every request's latency by second and name, the errors, the users.

    The file is Gatling's binary record log (3.10+; written by its LogFileDataWriter): a run record, then user, request,
    group and error records with times in ms from the run's start. Strings are an int length, the bytes and Java's
    coder byte (0 Latin-1, 1 UTF-16); names and messages are cached: a new one comes with its id, a seen one as -id.
    Gatling has no other export of its per-request data, so the format is read here; a Gatling upgrade that changes
    it only loses the client's numbers of the report, with a warning.
    """

    def __init__(self, path: Path):
        self.path = path
        self.version = None
        self.simulation = None
        self.start_ms = 0
        # (second from the start, request name) -> {latency ms: count}; KO latencies separately, messages by second.
        self.ok: dict[tuple[int, str], dict[int, int]] = {}
        self.ko: dict[tuple[int, str], int] = {}
        self.ko_messages: dict[tuple[int, str], int] = {}
        self.users: dict[int, int] = {}  # second -> users started minus ended in it
        self.crashes: dict[str, int] = {}
        self.read()

    def read(self) -> None:
        import struct

        data = self.path.read_bytes()
        view = memoryview(data)
        pos = 0
        unpack_int = struct.Struct(">i").unpack_from
        unpack_long = struct.Struct(">q").unpack_from
        cache: dict[int, str] = {}

        def string() -> str:
            nonlocal pos
            (length,) = unpack_int(view, pos)
            pos += 4
            if length == 0:
                return ""
            raw = bytes(view[pos : pos + length])
            coder = view[pos + length]
            pos += length + 1
            return raw.decode("latin-1") if coder == 0 else raw.decode("utf-16-le", errors="replace")

        def cached() -> str:
            nonlocal pos
            (key,) = unpack_int(view, pos)
            pos += 4
            if key >= 0:
                value = string()
                cache[key] = value
                return value
            return cache.get(-key, "?")

        if data[0] != 0:
            raise ValueError("not a Gatling simulation.log")
        pos = 1
        self.version = string()
        self.simulation = string()
        (self.start_ms,) = unpack_long(view, pos)
        pos += 8
        string()  # the run's description
        (scenarios,) = unpack_int(view, pos)
        pos += 4
        for _ in range(scenarios):
            string()
        (assertions,) = unpack_int(view, pos)
        pos += 4
        for _ in range(assertions):
            (length,) = unpack_int(view, pos)
            pos += 4 + length
        ok, ko, ko_messages, users = self.ok, self.ko, self.ko_messages, self.users
        size = len(data)
        while pos < size:
            header = data[pos]
            pos += 1
            if header == 1:  # request: groups, name, start, end, ok, message
                (groups,) = unpack_int(view, pos)
                pos += 4
                for _ in range(groups):
                    cached()
                name = cached()
                start, end = unpack_int(view, pos)[0], unpack_int(view, pos + 4)[0]
                status = data[pos + 8]
                pos += 9
                message = cached()
                key = (start // 1000, name)
                if status == 1:
                    bucket = ok.get(key)
                    if bucket is None:
                        bucket = ok[key] = {}
                    latency = end - start
                    bucket[latency] = bucket.get(latency, 0) + 1
                else:
                    ko[key] = ko.get(key, 0) + 1
                    mkey = (start // 1000, message or "KO")
                    ko_messages[mkey] = ko_messages.get(mkey, 0) + 1
            elif header == 2:  # user: scenario, start or end, time
                is_start = data[pos + 4]
                (t,) = unpack_int(view, pos + 5)
                pos += 9
                users[t // 1000] = users.get(t // 1000, 0) + (1 if is_start else -1)
            elif header == 3:  # group: groups, start, end, cumulated time, ok
                (groups,) = unpack_int(view, pos)
                pos += 4
                for _ in range(groups):
                    cached()
                pos += 13
            elif header == 4:  # error: message, time
                message = cached()
                pos += 4
                self.crashes[message] = self.crashes.get(message, 0) + 1
            else:
                raise ValueError(f"unknown record {header} at byte {pos - 1}")

    def window(self, start: float, end: float, names: str | None = None) -> dict | None:
        """The requests started in [start, end] (epoch seconds): count, KO, percentiles; [names] a regex of names."""
        lo = int(start - self.start_ms / 1000)
        hi = int(end - self.start_ms / 1000)
        pattern = re.compile(names) if names else None
        latencies: dict[int, int] = {}
        count = ko = 0
        for (second, name), bucket in self.ok.items():
            if lo <= second < hi and (pattern is None or pattern.search(name)):
                for latency, n in bucket.items():
                    latencies[latency] = latencies.get(latency, 0) + n
                    count += n
        for (second, name), n in self.ko.items():
            if lo <= second < hi and (pattern is None or pattern.search(name)):
                ko += n
        if not count and not ko:
            return None
        out = {
            "count": count + ko,
            "per_s": rnd((count + ko) / max(1, hi - lo)),
            "ko": ko,
            "ko_pct": rnd(ko / (count + ko) * 100, 2),
        }
        out.update(percentiles(latencies, count))
        return out

    def requests(self, start: float, end: float) -> dict:
        names = sorted({name for (_, name) in self.ok} | {name for (_, name) in self.ko})
        out = {}
        for name in names:
            w = self.window(start, end, "^" + re.escape(name) + "$")
            if w:
                out[name] = w
        return out

    def errors(self, start: float, end: float) -> list[dict]:
        lo = int(start - self.start_ms / 1000)
        hi = int(end - self.start_ms / 1000)
        counts: dict[str, int] = {}
        for (second, message), n in self.ko_messages.items():
            if lo <= second < hi:
                counts[message] = counts.get(message, 0) + n
        return [{"message": m, "count": n} for m, n in sorted(counts.items(), key=lambda kv: -kv[1])[:10]]

    def active_users(self, at: float) -> int:
        second = int(at - self.start_ms / 1000)
        return sum(n for s, n in self.users.items() if s <= second)

    def most_users(self, start: float, end: float) -> int:
        """The most virtual users at once over [start, end]."""
        lo = int(start - self.start_ms / 1000)
        hi = int(end - self.start_ms / 1000)
        active = 0
        seconds = sorted(self.users)
        for second in seconds:
            if second >= lo:
                break
            active += self.users[second]
        most = active
        for second in seconds:
            if lo <= second < hi:
                active += self.users[second]
                most = max(most, active)
        return most


def percentiles(latencies: dict[int, int], count: int) -> dict:
    if not count:
        return {}
    wanted = [("p50_ms", 0.50), ("p95_ms", 0.95), ("p99_ms", 0.99), ("p999_ms", 0.999)]
    out: dict = {}
    seen = 0
    total_ms = 0
    ordered = sorted(latencies.items())
    i = 0
    for latency, n in ordered:
        total_ms += latency * n
    for latency, n in ordered:
        seen += n
        while i < len(wanted) and seen >= wanted[i][1] * count:
            out[wanted[i][0]] = latency
            i += 1
    out["mean_ms"] = rnd(total_ms / count, 2)
    out["max_ms"] = ordered[-1][0]
    return out


# The hot path's requests: `sync LOBBY`, `sync HIDING`, `sync SEEKING`, over HTTP or the socket.
SYNC_REQUESTS = r"^sync "


def client_log(report_dir: Path | None) -> ClientLog | None:
    if not report_dir or not (report_dir / "simulation.log").exists():
        return None
    try:
        log = ClientLog(report_dir / "simulation.log")
    except (ValueError, IndexError, UnicodeDecodeError) as e:
        warn(f"Gatling's simulation.log can't be read ({e}): the report has no client numbers")
        return None
    if log.version and not log.version.startswith("3."):
        warn(f"Gatling {log.version}: simulation.log was read as 3.16's")
    return log


def latest_gatling_report(after: float | None = None) -> Path | None:
    reports = ROOT / "perf" / "build" / "reports" / "gatling"
    if not reports.exists():
        return None
    dirs = [d for d in reports.iterdir() if d.is_dir() and (d / "index.html").exists()]
    if after is not None:
        dirs = [d for d in dirs if d.stat().st_mtime >= after]
    return max(dirs, key=lambda d: d.stat().st_mtime) if dirs else None


# ---------------------------------------------------------------------------------------------------------------------
# The run's windows: the ladder's steps, or the hold of a run without a ladder


def steps_of(console: dict, params: dict, simulation: str, start: float, end: float) -> list[dict]:
    """A step is a level of the ladder from its players' arrival (plus a settle) to the next level's start."""
    defaults = DEFAULTS.get(simulation, {})
    ramp = int(params.get("rampSeconds", defaults.get("rampSeconds", 60)))
    plan = console.get("plan") or {}
    levels = plan.get("levels") or int(params.get("steps", defaults.get("steps", 1)))
    level_seconds = plan.get("level_seconds")
    if not level_seconds and simulation == "OrdinaryGames" and levels > 1:
        # No console (an imported run): OrdinaryGames' ladder by its parameters.
        level_seconds = ramp + int(params.get("minutes", defaults["minutes"])) * 60
    games_per_level = plan.get("games_per_level") or int(params.get("games", defaults.get("games", 0)))
    players_per_game = plan.get("players") or int(params.get("players", defaults.get("players", 0)))
    stop = (console.get("stop") or {}).get("at")
    steps = []
    if level_seconds and levels > 1:
        arrived = {lv["level"]: lv for lv in console.get("levels", [])}
        for k in range(1, levels + 1):
            level_start = start + (k - 1) * level_seconds
            if level_start >= end - 1:
                break
            came = arrived.get(k, {}).get("at", level_start + ramp)
            hold_from = came + SETTLE_SECONDS
            hold_to = min(level_start + level_seconds, end, stop or end)
            step = {
                "step": k,
                "from_s": rnd(level_start - start),
                "players_planned": k * games_per_level * players_per_game or None,
                "hold": [hold_from, hold_to],
            }
            if stop and level_start <= stop <= level_start + level_seconds:
                step["stopped_here"] = True
            if hold_to - hold_from < SAMPLE_SECONDS * 3:
                # The ladder broke while this level's players were still coming: its last half minute.
                step["hold"] = [max(level_start, hold_to - 30), hold_to]
                step["during_ramp"] = True
                if hold_to - level_start < SAMPLE_SECONDS * 3:
                    step["too_short"] = True
            steps.append(step)
            if stop and stop <= level_start + level_seconds:
                break
    else:
        hold_from = min(start + ramp + SETTLE_SECONDS, end)
        planned = (games_per_level * players_per_game) if simulation == "OrdinaryGames" else players_per_game
        steps.append({"step": 1, "from_s": 0, "players_planned": planned or None, "hold": [hold_from, end]})
        if end - hold_from < SAMPLE_SECONDS * 3:
            steps[-1]["hold"] = [start, end]
    return steps


# ---------------------------------------------------------------------------------------------------------------------
# The findings: the patterns the earlier runs taught (docs/perf.md, «Первые находки»), checked on every step


def findings(report: dict) -> list[dict]:
    out: list[dict] = []
    server_limits = report.get("server", {})
    heap_max = server_limits.get("heap_max_mb")
    vcpus = server_limits.get("cpus") or server_limits.get("jvm_cpus")
    target = report.get("load", {}).get("params", {}).get("targetMs") or DEFAULTS.get(
        report.get("load", {}).get("simulation", ""), {}
    ).get("targetMs")

    def add(level: str, code: str, text: str, step: int | None = None) -> None:
        if not any(f["code"] == code and f.get("step") == step for f in out):
            out.append({"level": level, "code": code, "step": step, "text": text})

    outcome = report.get("outcome", {})
    for name, c in (report.get("containers") or {}).items():
        if c.get("oom_killed"):
            add(
                "critical",
                "oom_killed",
                f"контейнер {name} убит нехваткой памяти (OOMKilled, код {c.get('exit_code')})",
            )
        elif c.get("state") not in (None, "running") and name == "server":
            add(
                "critical",
                "server_down",
                f"сервер не работает после прогона: {c.get('state')}, код {c.get('exit_code')}",
            )
    if outcome.get("stop_reason"):
        add("info", "ladder_stop", outcome["stop_reason"])
    if outcome.get("server_died_s") is not None:
        add(
            "critical",
            "server_died",
            f"сервер умер на {outcome['server_died_s']} с прогона, при {outcome.get('server_died_at_players')} "
            "игроках: цифры сервера дальше пустые",
        )
    leftovers = server_limits.get("players_in_memory_at_start")
    if leftovers:
        add(
            "info",
            "server_not_fresh",
            f"на сервере уже были {leftovers} игроков прошлых прогонов: их игры в памяти, куча и GC не с нуля "
            "(перезапустите сервер для чистого замера)",
        )

    for s in report.get("steps", []):
        n = s["step"]
        srv = s.get("server", {})
        con = s.get("containers", {})
        cli = s.get("client") or {}
        players = s.get("players") or s.get("players_planned")
        where = f"ступень {n} ({players} игроков)" if players is not None else f"ступень {n}"
        if (srv.get("gc_major") or 0) >= 1 and (srv.get("gc_major_max_pause_ms") or 0) >= 200:
            add(
                "warn",
                "full_gc",
                f"{where}: полные сборки ({rnd(srv['gc_major'], 0)}), пауза до "
                f"{rnd(srv['gc_major_max_pause_ms'], 0)} мс",
                n,
            )
        if (srv.get("gc_pause_pct") or 0) >= 5:
            add("warn", "gc_overhead", f"{where}: GC стоит {srv['gc_pause_pct']} % времени", n)
        old_max = server_limits.get("old_gen_max_mb") or heap_max
        if old_max and (srv.get("heap_after_gc_mb") or 0) >= 0.7 * old_max:
            add(
                "warn",
                "heap_pressure",
                f"{where}: живых данных после GC {srv['heap_after_gc_mb']} МБ из {old_max} МБ старого поколения",
                n,
            )
        server_c = con.get("server") or con.get("server-host") or {}
        limit = server_c.get("mem_limit_mb")
        if limit and (server_c.get("mem_mb_max") or 0) >= 0.9 * limit:
            add("warn", "memory_near_limit", f"{where}: память контейнера {server_c['mem_mb_max']} МБ из {limit} МБ", n)
        if (server_c.get("throttled_pct") or 0) >= 10:
            add(
                "warn",
                "cpu_throttled",
                f"{where}: CPU сервера упирается в лимит, троттлинг {server_c['throttled_pct']} % периодов",
                n,
            )
        cpu = srv.get("cpu_vcpu") or server_c.get("cpu_vcpu")
        if vcpus and cpu and cpu >= 0.9 * vcpus:
            add("warn", "cpu_saturated", f"{where}: сервер ест {cpu} из {vcpus} vCPU", n)
        machine = con.get("machine", {}).get("cpu_pct")
        gatling = con.get("gatling", {}).get("cpu_vcpu")
        if machine and machine >= 90:
            add(
                "warn",
                "machine_saturated",
                f"{where}: машина занята на {machine} % (Gatling {gatling} vCPU): предел "
                f"может быть генератора, а не сервера",
                n,
            )
        if (srv.get("db_pending") or 0) > 0 or (srv.get("db_acquire_ms") or 0) >= 5:
            add(
                "warn",
                "db_pool_wait",
                f"{where}: ждут соединения с базой (pending {srv.get('db_pending')}, ожидание "
                f"{srv.get('db_acquire_ms')} мс)",
                n,
            )
        max_threads = server_limits.get("tomcat_max_threads")
        if max_threads and (srv.get("tomcat_busy_threads") or 0) >= 0.9 * max_threads:
            add(
                "warn",
                "tomcat_threads",
                f"{where}: заняты {srv['tomcat_busy_threads']} из {max_threads} потоков Tomcat",
                n,
            )
        max_conn = server_limits.get("tomcat_max_connections")
        if max_conn and (srv.get("tomcat_connections") or 0) >= 0.9 * max_conn:
            add("warn", "tomcat_connections", f"{where}: {srv['tomcat_connections']} соединений из {max_conn}", n)
        if (srv.get("http_5xx_per_s") or 0) > 0:
            add("warn", "http_5xx", f"{where}: ответы 5xx, {srv['http_5xx_per_s']} в секунду", n)
        closes = {
            code: count for code, count in (srv.get("socket_closes") or {}).items() if code not in ("1000", "1001")
        }
        if closes:
            add("warn", "socket_closes", f"{where}: сокеты закрыты с кодами {closes}", n)
        if (cli.get("ko_pct") or 0) >= 1:
            add("warn", "client_errors", f"{where}: {cli['ko_pct']} % запросов с ошибкой у Gatling", n)
        client_p95 = cli.get("p95_ms")
        server_p95 = srv.get("sync_p95_ms")
        if client_p95 and server_p95 is not None and client_p95 >= 20 and client_p95 >= 5 * max(server_p95, 1):
            add(
                "warn",
                "queueing",
                f"{where}: у клиента p95 {client_p95} мс, внутри сервера {server_p95} мс: время "
                f"уходит до таймера (очередь Tomcat, GC, сеть, генератор)",
                n,
            )
        if target and server_p95 and server_p95 > target:
            add(
                "warn",
                "server_p95_over_target",
                f"{where}: p95 синхронизации внутри сервера {server_p95} мс, цель {target} мс",
                n,
            )
    return out


# ---------------------------------------------------------------------------------------------------------------------
# The run's facts besides the numbers: the code, the machine, the server's setup


def code_facts() -> dict:
    facts: dict = {}
    rc, out = run(["git", "rev-parse", "--short=10", "HEAD"], timeout=10)
    if rc == 0:
        facts["commit"] = out.strip()
    rc, out = run(["git", "rev-parse", "--abbrev-ref", "HEAD"], timeout=10)
    if rc == 0:
        facts["branch"] = out.strip()
    rc, out = run(["git", "log", "-1", "--format=%s%x00%cI"], timeout=10)
    if rc == 0 and "\x00" in out:
        facts["subject"], facts["committed_at"] = (part.strip() for part in out.split("\x00", 1))
    rc, out = run(["git", "status", "--porcelain", "--", ".", ":(exclude)perf/runs"], timeout=20)
    if rc == 0:
        changed = [line[3:] for line in out.splitlines() if line.strip() and not line.startswith("??")]
        untracked = [line[3:] for line in out.splitlines() if line.startswith("??")]
        facts["dirty"] = bool(changed)
        if changed:
            facts["changed_files"] = changed[:30]
        if untracked:
            facts["untracked_files"] = untracked[:30]
    jar = ROOT / "server" / "build" / "libs" / "hovanki-server.jar"
    if jar.exists():
        facts["server_jar_built_at"] = iso(jar.stat().st_mtime)
        newer = newest_source(jar.stat().st_mtime)
        if newer:
            facts["sources_newer_than_jar"] = newer
    return facts


def newest_source(than: float) -> list[str]:
    """Sources of the server changed after its jar was built: the server under load runs older code."""
    newer = []
    for top in ("server/src/main", "shared/src/commonMain", "shared/src/jvmMain"):
        base = ROOT / top
        if not base.exists():
            continue
        for dirpath, _, files in os.walk(base):
            for name in files:
                p = Path(dirpath) / name
                if p.stat().st_mtime > than + 1:
                    newer.append(str(p.relative_to(ROOT)))
                    if len(newer) >= 10:
                        return newer
    return newer


def machine_facts(docker: Docker) -> dict:
    facts = {
        "os": f"{platform.system()} {platform.release()}",
        "arch": platform.machine(),
        "cpus": os.cpu_count(),
        "ram_gb": rnd((os_ram_bytes() or 0) / 2**30),
    }
    info = docker.info()
    if info:
        facts["docker"] = {
            "cpus": info.get("NCPU"),
            "ram_gb": rnd((info.get("MemTotal") or 0) / 2**30),
            "version": info.get("ServerVersion"),
            "os": info.get("OperatingSystem"),
            "cgroup": info.get("CgroupVersion"),
        }
    return facts


def containers_facts(docker: Docker) -> dict:
    """Every lab container's limits and state, by service: OOMKilled and the exit code too."""
    facts = {}
    for c in docker.lab_containers():
        inspect = docker.inspect(c["id"]) or {}
        state = inspect.get("State") or {}
        host = inspect.get("HostConfig") or {}
        env = {
            e.split("=", 1)[0]: e.split("=", 1)[1] for e in (inspect.get("Config") or {}).get("Env") or [] if "=" in e
        }
        entry = {
            "name": c["name"],
            "image": c["image"],
            "state": state.get("Status"),
            "oom_killed": state.get("OOMKilled"),
            "exit_code": state.get("ExitCode") if state.get("Status") != "running" else None,
            "started_at": state.get("StartedAt"),
            "finished_at": state.get("FinishedAt") if state.get("Status") != "running" else None,
            "restarts": inspect.get("RestartCount"),
            "cpus": rnd(host.get("NanoCpus") / 1e9, 2) if host.get("NanoCpus") else None,
            "mem_limit_mb": rnd(host.get("Memory") / 1048576) if host.get("Memory") else None,
        }
        if state.get("Status") == "running":
            # The cgroup's own peak since the container started: v2 memory.peak, v1 max_usage_in_bytes.
            rc, out = run(
                [
                    "docker",
                    "exec",
                    c["name"],
                    "sh",
                    "-c",
                    "cat /sys/fs/cgroup/memory.peak 2>/dev/null || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes",
                ],
                timeout=10,
            )
            if rc == 0 and out.strip().isdigit():
                entry["cgroup_peak_mb"] = rnd(int(out.strip()) / 1048576)
        if c["service"] == "server":
            entry["java_tool_options"] = env.get("JAVA_TOOL_OPTIONS")
            entry["spring_profiles"] = env.get("SPRING_PROFILES_ACTIVE")
            entry["revision"] = env.get("HOVANKI_REVISION")
        facts[c["service"]] = {k: v for k, v in entry.items() if v is not None}
    return facts


# ---------------------------------------------------------------------------------------------------------------------
# Assembling the report


def collect(run_dir: Path) -> dict:
    meta = json.loads((run_dir / "meta.json").read_text())
    console_lines = read_console(run_dir / "gatling.log")
    console = parse_console(console_lines)
    start = console.get("start") or meta.get("gatling_started_at") or meta["started_at"]
    end = console.get("completed") or meta.get("ended_at") or now()
    simulation = meta["load"]["simulation"].rsplit(".", 1)[-1]
    params = meta["load"]["params"]

    report_dir = Path(console["report"]).parent if console.get("report") else None
    if report_dir and not report_dir.exists():
        report_dir = None
    if not report_dir and meta.get("gatling_report"):
        report_dir = Path(meta["gatling_report"])

    job = meta.get("prometheus_job") or server_job(end)
    samples = load_samples(run_dir / "samples.jsonl")
    client = client_log(report_dir)
    if client and not console.get("start"):
        start = client.start_ms / 1000

    containers = meta.get("containers_after") or {}
    server_c = containers.get("server", {})
    # A server that died during the run (OOMKilled, a crash): its metrics end there.
    died = parse_docker_time(server_c.get("finished_at")) if server_c.get("state") not in (None, "running") else None
    server_end = min(end, died) if died and died > start else end

    def server_window(a: float, b: float) -> dict | None:
        if not job or a >= server_end:
            return None
        b = min(b, server_end)
        return prom_window(job, max(a, b - 30) if b - a < SAMPLE_SECONDS * 3 else a, b)

    server = {
        "mode": "container" if job == "hovanki-container" else ("host" if job else None),
        "prometheus_job": job,
        "cpus": server_c.get("cpus"),
        "mem_limit_mb": server_c.get("mem_limit_mb"),
        "java_tool_options": server_c.get("java_tool_options"),
    }
    if job:
        server.update(prom_static(job, server_end - SAMPLE_SECONDS))
        before = prom("query", {"query": f'hovanki_players{{job="{job}"}}', "time": start})
        server["players_in_memory_at_start"] = rnd(prom_value(before))

    steps = []
    for s in steps_of(console, params, simulation, start, end):
        a, b = s.pop("hold")
        step = {**s, "hold_s": [rnd(a - start), rnd(b - start)]}
        numbers = server_window(a, b)
        if numbers:
            step["server"] = numbers
        step["containers"] = samples_in(samples, a, b)
        if client:
            step["client"] = client_numbers(client, a, b)
            step["players"] = step["client"]["users_max"]
        derive(step)
        steps.append(step)

    whole = {"window_s": [0, rnd(end - start)]}
    numbers = server_window(start, end)
    if numbers:
        whole["server"] = numbers
    whole["containers"] = samples_in(samples, start, end)
    if client:
        whole["client"] = {**client_numbers(client, start, end + 1), "requests": client.requests(start, end + 1)}
        whole["players"] = whole["client"]["users_max"]
        if client.crashes:
            whole["client"]["crashes"] = client.crashes
    derive(whole)

    timeline = None
    if end - start > 30:
        step_s = max(15, int(math.ceil((end - start) / 60 / 5.0)) * 5)
        times = list(range(int(start) + step_s, int(end) + 1, step_s))
        timeline = {"step_s": step_s, "t_s": [t - int(start) for t in times]}
        if job:
            timeline.update(prom_timeline(job, start, end, step_s))
        if client:
            timeline.update(client_timeline(client, times, step_s))
        timeline.update(samples_timeline(samples, start, end, step_s))

    stop = console.get("stop") or {}
    kept = None
    passed = [s for s in steps if not s.get("stopped_here") and not s.get("too_short")]
    if passed and not meta.get("imported"):
        last = passed[-1]
        kept = last.get("players") or last.get("players_planned")
    outcome = {
        "status": outcome_status(meta, console, containers),
        "stop_reason": stop.get("reason"),
        "stopped_at_players": stop.get("players"),
        "stopped_at_syncs_per_s": stop.get("syncs_per_s"),
        "players_kept": kept,
        "gradle_exit_code": meta.get("gradle_exit_code"),
        "gatling_errors": console.get("errors") or [],
    }
    if server_end < end:
        outcome["server_died_s"] = rnd(server_end - start)
        if client:
            outcome["server_died_at_players"] = client.most_users(server_end - SAMPLE_SECONDS, server_end)

    report = {
        "schema": SCHEMA,
        "id": meta["id"],
        "label": meta.get("label"),
        "baseline": meta.get("baseline"),
        "started_at": iso(start),
        "ended_at": iso(end),
        "duration_s": rnd(end - start),
        "code": meta.get("code"),
        "machine": meta.get("machine"),
        "server": {k: v for k, v in server.items() if v is not None},
        "containers": containers,
        "load": meta["load"],
        "outcome": outcome,
        "steps": steps,
        "whole": whole,
        "db": meta.get("db"),
        "timeline": timeline,
        "links": {
            "gatling_report": str(report_dir / "index.html") if report_dir else None,
            "grafana": grafana_link(job, start, end),
        },
    }
    report["findings"] = findings(report)
    return report


def parse_docker_time(text: str | None) -> float | None:
    if not text or text.startswith("0001-"):
        return None
    m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d+)?Z$", text)
    if not m:
        return None
    whole = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=datetime.timezone.utc)
    return whole.timestamp() + float(m.group(2) or 0)


def client_numbers(client: ClientLog, start: float, end: float) -> dict:
    """Gatling's view of a window: the syncs (every phase, both transports), all requests, the errors by message."""
    out: dict = {}
    sync = client.window(start, end, SYNC_REQUESTS)
    if sync:
        out.update(sync)
    every = client.window(start, end)
    if every:
        out["all_requests"] = {
            k: every[k] for k in ("count", "ko", "ko_pct", "p95_ms", "p99_ms", "max_ms") if k in every
        }
    errors = client.errors(start, end)
    if errors:
        out["errors"] = errors
    out["users_max"] = client.most_users(start, end)
    return out


def derive(window: dict) -> None:
    """The per-sync numbers: what one sync costs, comparable between loads."""
    srv = window.get("server") or {}
    if window.get("players") is not None and srv.get("sockets") is not None:
        srv["players_poll"] = rnd(max(0, window["players"] - srv["sockets"]))
    syncs = srv.get("syncs_per_s")
    if not syncs:
        return
    cpu = srv.get("cpu_vcpu")
    if cpu is not None:
        srv["cpu_ms_per_sync"] = rnd(cpu * 1000 / syncs, 3)
    if srv.get("alloc_mb_per_s") is not None:
        srv["alloc_kb_per_sync"] = rnd(srv["alloc_mb_per_s"] * 1e6 / syncs / 1024)
    for who, c in (window.get("containers") or {}).items():
        if c.get("net_in_kb_s") is not None and who in ("server", "caddy"):
            c["net_in_kb_per_sync"] = rnd(c["net_in_kb_s"] / syncs, 2)
            c["net_out_kb_per_sync"] = rnd(c["net_out_kb_s"] / syncs, 2)


def client_timeline(client: ClientLog, times: list[int], step: int) -> dict:
    keys = {
        "client_users": [],
        "client_sync_p95_ms": [],
        "client_sync_p99_ms": [],
        "client_sync_max_ms": [],
        "client_ko": [],
    }
    for t in times:
        w = client.window(t - step, t, SYNC_REQUESTS) or {}
        every = client.window(t - step, t) or {}
        keys["client_users"].append(client.most_users(t - step, t))
        keys["client_sync_p95_ms"].append(w.get("p95_ms"))
        keys["client_sync_p99_ms"].append(w.get("p99_ms"))
        keys["client_sync_max_ms"].append(w.get("max_ms"))
        keys["client_ko"].append(every.get("ko", 0))
    return keys


def samples_timeline(samples: list[dict], start: float, end: float, step: int) -> dict:
    if not samples:
        return {}
    keys = {}
    times = list(range(int(start) + step, int(end) + 1, step))
    for i, t in enumerate(times):
        window = samples_in(samples, t - step, t)
        for who, c in window.items():
            for key in ("cpu_vcpu", "mem_mb_max", "rss_mb_max"):
                if key in c:
                    keys.setdefault(f"{who}_{key}", [None] * len(times))[i] = c[key]
            if who == "machine" and "cpu_pct" in c:
                keys.setdefault("machine_cpu_pct", [None] * len(times))[i] = c["cpu_pct"]
    return keys


def outcome_status(meta: dict, console: dict, containers: dict) -> str:
    if any(c.get("oom_killed") for c in containers.values()):
        return "oom_killed"
    server = containers.get("server")
    if server and server.get("state") not in (None, "running"):
        return "server_down"
    if meta.get("interrupted"):
        return "interrupted"
    if meta.get("imported"):
        return "imported"
    if console.get("stop"):
        return "ladder_stopped"
    if meta.get("gradle_exit_code") not in (0, None):
        return "failed"
    return "completed"


def grafana_link(job: str | None, start: float, end: float) -> str:
    params = {"from": int(start * 1000) - 30000, "to": int(end * 1000) + 30000}
    if job:
        params["var-job"] = job
    return f"{GRAFANA}/d/{grafana_uid()}/?{urllib.parse.urlencode(params)}"


def grafana_uid() -> str:
    try:
        return json.loads((ROOT / "perf" / "grafana" / "dashboards" / "hovanki.json").read_text()).get("uid", "")
    except (OSError, ValueError):
        return ""


# ---------------------------------------------------------------------------------------------------------------------
# The digest: summary.md, short enough to read in one go


def fmt(value: object, unit: str = "") -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        text = f"{value:,.2f}".rstrip("0").rstrip(".") if abs(value) < 100 else f"{value:,.0f}"
    elif isinstance(value, int):
        text = f"{value:,}"
    else:
        text = str(value)
    return text.replace(",", " ") + unit


STATUS_RU = {
    "completed": "прогон прошёл до конца",
    "ladder_stopped": "лесенка остановилась",
    "oom_killed": "контейнер убит нехваткой памяти",
    "server_down": "сервер упал",
    "failed": "прогон упал (Gatling или его проверки)",
    "interrupted": "прогон прерван",
    "imported": "импортирован из отчёта Gatling: остановка лесенки, контейнеры и база неизвестны",
}


def row(*cells: object) -> str:
    return "| " + " | ".join(str(c) for c in cells) + " |"


def table(*titles: str) -> list[str]:
    return [row(*titles), "|" + "---|" * len(titles)]


def pair(a: object, b: object) -> str:
    return f"{fmt(a)} / {fmt(b)}"


def summary_md(r: dict) -> str:
    load = r.get("load", {})
    params = load.get("params", {})
    server = r.get("server", {})
    code = r.get("code") or {}
    out = r.get("outcome", {})
    sim = load.get("simulation", "?").rsplit(".", 1)[-1]
    limits = (
        f"контейнер {fmt(server.get('cpus'))} vCPU / {fmt(server.get('mem_limit_mb'))} МБ"
        if server.get("mode") == "container"
        else "сервер на машине"
    )
    code_line = f"- код: {code.get('commit', '?')} ({code.get('branch', '?')})"
    if code.get("dirty"):
        code_line += ", есть незакоммиченные правки"
    if code.get("sources_newer_than_jar"):
        code_line += "; ВНИМАНИЕ: исходники сервера новее его jar"
    outcome = f"- итог: **{STATUS_RU.get(out.get('status'), out.get('status'))}**"
    if out.get("stop_reason"):
        outcome += f": {out['stop_reason']}"
    if out.get("players_kept"):
        outcome += f"; удержано {fmt(out['players_kept'])} игроков"
    lines = [f"# Прогон {r['id']}", ""]
    if r.get("label"):
        lines += [f"**{r['label']}**", ""]
    lines += [
        f"- {sim}, {params.get('transport', 'poll')}, {limits}, куча {fmt(server.get('heap_max_mb'))} МБ, "
        f"GC {server.get('gc', '—')}",
        f"- параметры: {' '.join(f'{k}={v}' for k, v in params.items()) or 'по умолчанию'}",
        code_line,
        f"- {r.get('started_at')} → {r.get('ended_at')}, {fmt(r.get('duration_s'))} с",
        outcome,
        "",
    ]
    steps = r.get("steps") or []
    if steps:
        lines += ["## Ступени", ""]
        lines += table(
            "ст.",
            "игроков (сокет)",
            "синхр/с",
            "клиент p95/p99 мс",
            "сервер p95/p99 мс",
            "CPU vCPU",
            "CPU мс/синхр",
            "аллок КБ/синхр",
            "GC % / пауза мс / полных",
            "куча после GC МБ",
            "память / RSS МБ",
            "ошибки",
        )
        for s in steps:
            srv = s.get("server", {})
            cli = s.get("client") or {}
            sc = _step_server(s)
            errors = []
            if cli.get("ko_pct"):
                errors.append(f"KO {cli['ko_pct']} %")
            if srv.get("http_5xx_per_s"):
                errors.append(f"5xx {srv['http_5xx_per_s']}/с")
            if srv.get("socket_closes"):
                errors.append("закрытия " + ", ".join(f"{k}×{v}" for k, v in srv["socket_closes"].items()))
            lines.append(
                row(
                    f"{s['step']}{' ⛔' if s.get('stopped_here') else ''}",
                    f"{fmt(s.get('players'))} ({fmt(srv.get('sockets'))})",
                    fmt(srv.get("syncs_per_s")),
                    pair(cli.get("p95_ms"), cli.get("p99_ms")),
                    pair(srv.get("sync_p95_ms"), srv.get("sync_p99_ms")),
                    fmt(srv.get("cpu_vcpu")),
                    fmt(srv.get("cpu_ms_per_sync")),
                    fmt(srv.get("alloc_kb_per_sync")),
                    f"{pair(srv.get('gc_pause_pct'), srv.get('gc_max_pause_ms'))} / {fmt(srv.get('gc_major'))}",
                    fmt(srv.get("heap_after_gc_mb")),
                    pair(sc.get("mem_mb_max"), sc.get("rss_mb_max")),
                    "; ".join(errors) or "—",
                )
            )
        lines.append("")
    whole = r.get("whole", {})
    con = whole.get("containers") or {}
    if con:
        lines += ["## Контейнеры и процессы за прогон", ""]
        lines += table(
            "кто",
            "CPU vCPU",
            "память МБ макс",
            "RSS МБ макс",
            "лимит МБ",
            "сеть вх/исх КБ/с",
            "КБ на синхр вх/исх",
            "троттлинг %",
        )
        for who, c in con.items():
            if who == "machine":
                continue
            lines.append(
                row(
                    who,
                    fmt(c.get("cpu_vcpu")),
                    fmt(c.get("mem_mb_max")),
                    fmt(c.get("rss_mb_max")),
                    fmt(c.get("mem_limit_mb")),
                    pair(c.get("net_in_kb_s"), c.get("net_out_kb_s")),
                    pair(c.get("net_in_kb_per_sync"), c.get("net_out_kb_per_sync")),
                    fmt(c.get("throttled_pct")),
                )
            )
        if con.get("machine"):
            lines.append(
                f"\nМашина занята в среднем на {fmt(con['machine'].get('cpu_pct'))} %, максимум "
                f"{fmt(con['machine'].get('cpu_pct_max'))} %."
            )
        lines.append("")
    client = whole.get("client") or {}
    if client.get("requests"):
        lines += ["## Запросы Gatling за прогон", ""]
        lines += table("запрос", "всего", "KO", "p50", "p95", "p99", "max, мс")
        for name, q in sorted(client["requests"].items(), key=lambda kv: -kv[1]["count"]):
            lines.append(row(name, *(fmt(q.get(k)) for k in ("count", "ko", "p50_ms", "p95_ms", "p99_ms", "max_ms"))))
        lines.append("")
    errors = client.get("errors") or out.get("gatling_errors") or []
    if errors:
        lines += ["## Ошибки Gatling", ""]
        lines += [f"- {e['count']} × {e['message']}" for e in errors[:10]]
        lines.append("")
    fs = r.get("findings") or []
    if fs:
        icon = {"critical": "🔴", "warn": "🟠", "info": "ℹ️"}
        lines += ["## Находки", ""]
        lines += [f"- {icon.get(f['level'], '')} `{f['code']}` {f['text']}" for f in fs]
        lines.append("")
    db = r.get("db") or {}
    if db.get("top"):
        lines += ["## База: топ запросов по времени", ""]
        lines += table("вызовов", "всего мс", "среднее мс", "запрос")
        for q in db["top"][:5]:
            query = q["query"][:90].replace("|", "/")
            lines.append(row(fmt(q["calls"]), fmt(q["total_ms"]), fmt(q["mean_ms"]), f"`{query}`"))
        delta = db.get("delta") or {}
        if delta:
            lines.append(
                f"\nКоммитов {fmt(delta.get('xact_commit'))}, строк вставлено {fmt(delta.get('tup_inserted'))}, "
                f"обновлено {fmt(delta.get('tup_updated'))}, удалено {fmt(delta.get('tup_deleted'))}."
            )
        lines.append("")
    if r.get("baseline_comparison"):
        lines += ["## Против базового прогона", "", r["baseline_comparison"], ""]
    links = r.get("links") or {}
    lines += ["## Ссылки", "", f"- Grafana: {links.get('grafana')}", f"- Gatling: {links.get('gatling_report')}", ""]
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------------------------------
# The index and the comparison


def index_line(r: dict) -> dict:
    server = r.get("server", {})
    load = r.get("load", {})
    whole = r.get("whole", {})
    srv = whole.get("server") or {}
    client = whole.get("client") or {}
    con = whole.get("containers") or {}
    sc = con.get("server") or con.get("server-host") or {}
    return {
        "id": r["id"],
        "label": r.get("label"),
        "started_at": r.get("started_at"),
        "commit": (r.get("code") or {}).get("commit"),
        "dirty": (r.get("code") or {}).get("dirty"),
        "simulation": load.get("simulation", "").rsplit(".", 1)[-1],
        "transport": load.get("params", {}).get("transport", "poll"),
        "params": load.get("params"),
        "server": f"{server.get('mode')} {server.get('cpus') or ''} {server.get('mem_limit_mb') or ''}".strip(),
        "status": r["outcome"]["status"],
        "players_kept": r["outcome"].get("players_kept"),
        "players_max": max([s.get("players") or 0 for s in r.get("steps", [])] or [0]) or None,
        "syncs_per_s_max": max([((s.get("server") or {}).get("syncs_per_s") or 0) for s in r.get("steps", [])] or [0]),
        "client_p95_ms": client.get("p95_ms"),
        "client_p99_ms": client.get("p99_ms"),
        "ko_pct": (client.get("all_requests") or {}).get("ko_pct"),
        "cpu_ms_per_sync": srv.get("cpu_ms_per_sync"),
        "alloc_kb_per_sync": srv.get("alloc_kb_per_sync"),
        "mem_mb_max": sc.get("mem_mb_max"),
        "findings": sorted({f["code"] for f in r.get("findings", []) if f["level"] != "info"}),
    }


def write_run(run_dir: Path, report: dict) -> None:
    (run_dir / "run.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n")
    (run_dir / "summary.md").write_text(summary_md(report))
    entry = json.dumps(index_line(report), ensure_ascii=False)
    for base in [run_dir.parent] + ([EXPORT] if EXPORT else []):
        base.mkdir(parents=True, exist_ok=True)
        index = base / "index.jsonl"
        lines = [
            line
            for line in (index.read_text().splitlines() if index.exists() else [])
            if json.loads(line).get("id") != report["id"]
        ]
        index.write_text("\n".join(lines + [entry]) + "\n")
        if base != run_dir.parent:
            target = base / report["id"]
            target.mkdir(parents=True, exist_ok=True)
            shutil.copy(run_dir / "run.json", target / "run.json")
            shutil.copy(run_dir / "summary.md", target / "summary.md")


def resolve_run(ref: str) -> Path:
    """A run by its id, a unique prefix or label, a path, `latest` or `latest~N`."""
    path = Path(ref)
    if (path / "run.json").exists() or (path / "meta.json").exists():
        return path
    if path.name == "run.json" and path.exists():
        return path.parent
    runs = sorted(d for d in RUNS.iterdir() if (d / "meta.json").exists()) if RUNS.exists() else []
    m = re.match(r"^latest(?:~(\d+))?$", ref)
    if m:
        n = int(m.group(1) or 0)
        if n < len(runs):
            return runs[-1 - n]
        raise SystemExit(f"No run {ref}: {len(runs)} runs in {RUNS}")
    matches = [d for d in runs if d.name.startswith(ref)]
    if not matches:
        matches = [
            d
            for d in runs
            if (d / "run.json").exists() and json.loads((d / "run.json").read_text()).get("label") == ref
        ]
    if len(matches) == 1:
        return matches[0]
    raise SystemExit(f"No single run matches {ref!r} in {RUNS}" + (f": {[d.name for d in matches]}" if matches else ""))


def load_report(ref: str) -> dict:
    run_dir = resolve_run(ref)
    return json.loads((run_dir / "run.json").read_text())


def load_params(params: dict) -> str:
    return " ".join(f"{k}={v}" for k, v in params.items())


def _whole(r: dict, part: str) -> dict:
    return (r.get("whole") or {}).get(part) or {}


def _server_container(r: dict) -> dict:
    con = (r.get("whole") or {}).get("containers") or {}
    return con.get("server") or con.get("server-host") or {}


# The fields two runs are compared by: key in the JSON, title in the digest, how to read it from a report.
COMPARE_ROWS = [
    ("status", "итог", lambda r: r["outcome"]["status"]),
    ("players_kept", "удержано игроков", lambda r: r["outcome"].get("players_kept")),
    ("stopped_at_players", "остановка на игроках", lambda r: r["outcome"].get("stopped_at_players")),
    ("syncs_per_s_max", "синхр/с максимум", lambda r: index_line(r)["syncs_per_s_max"]),
    ("client_sync_p95_ms", "клиент: синхр p95, мс", lambda r: _whole(r, "client").get("p95_ms")),
    ("client_sync_p99_ms", "клиент: синхр p99, мс", lambda r: _whole(r, "client").get("p99_ms")),
    ("client_ko_pct", "ошибки запросов, %", lambda r: (_whole(r, "client").get("all_requests") or {}).get("ko_pct")),
    ("server_sync_p95_ms", "сервер: синхр p95, мс", lambda r: _whole(r, "server").get("sync_p95_ms")),
    ("server_sync_p99_ms", "сервер: синхр p99, мс", lambda r: _whole(r, "server").get("sync_p99_ms")),
    ("cpu_ms_per_sync", "CPU мс/синхр", lambda r: _whole(r, "server").get("cpu_ms_per_sync")),
    ("alloc_kb_per_sync", "аллокации КБ/синхр", lambda r: _whole(r, "server").get("alloc_kb_per_sync")),
    ("gc_pause_pct", "GC паузы %", lambda r: _whole(r, "server").get("gc_pause_pct")),
    ("gc_max_pause_ms", "GC пауза макс, мс", lambda r: _whole(r, "server").get("gc_max_pause_ms")),
    ("gc_major", "полных GC", lambda r: _whole(r, "server").get("gc_major")),
    ("heap_after_gc_mb", "куча после GC макс, МБ", lambda r: _whole(r, "server").get("heap_after_gc_mb")),
    ("server_mem_mb_max", "память сервера макс, МБ", lambda r: _server_container(r).get("mem_mb_max")),
    ("server_rss_mb_max", "RSS сервера макс, МБ", lambda r: _server_container(r).get("rss_mb_max")),
    ("net_out_kb_per_sync", "КБ на синхр вниз", lambda r: _server_container(r).get("net_out_kb_per_sync")),
    ("net_in_kb_per_sync", "КБ на синхр вверх", lambda r: _server_container(r).get("net_in_kb_per_sync")),
]

# The same at equal load: a step of each run with the same planned players.
STEP_ROWS = [
    ("server_sync_p95_ms", lambda s: (s.get("server") or {}).get("sync_p95_ms")),
    ("server_sync_p99_ms", lambda s: (s.get("server") or {}).get("sync_p99_ms")),
    ("client_sync_p95_ms", lambda s: (s.get("client") or {}).get("p95_ms")),
    ("client_sync_p99_ms", lambda s: (s.get("client") or {}).get("p99_ms")),
    ("cpu_vcpu", lambda s: (s.get("server") or {}).get("cpu_vcpu")),
    ("cpu_ms_per_sync", lambda s: (s.get("server") or {}).get("cpu_ms_per_sync")),
    ("alloc_kb_per_sync", lambda s: (s.get("server") or {}).get("alloc_kb_per_sync")),
    ("gc_pause_pct", lambda s: (s.get("server") or {}).get("gc_pause_pct")),
    ("heap_after_gc_mb", lambda s: (s.get("server") or {}).get("heap_after_gc_mb")),
    ("server_mem_mb_max", lambda s: _step_server(s).get("mem_mb_max")),
    ("net_out_kb_per_sync", lambda s: _step_server(s).get("net_out_kb_per_sync")),
]


def _step_server(s: dict) -> dict:
    con = s.get("containers") or {}
    return con.get("server") or con.get("server-host") or {}


def step_by_players(r: dict) -> dict[int, dict]:
    out = {}
    for s in r.get("steps") or []:
        if s.get("too_short"):
            continue
        players = s.get("players_planned") or s.get("players")
        if players:
            out[int(players)] = s
    return out


def delta(base: object, value: object) -> dict:
    change = pct_change(base, value) if isinstance(base, (int, float)) and isinstance(value, (int, float)) else None
    return {"baseline": base, "run": value, "change_pct": rnd(change)}


def compare_data(base: dict, run_: dict) -> dict:
    """The run against its baseline, as data: every compared field with the change in %, then at equal load."""
    out = {"baseline": base["id"], "fields": {key: delta(get(base), get(run_)) for key, _, get in COMPARE_ROWS}}
    base_steps, run_steps = step_by_players(base), step_by_players(run_)
    out["equal_load"] = {
        str(players): {key: delta(get(base_steps[players]), get(run_steps[players])) for key, get in STEP_ROWS}
        for players in sorted(set(base_steps) & set(run_steps))
    }
    return out


def compare_md(reports: list[dict]) -> str:
    names = [chr(65 + i) for i in range(len(reports))]
    two = len(reports) == 2
    lines = [
        "| | "
        + " | ".join(f"{n}: {r['id']}" + (f" ({r['label']})" if r.get("label") else "") for n, r in zip(names, reports))
        + (" | Δ B/A |" if two else " |"),
        "|---" * (len(reports) + 1 + (1 if two else 0)) + "|",
    ]
    tail = " | |" if two else " |"
    lines.append(
        "| коммит | "
        + " | ".join(
            str((r.get("code") or {}).get("commit")) + ("*" if (r.get("code") or {}).get("dirty") else "")
            for r in reports
        )
        + tail
    )
    lines.append(
        "| нагрузка | "
        + " | ".join(
            f"{r['load']['simulation'].rsplit('.', 1)[-1]} {load_params(r['load']['params'])}" for r in reports
        )
        + tail
    )
    lines.append(
        "| сервер | "
        + " | ".join(
            f"{(r.get('server') or {}).get('mode')} {fmt((r.get('server') or {}).get('cpus'))} vCPU "
            f"{fmt((r.get('server') or {}).get('mem_limit_mb'))} МБ"
            for r in reports
        )
        + tail
    )
    for _, title, get in COMPARE_ROWS:
        values = [get(r) for r in reports]
        change = ""
        if two:
            c = delta(values[0], values[1])["change_pct"]
            change = f" {c:+.0f} % |" if c is not None else " |"
        lines.append(f"| {title} | " + " | ".join(fmt(v) for v in values) + " |" + change)
    common = sorted(set.intersection(*(set(step_by_players(r)) for r in reports))) if reports else []
    if common:
        lines += [
            "",
            "На одинаковой нагрузке (ступени с тем же числом игроков):",
            "",
            "| игроков | "
            + " | ".join(f"{n} сервер p95/p99" for n in names)
            + " | "
            + " | ".join(f"{n} клиент p95/p99" for n in names)
            + " | "
            + " | ".join(f"{n} CPU мс/синхр" for n in names)
            + " | "
            + " | ".join(f"{n} аллок КБ/синхр" for n in names)
            + " | "
            + " | ".join(f"{n} память МБ" for n in names)
            + " |",
            "|---" * (1 + 5 * len(reports)) + "|",
        ]
        for players in common:
            ss = [step_by_players(r)[players] for r in reports]
            srv = [s.get("server") or {} for s in ss]
            cli = [s.get("client") or {} for s in ss]
            lines.append(
                f"| {fmt(players)} | "
                + " | ".join(f"{fmt(x.get('sync_p95_ms'))} / {fmt(x.get('sync_p99_ms'))}" for x in srv)
                + " | "
                + " | ".join(f"{fmt(x.get('p95_ms'))} / {fmt(x.get('p99_ms'))}" for x in cli)
                + " | "
                + " | ".join(fmt(x.get("cpu_ms_per_sync")) for x in srv)
                + " | "
                + " | ".join(fmt(x.get("alloc_kb_per_sync")) for x in srv)
                + " | "
                + " | ".join(fmt(_step_server(s).get("mem_mb_max")) for s in ss)
                + " |"
            )
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------------------------------
# Commands


def parse_props(args: list[str]) -> tuple[dict, list[str]]:
    params, gradle = {}, []
    for a in args:
        m = re.match(r"^-Pperf\.([A-Za-z]+)=(.*)$", a)
        if m:
            value: object = m.group(2)
            if re.match(r"^-?\d+$", m.group(2)):
                value = int(m.group(2))
            params[m.group(1)] = value
        gradle.append(a)
    return params, gradle


def run_id(started: float, simulation: str, params: dict) -> str:
    stamp = datetime.datetime.fromtimestamp(started, datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
    short = re.sub(r"[^a-z0-9]+", "", simulation.rsplit(".", 1)[-1].lower())
    return f"{stamp}-{short}-{params.get('transport', 'poll')}"


def cmd_run(args: argparse.Namespace) -> int:
    docker = Docker()
    simulation = args.simulation if "." in args.simulation else SIMULATION_PACKAGE + args.simulation
    params, gradle_args = parse_props(args.gradle)
    started = now()
    rid = run_id(started, simulation, params)
    run_dir = RUNS / rid
    run_dir.mkdir(parents=True, exist_ok=False)
    if args.baseline:
        resolve_run(args.baseline)  # fails early on a wrong name
    job = server_job(started)
    if not job:
        warn(
            "Prometheus doesn't see the server (perf/up.sh, then perf/server.sh or perf/up.sh --container): "
            "the report will have no server numbers"
        )
    pg = postgres_container(docker)
    reset = db_reset(pg) if pg else False
    meta = {
        "id": rid,
        "label": args.label,
        "baseline": args.baseline,
        "started_at": started,
        "code": code_facts(),
        "machine": machine_facts(docker),
        "containers_before": containers_facts(docker),
        "load": {
            "simulation": simulation,
            "params": params,
            # PERF_GATLING=docker: Gatling inside the stand's network (perf/gatling.sh), for runs past ~10 000 sockets.
            "command": (
                ["perf/gatling.sh", simulation, *gradle_args]
                if os.environ.get("PERF_GATLING") == "docker"
                else ["./gradlew", ":perf:gatlingRun", "--simulation", simulation, *gradle_args]
            ),
        },
        "prometheus_job": job,
        "db_before": psql(pg, DB_SNAPSHOT_SQL) if pg else None,
    }
    (run_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"[report] run {rid}: {run_dir}")

    sampler = Sampler(docker, run_dir / "samples.jsonl")
    sampler.start()
    gradle = subprocess.Popen(
        meta["load"]["command"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    interrupted = False
    try:
        with (run_dir / "gatling.log").open("w") as log:
            assert gradle.stdout is not None
            for line in gradle.stdout:
                log.write(f"{now():.3f} {line}")
                log.flush()
                sys.stdout.write(line)
                sys.stdout.flush()
        code = gradle.wait()
    except KeyboardInterrupt:
        interrupted = True
        warn("interrupted: stopping Gatling, then the report of what ran")
        try:
            os.killpg(gradle.pid, signal.SIGINT)
            code = gradle.wait(timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            gradle.kill()
            code = gradle.wait()
    ended = now()
    # The last samples: the load is over, the containers' end state shows.
    time.sleep(SAMPLE_SECONDS)
    sampler.stop()

    meta.update(
        {
            "ended_at": ended,
            "gradle_exit_code": code,
            "interrupted": interrupted,
            "containers_after": containers_facts(docker),
        }
    )
    meta["db"] = db_report(pg if pg and postgres_container(docker) else None, meta.pop("db_before"), reset)
    console = parse_console(read_console(run_dir / "gatling.log"))
    if console.get("report"):
        meta["gatling_report"] = str(Path(console["report"]).parent)
    else:
        latest = latest_gatling_report(after=started)
        meta["gatling_report"] = str(latest) if latest else None
    (run_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    finish(run_dir)
    return code if not interrupted else 130


def finish(run_dir: Path) -> dict:
    report = collect(run_dir)
    if report.get("baseline"):
        try:
            base = load_report(report["baseline"])
            report["baseline_delta"] = compare_data(base, report)
            report["baseline_comparison"] = compare_md([base, report])
        except SystemExit as e:
            warn(str(e))
    write_run(run_dir, report)
    print()
    print((run_dir / "summary.md").read_text())
    print(f"[report] {run_dir / 'run.json'}")
    return report


def cmd_collect(args: argparse.Namespace) -> int:
    finish(resolve_run(args.run))
    return 0


def cmd_import(args: argparse.Namespace) -> int:
    """A run made without perf/run.sh: its window from the Gatling report's directory name and stats."""
    docker = Docker()
    report_dir = Path(args.report) if args.report else latest_gatling_report()
    if not report_dir or not (report_dir / "index.html").exists():
        raise SystemExit("No Gatling report: give its directory (perf/build/reports/gatling/<simulation>-<time>)")
    m = re.match(r"^([a-z0-9]+)-(\d{17})$", report_dir.name)
    if not m:
        raise SystemExit(f"Unexpected report directory name {report_dir.name}")
    started = datetime.datetime.strptime(m.group(2), "%Y%m%d%H%M%S%f").replace(tzinfo=datetime.timezone.utc).timestamp()
    ended = (report_dir / "index.html").stat().st_mtime
    simulation = next((s for s in DEFAULTS if s.lower() == m.group(1)), m.group(1))
    params = dict(kv.split("=", 1) for kv in args.param or [])
    params = {k: int(v) if re.match(r"^-?\d+$", v) else v for k, v in params.items()}
    rid = run_id(started, simulation, params) + "-imported"
    run_dir = RUNS / rid
    run_dir.mkdir(parents=True, exist_ok=True)
    meta = {
        "id": rid,
        "label": args.label,
        "baseline": args.baseline,
        "started_at": started,
        "ended_at": ended,
        "gatling_started_at": started,
        "code": code_facts(),
        "machine": machine_facts(docker),
        "containers_after": containers_facts(docker),
        "load": {"simulation": SIMULATION_PACKAGE + simulation, "params": params},
        "prometheus_job": server_job(ended),
        "gatling_report": str(report_dir),
        "imported": True,
        "note": "imported from a Gatling report: no console (the ladder's stop is unknown), no container samples, "
        "no database numbers",
    }
    (run_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    finish(run_dir)
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    index = RUNS / "index.jsonl"
    if not index.exists():
        print(f"No runs in {RUNS}")
        return 0
    rows = [json.loads(line) for line in index.read_text().splitlines() if line.strip()][-args.n :]
    for line in table(
        "run",
        "label",
        "commit",
        "load",
        "server",
        "status",
        "kept",
        "client p95/p99",
        "CPU мс/синхр",
        "КБ/синхр",
        "память МБ",
        "находки",
    ):
        print(line)
    for e in rows:
        params = load_params({k: v for k, v in (e.get("params") or {}).items() if k != "transport"})
        print(
            row(
                e["id"],
                e.get("label") or "",
                f"{e.get('commit')}{'*' if e.get('dirty') else ''}",
                f"{e.get('simulation')} {e.get('transport')} {params}",
                e.get("server"),
                e.get("status"),
                fmt(e.get("players_kept")),
                pair(e.get("client_p95_ms"), e.get("client_p99_ms")),
                fmt(e.get("cpu_ms_per_sync")),
                fmt(e.get("alloc_kb_per_sync")),
                fmt(e.get("mem_mb_max")),
                ", ".join(e.get("findings") or []),
            )
        )
    return 0


def cmd_show(args: argparse.Namespace) -> int:
    run_dir = resolve_run(args.run)
    target = run_dir / ("run.json" if args.json else "summary.md")
    print(target.read_text())
    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    print(compare_md([load_report(ref) for ref in args.runs]))
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="perf/report.py", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("run", help="run a simulation and report it")
    p.add_argument("--label", help="what this run checks, in a few words")
    p.add_argument("--baseline", help="the run to compare with (id, prefix, label, latest~N)")
    p.add_argument("simulation", help="OrdinaryGames, BigGame or a class name")
    p.add_argument("gradle", nargs=argparse.REMAINDER, help="-Pperf.<name>=<value> and other Gradle arguments")
    p.set_defaults(func=cmd_run)
    p = sub.add_parser("collect", help="build a run's report again (Prometheus keeps 14 days)")
    p.add_argument("run")
    p.set_defaults(func=cmd_collect)
    p = sub.add_parser("import", help="report a run made by ./gradlew :perf:gatlingRun directly")
    p.add_argument("report", nargs="?", help="the Gatling report's directory; the latest by default")
    p.add_argument("--param", action="append", help="name=value of the run's -Pperf.* (for the steps)")
    p.add_argument("--label")
    p.add_argument("--baseline")
    p.set_defaults(func=cmd_import)
    p = sub.add_parser("list", help="the runs, newest last")
    p.add_argument("-n", type=int, default=30)
    p.set_defaults(func=cmd_list)
    p = sub.add_parser("show", help="a run's digest, or with --json its whole report")
    p.add_argument("run", nargs="?", default="latest")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_show)
    p = sub.add_parser("compare", help="runs side by side, the change of the second against the first")
    p.add_argument("runs", nargs="+")
    p.set_defaults(func=cmd_compare)
    if argv and argv[0] == "run":
        # --label and --baseline may come anywhere: everything else after the simulation goes to Gradle.
        own, rest, i = [], [], 1
        while i < len(argv):
            if argv[i] in ("--label", "--baseline") and i + 1 < len(argv):
                own += argv[i : i + 2]
                i += 2
            elif argv[i].startswith(("--label=", "--baseline=")):
                own.append(argv[i])
                i += 1
            else:
                rest.append(argv[i])
                i += 1
        argv = ["run", *own, *rest]
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
