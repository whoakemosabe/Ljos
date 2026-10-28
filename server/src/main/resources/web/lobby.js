import { startSky, skyFor } from "/aurora.js";

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

const pill = (content, { tint, cls = "" } = {}) => {
  const p = el("span", { class: `pill ${cls}`.trim() }, content);
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
  sky.set(skyFor(data.current?.hour, hours(), false));
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
  if (peak.score < 20 || !w) line = `Best hour at ${hhmm(peak.time)}`;
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
  $("hero-line").textContent = bits.join(" ");

  const conds = [];
  const wx = data.weather;
  if (wx) {
    conds.push(["Temperature", `${deg(wx.temp)}, feels ${deg(wx.feels)}`]);
    if (wx.wind != null) conds.push(["Wind", `${Math.round(wx.wind)} m/s`]);
  }
  if (n.darkFrom && n.darkUntil) conds.push(now < n.darkFrom ? ["Dark from", hhmm(n.darkFrom)] : ["Dark until", hhmm(n.darkUntil)]);
  $("conditions").replaceChildren(...conds.map(([k, v]) => el("span", { class: "cond" }, el("span", { class: "cond-k" }, k), el("span", { class: "cond-v" }, v))));
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
  if (c.lookUp) { state.textContent = "Look up now"; applyTint(state, 85); }
  else if (!c.isDark) state.textContent = n.darkFrom && data.now < n.darkFrom ? `Dark from ${hhmm(n.darkFrom)}` : "Too light right now";
  else if (c.bz == null || !c.bzFresh) state.textContent = "No live solar wind";
  else { state.textContent = "Watching the sky"; applyTint(state, 50); }

  const kp = c.hour?.kp;
  $("activity").hidden = kp == null;
  if (kp != null) {
    const at = level(kp);
    $("meter").replaceChildren(...LEVELS.map((name, i) => {
      const bar = el("span", { class: "level-bar" });
      if (i <= at) bar.style.background = scoreColor(15 + i * 21);
      return el("span", { class: "level" + (i === at ? " current" : "") }, bar, el("span", { class: "level-name" }, name));
    }));
    $("meter").setAttribute("aria-label", `Activity: ${LEVELS[at]}, Kp ${kp.toFixed(1)}`);
  }

  const facts = [];
  if (c.etaMinutes != null && c.bzFresh) facts.push(["Next wave", `Reaches Earth in about ${c.etaMinutes} min`]);
  const cloud = c.cloud < 0 ? "Unknown" : `${c.cloud}%`;
  facts.push(["Clouds here", c.clearer ? `${cloud}, clearer at ${c.clearer.name}` : cloud]);
  if (c.bz != null && c.bzFresh) {
    const dir = c.bz <= -3 ? "pointing south, good" : c.bz >= 3 ? "pointing north, quiet" : "neutral";
    facts.push(["Solar wind", `Bz ${signed(c.bz)} nT, ${dir}`]);
  } else facts.push(["Solar wind", "No live reading"]);
  $("facts").replaceChildren(...facts.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));

  $("days-wrap").hidden = !(data.outlook || []).length;
  $("days").replaceChildren(...(data.outlook || []).slice(0, 3).map((d, i) => {
    const name = i === 0 ? "Today" : i === 1 ? "Tomorrow" : fmt(d.dayStart, { weekday: "long" });
    const k = d.maxKp;
    const day = el("div", { class: "day" }, el("span", { class: "day-name" }, name),
      el("span", { class: "day-kp" }, k == null ? "No forecast" : `${LEVELS[level(k)]}, Kp ${k.toFixed(1)}`));
    if (k != null) {
      applyTint(day, kpScore(k));
      day.style.background = rgb(scoreRgb(kpScore(k)), 0.1);
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
  const peakTime = n.peak?.time;
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
    fill.style.background = scoreColor(h.score);
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
    const clouds = cloud != null && cloud >= 0 ? `${cloud}% cloud` : null;
    const detail = here ? (clouds ? `You're here, ${clouds}` : "You're here") : [clouds, `${drive(s.distanceKm)} min`].filter(Boolean).join(", ");
    const card = el("li", { class: "place" }, best ? pill("Best bet", { cls: "best-bet", tint: Math.max(score, 80) }) : null, badge,
      el("span", { class: "place-body" },
        el("span", { class: "place-name" }, here ? data.place.name : s.name),
        el("span", { class: "place-detail" }, detail)));
    if (best) card.style.borderColor = rgb(scoreRgb(score), 0.5);
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
