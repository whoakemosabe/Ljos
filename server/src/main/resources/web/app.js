import { startSky, skyFor, skyDetail } from "/aurora.js";
import { skyLab, labHour, labExtras } from "/skylab.js";
import { renderMap } from "/map.js";

// ---------------------------------------------------------------------------------------------
// Mode. /lobby and /lobby/{place} are the TV layout; everything else is the phone layout.
// ---------------------------------------------------------------------------------------------
const params = new URLSearchParams(location.search);
const path = location.pathname.replace(/\/+$/, "") || "/";
// The lobby screen has its own page (lobby.html); this one is always the phone layout.
const TV = false;
const PLACE_ID = path.startsWith("/lobby/") ? decodeURIComponent(path.split("/")[2] || "") : TV ? "njardvik" : null;
document.documentElement.classList.toggle("tv", TV);
document.documentElement.classList.toggle("phone", !TV);

const BROWSER_TZ = (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "Atlantic/Reykjavik"; } catch { return "Atlantic/Reykjavik"; } })();
// Phones follow the visitor's own clock style; lobby screens in Iceland use 24-hour time.
const H12 = !TV && (() => { try { return ["h11", "h12"].includes(new Intl.DateTimeFormat(undefined, { hour: "numeric" }).resolvedOptions().hourCycle); } catch { return false; } })();

// ---------------------------------------------------------------------------------------------
// Words. Core sends score bands as keys; the page words them. One language for now, ready for more.
// ---------------------------------------------------------------------------------------------
const CHANCE = {
  EXCELLENT: "Excellent",
  GOOD: "Good chance",
  POSSIBLE: "Possible",
  UNLIKELY: "Unlikely",
  NOT_TONIGHT: "Not tonight",
};

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------
const $ = (id) => document.getElementById(id);
const el = (tag, attrs = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") n.className = v;
    // Through the CSSOM, which the page's strict CSP allows (a style attribute would be blocked).
    else if (k === "style") n.style.cssText = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) n.append(kid instanceof Node ? kid : String(kid));
  return n;
};
const store = {
  get(k) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : null; } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode: fine */ } },
  del(k) { try { localStorage.removeItem(k); } catch { /* ignore */ } },
};
const HOUR = 3600000;
const MIN = 60000;

let data = null;
let selected = null; // an hour's time (ms) the visitor tapped, or null for the default view
let lastLoad = 0;

const zone = () => (data && data.tz) || BROWSER_TZ;
function fmt(ms, opts) {
  try { return new Intl.DateTimeFormat(H12 ? "en-US" : "en-GB", { timeZone: zone(), ...opts }).format(ms); }
  catch { return new Date(ms).toLocaleTimeString(); }
}
const hhmm = (ms) => fmt(ms, { hour: H12 ? "numeric" : "2-digit", minute: "2-digit", hour12: H12 });
const hourLabel = (ms) => H12
  ? fmt(ms, { hour: "numeric", hour12: true }).replace(/\s?([AP])M/i, (_, a) => a.toLowerCase())
  : fmt(ms, { hour: "2-digit", hour12: false });
const weekday = (ms) => fmt(ms, { weekday: "short" });
function span(ms) {
  const m = Math.max(0, Math.round(ms / MIN));
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}
function ago(ms) {
  const m = Math.max(0, Math.round((Date.now() - ms) / MIN));
  if (!ms) return "never";
  if (m < 1) return "just now";
  if (m < 60) return `${m} min ago`;
  return `${Math.floor(m / 60)} h ago`;
}
const signed = (v) => (v < 0 ? "−" : "+") + Math.abs(v).toFixed(1);
const cloudText = (pct) => (pct < 0 ? "clouds unknown" : `${pct}% cloud`);
const drive = (km) => Math.max(2, Math.round((km * 1.35) / (km * 1.35 < 6 ? 40 : 70) * 60 + 2));

/** Same ramp as the app: slate → teal → green → violet. */
function scoreColor(score) {
  const s = Math.min(100, Math.max(0, score));
  const lerp = (a, b, f) => a.map((v, i) => Math.round(v + (b[i] - v) * f));
  const slate = [46, 74, 96], teal = [47, 211, 198], green = [61, 255, 160], violet = [183, 156, 255];
  const c = s < 40 ? lerp(slate, teal, s / 40) : s < 80 ? lerp(teal, green, (s - 40) / 40) : lerp(green, violet, (s - 80) / 20);
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`;
}
const kpColor = (kp) => scoreColor(Math.min(100, (kp / 6) * 100));

// ---------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------
function endpoint() {
  if (TV) return `/v1/places/${encodeURIComponent(PLACE_ID || "njardvik")}`;
  const pos = store.get("ljos.pos");
  if (pos && Number.isFinite(pos.lat) && Number.isFinite(pos.lon)) {
    const q = new URLSearchParams({ lat: pos.lat.toFixed(3), lon: pos.lon.toFixed(3), tz: BROWSER_TZ, name: "Your location" });
    return `/v1/forecast?${q}`;
  }
  return "/v1/forecast";
}

let glassOn = false;
/** The edge lens (glasspane.js) only works in Chromium (Android Chrome, desktop Chrome/Edge), so
 * other browsers (iPhone Safari, Firefox) don't download it at all. */
function loadLens() {
  if (!navigator.userAgentData) return;
  const go = () => {
    const sc = document.createElement("script");
    sc.src = "/glasspane.js";
    sc.onload = () => window.Glasspane?.init({ selector: ".lg", light: false });
    document.head.append(sc);
  };
  ("requestIdleCallback" in window) ? requestIdleCallback(go, { timeout: 1500 }) : setTimeout(go, 300);
}
async function load() {
  lastLoad = Date.now();
  try {
    const r = await fetch(endpoint(), { headers: { Accept: "application/json" }, cache: "no-cache" });
    if (r.status === 404 && TV) return showProblem("This screen's place isn't set up. Check the address ends in /lobby/<place>.");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    // Keep a copy for offline from the very first visit (before the offline helper is running).
    if ("caches" in window && !navigator.serviceWorker?.controller) {
      const copy = r.clone();
      caches.open("ljos-data").then((c) => c.put(endpoint(), copy)).catch(() => {});
    }
    data = await r.json();
    hideProblem();
    render();
    // The edge lens (glasspane.js, Chromium) on every glass surface. No moving light: no shine.
    if (!glassOn) { glassOn = true; loadLens(); }
  } catch (e) {
    console.error(e);
    if (data) showProblem("Couldn't reach the forecast. Showing the last one.");
    else showProblem("Reading the sky… the forecast feeds are busy, trying again shortly.");
  }
}

function showProblem(text) { const b = $("problem"); b.textContent = text; b.hidden = false; }
function hideProblem() { $("problem").hidden = true; }

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------
const sky = startSky($("sky"), { scale: TV ? 0.5 : 0.6, rich: true, maxWidth: 1280, moonSize: 0.032, fps: TV ? 60 : 30, pauseOnScroll: !TV });
skyLab($("brand"), () => setSky());

let showTomorrow = false; // the Tonight / Tomorrow switch in Hour by hour, as in the app
const hasTomorrow = () => (data?.tomorrow?.hours?.length || 0) > 0;
/** The night being shown: tonight, or tomorrow night when the switch says so. */
function night() { return showTomorrow && hasTomorrow() ? data.tomorrow : data?.tonight; }
function hours() { return night()?.hours || []; }
function hourAt(time) { return hours().find((h) => h.time === time) || null; }
function nowHour() { return data?.current?.hour || null; }
/** Once it's dark: the hour we're in tonight (the app's nowHour). The big number then shows right now. */
function darkNowHour() {
  const now = data?.now ?? Date.now();
  return (data?.tonight?.hours || []).find((h) => now >= h.time && now < h.time + HOUR && (h.dark ?? 0) > 0) || null;
}
/** The hour the big number is for: right now once dark, else tonight's peak (the app's heroHour). */
function heroHour() { return darkNowHour() || data?.tonight?.peak || null; }
/** The hour the cards show: the tapped one, else right now (tonight, once dark), else the peak (the app's sel). */
function focusHour() {
  if (selected != null) { const h = hourAt(selected); if (h) return h; }
  return (!showTomorrow && darkNowHour()) || night()?.peak || null;
}

// Folding cards, as in the app: tap a card's title row to fold it to one summary line. Which
// cards are folded is remembered on this device.
const FOLD_KEY = "ljos-folded";
let folded = new Set();
try { folded = new Set(JSON.parse(localStorage.getItem(FOLD_KEY) || "[]")); } catch { /* private mode */ }
const CHEVRON = '<svg viewBox="0 0 22 22" aria-hidden="true"><path d="M7 9.2l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';

function setupFolds() {
  document.querySelectorAll(".card[data-fold]").forEach((card) => {
    if (card.querySelector(".fold-head")) return;
    const id = card.dataset.fold;
    const head = card.querySelector(".card-head");
    const body = el("div", { class: "card-body" });
    const inner = el("div", { class: "card-inner" });
    for (const n of [...card.children]) if (n !== head) inner.append(n);
    body.append(inner);
    // Title, then the subtitle (or, folded, the summary) underneath; chevron at the end.
    const text = el("div", { class: "fold-text" });
    const h2 = head.querySelector("h2"), sub = head.querySelector(".sub");
    text.append(h2);
    if (sub) text.append(sub);
    text.append(el("p", { class: "card-sum", id: `sum-${id}` }));
    const btn = el("button", { type: "button", class: "fold-btn", "aria-expanded": "true", "aria-label": `Fold ${h2.textContent}` });
    btn.innerHTML = CHEVRON;
    head.classList.add("fold-head");
    head.prepend(text);
    head.append(btn);
    head.addEventListener("click", (e) => {
      if (e.target.closest("a")) return;
      folded.has(id) ? folded.delete(id) : folded.add(id);
      try { localStorage.setItem(FOLD_KEY, JSON.stringify([...folded])); } catch { /* ignore */ }
      applyFold(card);
      if (id === "where" && !folded.has(id) && data) renderSpots(); // the map needs its width
    });
    card.append(body);
    applyFold(card, true);
  });
}

function applyFold(card, instant = false) {
  const f = folded.has(card.dataset.fold);
  if (instant) card.classList.add("no-anim");
  else {
    // Clip only while moving, so edges and glows inside an open card aren't cut off.
    card.classList.add("folding");
    const body = card.querySelector(".card-body");
    const done = (e) => { if (e.target !== body || e.propertyName !== "grid-template-rows") return; card.classList.remove("folding"); body.removeEventListener("transitionend", done); };
    body?.addEventListener("transitionend", done);
    setTimeout(() => card.classList.remove("folding"), 600);
  }
  card.classList.toggle("folded", f);
  card.querySelector(".fold-btn")?.setAttribute("aria-expanded", String(!f));
  if (instant) requestAnimationFrame(() => card.classList.remove("no-anim"));
}

const setSum = (id, text) => { const n = document.getElementById(`sum-${id}`); if (n) n.textContent = text || "—"; };

function render() {
  if (!TV) setupFolds();
  if (!data) return;
  if (selected != null && !hourAt(selected)) selected = null;
  renderHeader();
  renderHero();
  renderStrip();
  renderWhy();
  renderNow();
  renderSpots();
  renderOutlook();
  renderDress();
  renderTips();
  renderLookUp();
  renderSums();
  setSky();
}

/** The live sky, as in the app: the tapped hour, else right now. Moon and stars stay above the score. */
function setSky() {
  if (!data) return;
  const h = labHour(selected != null ? hourAt(selected) : nowHour());
  const c = data.current;
  const x = labExtras({ bz: c?.bzFresh ? c.bz : null, waxing: !!data.moon?.waxing, now: h ? h.time + HOUR / 2 : Date.now() });
  const fit = TV ? { horizon: 0.42, top: 0.03, anchor: 0.5 } : { horizon: 0.15, top: 0.04, anchor: 0.72 };
  sky.set({
    ...skyFor(h, hours()),
    ...skyDetail(h, { bz: x.bz, waxing: x.waxing, ...fit, lat: data.place?.lat, lon: data.place?.lon, starsAt: x.now }),
  });
}

function renderHeader() {
  const p = data.place;
  $("place").textContent = TV && p.title ? p.name : p.name;
  $("brand").textContent = TV && p.title ? p.title : "Ljós";
  const stale = data.stale;
  $("updated").textContent = (stale ? "Data may be out of date · " : "") + `Updated ${ago(data.updatedAt)}`;
  $("updated").classList.toggle("warn", !!stale);
  const locate = $("locate");
  if (locate) locate.setAttribute("aria-label", store.get("ljos.pos") ? "Update my location" : "Use my location");
  const ps = $("pill-score"), hh = heroHour();
  if (ps) { ps.textContent = hh ? String(hh.score) : ""; ps.style.color = hh ? scoreColor(hh.score) : ""; }
}

function renderHero() {
  // As in the app: always tonight; right now once it's dark, otherwise tonight's peak.
  const n = data.tonight;
  const peak = n?.peak;
  const nowH = darkNowHour();
  const h = nowH || peak;
  const now = data.now;
  const eb = $("eyebrow");
  eb.textContent = nowH ? "Right now" : "Tonight";
  eb.classList.toggle("live", !!nowH);
  if (!h) {
    setScore(null);
    $("chance").textContent = "Too bright for aurora";
    $("peak-line").textContent = "Nights are too light here this time of year.";
    renderChips(null, n, now);
    return;
  }
  setScore(h.score);
  $("chance").textContent = CHANCE[h.chance] || "";
  let line = "";
  if (nowH && peak && peak.time !== nowH.time && peak.score > nowH.score) {
    line = `Best tonight: ${peak.score} around ${hhmm(peak.time)}`;
  } else if (nowH) {
    line = "Tonight's best hour" + (n.darkUntil ? ` · dark until ${hhmm(n.darkUntil)}` : "");
  } else if (peak.score <= 0) {
    line = n.darkFrom && now < n.darkFrom ? `Dark in ${span(n.darkFrom - now)} · no aurora expected` : "No aurora expected tonight";
  } else if (n.darkFrom && now < n.darkFrom) {
    line = `Dark in ${span(n.darkFrom - now)} · peak around ${hhmm(peak.time)}`;
  } else if (now < peak.time) {
    line = `Peak in ${span(peak.time - now)}, around ${hhmm(peak.time)}`;
  } else if (n.darkUntil && now < n.darkUntil) {
    line = `Dark until ${hhmm(n.darkUntil)}`;
  }
  $("peak-line").textContent = line;
  $("peak-line").hidden = !line;
  renderChips(h, n, now);
  renderNudge();
}

/** "Tomorrow looks better" (the app's TomorrowNudge): when tomorrow's peak is clearly higher. */
function renderNudge() {
  const b = $("nudge");
  const t = data.tomorrow?.peak?.score, p = data.tonight?.peak?.score;
  const show = !TV && t != null && p != null && t >= 40 && t >= p + 15 && !showTomorrow;
  b.hidden = !show;
  if (!show) return;
  $("nudge-score").textContent = `${t} ↗`;
  $("nudge-score").style.color = scoreColor(t);
}

document.addEventListener("click", (e) => {
  const chip = e.target.closest?.(".chip[data-go]");
  if (!chip) return;
  const card = document.getElementById(chip.dataset.go);
  if (!card || card.hidden) return;
  if (card.classList.contains("folded")) card.querySelector(".fold-head")?.click();
  const top = card.getBoundingClientRect().top + scrollY - 12;
  scrollTo({ top, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
});

/** Kp, cloud, moon, dark: the app's four condition chips under the score. Tap to jump to the card. */
function renderChips(h, n, now) {
  const box = $("chips");
  if (!h || TV) { box.hidden = true; return; }
  box.hidden = false;
  $("chip-kp").textContent = h.kp != null ? `Kp ${h.kp.toFixed(1)}` : "–";
  $("chip-cloud").textContent = h.cloud == null || h.cloud < 0 ? "–" : `${h.cloud}%`;
  const illum = Math.max(0, Math.min(1, h.moonIllum ?? 0));
  $("chip-moon").textContent = `${Math.round(illum * 100)}%`;
  const sh = $("chip-moon-shadow");
  sh.setAttribute("cx", String(6.5 - 11 * illum));
  sh.setAttribute("visibility", illum > 0.98 ? "hidden" : "visible");
  const t = (ms) => (H12 ? hourLabel(ms).toUpperCase() + "M" : hhmm(ms)); // "8PM" fits; dark starts on the hour
  const dark = !n.darkFrom ? "–" : now < n.darkFrom ? t(n.darkFrom) : n.darkUntil && now < n.darkUntil ? t(n.darkUntil) : t(n.darkFrom);
  $("chip-dark").textContent = dark;
}

function setScore(score) {
  const s = $("score");
  s.textContent = score == null ? "–" : String(score);
  s.style.color = "";
}

/** Tonight / Tomorrow: a groove with a green thumb that slides, like the app's segments. */
function renderNightSwitch() {
  const seg = $("night-seg");
  seg.hidden = !hasTomorrow();
  const on = showTomorrow && hasTomorrow() ? 1 : 0;
  seg.dataset.pos = String(on);
  seg.querySelectorAll("button").forEach((b, i) => b.setAttribute("aria-selected", i === on ? "true" : "false"));
}
$("nudge").addEventListener("click", () => {
  showTomorrow = true;
  selected = null;
  folded.delete("hours");
  try { localStorage.setItem(FOLD_KEY, JSON.stringify([...folded])); } catch { /* ignore */ }
  const card = $("hours-card");
  if (card) applyFold(card);
  render();
});
$("night-seg").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-i]");
  if (!b) return;
  const next = b.dataset.i === "1";
  if (next === showTomorrow) return;
  showTomorrow = next;
  selected = null;
  render();
  if (!next) {
    const strip = $("strip"), cur = strip.querySelector(".bar.now");
    if (cur) strip.scrollLeft = Math.max(0, cur.offsetLeft - 24);
  } else $("strip").scrollLeft = 0;
});

function renderStrip() {
  if (TV) return renderStripTV();
  // As in the app (ui/HourStrip.kt): the whole night fits, 14px channels with the cloud from the
  // top and the score from the floor, every second hour labelled, a green dot under now. The
  // hour the cards show is ringed and the others dim; tap or drag across to pick an hour.
  const strip = $("strip");
  const list = hours();
  $("hours-card").hidden = list.length === 0;
  renderNightSwitch();
  strip.replaceChildren();
  strip.classList.add("fit");
  if (!list.length) { $("moonline").hidden = true; return; }
  strip.style.gridTemplateColumns = `repeat(${list.length}, minmax(0, 1fr))`;
  const now = data.now;
  const focus = focusHour()?.time ?? null;
  list.forEach((h, i) => {
    const isNow = now >= h.time && now < h.time + HOUR;
    const isSel = h.time === focus;
    const fill = el("span", { class: "fill" });
    fill.style.setProperty("--c", scoreColor(h.score));
    fill.style.height = `max(3px, ${h.score}%)`;
    const bar = el("button", {
      type: "button",
      class: "bar" + (isSel ? " sel" : focus != null ? " dim" : "") + (isNow ? " now" : ""),
      role: "listitem",
      "data-t": String(h.time),
      "aria-label": `${hhmm(h.time)}, score ${h.score}, ${CHANCE[h.chance] || ""}${h.cloud >= 0 ? `, ${h.cloud}% cloud` : ""}`,
      "aria-pressed": selected === h.time ? "true" : "false",
    },
      el("span", { class: "track" }, h.cloud > 0 ? el("span", { class: "cloud", style: `height:${Math.min(100, h.cloud)}%` }) : null, fill),
      el("span", { class: "dot" }),
      el("span", { class: "label" }, i % 2 === 0 ? (H12 ? hourLabel(h.time) + "m" : hourLabel(h.time)) : ""),
    );
    strip.append(bar);
  });
  renderMoonLine(list);
}

/** Picking an hour only changes the cards below the score: redraw just those, once per frame. */
let focusFrame = 0;
function renderFocus() {
  if (focusFrame) return;
  focusFrame = requestAnimationFrame(() => {
    focusFrame = 0;
    renderStrip();
    renderWhy();
    renderSpots();
    renderTips();
    renderSums();
  });
}

// Tap or drag across the strip to pick an hour, as in the app. Tapping the hour already shown
// goes back to the default (right now, or the peak).
(() => {
  const strip = $("strip");
  let down = false, moved = false, startX = 0;
  const pickAt = (x, tap) => {
    const bars = [...strip.querySelectorAll(".bar")];
    if (!bars.length) return;
    const r = strip.getBoundingClientRect();
    const i = Math.max(0, Math.min(bars.length - 1, Math.floor(((x - r.left) / r.width) * bars.length)));
    const t = Number(bars[i].dataset.t);
    const cur = focusHour()?.time ?? null;
    if (t !== cur) { selected = t; renderFocus(); }
    else if (tap && selected != null) { selected = null; renderFocus(); }
  };
  strip.addEventListener("pointerdown", (e) => {
    if (TV || e.button > 0) return;
    down = true; moved = false; startX = e.clientX;
    strip.setPointerCapture?.(e.pointerId);
  });
  strip.addEventListener("pointermove", (e) => {
    if (!down) return;
    if (!moved && Math.abs(e.clientX - startX) > 6) moved = true;
    if (moved) { e.preventDefault(); pickAt(e.clientX, false); }
  });
  strip.addEventListener("pointerup", (e) => { if (down && !moved) pickAt(e.clientX, true); down = false; });
  strip.addEventListener("pointercancel", () => { down = false; });
  // Keyboard: Enter or Space on a bar (a pointer click is handled above).
  strip.addEventListener("click", (e) => {
    if (TV || e.detail !== 0) return;
    const b = e.target.closest(".bar"); if (!b) return;
    const t = Number(b.dataset.t);
    selected = selected === t ? null : t; renderFocus();
  });
})();

/** The moon line under the strip (the app's MoonLine): when the moon is up, brighter for a brighter moon. */
function renderMoonLine(list) {
  const box = $("moonline");
  const start = list[0].time, end = list[list.length - 1].time + HOUR;
  const tonight = !showTomorrow || !hasTomorrow();
  const m = data.moon || {};
  // Tonight: the exact rise and set; tomorrow: from the hours the moon is above the horizon.
  let segs = [];
  if (tonight && Array.isArray(m.up)) segs = m.up.map(([a, b]) => [a, b]);
  else {
    let a = null;
    list.forEach((h) => { if (h.moonAlt > 0) { if (a == null) a = h.time; } else if (a != null) { segs.push([a, h.time]); a = null; } });
    if (a != null) segs.push([a, end]);
  }
  const illum = tonight && m.illumination != null ? m.illumination : (list.find((h) => h.moonIllum != null)?.moonIllum ?? 0);
  const glow = Math.max(0.25, Math.min(1, 0.25 + 0.75 * illum));
  const track = $("ml-track");
  track.replaceChildren();
  track.style.setProperty("--glow", String(glow));
  const x = (t) => Math.max(0, Math.min(1, (t - start) / (end - start))) * 100;
  segs.forEach(([a, b]) => {
    const x0 = x(a), x1 = x(b);
    if (x1 - x0 < 0.3) return;
    const seg = el("span", { class: "ml-seg" });
    seg.style.cssText = `left:${x0}%;width:${x1 - x0}%`;
    track.append(seg);
  });
  const pct = Math.round(illum * 100);
  const parts = [`Moon ${pct}%`];
  if (tonight && m.rise) parts.push(`rises ${hhmm(m.rise)}`);
  if (tonight && m.set) parts.push(`sets ${hhmm(m.set)}`);
  if (!segs.length) parts.push("down all night");
  $("ml-words").textContent = parts.join(" · ");
  const sh = $("ml-shadow");
  const shift = 11 * Math.max(0, Math.min(1, illum));
  sh.setAttribute("cx", String(m.waxing === false ? 5.5 + shift : 5.5 - shift));
  sh.setAttribute("visibility", shift >= 10.5 ? "hidden" : "visible");
  box.hidden = false;
}

function renderStripTV() {
  const strip = $("strip");
  strip.replaceChildren();
  const list = hours();
  $("hours-card").hidden = list.length === 0;
  renderNightSwitch();
  if (!list.length) return;
  // No peak marker when every hour scores 0.
  const pk = night()?.peak;
  const peakTime = pk?.score > 0 ? pk.time : null;
  const now = data.now;
  list.forEach((h) => {
    const isNow = now >= h.time && now < h.time + HOUR;
    const pct = Math.max(4, h.score);
    const bar = el("button", {
      type: "button",
      class: "bar" + (h.time === peakTime ? " peak" : "") + (isNow ? " now" : "") + (selected === h.time ? " selected" : "") + (h.time + HOUR <= now ? " past" : ""),
      role: "listitem",
      "aria-label": `${hhmm(h.time)}, score ${h.score}, ${CHANCE[h.chance] || ""}${h.cloud >= 0 ? `, ${h.cloud}% cloud` : ""}`,
      "aria-pressed": selected === h.time ? "true" : "false",
      onclick: () => { selected = selected === h.time ? null : h.time; render(); },
    },
      el("span", { class: "value" }, h.score),
      el("span", { class: "track" },
        h.cloud > 0 ? el("span", { class: "cloud", style: `height:${Math.min(100, h.cloud)}%` }) : null,
        el("span", { class: "fill", style: `height:${pct}%;background:${scoreColor(h.score)}` })),
      el("span", { class: "label" }, isNow ? "now" : hourLabel(h.time)),
    );
    strip.append(bar);
  });
  if (!TV) {
    // Start the strip at the current hour, or at dusk.
    const target = strip.querySelector(".bar.now") || strip.querySelector(".bar:not(.past)");
    if (target && selected == null) strip.scrollLeft = Math.max(0, target.offsetLeft - 24);
  }
}

function renderWhy() {
  const box = $("why");
  const h = selected != null ? hourAt(selected) : null;
  if (!h || TV) { box.hidden = true; return; }
  box.hidden = false;
  const meter = (name, v, note) => el("div", { class: "meter" },
    el("span", { class: "meter-name" }, name),
    el("span", { class: "meter-track" }, el("span", { class: "meter-fill", style: `width:${Math.round((v ?? 0) * 100)}%` })),
    el("span", { class: "meter-note" }, note),
  );
  box.replaceChildren(
    el("p", { class: "why-head" }, `${hhmm(h.time)} · ${h.score} · ${CHANCE[h.chance] || ""}`),
    el("p", { class: "quiet small" }, "Each one multiplies the score, so any one can zero it."),
    meter("Solar activity", h.activity, `Kp ${h.kp?.toFixed(1)}${h.live ? " · live" : ""}`),
    meter("Clear sky", h.clear, cloudText(h.cloud)),
    meter("Moon", h.moon, h.moonAlt > 0 ? `${Math.round(h.moonIllum * 100)}% lit, up` : "below the horizon"),
    meter("Darkness", h.dark, h.sunAlt < -15 ? "full dark" : `sun ${Math.round(h.sunAlt)}°`),
  );
}

// Right now: a slim glass strip under the score, as in the app's SolarWindStrip. Status word,
// live score and the last two hours of solar wind; tap to fold out the details.
let nowOpen = false;
try { nowOpen = localStorage.getItem("ljos-now-open") === "1"; } catch { /* private mode */ }

function renderNow() {
  const c = data.current;
  const strip = $("now-strip");
  if (TV) { strip.hidden = true; return; }
  const [word, tone] = c.lookUp ? ["Look up now", "go"]
    : c.bz == null || !c.bzFresh ? ["No live data", "faint"]
    : c.bz <= -3 ? ["Helping", "go"]
    : c.bz >= 3 ? ["Quiet", "muted"]
    : ["Neutral", "ink"];
  $("ns-word").textContent = word;
  $("ns-word").dataset.tone = tone;
  $("ns-score").textContent = c.score;
  $("ns-score").style.color = scoreColor(c.score);
  strip.classList.toggle("look", !!c.lookUp);
  strip.setAttribute("aria-label", `Right now: ${word}, live score ${c.score}. ${nowOpen ? "Hide" : "Show"} details`);

  const rows = [];
  if (c.bz != null) rows.push(["Bz now", `${signed(c.bz)} nT${c.bzFresh ? "" : " (old)"}`]);
  if (c.bzSustained != null) rows.push(["Arriving now, 20 min average", `${signed(c.bzSustained)} nT`]);
  rows.push(["Clouds here", cloudText(c.cloud)]);
  if (c.clearer) rows.push(["Clearer at", `${c.clearer.name} (${cloudText(c.clearer.cloud)})`]);
  $("ns-rows").replaceChildren(...rows.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
  $("ns-eta").textContent = c.etaMinutes != null ? `reaches us in ~${c.etaMinutes} min` : "";
  const has = renderSpark();
  $("ns-cap").hidden = !has;
  $("ns-explain").hidden = !has;
  strip.classList.toggle("open", nowOpen);
  strip.setAttribute("aria-expanded", String(nowOpen));
  if (!strip.dataset.wired) {
    strip.dataset.wired = "1";
    const flip = () => {
      nowOpen = !nowOpen;
      try { localStorage.setItem("ljos-now-open", nowOpen ? "1" : "0"); } catch { /* ignore */ }
      renderNow();
    };
    strip.addEventListener("click", flip);
    strip.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); flip(); } });
  }
}

/** The last two hours of Bz at L1 as a slim line; minutes pointing south are shaded green. */
function renderSpark() {
  const box = $("spark");
  const now = data.now;
  const pts = (data.solarWind || []).filter((p) => now - p[0] <= 2 * HOUR && p[1] != null);
  if (pts.length < 6) { box.hidden = true; return false; }
  box.hidden = false;
  const w = 320, hgt = 34;
  const t0 = now - 2 * HOUR;
  const max = Math.max(8, ...pts.map((p) => Math.abs(p[1])));
  const mid = hgt / 2;
  const x = (t) => Math.min(1, Math.max(0, (t - t0) / (2 * HOUR))) * w;
  const y = (v) => mid - (v / max) * (mid - 2);
  const line = pts.map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)} ${y(p[1]).toFixed(1)}`).join("");
  const south = `M${x(pts[0][0]).toFixed(1)} ${mid}` + pts.map((p) => `L${x(p[0]).toFixed(1)} ${(p[1] < 0 ? y(p[1]) : mid).toFixed(1)}`).join("") + `L${x(pts[pts.length - 1][0]).toFixed(1)} ${mid}Z`;
  const ns = "http://www.w3.org/2000/svg";
  const mk = (tag, attrs) => { const n = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v)); return n; };
  const svg = mk("svg", { viewBox: `0 0 ${w} ${hgt}`, preserveAspectRatio: "none", role: "img", "aria-label": "Solar wind Bz over the last two hours; below the line is south, which is good" });
  const grad = mk("linearGradient", { id: "sparkSouth", gradientUnits: "userSpaceOnUse", x1: 0, y1: mid, x2: 0, y2: hgt });
  grad.append(mk("stop", { offset: "0%", "stop-color": "#3dffa0", "stop-opacity": 0 }), mk("stop", { offset: "100%", "stop-color": "#3dffa0", "stop-opacity": 0.45 }));
  const defs = mk("defs", {}); defs.append(grad);
  const last = pts[pts.length - 1];
  svg.append(defs,
    mk("path", { d: south, fill: "url(#sparkSouth)" }),
    mk("line", { x1: 0, x2: w, y1: mid, y2: mid, class: "spark-zero" }),
    mk("path", { d: line, class: "spark-line" }));
  box.replaceChildren(svg, el("span", { class: "spark-dot" + (last[1] < 0 ? " south" : "") }));
  // The live dot sits on top as HTML so it stays round while the chart stretches.
  const dot = box.lastChild;
  dot.style.cssText = `left:${(x(last[0]) / w) * 100}%;top:${(y(last[1]) / hgt) * 100}%`;
  return true;
}

let pickedSpot = null; // the spot picked on the map or list, like the app's WhereCard
let pickedFor = undefined; // the hour that pick belongs to; a new hour picks the best spot again
let mapResize = 0;
addEventListener("resize", () => { clearTimeout(mapResize); mapResize = setTimeout(() => data && renderSpots(), 150); });

/** The one-line summaries a folded card shows, worded as in the app. */
function renderSums() {
  if (TV) return;
  const n = night(), pk = n?.peak, tom = showTomorrow && hasTomorrow();
  setSum("hours", !pk ? "—" : pk.score <= 0 ? "No aurora expected" : `${tom ? "Tomorrow peak" : "Peak"} ${hhmm(pk.time)} · ${pk.score}`);
  const h = focusHour();
  const ranked = h?.spots || [];
  const top = ranked.find((r) => (r[1] ?? 0) > 0);
  const meta = new Map((data.spots || []).map((s) => [s.id, s]));
  const ts = top && meta.get(top[0]);
  setSum("where", !top ? "Nothing clear nearby" : top[0] === "home" ? `Best right here · ${top[1]}` : ts ? `${ts.name} · ${top[1]} · ≈ ${drive(ts.distanceKm)} min` : "—");
  const w = data.weather;
  const deg = (c) => { const r = Math.round(c); return `${r < 0 ? "−" : ""}${Math.abs(r)}°`; };
  setSum("dress", w ? `${deg(w.temp)}, feels like ${deg(w.feels)}` : "—");
  const score = h ? h.score : 0;
  const [shutter, iso] = score >= 80 ? ["0.5–2 s", "800"] : score >= 60 ? ["2–4 s", "800–1600"] : ["4–8 s", "1600–3200"];
  setSum("tips", `${shutter} · ISO ${iso} · focus ∞`);
  const days = data.outlook || [];
  let bi = -1;
  days.forEach((d, i) => { if (d.maxKp != null && (bi < 0 || d.maxKp > days[bi].maxKp)) bi = i; });
  setSum("days", bi < 0 ? "—" : `Highest Kp ${days[bi].maxKp.toFixed(1)} ${bi === 0 ? "today" : bi === 1 ? "tomorrow" : weekday(days[bi].dayStart)}`);
}

function renderSpots() {
  const meta = new Map((data.spots || []).map((s) => [s.id, s]));
  const pk = night()?.peak;
  const h = selected != null ? hourAt(selected) : (pk ? hourAt(pk.time) : null);
  const ranked = h?.spots?.length ? h.spots : (data.spots || []).map((s) => [s.id, null, null, null]);
  const top = ranked.find((r) => (r[1] ?? 0) > 0);
  const stayPut = top && top[0] === "home";
  const bestId = top && !stayPut ? top[0] : null;
  const homeName = data.place?.name || "Here";
  const at = h ? hhmm(h.time) : null;
  $("spots-title").textContent = at ? `Where to go ${showTomorrow ? "tomorrow " : ""}at ${at}` : "Where to go";
  $("spots-sub").textContent = !h ? "" : !top ? "No clear, dark spot nearby at this hour"
    : stayPut ? "Best right where you are"
    : selected == null ? `${showTomorrow ? "Tomorrow's" : "Tonight's"} peak hour. ${homeName} matches the big number; other places have their own clouds.`
    : "At the hour you picked. Each place has its own clouds.";
  const hourKey = h?.time ?? null;
  if (pickedFor !== hourKey) { pickedFor = hourKey; pickedSpot = bestId; }
  if (TV) return renderSpotList(ranked, meta, top);

  const box = $("spot-map"), pick = $("spot-pick"), list = $("spots");
  const scores = new Map(ranked.filter((r) => r[1] != null).map((r) => [r[0], r[1]]));
  const choose = (id) => { pickedSpot = id; renderSpots(); };
  const home = data.place;
  if (home?.lat != null && meta.size) {
    box.hidden = false;
    renderMap(box, {
      home, scores, bestId, selectedId: pickedSpot, onSelect: choose, colorFor: scoreColor,
      spots: [{ id: "home", lat: home.lat, lon: home.lon }, ...[...meta.values()].filter((s) => s.id !== "home")],
    });
  } else box.hidden = true;

  // The picked pin: drive time, distance, cloud, score and Go.
  const row = ranked.find((r) => r[0] === pickedSpot), ps = row && meta.get(row[0]);
  if (ps) {
    const [, score, cloud] = row;
    const bits = [`≈ ${drive(ps.distanceKm)} min drive`, `${Math.round(ps.distanceKm)} km`];
    if (cloud != null) bits.push(cloudText(cloud));
    const href = `https://www.google.com/maps/dir/?api=1&destination=${ps.lat},${ps.lon}&travelmode=driving`;
    const fresh = pick.dataset.id !== ps.id;
    pick.replaceChildren(
      el("span", { class: "pick-body" },
        el("span", { class: "pick-name" }, ps.name),
        el("span", { class: "pick-detail" }, bits.join(" · "))),
      el("span", { class: "pick-score", style: score != null ? `color:${scoreColor(score)}` : "" }, score ?? "–"),
      el("a", { class: "pick-go", href, target: "_blank", rel: "noopener", "aria-label": `Directions to ${ps.name}` }, "Go"),
    );
    pick.dataset.id = ps.id;
    pick.hidden = false;
    if (fresh) { pick.classList.remove("in"); void pick.offsetWidth; pick.classList.add("in"); }
  } else { pick.hidden = true; delete pick.dataset.id; }

  // Every place, ranked: dot, name, distance, score. Tap one to pick it.
  list.replaceChildren();
  ranked.forEach(([id, score]) => {
    const s = meta.get(id);
    if (!s) return;
    const here = id === "home" || s.distanceKm < 1;
    const sub = here ? (s.dark ? "you're here" : "you're here · town lights dim faint aurora") : `${Math.round(s.distanceKm)} km · dark sky`;
    const col = score != null ? scoreColor(score) : "";
    const li = el("li", { class: "place" + (id === pickedSpot ? " picked" : "") },
      el("span", { class: "place-dot", style: col ? `background:${col}` : "" }),
      el("span", { class: "place-body" },
        el("span", { class: "place-name" }, here && id === "home" ? homeName : s.name),
        el("span", { class: "place-detail" }, sub)),
      el("span", { class: "place-score", style: col ? `color:${col}` : "" }, score ?? "–"),
    );
    if (!here) {
      li.tabIndex = 0;
      li.setAttribute("role", "button");
      li.addEventListener("click", () => choose(id));
      li.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); choose(id); } });
    }
    list.append(li);
  });
}

/** The lobby-sized list (TV mode): unchanged. */
function renderSpotList(ranked, meta, top) {
  const list = $("spots");
  list.replaceChildren();
  ranked.slice(0, 5).forEach(([id, score, cloud]) => {
    const s = meta.get(id);
    if (!s) return;
    const here = id === "home";
    const best = top && id === top[0] && !here;
    const detail = here
      ? (s.dark ? "you're here" : "you're here · town lights dim faint aurora")
      : `${Math.round(s.distanceKm)} km · ≈ ${drive(s.distanceKm)} min drive`;
    list.append(el("li", { class: "spot" + (best ? " best" : "") + (here ? " here" : "") },
      el("span", { class: "spot-score", style: score != null ? `color:${scoreColor(score)}` : "" }, score ?? "–"),
      el("span", { class: "spot-body" },
        el("span", { class: "spot-name" }, here ? data.place.name : s.name),
        el("span", { class: "spot-detail" }, cloud != null ? `${cloudText(cloud)} · ${detail}` : detail))));
  });
}

function renderOutlook() {
  // As in the app (KpOutlookCard): eight 3-hour channels a day, filled to the Kp (of 7), violet
  // for storm levels, green for active, teal below; a white dot under the block we're in.
  const box = $("outlook");
  box.replaceChildren();
  const now = data.now;
  (data.outlook || []).forEach((d, i) => {
    const label = i === 0 ? "Today" : i === 1 ? "Tomorrow" : weekday(d.dayStart);
    const kp = d.maxKp;
    const blocks = el("span", { class: "blocks", "aria-hidden": "true" });
    for (let b = 0; b < 8; b++) {
      const t0 = d.dayStart + b * 3 * HOUR;
      const k = d.blocks.find((x) => x.time === t0)?.kp;
      const fill = k == null ? null : el("span", { class: "kfill" + (k >= 5 ? " storm" : k >= 3 ? " active" : "") });
      if (fill) fill.style.height = `max(var(--kw), ${Math.min(100, (k / 7) * 100)}%)`;
      blocks.append(el("span", { class: "kslot" + (now >= t0 && now < t0 + 3 * HOUR ? " now" : "") }, el("span", { class: "kchan" }, fill)));
    }
    box.append(el("div", { class: "day" },
      el("span", { class: "day-name" }, label),
      blocks,
      el("span", { class: "day-kp" + (kp == null ? "" : kp >= 5 ? " storm" : kp >= 3 ? " active" : "") }, kp == null ? "—" : `Kp ${kp.toFixed(1)}`),
    ));
  });
}

function renderDress() {
  const w = data.weather;
  $("dress-card").hidden = !w;
  if (!w) return;
  const deg = (c) => { const r = Math.round(c); return `${r < 0 ? "−" : ""}${Math.abs(r)}°`; };
  const advice = w.feels <= -10 ? "Serious cold. Layers, hat, gloves, warm boots, and a flask."
    : w.feels <= 0 ? "Cold. Warm jacket, hat and gloves; you'll be standing still."
    : w.feels <= 6 ? "Chilly. A proper jacket and a hat."
    : "Mild for an aurora night. A light jacket will do.";
  $("dress-sub").textContent = `At ${hhmm(w.time)} in ${data.place.name}`;
  $("dress-temp").textContent = deg(w.temp);
  $("dress-feels").textContent = `feels like ${deg(w.feels)}${w.wind != null ? ` · ${Math.round(w.wind)} m/s wind` : ""}`;
  $("dress-advice").textContent = advice;
}

function renderTips() {
  const h = focusHour();
  const score = h ? h.score : 0;
  const [shutter, iso] = score >= 80 ? ["0.5–2 s", "800"] : score >= 60 ? ["2–4 s", "800–1600"] : ["4–8 s", "1600–3200"];
  const moonBright = h && h.moonAlt > 0 && h.moonIllum > 0.5;
  const tips = [
    `Night mode or Pro: ${shutter}, ISO ${iso}`,
    "Focus at infinity (∞), or tap a bright star",
    "Rest the phone on something solid; use a 3 s timer",
    moonBright ? "Bright moon: drop ISO a step, it'll light the landscape for you" : "Dark sky: include a foreground, like a lighthouse, rocks or the shoreline",
  ];
  $("tips").replaceChildren(...tips.map((t) => el("li", {}, t)));
}

// Look up now. On a TV it takes over the whole screen, alternating with the forecast so the
// rest stays readable; on a phone it's a banner at the top.
let takeoverTimer = 0;
function renderLookUp() {
  const c = data.current;
  const go = !!c.lookUp;
  const bits = [];
  if (c.bz != null && c.bzFresh) bits.push(`Bz ${signed(c.bz)} nT`);
  bits.push(cloudText(c.cloud));
  if (c.clearer) bits.push(`clearer at ${c.clearer.name}`);
  $("lookup-detail").textContent = bits.join(" · ");
  $("banner-detail").textContent = bits.join(" · ");
  $("banner").hidden = !go || TV;
  document.documentElement.classList.toggle("looking-up", go);
  clearInterval(takeoverTimer);
  if (go && TV) {
    let show = true;
    $("lookup").hidden = false;
    takeoverTimer = setInterval(() => { show = !show; $("lookup").hidden = !show; }, 20000);
  } else {
    $("lookup").hidden = true;
  }
}

// ---------------------------------------------------------------------------------------------
// Location (phone only)
// ---------------------------------------------------------------------------------------------
function locate() {
  if (!("geolocation" in navigator)) return showProblem("This browser can't share its location.");
  const btn = $("locate");
  btn.disabled = true;
  btn.classList.add("busy");
  btn.setAttribute("aria-label", "Finding you…");
  navigator.geolocation.getCurrentPosition(
    (p) => {
      store.set("ljos.pos", { lat: p.coords.latitude, lon: p.coords.longitude });
      btn.disabled = false;
      btn.classList.remove("busy");
      selected = null;
      load();
    },
    () => {
      btn.disabled = false;
      btn.classList.remove("busy");
      renderHeader();
      showProblem("Location is off for this site, so this shows Njarðvík. You can allow it in your browser settings.");
    },
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 10 * MIN },
  );
}

// ---------------------------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------------------------
if (!TV) {
  $("locate").addEventListener("click", locate);
  $("refresh").addEventListener("click", async () => {
    const b = $("refresh");
    if (b.classList.contains("busy")) return;
    b.classList.add("busy");
    await load();
    b.classList.remove("busy");
  });
  $("place-pill").addEventListener("click", locate);
  // As in the app: the header turns to glass once the page scrolls, and once the big number has
  // scrolled away the score shows in the place pill.
  const top = document.querySelector(".top");
  const onScroll = () => top.style.setProperty("--glass-in", String(Math.min(1, scrollY / 24)));
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();
  if ("IntersectionObserver" in window) {
    new IntersectionObserver(([e]) => top.classList.toggle("score-up", !e.isIntersecting), { rootMargin: "-90px 0px 0px 0px" }).observe($("score"));
  }
}
$("banner").addEventListener("click", () => { $("banner").hidden = true; });

load();
if (TV) {
  setInterval(load, 60 * 1000);
  // Clock, and a slow drift of a few pixels so nothing burns into the panel.
  const tick = () => { $("clock").textContent = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: zone() }).format(Date.now()); };
  tick();
  setInterval(tick, 10 * 1000);
  let step = 0;
  setInterval(() => {
    step++;
    const dx = Math.round(Math.sin(step / 3) * 6), dy = Math.round(Math.cos(step / 4) * 4);
    $("drift").style.transform = `translate(${dx}px, ${dy}px)`;
  }, 60 * 1000);
  // Keep the screen on where the browser allows it.
  const wake = async () => { try { await navigator.wakeLock?.request("screen"); } catch { /* not allowed: fine */ } };
  wake();
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") wake(); });
} else {
  setInterval(() => { if (document.visibilityState === "visible") load(); }, 5 * 60 * 1000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && Date.now() - lastLoad > 2 * 60 * 1000) load();
  });
}
// Keep "updated x min ago" honest between loads.
setInterval(() => { if (data) renderHeader(); }, 30 * 1000);

// Offline helper and updates: a new version installs quietly; "Update" switches to it.
if ("serviceWorker" in navigator) {
  const hadController = !!navigator.serviceWorker.controller;
  let reloading = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => { if (hadController && !reloading) { reloading = true; location.reload(); } });
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").then((reg) => {
      const offer = (w) => {
        if (!w || TV) return;
        $("update").hidden = false;
        $("update-go").onclick = () => { $("update-go").disabled = true; w.postMessage({ type: "SKIP_WAITING" }); };
      };
      if (reg.waiting && navigator.serviceWorker.controller) offer(reg.waiting);
      reg.addEventListener("updatefound", () => {
        const w = reg.installing;
        w?.addEventListener("statechange", () => { if (w.state === "installed" && navigator.serviceWorker.controller) offer(w); });
      });
      // Someone who keeps it open all night still hears about new versions.
      setInterval(() => { if (document.visibilityState === "visible") reg.update().catch(() => {}); }, 30 * 60 * 1000);
    }).catch(() => {});
  });
}

// Install: Android/Chrome offers its own prompt (we show a small "Install" button for it);
// iPhone Safari has none, so it gets a one-line how-to. Either can be dismissed for good.
(() => {
  if (TV) return;
  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  let dismissed = false;
  try { dismissed = localStorage.getItem("ljos-install-dismissed") === "1"; } catch { /* ignore */ }
  if (standalone || dismissed) return;
  const box = $("install");
  const hide = (forGood) => {
    box.hidden = true;
    if (forGood) try { localStorage.setItem("ljos-install-dismissed", "1"); } catch { /* ignore */ }
  };
  $("install-x").addEventListener("click", () => hide(true));
  let prompt = null;
  addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    prompt = e;
    $("install-text").textContent = "Install Ljós on your phone";
    $("install-go").hidden = false;
    box.hidden = false;
  });
  $("install-go").addEventListener("click", async () => {
    if (!prompt) return;
    prompt.prompt();
    const choice = await prompt.userChoice.catch(() => null);
    prompt = null;
    hide(choice?.outcome === "accepted");
  });
  addEventListener("appinstalled", () => hide(true));
  const ua = navigator.userAgent;
  const iosSafari = /iP(hone|ad|od)/.test(ua) && /Safari/.test(ua) && !/CriOS|FxiOS|EdgiOS/.test(ua);
  if (iosSafari) {
    $("install-text").textContent = "Add Ljós to your Home Screen: tap Share, then Add to Home Screen";
    $("install-go").hidden = true;
    setTimeout(() => { box.hidden = false; }, 4000);
  }
})();
