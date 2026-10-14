#!/usr/bin/env python3
"""Tests of perf/report.py's readers: python3 perf/report_test.py (not part of `check`: the lab is local only)."""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ in perf/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import report  # noqa: E402


class SimulationLog:
    """Writes Gatling's binary simulation.log the way its LogFileDataWriter does (3.10+)."""

    def __init__(self, start_ms: int):
        self.out = bytearray()
        self.ids: dict[str, int] = {}
        self.out += b"\x00"
        self.string("3.16.0")
        self.string("app.hovanki.perf.OrdinaryGames")
        self.out += struct.pack(">q", start_ms)
        self.string("")
        self.out += struct.pack(">i", 1)
        self.string("Ordinary games")
        self.out += struct.pack(">i", 0)

    def string(self, value: str) -> None:
        latin = all(ord(c) < 256 for c in value)
        raw = value.encode("latin-1") if latin else value.encode("utf-16-le")
        self.out += struct.pack(">i", len(raw))
        if raw:
            self.out += raw + (b"\x00" if latin else b"\x01")

    def cached(self, value: str) -> None:
        if value in self.ids:
            self.out += struct.pack(">i", -self.ids[value])
        else:
            self.ids[value] = len(self.ids) + 1
            self.out += struct.pack(">i", self.ids[value])
            self.string(value)

    def user(self, start: bool, at: int) -> None:
        self.out += b"\x02" + struct.pack(">i", 0) + (b"\x01" if start else b"\x00") + struct.pack(">i", at)

    def request(self, name: str, start: int, end: int, ok: bool = True, message: str = "") -> None:
        self.out += b"\x01" + struct.pack(">i", 0)
        self.cached(name)
        self.out += struct.pack(">ii", start, end) + (b"\x01" if ok else b"\x00")
        self.cached(message)

    def error(self, message: str, at: int) -> None:
        self.out += b"\x04"
        self.cached(message)
        self.out += struct.pack(">i", at)


class ClientLogTest(unittest.TestCase):
    def test_reads_requests_users_and_errors(self):
        log = SimulationLog(start_ms=1_000_000_000_000)
        log.user(True, 100)
        log.user(True, 200)
        for i in range(100):
            log.request("sync SEEKING", 1_000 + i * 10, 1_000 + i * 10 + i + 1)  # 1..100 ms
        log.request("sync SEEKING", 2_000, 2_500, ok=False, message="status.find.is(200), but actually found 503")
        log.request("join", 1_500, 1_520)
        log.request("sync LOBBY", 3_000, 3_004, ok=False, message="Ошибка сокета")
        log.error("crashed: no walker", 3_100)
        log.user(False, 4_000)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "simulation.log"
            path.write_bytes(bytes(log.out))
            client = report.ClientLog(path)
        start = 1_000_000_000.0
        self.assertEqual(client.version, "3.16.0")
        syncs = client.window(start, start + 10, report.SYNC_REQUESTS)
        self.assertEqual(syncs["count"], 102)
        self.assertEqual(syncs["ko"], 2)
        self.assertEqual(syncs["p50_ms"], 50)
        self.assertEqual(syncs["p95_ms"], 95)
        self.assertEqual(syncs["p99_ms"], 99)
        self.assertEqual(syncs["max_ms"], 100)
        self.assertEqual(client.window(start, start + 10, "^join$")["count"], 1)
        errors = {e["message"]: e["count"] for e in client.errors(start, start + 10)}
        self.assertEqual(errors, {"status.find.is(200), but actually found 503": 1, "Ошибка сокета": 1})
        self.assertEqual(client.crashes, {"crashed: no walker": 1})
        self.assertEqual(client.most_users(start, start + 3), 2)
        self.assertEqual(client.most_users(start + 5, start + 6), 1)
        self.assertIsNone(client.window(start + 5, start + 9))


class ConsoleTest(unittest.TestCase):
    LINES = [
        (100.0, "Simulation app.hovanki.perf.OrdinaryGames started..."),
        (100.2, "240 games of 30 over socket, 6 levels of 40 games, 80s each"),
        (121.0, "Level 1 of 6: 1200 players"),
        (196.0, "The ladder stops at 2139 players (641 syncs/s) the sync's p95 is over 50 ms: 12.1 % were slower"),
        (199.0, "---- Errors --------------------------------------------------------------------------"),
        (199.0, "> status.find.is(200), but actually found 503                     12 (60.00%)"),
        (199.0, "> j.n.ConnectException: Connection refused                         8 (40.00%)"),
        (199.0, "======================================================================================"),
        (200.0, "Simulation app.hovanki.perf.OrdinaryGames completed in 99 seconds"),
        (
            201.0,
            "Reports generated, please open the following file: file:///x/perf/build/reports/gatling/o-1/index.html",
        ),
    ]

    def test_reads_the_ladder_and_the_errors(self):
        console = report.parse_console(self.LINES)
        self.assertEqual(console["start"], 100.0)
        self.assertEqual(console["plan"]["levels"], 6)
        self.assertEqual(console["plan"]["level_seconds"], 80)
        self.assertEqual(console["levels"], [{"level": 1, "players": 1200, "at": 121.0}])
        self.assertEqual(console["stop"]["players"], 2139)
        self.assertEqual(console["stop"]["syncs_per_s"], 641)
        self.assertEqual([e["count"] for e in console["errors"]], [12, 8])
        self.assertEqual(console["completed"], 200.0)
        self.assertEqual(console["report"], "/x/perf/build/reports/gatling/o-1/index.html")

    def test_steps_end_at_the_stop(self):
        console = report.parse_console(self.LINES)
        steps = report.steps_of(console, {"rampSeconds": 20}, "OrdinaryGames", 100.2, 200.0)
        self.assertEqual([s["step"] for s in steps], [1, 2])
        self.assertEqual(steps[0]["hold"], [121.0 + report.SETTLE_SECONDS, 180.2])
        self.assertEqual(steps[0]["players_planned"], 1200)
        self.assertTrue(steps[1]["stopped_here"])
        # Broken while its players were coming: the half minute before the stop.
        self.assertTrue(steps[1]["during_ramp"])
        self.assertEqual(steps[1]["hold"], [180.2, 196.0])

    def test_a_run_without_a_ladder_is_one_step_after_the_ramp(self):
        steps = report.steps_of({}, {"rampSeconds": 30, "games": 2}, "OrdinaryGames", 0.0, 300.0)
        self.assertEqual(len(steps), 1)
        self.assertEqual(steps[0]["hold"], [30 + report.SETTLE_SECONDS, 300.0])
        self.assertEqual(steps[0]["players_planned"], 60)


def a_report(rid: str, cpu_ms: float, p95: float, steps: list[dict]) -> dict:
    return {
        "id": rid,
        "label": None,
        "code": {"commit": "abc"},
        "server": {"mode": "container", "cpus": 2, "mem_limit_mb": 640, "heap_max_mb": 464, "old_gen_max_mb": 320},
        "load": {"simulation": "app.hovanki.perf.OrdinaryGames", "params": {"transport": "socket"}},
        "outcome": {"status": "ladder_stopped", "players_kept": 1800},
        "whole": {"server": {"cpu_ms_per_sync": cpu_ms, "sync_p95_ms": p95}, "client": {"p95_ms": 3}},
        "steps": steps,
    }


class CompareAndFindingsTest(unittest.TestCase):
    def test_compares_fields_and_equal_load(self):
        base = a_report("a", 0.5, 1.0, [{"step": 1, "players_planned": 1200, "server": {"cpu_ms_per_sync": 0.5}}])
        run = a_report(
            "b",
            0.4,
            1.0,
            [
                {"step": 1, "players_planned": 1200, "server": {"cpu_ms_per_sync": 0.25}},
                {"step": 2, "players_planned": 2400, "server": {}},
            ],
        )
        data = report.compare_data(base, run)
        self.assertEqual(data["fields"]["cpu_ms_per_sync"], {"baseline": 0.5, "run": 0.4, "change_pct": -20})
        self.assertEqual(list(data["equal_load"]), ["1200"])
        self.assertEqual(data["equal_load"]["1200"]["cpu_ms_per_sync"]["change_pct"], -50)
        self.assertIn("| CPU мс/синхр | 0.5 | 0.4 | -20 % |", report.compare_md([base, run]))

    def test_finds_the_known_patterns(self):
        r = a_report(
            "a",
            0.5,
            1.0,
            [
                {
                    "step": 2,
                    "server": {
                        "players": 2400,
                        "gc_major": 2,
                        "gc_major_max_pause_ms": 450,
                        "gc_pause_pct": 7,
                        "heap_after_gc_mb": 300,
                        "sync_p95_ms": 1,
                        "cpu_vcpu": 1.9,
                    },
                    "client": {"p95_ms": 150, "ko_pct": 0},
                    "containers": {"server": {"mem_mb_max": 600, "mem_limit_mb": 640}},
                }
            ],
        )
        r["containers"] = {"server": {"state": "exited", "oom_killed": True, "exit_code": 137}}
        codes = {f["code"] for f in report.findings(r)}
        self.assertEqual(
            codes,
            {"oom_killed", "full_gc", "gc_overhead", "heap_pressure", "memory_near_limit", "cpu_saturated", "queueing"},
        )


if __name__ == "__main__":
    unittest.main()
