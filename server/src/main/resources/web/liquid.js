// Liquid glass lens for the phone page: the web version of the lens in the Android app's
// Glass.kt (Kyant's Backdrop library). Each glass element gets its own SVG filter, used inside
// backdrop-filter, that bends what's behind it in a band around its rounded edge, like thick
// glass, with the red, green and blue channels bent by slightly different amounts for a faint
// colour fringe at the rim. The refraction map is drawn to the element's exact size and corner
// radius. Technique from korbenmade's glass.js, generalised from a pill to any rounded rect.
//
// Only Chromium can put an SVG filter inside backdrop-filter; other browsers keep the frosted
// glass from style.css. If the phone can't keep a smooth frame rate, the fringe goes first,
// then the lens on the big cards, then the lens everywhere.

const NS = "http://www.w3.org/2000/svg";
const canRefract = () =>
  navigator.userAgentData?.brands?.some((b) => /Chromium/.test(b.brand)) ?? /Chrome\//.test(navigator.userAgent);

// Matching Glass.kt: glassCard lens(16dp, 24dp), glassControl lens(10dp, 18dp).
const KINDS = {
  card: { band: 16, amount: 24 },
  control: { band: 10, amount: 18 },
};

let quality = 2; // 2: lens with fringe, 1: lens without fringe, 0: cards plain, -1: no lens
let svg = null;
let uid = 0;
const items = new Map(); // element -> { id, kind, w, h, r }

/** Refraction map for a w×h rounded rect with corner radius r. 128 = no shift. */
function drawMap(w, h, r, band) {
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const ctx = c.getContext("2d");
  const img = ctx.createImageData(w, h);
  const d = img.data;
  const hx = w / 2, hy = h / 2;
  r = Math.min(r, hx, hy);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      // Signed distance to the rounded rect, and the outward normal at that point.
      const px = x + 0.5 - hx, py = y + 0.5 - hy;
      const qx = Math.abs(px) - (hx - r), qy = Math.abs(py) - (hy - r);
      let nx, ny, dist;
      if (qx > 0 && qy > 0) {
        const l = Math.hypot(qx, qy) || 1;
        dist = l - r; nx = (qx / l) * Math.sign(px); ny = (qy / l) * Math.sign(py);
      } else if (qx > qy) {
        dist = qx - r; nx = Math.sign(px); ny = 0;
      } else {
        dist = qy - r; nx = 0; ny = Math.sign(py);
      }
      const depth = -dist; // 0 at the edge, growing inward
      let ox = 0, oy = 0;
      if (depth > 0 && depth < band) {
        const t = 1 - depth / band;
        const k = t * t; // strongest at the rim
        ox = -nx * k; oy = -ny * k; // sample inward: what's near the edge is pulled toward the middle
      }
      const i = (y * w + x) * 4;
      d[i] = 128 + ox * 127; d[i + 1] = 128 + oy * 127; d[i + 2] = 128; d[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}

const node = (name, attrs) => {
  const n = document.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  return n;
};

function buildFilter(el, it) {
  const box = el.getBoundingClientRect();
  const w = Math.max(1, Math.round(box.width)), h = Math.max(1, Math.round(box.height));
  const r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0;
  const { band, amount } = KINDS[it.kind];
  const key = `${w}x${h}x${r}x${quality}`;
  if (it.key === key) return;
  it.key = key;
  svg.querySelector(`#${it.id}`)?.remove();
  const f = node("filter", { id: it.id, x: 0, y: 0, width: w, height: h, filterUnits: "userSpaceOnUse", "color-interpolation-filters": "sRGB" });
  f.append(node("feImage", { href: drawMap(w, h, r, band), x: 0, y: 0, width: w, height: h, preserveAspectRatio: "none", result: "map" }));
  if (quality >= 2) {
    // Each channel bends a little more than the last, leaving a faint fringe at the rim.
    [["r", amount, "1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0"],
     ["g", amount * 1.08, "0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0"],
     ["b", amount * 1.16, "0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0"]].forEach(([c, s, m]) => {
      f.append(node("feDisplacementMap", { in: "SourceGraphic", in2: "map", scale: s.toFixed(1), xChannelSelector: "R", yChannelSelector: "G", result: `d${c}` }));
      f.append(node("feColorMatrix", { in: `d${c}`, type: "matrix", values: m, result: c }));
    });
    f.append(node("feBlend", { in: "r", in2: "g", mode: "screen", result: "rg" }));
    f.append(node("feBlend", { in: "rg", in2: "b", mode: "screen" }));
  } else {
    f.append(node("feDisplacementMap", { in: "SourceGraphic", in2: "map", scale: amount, xChannelSelector: "R", yChannelSelector: "G" }));
  }
  svg.append(f);
}

function apply(el, it) {
  const on = quality >= 1 || (quality === 0 && it.kind === "control");
  el.classList.toggle("lg-lens", on);
  if (on) {
    buildFilter(el, it);
    el.style.setProperty("--lg-filter", `url(#${it.id})`);
  } else {
    el.style.removeProperty("--lg-filter");
  }
}

const resize = new ResizeObserver((entries) => {
  for (const e of entries) {
    const it = items.get(e.target);
    if (it && e.target.classList.contains("lg-lens")) buildFilter(e.target, it);
  }
});

/** Gives [el] the lens. [kind] is "card" or "control". */
export function lens(el, kind = "card") {
  if (!svg || items.has(el)) return;
  const it = { id: `lg${++uid}`, kind, key: "" };
  items.set(el, it);
  apply(el, it);
  resize.observe(el);
}

/** Starts the lens on every [selector] → kind pair; call once the page is laid out. */
export function liquidGlass(targets) {
  if (!canRefract() || matchMedia("(prefers-reduced-transparency: reduce)").matches) return;
  svg = node("svg", { width: 0, height: 0, "aria-hidden": "true" });
  svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden";
  document.body.append(svg);
  for (const [selector, kind] of targets) document.querySelectorAll(selector).forEach((el) => lens(el, kind));
  watchFrames();
}

// Measure smoothness while scrolling and settle at the best quality the phone holds.
function watchFrames() {
  let frames = 0, slow = 0, last = 0, running = false;
  const sample = (t) => {
    if (last) { frames++; if (t - last > 24) slow++; }
    last = t;
    if (frames < 90) requestAnimationFrame(sample);
    else {
      running = false;
      if (slow / frames > 0.25 && quality > -1) {
        quality--;
        for (const [el, it] of items) { it.key = ""; apply(el, it); }
      }
    }
  };
  const start = () => {
    if (running || quality < 0) return;
    running = true; frames = 0; slow = 0; last = 0;
    requestAnimationFrame(sample);
  };
  setTimeout(start, 1500);
  let wait = 0;
  addEventListener("scroll", () => { clearTimeout(wait); wait = setTimeout(start, 120); }, { passive: true });
}
