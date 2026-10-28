import { startSky, skyFor, skyDetail } from "/aurora.js";
import { skyLab, labHour, labExtras } from "/skylab.js";

// The lobby screen: /lobby/{place}. One screen, refreshed every minute, nothing to tap.

const path = location.pathname.replace(/\/+$/, "");
const PLACE_ID = path.startsWith("/lobby/") ? decodeURIComponent(path.split("/")[2] || "") : "njardvik";

const CHANCE = {
  EXCELLENT: "Excellent chance tonight",
  GOOD: "Good chance tonight",
  POSSIBLE: "Possible tonight",
  UNLIKELY: "Unlikely tonight",
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

// One small line-icon set, drawn in currentColor so each takes its label's colour.
const ICONS = {
  moon: '<path d="M13.5 10.2A5.6 5.6 0 0 1 5.8 2.5a5.8 5.8 0 1 0 7.7 7.7Z"/>',
  dark: '<circle cx="8" cy="8" r="5.5"/><path class="solid" d="M8 2.5v11a5.5 5.5 0 0 0 0-11Z"/>',
  sun: '<circle cx="8" cy="8" r="3"/><path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1"/>',
  cloud: '<path d="M4.5 12.5h7a3 3 0 0 0 .3-6 4 4 0 0 0-7.6 1.2 2.4 2.4 0 0 0 .3 4.8Z"/>',
  bolt: '<path d="M9 1.5 3.5 9H8l-1 5.5L12.5 7H8l1-5.5Z"/>',
  wind: '<path d="M2 6h8.5a2 2 0 1 0-2-2M2 10h10.5a2 2 0 1 1-2 2"/>',
  thermo: '<path d="M6.5 9.4V3a1.5 1.5 0 0 1 3 0v6.4a2.8 2.8 0 1 1-3 0Z"/><path d="M8 6.5v4.3"/>',
  arrow: '<path d="M8 13.5v-11M3.5 7 8 2.5 12.5 7"/>',
  eye: '<path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8Z"/><circle cx="8" cy="8" r="2"/>',
  star: '<path d="m8 2 1.8 3.8 4.2.5-3.1 2.9.8 4.1L8 11.3l-3.7 2 .8-4.1L2 6.3l4.2-.5Z"/>',
  car: '<path d="M2.5 11.5V8.4l1.3-3.3a1 1 0 0 1 .9-.6h6.6a1 1 0 0 1 .9.6l1.3 3.3v3.1h-11Z"/><path d="M2.5 8.4h11M4.8 13.5v-2M11.2 13.5v-2"/>',
  pin: '<path d="M8 14.5s4.5-4.2 4.5-8a4.5 4.5 0 0 0-9 0c0 3.8 4.5 8 4.5 8Z"/><circle cx="8" cy="6.5" r="1.6"/>',
  pulse: '<path d="M1.5 8.5h3l1.6-4.5 3.3 8 1.6-3.5h3.5"/>',
  days: '<rect x="2.5" y="3.5" width="11" height="10" rx="1.5"/><path d="M2.5 6.8h11M5.5 2v3M10.5 2v3"/>',
};
function icon(name, rotate = 0) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("class", "ico");
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = ICONS[name] || "";
  if (rotate) svg.style.transform = `rotate(${rotate}deg)`;
  return svg;
}

const pill = (content, { tint, cls = "", ic } = {}) => {
  const p = el("span", { class: `pill ${cls}`.trim() }, ic ? icon(ic) : null, content);
  if (tint) applyTint(p, tint);
  return p;
};

let data = null;
const zone = () => (data && data.tz) || "Atlantic/Reykjavik";
const fmt = (ms, opts) => { try { return new Intl.DateTimeFormat("en-GB", { timeZone: zone(), ...opts }).format(ms); } catch { return ""; } };
const hhmm = (ms) => fmt(ms, { hour: "2-digit", minute: "2-digit", hour12: false });
const hourLabel = (ms) => fmt(ms, { hour: "2-digit", hour12: false });
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
/** A tinted badge with an opaque fill, for badges that sit over a border line. */
function solidPill(text, score, ic) {
  const p = pill(text, { cls: "best-bet", tint: score, ic });
  const c = scoreRgb(score), base = [10, 16, 30];
  p.style.setProperty("--solid", rgb(base.map((v, i) => Math.round(v + (c[i] - v) * 0.22))));
  return p;
}
/** A badge in a score's colour: tinted fill, stronger border, coloured text. */
function applyTint(node, score) {
  const c = scoreRgb(score);
  node.style.setProperty("--tint", c.join(", "));
  node.classList.add("tinted");
}

// ---------------------------------------------------------------------------------------------

const sky = startSky($("sky"), { scale: 0.5, rich: true, maxWidth: 1280, moonSize: 0.042 });
skyLab($("brand"), () => renderSky());
$("activity-label").prepend(icon("pulse"));
$("days-label").prepend(icon("days"));
const hours = () => data?.tonight?.hours || [];
const GAUGE = 2 * Math.PI * 110;
const LEVELS = ["Quiet", "Unsettled", "Active", "Strong", "Storm"];
const level = (kp) => (kp >= 6 ? 4 : kp >= 5 ? 3 : kp >= 4 ? 2 : kp >= 3 ? 1 : 0);

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
  renderHours();
  renderPlaces();
  renderLookUp();
  renderSky();
}

/** The background is the live sky here: this hour's weather, moon, stars and aurora. */
function renderSky() {
  if (!data) return;
  const h = labHour(data.current?.hour);
  const c = data.current;
  const x = labExtras({ bz: c?.bzFresh ? c.bz : null, waxing: !!data.moon?.waxing, now: Date.now() });
  sky.set({
    ...skyFor(h, hours(), false),
    // The open sky on this screen is the band above the forecast, between the title and the clock.
    ...skyDetail(h, { bz: x.bz, waxing: x.waxing, horizon: 0.2, top: 0.035, anchor: 0.45, lat: data.place.lat, lon: data.place.lon, starsAt: x.now }),
  });
}

function renderHeader() {
  const p = data.place;
  $("brand").textContent = p.title || "Ljós";
  $("place").textContent = `Aurora forecast for ${p.name}`;
  $("live").classList.toggle("stale", !!data.stale);
  $("live-text").textContent = data.stale ? "Data delayed" : "Live";
  $("updated").textContent = `Updated ${ago(data.updatedAt)}`;
}

/** The run of hours around tonight's peak that score nearly as well, from now on. */
function bestWindow() {
  const list = hours();
  const peak = data.tonight.peak;
  if (!peak) return null;
  const i = list.findIndex((h) => h.time === peak.time);
  if (i < 0) return null;
  const floor = peak.score - Math.max(4, peak.score * 0.08);
  let a = i, b = i;
  while (a > 0 && list[a - 1].score >= floor && list[a - 1].time + HOUR > data.now) a--;
  while (b < list.length - 1 && list[b + 1].score >= floor) b++;
  return { from: list[a].time, to: list[b].time + HOUR };
}

function renderHero() {
  const n = data.tonight;
  const peak = n.peak;
  const now = data.now;
  const cur = data.current;
  setGauge(peak ? peak.score : null);
  const chance = $("chance");
  chance.style.cssText = "";
  if (!peak) {
    chance.textContent = "Too bright for aurora";
    $("best-time").textContent = "The nights are too light here this time of year.";
    $("hero-line").textContent = "";
    $("conditions").replaceChildren();
    return;
  }
  chance.textContent = CHANCE[peak.chance] || "";
  applyTint(chance, peak.score);

  const w = bestWindow();
  let line;
  if (peak.score <= 0) line = "No aurora expected tonight";
  else if (peak.score < 20 || !w) line = `Best hour at ${hhmm(peak.time)}`;
  else if (now >= w.from) line = `Best now, until ${hhmm(w.to)}`;
  else if (w.to - w.from <= HOUR) line = `Best around ${hhmm(w.from)}`;
  else line = `Best from ${hhmm(w.from)} to ${hhmm(w.to)}`;
  $("best-time").textContent = line;

  const bits = [];
  if (n.darkFrom && now < n.darkFrom) bits.push(`Dark in ${span(n.darkFrom - now)}.`);
  const m = data.moon;
  if (m) {
    const lit = `${Math.round(m.illumination * 100)}% lit`;
    const up = (cur.hour?.moonAlt ?? -1) > 0;
    if (m.illumination < 0.05) bits.push("New moon, so the sky is properly dark.");
    else if (up) bits.push(m.set ? `The moon is up, ${lit}, and sets at ${hhmm(m.set)}.` : `The moon is up all night, ${lit}.`);
    else if (m.rise && m.rise > now) bits.push(`The moon rises at ${hhmm(m.rise)}, ${lit}.`);
    else bits.push("No moon in the sky tonight.");
  }
  $("hero-line").replaceChildren(...(bits.length ? [icon(m ? "moon" : "dark"), el("span", {}, bits.join(" "))] : []));

  const conds = [];
  const wx = data.weather;
  if (wx) {
    conds.push(["thermo", "Temperature", `${deg(wx.temp)}, feels ${deg(wx.feels)}`]);
    if (wx.wind != null) conds.push(["wind", "Wind", `${Math.round(wx.wind)} m/s`]);
  }
  if (n.darkFrom && n.darkUntil) conds.push(now < n.darkFrom ? ["sun", "Dark from", hhmm(n.darkFrom)] : ["dark", "Dark until", hhmm(n.darkUntil)]);
  $("conditions").replaceChildren(...conds.map(([ic, k, v]) => el("span", { class: "cond" }, icon(ic), el("span", { class: "cond-k" }, k), el("span", { class: "cond-v" }, v))));
}

function setGauge(score) {
  const s = $("score");
  s.textContent = score == null ? "–" : String(score);
  s.style.color = score == null ? "" : scoreColor(score);
  const fill = $("gauge-fill");
  fill.setAttribute("stroke-dasharray", `${((score ?? 0) / 100) * GAUGE} ${GAUGE}`);
  fill.style.stroke = score == null ? "" : scoreColor(score);
  fill.style.opacity = score ? "1" : "0";
}

function renderNow() {
  const c = data.current;
  const n = data.tonight;
  const state = $("now-state");
  state.style.cssText = "";
  const setState = (ic, text, tint) => { state.replaceChildren(icon(ic), text); if (tint) applyTint(state, tint); };
  if (c.lookUp) setState("bolt", "Look up now", 85);
  else if (!c.isDark) setState("sun", n.darkFrom && data.now < n.darkFrom ? `Dark from ${hhmm(n.darkFrom)}` : "Too light right now");
  else if (c.bz == null || !c.bzFresh) setState("pulse", "No live solar wind");
  else setState("eye", "Watching the sky", 50);

  const kp = c.hour?.kp;
  $("activity").classList.toggle("is-empty", kp == null);
  if (kp != null) {
    const at = level(kp);
    $("meter").replaceChildren(...LEVELS.map((name, i) => {
      const bar = el("span", { class: "level-bar" });
      if (i <= at) { bar.style.setProperty("--tint", scoreRgb(15 + i * 21).join(", ")); bar.classList.add("lit"); }
      return el("span", { class: "level" + (i === at ? " current" : "") }, bar, el("span", { class: "level-name" }, name));
    }));
    $("meter").setAttribute("aria-label", `Activity: ${LEVELS[at]}, Kp ${kp.toFixed(1)}`);
  }

  const facts = [];
  facts.push([icon("bolt"), "Next wave", c.etaMinutes != null ? `In about ${c.etaMinutes} min` : "Waiting for the next reading"]);
  const cloud = c.cloud < 0 ? "Unknown" : `${c.cloud}%`;
  facts.push([icon("cloud"), "Clouds here", c.clearer ? `${cloud}, clearer at ${c.clearer.name}` : cloud]);
  if (c.bz != null && c.bzFresh) {
    // The arrow shows which way the field points: south (down) is what drives aurora.
    const south = c.bz <= -3, north = c.bz >= 3;
    const dir = south ? "pointing south, good" : north ? "pointing north, quiet" : "neutral";
    facts.push([icon("arrow", south ? 180 : north ? 0 : 90), "Solar wind", `Bz ${signed(c.bz)} nT, ${dir}`]);
  } else facts.push([icon("wind"), "Solar wind", "No live reading"]);
  $("facts").replaceChildren(...facts.flatMap(([ic, k, v]) => [el("dt", {}, ic, k), el("dd", {}, v)]));

  $("days-wrap").classList.toggle("is-empty", !(data.outlook || []).length);
  $("days").replaceChildren(...(data.outlook || []).slice(0, 3).map((d, i) => {
    const name = i === 0 ? "Today" : i === 1 ? "Tomorrow" : fmt(d.dayStart, { weekday: "long" });
    const k = d.maxKp;
    const day = el("div", { class: "day" }, el("span", { class: "day-name" }, name),
      el("span", { class: "day-kp" }, k == null ? "No forecast" : `${LEVELS[level(k)]}, Kp ${k.toFixed(1)}`));
    if (k != null) {
      applyTint(day, kpScore(k));
    }
    return day;
  }));
}

function renderHours() {
  const list = hours();
  $("hours-card").hidden = list.length === 0;
  if (!list.length) return;
  const n = data.tonight;
  const now = data.now;
  // No Peak badge on a night where every hour scores 0: there's no best hour to point at.
  const peakTime = n.peak?.score > 0 ? n.peak.time : null;
  const w = n.peak && n.peak.score >= 20 ? bestWindow() : null;
  $("key-window").hidden = !w;
  $("timeline").replaceChildren(...list.map((h) => {
    const isNow = now >= h.time && now < h.time + HOUR;
    const isPeak = h.time === peakTime;
    const cls = ["tick"];
    if (n.darkFrom && n.darkUntil && h.time + HOUR > n.darkFrom && h.time < n.darkUntil) cls.push("dark");
    if (w && h.time >= w.from && h.time < w.to) cls.push("window");
    if (h.moonAlt > 0) cls.push("moon");
    if (h.time + HOUR <= now) cls.push("past");
    if (isNow) cls.push("now");
    if (isPeak) cls.push("peak");
    const flag = isNow ? el("span", { class: "pill now-flag" }, isPeak ? "Peak now" : "Now")
      : isPeak ? pill("Peak", { tint: h.score }) : null;
    const fill = el("span", { class: "tick-fill" });
    fill.style.height = `${Math.max(0.2, h.score * 0.034)}rem`;
    fill.style.setProperty("--tint", scoreRgb(h.score).join(", "));
    return el("div", { class: cls.join(" "), role: "listitem", "aria-label": `${hhmm(h.time)}, score ${h.score}` },
      el("span", { class: "tick-flag" }, flag),
      el("span", { class: "tick-track" }, el("span", { class: "tick-value" }, h.score), fill),
      el("span", { class: "tick-moon" }),
      el("span", { class: "tick-hour" }, hourLabel(h.time)),
    );
  }));
}

function renderPlaces() {
  const meta = new Map((data.spots || []).map((s) => [s.id, s]));
  const h = data.tonight.peak ? hours().find((x) => x.time === data.tonight.peak.time) : null;
  const ranked = (h?.spots || []).filter((r) => meta.has(r[0]));
  $("go-card").hidden = ranked.length === 0;
  if (!ranked.length) return;
  const top = ranked.find((r) => (r[1] ?? 0) > 0);
  $("go-title").textContent = `Where to go at ${hhmm(h.time)}`;
  $("go-sub").textContent = !top ? "No clear, dark place nearby tonight." : top[0] === "home" ? "Best right here. Each place has its own clouds." : "Each place has its own clouds. Best first.";
  $("places").replaceChildren(...ranked.slice(0, 5).map(([id, score, cloud]) => {
    const s = meta.get(id);
    const here = id === "home";
    const best = top && id === top[0];
    const badge = el("span", { class: "place-score" }, score ?? "–");
    if (score != null) applyTint(badge, score);
    const clouds = cloud != null && cloud >= 0 ? el("span", { class: "meta" }, icon("cloud"), `${cloud}%`) : null;
    const detail = [here ? el("span", { class: "meta" }, icon("pin"), "You're here") : el("span", { class: "meta" }, icon("car"), `${drive(s.distanceKm)} min`), clouds];
    const card = el("li", { class: "place" }, best ? solidPill("Best bet", Math.max(score, 80), "star") : null, badge,
      el("span", { class: "place-body" },
        el("span", { class: "place-name" }, here ? data.place.name : s.name),
        el("span", { class: "place-detail" }, detail)));
    if (best) card.style.setProperty("--tint", scoreRgb(score).join(", ")), card.classList.add("best");
    return card;
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
setInterval(() => { if (data) { renderHeader(); renderSky(); } }, 30 * 1000);

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
