// The map of the big games page (docs/adr/0010-big-games.md): a slippy map on a canvas, without libraries. It draws
// the tiles of the players' map, which the server hands over as JSON (the page loads nothing from other hosts), and the
// zone an admin draws with the pencil: a click puts a corner, a corner drags, the dot in the middle of a side drags a
// new corner out of it, a right click takes a corner away.

const TILE = 256;
const MAX_TILE_ZOOM = 14;
const MIN_ZOOM = 3;
const MAX_ZOOM = 19;
const HIT = 12;
const MAX_LOADS = 6;

const COLORS = {
  ground: "#f3efe2", green: "#d9e8c0", water: "#b9d3ef", building: "#dcd3c1", buildingLine: "#c7bca6",
  street: "#ffffff", streetCase: "#d2ccbb", major: "#fff3c4", zone: "rgba(107, 75, 255, 0.16)", zoneLine: "#6b4bff",
  corner: "#ffffff", ghost: "rgba(107, 75, 255, 0.5)",
};

/** Web Mercator: [lat], [lon] as pixels of the world at [zoom]. */
function project(lat, lon, zoom) {
  const scale = TILE * 2 ** zoom;
  const sin = Math.sin((Math.max(-85, Math.min(85, lat)) * Math.PI) / 180);
  return { x: ((lon + 180) / 360) * scale, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale };
}

function unproject(x, y, zoom) {
  const scale = TILE * 2 ** zoom;
  const n = Math.PI - (2 * Math.PI * y) / scale;
  return { lat: (180 / Math.PI) * Math.atan(Math.sinh(n)), lon: (x / scale) * 360 - 180 };
}

/** Square meters inside [points] ({lat, lon}), on a flat projection around the first one: as the server counts. */
export function areaSquareMeters(points) {
  if (points.length < 3) return 0;
  const R = 6371008.8;
  const origin = points[0];
  const cos = Math.cos((origin.lat * Math.PI) / 180);
  const xy = points.map((p) => [((p.lon - origin.lon) * Math.PI) / 180 * R * cos, ((p.lat - origin.lat) * Math.PI) / 180 * R]);
  let sum = 0;
  for (let i = 0; i < xy.length; i++) {
    const [ax, ay] = xy[i];
    const [bx, by] = xy[(i + 1) % xy.length];
    sum += ax * by - bx * ay;
  }
  return Math.abs(sum) / 2;
}

export class ZoneMap {
  /**
   * [canvas]: where to draw; [loadTile](z, x, y): a promise of the tile's JSON; [points]: the zone's corners
   * ({lat, lon}), changed in place; [onChange]: called after every change of the zone.
   */
  constructor(canvas, { loadTile, center, zoom = 14, points = [], editable = true, onChange = () => {} }) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.loadTile = loadTile;
    this.center = center;
    this.zoom = zoom;
    this.points = points;
    this.editable = editable;
    this.pencil = editable && points.length === 0;
    this.onChange = onChange;
    this.tiles = new Map();
    this.queue = [];
    this.loading = 0;
    this.frame = 0;
    this.listen();
    this.resize();
    new ResizeObserver(() => this.resize()).observe(canvas);
  }

  // View

  resize() {
    const ratio = window.devicePixelRatio || 1;
    const { width, height } = this.canvas.getBoundingClientRect();
    this.width = width;
    this.height = height;
    this.canvas.width = Math.round(width * ratio);
    this.canvas.height = Math.round(height * ratio);
    this.context.setTransform(ratio, 0, 0, ratio, 0, 0);
    if (this.fitPending && width > 0) this.fit();
    this.redraw();
  }

  /** Screen pixels of [point] ({lat, lon}). */
  screen(point) {
    const c = project(this.center.lat, this.center.lon, this.zoom);
    const p = project(point.lat, point.lon, this.zoom);
    return { x: p.x - c.x + this.width / 2, y: p.y - c.y + this.height / 2 };
  }

  /** The place under screen pixels [x], [y]. */
  place(x, y) {
    const c = project(this.center.lat, this.center.lon, this.zoom);
    return unproject(c.x + x - this.width / 2, c.y + y - this.height / 2, this.zoom);
  }

  zoomAt(zoom, x = this.width / 2, y = this.height / 2) {
    const next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    const anchor = this.place(x, y);
    this.zoom = next;
    const moved = this.screen(anchor);
    this.center = this.place(this.width / 2 + moved.x - x, this.height / 2 + moved.y - y);
    this.redraw();
  }

  goTo(center, zoom = this.zoom) {
    this.center = center;
    this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    this.redraw();
  }

  /** The whole zone in view. */
  fit() {
    if (this.points.length < 2) return;
    // Not laid out yet: once the canvas has its size.
    this.fitPending = !this.width;
    if (this.fitPending) return;
    const lats = this.points.map((p) => p.lat);
    const lons = this.points.map((p) => p.lon);
    this.center = { lat: (Math.min(...lats) + Math.max(...lats)) / 2, lon: (Math.min(...lons) + Math.max(...lons)) / 2 };
    for (let zoom = MAX_ZOOM; zoom >= MIN_ZOOM; zoom -= 0.25) {
      this.zoom = zoom;
      const corners = this.points.map((p) => this.screen(p));
      const inside = corners.every((p) => p.x > 30 && p.x < this.width - 30 && p.y > 30 && p.y < this.height - 30);
      if (inside) break;
    }
    this.redraw();
  }

  setPencil(on) {
    this.pencil = this.editable && on;
    this.canvas.classList.toggle("pencil", this.pencil);
  }

  setPoints(points) {
    this.points.splice(0, this.points.length, ...points);
    this.changed();
  }

  changed() {
    this.redraw();
    this.onChange(this.points);
  }

  // Tiles

  tile(z, x, y) {
    const key = `${z}/${x}/${y}`;
    const known = this.tiles.get(key);
    if (known !== undefined) return known;
    this.tiles.set(key, null);
    this.queue.push([key, z, x, y]);
    this.pump();
    return null;
  }

  pump() {
    while (this.loading < MAX_LOADS && this.queue.length) {
      const [key, z, x, y] = this.queue.pop();
      this.loading++;
      this.loadTile(z, x, y)
        .then((tile) => this.tiles.set(key, tile), () => this.tiles.set(key, false))
        .finally(() => {
          this.loading--;
          this.pump();
          this.redraw();
        });
    }
  }

  // Drawing

  redraw() {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.draw();
    });
  }

  draw() {
    const g = this.context;
    g.fillStyle = COLORS.ground;
    g.fillRect(0, 0, this.width, this.height);
    const tileZoom = Math.max(0, Math.min(MAX_TILE_ZOOM, Math.floor(this.zoom)));
    const size = TILE * 2 ** (this.zoom - tileZoom);
    const c = project(this.center.lat, this.center.lon, this.zoom);
    const left = c.x - this.width / 2;
    const top = c.y - this.height / 2;
    const count = 2 ** tileZoom;
    const visible = [];
    for (let x = Math.floor(left / size); x <= Math.floor((left + this.width) / size); x++) {
      for (let y = Math.floor(top / size); y <= Math.floor((top + this.height) / size); y++) {
        if (y < 0 || y >= count) continue;
        const wrapped = ((x % count) + count) % count;
        const tile = this.tile(tileZoom, wrapped, y);
        if (tile) visible.push({ tile, x0: x * size - left, y0: y * size - top, scale: size / tile.extent });
      }
    }
    const rings = (feature, t) => {
      for (const ring of feature) {
        for (let i = 0; i < ring.length; i += 2) {
          const px = t.x0 + ring[i] * t.scale;
          const py = t.y0 + ring[i + 1] * t.scale;
          if (i === 0) g.moveTo(px, py); else g.lineTo(px, py);
        }
        g.closePath();
      }
    };
    const fill = (layer, color, line) => {
      g.fillStyle = color;
      if (line) { g.strokeStyle = line; g.lineWidth = 0.5; }
      for (const t of visible) {
        for (const feature of t.tile[layer] ?? []) {
          g.beginPath();
          rings(feature, t);
          g.fill("evenodd");
          if (line) g.stroke();
        }
      }
    };
    fill("green", COLORS.green);
    fill("water", COLORS.water);
    if (this.zoom >= 13) fill("buildings", COLORS.building, this.zoom >= 16 ? COLORS.buildingLine : null);
    const streets = (major, color, width) => {
      g.strokeStyle = color;
      g.lineWidth = width;
      g.lineCap = "round";
      g.lineJoin = "round";
      for (const t of visible) {
        g.beginPath();
        for (const street of t.tile.streets ?? []) {
          if (street.major !== major) continue;
          const p = street.points;
          for (let i = 0; i < p.length; i += 2) {
            const px = t.x0 + p[i] * t.scale;
            const py = t.y0 + p[i + 1] * t.scale;
            if (i === 0) g.moveTo(px, py); else g.lineTo(px, py);
          }
        }
        g.stroke();
      }
    };
    const width = Math.max(1, 2 ** (this.zoom - 15) * 6);
    streets(false, COLORS.streetCase, width + 2);
    streets(true, COLORS.streetCase, width * 1.6 + 2);
    streets(false, COLORS.street, width);
    streets(true, COLORS.major, width * 1.6);
    this.drawZone(g);
  }

  drawZone(g) {
    if (!this.points.length) return;
    const corners = this.points.map((p) => this.screen(p));
    g.beginPath();
    corners.forEach((p, i) => (i === 0 ? g.moveTo(p.x, p.y) : g.lineTo(p.x, p.y)));
    if (corners.length > 2) {
      g.closePath();
      g.fillStyle = COLORS.zone;
      g.fill();
    }
    g.strokeStyle = COLORS.zoneLine;
    g.lineWidth = 2.5;
    g.stroke();
    if (!this.editable) return;
    if (corners.length > 2) {
      g.fillStyle = COLORS.ghost;
      for (const mid of this.middles(corners)) {
        g.beginPath();
        g.arc(mid.x, mid.y, 4, 0, 2 * Math.PI);
        g.fill();
      }
    }
    for (const p of corners) {
      g.beginPath();
      g.arc(p.x, p.y, 6, 0, 2 * Math.PI);
      g.fillStyle = COLORS.corner;
      g.fill();
      g.lineWidth = 2;
      g.stroke();
    }
  }

  middles(corners) {
    return corners.map((p, i) => {
      const q = corners[(i + 1) % corners.length];
      return { x: (p.x + q.x) / 2, y: (p.y + q.y) / 2, after: i };
    });
  }

  // Pointer

  hit(x, y) {
    const corners = this.points.map((p) => this.screen(p));
    const corner = corners.findIndex((p) => Math.hypot(p.x - x, p.y - y) < HIT);
    if (corner >= 0) return { corner };
    if (corners.length > 2) {
      const middle = this.middles(corners).find((m) => Math.hypot(m.x - x, m.y - y) < HIT);
      if (middle) return { middle: middle.after };
    }
    return null;
  }

  listen() {
    const canvas = this.canvas;
    let drag = null;
    const at = (event) => {
      const box = canvas.getBoundingClientRect();
      return { x: event.clientX - box.left, y: event.clientY - box.top };
    };
    canvas.addEventListener("pointerdown", (event) => {
      if (event.button !== 0) return;
      const p = at(event);
      const hit = this.editable ? this.hit(p.x, p.y) : null;
      if (hit?.middle !== undefined) {
        this.points.splice(hit.middle + 1, 0, this.place(p.x, p.y));
        drag = { corner: hit.middle + 1, start: p, moved: true };
      } else if (hit) {
        drag = { corner: hit.corner, start: p, moved: false };
      } else {
        drag = { pan: this.center, start: p, moved: false };
      }
      canvas.setPointerCapture(event.pointerId);
    });
    canvas.addEventListener("pointermove", (event) => {
      if (!drag) return;
      const p = at(event);
      if (Math.hypot(p.x - drag.start.x, p.y - drag.start.y) > 4) drag.moved = true;
      if (!drag.moved) return;
      if (drag.corner !== undefined) {
        this.points[drag.corner] = this.place(p.x, p.y);
      } else {
        const start = project(drag.pan.lat, drag.pan.lon, this.zoom);
        this.center = unproject(start.x - (p.x - drag.start.x), start.y - (p.y - drag.start.y), this.zoom);
      }
      this.redraw();
    });
    canvas.addEventListener("pointerup", (event) => {
      if (!drag) return;
      const p = at(event);
      if (drag.corner !== undefined && drag.moved) this.changed();
      else if (!drag.moved && drag.corner === undefined && this.pencil) {
        this.points.push(this.place(p.x, p.y));
        this.changed();
      }
      drag = null;
    });
    canvas.addEventListener("pointercancel", () => { drag = null; });
    canvas.addEventListener("contextmenu", (event) => {
      event.preventDefault();
      if (!this.editable) return;
      const p = at(event);
      const hit = this.hit(p.x, p.y);
      if (hit?.corner !== undefined) {
        this.points.splice(hit.corner, 1);
        this.changed();
      }
    });
    canvas.addEventListener("wheel", (event) => {
      event.preventDefault();
      const p = at(event);
      this.zoomAt(this.zoom - event.deltaY * (event.deltaMode === 1 ? 0.05 : 0.002), p.x, p.y);
    }, { passive: false });
  }
}
