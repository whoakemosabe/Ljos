import { startSky, skyFor } from "/aurora.js";

// The lobby screen: /lobby/{place}. One screen, refreshed every minute, nothing to tap.

const path = location.pathname.replace(/\/+$/, "");
const PLACE_ID = path.startsWith("/lobby/") ? decodeURIComponent(path.split("/")[2] || "") : "njardvik";

const CHANCE = {
  EXCELLENT: "Excellent",
  GOOD: "Good chance",
  POSSIBLE: "Possible",
  UNLIKELY: "Unlikely",
  NOT_TONIGHT: "Not tonight",
};

const HOUR = 3600000;
const MIN = 60000;
const $ = (id) => document.getElementById(id);
const el = (tag, attrs = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") n.className = v;
    // Through the CSSOM, which the page's strict CSP allows (a style attribute would be blocked).
    else if (k === "style") n.style.cssText = v;
    else n.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) n.append(kid instanceof Node ? kid : String(kid));
  return n;
};

// Small line icons for the badges, drawn with the badge's own colour.
const ICONS = {
  moon: '<path d="M13.5 10.2A5.6 5.6 0 0 1 5.8 2.5a5.8 5.8 0 1 0 7.7 7.7Z"/>',
  dark: '<circle cx="8" cy="8" r="5.5"/><path class="solid" d="M8 2.5v11a5.5 5.5 0 0 0 0-11Z"/>',
  cloud: '<path d="M4.5 12.5h7a3 3 0 0 0 .3-6 4 4 0 0 0-7.6 1.2 2.4 2.4 0 0 0 .3 4.8Z"/>',
  sun: '<circle cx="8" cy="8" r="3"/><path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1"/>',
  bolt: '<path d="M9 1.5 3.5 9H8l-1 5.5L12.5 7H8l1-5.5Z"/>',
  wind: '<path d="M2 6h8.5a2 2 0 1 0-2-2M2 10h10.5a2 2 0 1 1-2 2"/>',
  feels: '<path d="M8 2v7.2a2.6 2.6 0 1 0 0 0V2Z"/><path d="M6.5 3.5h3M6.5 6h3"/>',
};
function icon(name) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = ICONS[name] || "";
  return svg;
}
const pill = (content, { tint, cls = "", icon: ic } = {}) => {
  const p = el("span", { class: `pill ${cls}`.trim() }, ic ? icon(ic) : null, content);
  if (tint) applyTint(p, tint);
  return p;
};

let data = null;
const zone = () => (data && data.tz) || "Atlantic/Reykjavik";
const fmt = (ms, opts) => { try { return new Intl.DateTimeFormat("en-GB", { timeZone: zone(), ...opts }).format(ms); } catch { return ""; } };
const hhmm = (ms) => fmt(ms, { hour: "2-digit", minute: "2-digit", hour12: false });
const hourLabel = (ms) => fmt(ms, { hour: "2-digit", hour12: false });
const weekday = (ms) => fmt(ms, { weekday: "short" });
const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);
const signed = (v) => (v < 0 ? "−" : "+") + Math.abs(v).toFixed(1);
const deg = (c) => { const r = Math.round(c); return `${r < 0 ? "−" : ""}${Math.abs(r)}°`; };
const drive = (km) => Math.max(2, Math.round((km * 1.35) / (km * 1.35 < 6 ? 40 : 70) * 60 + 2));
function span(ms) {
  const m = Math.max(0, Math.round(ms / MIN));
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}
function ago(ms) {
  if (!ms) return "never";
  const m = Math.max(0, Math.round((Date.now() - ms) / MIN));
  return m < 1 ? "just now" : m < 60 ? `${m} min ago` : `${Math.floor(m / 60)} h ago`;
}

/** Same ramp as the app: slate, teal, green, violet. Returns [r, g, b]. */
function scoreRgb(score) {
  const s = Math.min(100, Math.max(0, score));
  const lerp = (a, b, f) => a.map((v, i) => Math.round(v + (b[i] - v) * f));
  const slate = [92, 110, 138], teal = [96, 196, 196], green = [121, 232, 176], violet = [185, 166, 255];
  return s < 40 ? lerp(slate, teal, s / 40) : s < 80 ? lerp(teal, green, (s - 40) / 40) : lerp(green, violet, (s - 80) / 20);
}
const rgb = (c, a = 1) => `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${a})`;
const scoreColor = (score) => rgb(scoreRgb(score));
const kpScore = (kp) => Math.min(100, (kp / 6) * 100);
/** A badge in a score's colour: tinted fill, stronger border, coloured text. */
function applyTint(node, score) {
  const c = scoreRgb(score);
  node.style.color = rgb(c);
  node.style.background = rgb(c, 0.14);
  node.style.borderColor = rgb(c, 0.45);
}

// ---------------------------------------------------------------------------------------------

const sky = startSky($("sky"), { scale: 0.5 });
const hours = () => data?.tonight?.hours || [];

async function load() {
  try {
    const r = await fetch(`/v1/places/${encodeURIComponent(PLACE_ID)}`, { headers: { Accept: "application/json" }, cache: "no-cache" });
    if (r.status === 404) return problem("This screen's place isn't set up. The address should end in /lobby/ and a place name.");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    data = await r.json();
    $("problem").hidden = true;
    render();
  } catch {
    problem(data ? "Can't reach the forecast right now. Showing the last one." : "Reading the sky. The forecast feeds are busy, trying again shortly.");
  }
}
function problem(text) { $("problem").textContent = text; $("problem").hidden = false; }

function render() {
  if (!data) return;
  renderHeader();
  renderHero();
  renderNow();
  renderSpots();
  renderOutlook();
  renderDress();
  renderHours();
  renderLookUp();
  sky.set(skyFor(data.current?.hour, hours(), false));
}

function renderHeader() {
  const p = data.place;
  $("brand").textContent = p.title || "Ljós";
  $("place").textContent = `Aurora forecast for ${p.name}`;
  const live = $("live");
  live.classList.toggle("stale", !!data.stale);
  $("live-text").textContent = data.stale ? "Data delayed" : "Live";
  $("updated").textContent = `Updated ${ago(data.updatedAt)}`;
}

function renderHero() {
  const n = data.tonight;
  const peak = n.peak;
  const now = data.now;
  const cur = data.current;
  if (!peak) {
    setScore(null);
    $("eyebrow").textContent = "Tonight";
    $("chance").textContent = "Too bright for aurora";
    $("peak-pill").hidden = true;
    $("peak-line").textContent = "The nights here are too light this time of year.";
    $("scale").hidden = true;
    $("conditions").replaceChildren();
    return;
  }
  setScore(peak.score);
  $("eyebrow").textContent = "Tonight's best hour";
  const chance = $("chance");
  chance.textContent = CHANCE[peak.chance] || "";
  applyTint(chance, peak.score);
  const pp = $("peak-pill");
  pp.hidden = false;
  pp.replaceChildren(el("span", { class: "muted" }, "Best at"), hhmm(peak.time));

  let line;
  if (n.darkFrom && now < n.darkFrom) line = `Dark in ${span(n.darkFrom - now)}. Best hour in ${span(peak.time - now)}.`;
  else if (now < peak.time) line = `The best hour is ${span(peak.time - now)} away.`;
  else if (n.darkUntil && now < n.darkUntil) line = `Past the best hour. Still dark until ${hhmm(n.darkUntil)}.`;
  else line = "";
  $("peak-line").textContent = line;

  // Scale: tonight's best (triangle) and right now (ring).
  $("scale").hidden = false;
  $("scale-best").style.left = `${peak.score}%`;
  $("scale-now").style.left = `${cur.score}%`;

  // Conditions: darkness, moon, activity, clouds.
  const chips = [];
  if (n.darkFrom && n.darkUntil) {
    chips.push(cur.isDark
      ? pill([el("span", { class: "muted" }, "Dark until"), hhmm(n.darkUntil)], { icon: "dark" })
      : pill([el("span", { class: "muted" }, "Dark from"), hhmm(n.darkFrom)], { icon: "sun" }));
  }
  const m = data.moon;
  if (m) {
    const lit = `${Math.round(m.illumination * 100)}%`;
    const up = cur.hour && cur.hour.moonAlt > 0;
    const when = up ? (m.set ? `sets ${hhmm(m.set)}` : "up all night") : (m.rise ? `rises ${hhmm(m.rise)}` : "down tonight");
    chips.push(pill([el("span", { class: "muted" }, "Moon"), lit, el("span", { class: "muted" }, when)], { icon: "moon" }));
  }
  if (cur.hour?.kp != null) chips.push(pill([el("span", { class: "muted" }, "Kp"), cur.hour.kp.toFixed(1)], { icon: "bolt", tint: kpScore(cur.hour.kp) }));
  $("conditions").replaceChildren(...chips);
}

function setScore(score) {
  const s = $("score");
  s.textContent = score == null ? "–" : String(score);
  s.style.color = score == null ? "" : scoreColor(score);
}

function renderNow() {
  const c = data.current;
  const n = data.tonight;
  const state = $("now-state");
  state.style.cssText = "";
  if (c.lookUp) { state.textContent = "Look up now"; applyTint(state, 85); }
  else if (!c.isDark) state.textContent = n.darkFrom && data.now < n.darkFrom ? `Dark from ${hhmm(n.darkFrom)}` : "Too light right now";
  else if (c.bz == null || !c.bzFresh) state.textContent = "No live solar wind";
  else { state.textContent = "Watching the sky"; applyTint(state, 50); }
  const badge = $("now-score");
  badge.textContent = c.score;
  applyTint(badge, c.score);

  // Bz direction: south (down) is what drives aurora.
  const bz = $("bz");
  if (c.bz != null && c.bzFresh) {
    bz.hidden = false;
    const south = c.bz <= -3, north = c.bz >= 3;
    const arrow = $("bz-arrow");
    arrow.replaceChildren(arrowIcon());
    arrow.firstChild.style.transform = south ? "rotate(180deg)" : north ? "rotate(0deg)" : "rotate(90deg)";
    arrow.style.color = south ? "var(--green)" : "var(--frost)";
    arrow.style.borderColor = south ? "rgba(121, 232, 176, 0.5)" : "";
    $("bz-value").textContent = `Bz ${signed(c.bz)} nT`;
    $("bz-note").textContent = south ? "Field pointing south, which drives aurora" : north ? "Field pointing north, quiet" : "Field neutral";
  } else bz.hidden = true;

  const facts = [];
  if (c.etaMinutes != null && c.bzFresh) facts.push(["Reaches Earth", `in about ${c.etaMinutes} min`]);
  if (c.bzSustained != null) facts.push(["Arriving now", `${signed(c.bzSustained)} nT on average`]);
  facts.push(["Clouds here", c.cloud < 0 ? "unknown" : `${c.cloud}%`]);
  if (c.clearer) facts.push(["Clearer at", `${c.clearer.name}, ${c.clearer.cloud}% cloud`]);
  $("now-lines").replaceChildren(...facts.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
  renderSpark();
}

function arrowIcon() {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.innerHTML = '<path d="M8 13V3M3.5 7.5 8 3l4.5 4.5"/>';
  return svg;
}

function renderSpark() {
  const box = $("spark");
  const pts = data.solarWind || [];
  if (pts.length < 6) { box.hidden = true; return; }
  box.hidden = false;
  const w = 300, h = 64;
  const t0 = pts[0][0], t1 = pts[pts.length - 1][0];
  const max = Math.max(5, ...pts.map((p) => Math.abs(p[1])));
  const x = (t) => ((t - t0) / (t1 - t0 || 1)) * w;
  const y = (v) => h / 2 - (v / max) * (h / 2 - 4);
  const line = pts.map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)} ${y(p[1]).toFixed(1)}`).join("");
  const ns = "http://www.w3.org/2000/svg";
  const mk = (tag, attrs) => { const n = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v); return n; };
  const svg = mk("svg", { viewBox: `0 0 ${w} ${h}`, preserveAspectRatio: "none", role: "img", "aria-label": "Bz over the last two hours" });
  const defs = mk("defs", {});
  const clip = mk("clipPath", { id: "south" });
  clip.append(mk("rect", { x: 0, y: h / 2, width: w, height: h / 2 }));
  defs.append(clip);
  svg.append(defs, mk("path", { d: `${line}L${w} ${h / 2}L0 ${h / 2}Z`, class: "spark-south", "clip-path": "url(#south)" }),
    mk("line", { x1: 0, x2: w, y1: h / 2, y2: h / 2, class: "spark-zero" }), mk("path", { d: line, class: "spark-line" }));
  box.replaceChildren(svg, el("p", {}, "Bz at the satellite over the last 2 hours. Shaded means south."));
}

function renderSpots() {
  const meta = new Map((data.spots || []).map((s) => [s.id, s]));
  const h = data.tonight.peak ? hours().find((x) => x.time === data.tonight.peak.time) : null;
  const ranked = h?.spots?.length ? h.spots : (data.spots || []).map((s) => [s.id, null, null, null]);
  const top = ranked.find((r) => (r[1] ?? 0) > 0);
  const stayPut = top && top[0] === "home";
  $("spots-title").textContent = h ? `Where to go at ${hhmm(h.time)}` : "Where to go";
  $("spots-sub").textContent = !h ? "" : !top ? "No clear, dark place nearby" : stayPut ? "Best right here" : "Each place has its own clouds";
  $("spots").replaceChildren(...ranked.slice(0, 5).map(([id, score, cloud]) => {
    const s = meta.get(id);
    if (!s) return null;
    const here = id === "home";
    const best = top && id === top[0];
    const badge = el("span", { class: "spot-badge" }, score ?? "–");
    if (score != null) applyTint(badge, score);
    const detail = here ? (s.dark ? "You're here" : "You're here. Town lights hide faint aurora.") : `${Math.round(s.distanceKm)} km, about ${drive(s.distanceKm)} min by car`;
    return el("li", { class: "spot" },
      badge,
      el("span", { class: "spot-body" },
        el("span", { class: "spot-name" }, here ? data.place.name : s.name, best ? pill("Best bet", { tint: 85 }) : null),
        el("span", { class: "spot-detail" }, detail)),
      cloud != null ? pill(`${cloud}% cloud`, { cls: "cloud-pill", icon: "cloud" }) : null,
    );
  }).filter(Boolean));
}

function renderOutlook() {
  $("outlook").replaceChildren(...(data.outlook || []).map((d, i) => {
    const label = i === 0 ? "Today" : i === 1 ? "Tomorrow" : weekday(d.dayStart);
    const kp = d.maxKp;
    const word = kp == null ? "no forecast" : kp >= 5 ? "storm" : kp >= 4 ? "active" : kp >= 3 ? "unsettled" : "quiet";
    return el("div", { class: "day" },
      el("span", { class: "day-name" }, label),
      el("span", { class: "blocks", "aria-hidden": "true" },
        ...d.blocks.map((b) => el("span", { class: "block-bar", style: `height:${Math.max(8, Math.min(100, (b.kp / 7) * 100))}%;background:${scoreColor(kpScore(b.kp))}` }))),
      kp == null ? pill("No forecast") : pill([`Kp ${kp.toFixed(1)}`, el("span", { class: "muted" }, word)], { tint: kpScore(kp) }),
    );
  }));
}

function renderDress() {
  const w = data.weather;
  $("dress-card").hidden = !w;
  if (!w) return;
  $("dress-sub").textContent = `At ${hhmm(w.time)}`;
  $("dress-temp").textContent = deg(w.temp);
  $("dress-badges").replaceChildren(
    pill([el("span", { class: "muted" }, "Feels like"), deg(w.feels)], { icon: "feels" }),
    w.wind != null ? pill([el("span", { class: "muted" }, "Wind"), `${Math.round(w.wind)} m/s`], { icon: "wind" }) : null,
  );
  $("dress-advice").textContent = w.feels <= -10 ? "Serious cold. Layers, hat, gloves, warm boots, and a flask."
    : w.feels <= 0 ? "Cold. Warm jacket, hat and gloves; you'll be standing still."
    : w.feels <= 6 ? "Chilly. A proper jacket and a hat."
    : "Mild for an aurora night. A light jacket will do.";
}

function renderHours() {
  const list = hours();
  $("hours-card").hidden = list.length === 0;
  if (!list.length) return;
  const n = data.tonight;
  const peakTime = n.peak?.time;
  const now = data.now;
  $("hours-sub").textContent = n.darkFrom && n.darkUntil ? `Dark from ${hhmm(n.darkFrom)} to ${hhmm(n.darkUntil)}` : "";
  $("strip").replaceChildren(...list.map((h) => {
    const isNow = now >= h.time && now < h.time + HOUR;
    const isPeak = h.time === peakTime;
    return el("div", { class: "bar" + (isPeak ? " peak" : "") + (isNow ? " now" : "") + (h.time + HOUR <= now ? " past" : ""), role: "listitem", "aria-label": `${hhmm(h.time)}, score ${h.score}` },
      el("span", { class: "flag" }, isPeak ? pill("Peak", { tint: h.score }) : isNow ? pill("Now") : null),
      el("span", { class: "value" }, h.score),
      el("span", { class: "track" }, el("span", { class: "fill", style: `height:${Math.max(3, h.score)}%;background:${scoreColor(h.score)}` })),
      el("span", { class: "label" }, hourLabel(h.time)),
    );
  }));
}

// Look up now takes over the screen, alternating with the forecast every 20 s.
let takeoverTimer = 0;
function renderLookUp() {
  const c = data.current;
  const bits = [];
  if (c.bz != null && c.bzFresh) bits.push(`Bz ${signed(c.bz)} nT`);
  if (c.cloud >= 0) bits.push(`${c.cloud}% cloud`);
  let detail = bits.length ? cap(bits.join(", ")) + "." : "";
  if (c.clearer) detail += ` Clearer at ${c.clearer.name}.`;
  $("lookup-detail").textContent = detail.trim();
  clearInterval(takeoverTimer);
  const set = (show) => {
    $("lookup").hidden = !show;
    document.documentElement.classList.toggle("taking-over", show);
  };
  if (c.lookUp) {
    let show = true;
    set(show);
    takeoverTimer = setInterval(() => { show = !show; set(show); }, 20000);
  } else set(false);
}

// ---------------------------------------------------------------------------------------------

load();
setInterval(load, 60 * 1000);
setInterval(() => { if (data) renderHeader(); }, 30 * 1000);

const tick = () => { $("clock").textContent = fmt(Date.now(), { hour: "2-digit", minute: "2-digit", hour12: false }); };
tick();
setInterval(tick, 10 * 1000);

// A slow drift of a few pixels each minute so nothing burns into the panel.
let step = 0;
setInterval(() => {
  step++;
  $("drift").style.transform = `translate(${Math.round(Math.sin(step / 3) * 6)}px, ${Math.round(Math.cos(step / 4) * 4)}px)`;
}, 60 * 1000);

// The pointer shows while the mouse moves and hides after 3 s still.
let idleTimer = 0;
const awake = () => {
  document.documentElement.classList.remove("idle");
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => document.documentElement.classList.add("idle"), 3000);
};
["mousemove", "pointerdown", "keydown"].forEach((ev) => window.addEventListener(ev, awake, { passive: true }));
awake();

// Offline shell: if the hotel Wi-Fi drops and the TV reloads, the page still comes up.
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});

// Keep the screen on where the browser allows it.
const wake = async () => { try { await navigator.wakeLock?.request("screen"); } catch { /* not allowed: fine */ } };
wake();
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") wake(); });
