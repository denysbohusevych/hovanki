#!/usr/bin/env python3
"""The map tiles of the lab's server, recorded once and replayed (docs/perf.md, «Настоящие здания»).

    python3 perf/tiles.py                serve on 127.0.0.1:8090; a tile not recorded yet is fetched from OpenFreeMap
    python3 perf/tiles.py --offline      serve the recorded tiles only: a missing one is a 404
    python3 perf/tiles.py --bind 0.0.0.0 for the server in a container (perf/up.sh --container), which reaches this
                                         machine as host.docker.internal

The server (PERF_MAP=tiles, perf/compose.yaml) reads its TileJSON at /planet and the tiles it names: the buildings,
streets and ground of real places, as on production, so the memory and the CPU of the buildings are measured. The tiles
are kept in perf/tiles/ (git-ignored) under the map's version, so a run repeats the last one's map without the
network. Python 3.9+, the standard library only.
"""

import argparse
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://tiles.openfreemap.org/planet"
# OpenFreeMap refuses Python's own User-Agent.
USER_AGENT = "hovanki-perf-lab"
HERE = os.path.dirname(os.path.abspath(__file__))
TILE_PATH = re.compile(r"^/tiles/(\d+)/(\d+)/(\d+)\.pbf$")


class Recorder:
    def __init__(self, root, offline):
        self.root = root
        self.offline = offline
        self.lock = threading.Lock()
        self.fetched = 0
        self.served = 0
        self.tilejson = self._tilejson()
        upstream = self.tilejson["tiles"][0]
        self.template = upstream
        # The map's version, part of OpenFreeMap's tile URL: tiles of another week are kept apart.
        match = re.search(r"/planet/([^/]+)/\{z\}", upstream)
        self.version = match.group(1) if match else "unknown"

    def _tilejson(self):
        path = os.path.join(self.root, "planet.json")
        if self.offline:
            if not os.path.exists(path):
                sys.exit(f"No recorded map in {self.root}: run perf/tiles.py without --offline once")
            with open(path, encoding="utf-8") as file:
                return json.load(file)
        body = fetch(UPSTREAM)
        os.makedirs(self.root, exist_ok=True)
        with open(path, "wb") as file:
            file.write(body)
        return json.loads(body)

    def tilejson_for(self, host):
        """The TileJSON with the tile URL of this server, as the asking server reaches it."""
        local = dict(self.tilejson)
        local["tiles"] = [f"http://{host}/tiles/{{z}}/{{x}}/{{y}}.pbf"]
        return json.dumps(local).encode()

    def tile(self, z, x, y):
        """The tile's bytes as OpenFreeMap sent them, or None when it is not recorded and this server is offline."""
        path = os.path.join(self.root, self.version, z, x, f"{y}.pbf")
        if os.path.exists(path):
            with open(path, "rb") as file:
                body = file.read()
            with self.lock:
                self.served += 1
            return body
        if self.offline:
            return None
        body = fetch(self.template.replace("{z}", z).replace("{x}", x).replace("{y}", y))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        partial = path + ".part"
        with open(partial, "wb") as file:
            file.write(body)
        os.replace(partial, path)
        with self.lock:
            self.fetched += 1
            self.served += 1
        return body


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def handler(recorder):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 (the standard library's name)
            if self.path == "/planet":
                self.answer(200, "application/json", recorder.tilejson_for(self.headers.get("Host", "localhost")))
                return
            match = TILE_PATH.match(self.path)
            if not match:
                self.answer(404, "text/plain", b"not found")
                return
            try:
                body = recorder.tile(*match.groups())
            except (urllib.error.URLError, OSError) as error:
                self.answer(502, "text/plain", str(error).encode())
                return
            if body is None:
                self.answer(404, "text/plain", b"not recorded")
            else:
                self.answer(200, "application/x-protobuf", body)

        def answer(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):  # noqa: A002 (the standard library's name)
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description="The lab's map tiles, recorded once and replayed")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--offline", action="store_true", help="serve the recorded tiles only")
    parser.add_argument("--dir", default=os.path.join(HERE, "tiles"))
    args = parser.parse_args()
    recorder = Recorder(args.dir, args.offline)
    server = ThreadingHTTPServer((args.bind, args.port), handler(recorder))
    mode = "offline" if args.offline else "recording what is missing"
    print(f"Map {recorder.version} on http://{args.bind}:{args.port}/planet, {mode}; tiles in {args.dir}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        print(f"Served {recorder.served} tiles, fetched {recorder.fetched}", flush=True)


if __name__ == "__main__":
    main()
