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
async function load() {
  lastLoad = Date.now();
  try {
    const r = await fetch(endpoint(), { headers: { Accept: "application/json" }, cache: "no-cache" });
    if (r.status === 404 && TV) return showProblem("This screen's place isn't set up. Check the address ends in /lobby/<place>.");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    data = await r.json();
    hideProblem();
    render();
    // The edge lens (glasspane.js, Chromium) on every glass surface. No moving light: no shine.
    if (!glassOn && window.Glasspane) { glassOn = true; window.Glasspane.init({ selector: ".lg", light: false }); }
  } catch (e) {
    if (data) showProblem("Couldn't reach the forecast. Showing the last one.");
    else showProblem("Reading the sky… the forecast feeds are busy, trying again shortly.");
  }
}

function showProblem(text) { const b = $("problem"); b.textContent = text; b.hidden = false; }
function hideProblem() { $("problem").hidden = true; }

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------
const sky = startSky($("sky"), { scale: TV ? 0.5 : 0.6, rich: true, maxWidth: 1280, moonSize: 0.032 });
skyLab($("brand"), () => setSky());

let showTomorrow = false; // the Tonight / Tomorrow switch in Hour by hour, as in the app
const hasTomorrow = () => (data?.tomorrow?.hours?.length || 0) > 0;
/** The night being shown: tonight, or tomorrow night when the switch says so. */
function night() { return showTomorrow && hasTomorrow() ? data.tomorrow : data?.tonight; }
function hours() { return night()?.hours || []; }
function hourAt(time) { return hours().find((h) => h.time === time) || null; }
function nowHour() { return data?.current?.hour || null; }
function focusHour() {
  if (selected != null) return hourAt(selected);
  return night()?.peak || null;
}

function render() {
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
  if (locate) locate.textContent = store.get("ljos.pos") ? "Update location" : "Use my location";
}

function renderHero() {
  const n = night();
  const which = showTomorrow && hasTomorrow() ? "Tomorrow" : "Tonight";
  const peak = n.peak;
  const h = focusHour();
  const now = data.now;
  if (!peak || !h) {
    $("eyebrow").textContent = which;
    setScore(null);
    $("chance").textContent = "Too bright for aurora";
    $("peak-line").textContent = "Nights are too light here this time of year.";
    return;
  }
  const isPeak = selected == null || selected === peak.time;
  $("eyebrow").textContent = isPeak ? `${which}'s best` : `At ${hhmm(h.time)}`;
  setScore(h.score);
  $("chance").textContent = CHANCE[h.chance] || "";
  let line = "";
  if (selected != null && !isPeak) {
    line = `${cloudText(h.cloud)} · Kp ${h.kp?.toFixed(1)}`;
  } else if (peak.score <= 0) {
    const when = which === "Tonight" ? "tonight" : "tomorrow night";
    line = n.darkFrom && now < n.darkFrom ? `Dark in ${span(n.darkFrom - now)} · no aurora expected ${when}` : `No aurora expected ${when}`;
  } else if (n.darkFrom && now < n.darkFrom) {
    line = `Dark in ${span(n.darkFrom - now)} · peak around ${hhmm(peak.time)}`;
  } else if (now < peak.time) {
    line = `Peak in ${span(peak.time - now)}, around ${hhmm(peak.time)}`;
  } else if (n.darkUntil && now < n.darkUntil) {
    line = `Best was around ${hhmm(peak.time)} · dark until ${hhmm(n.darkUntil)}`;
  } else {
    line = `Peak around ${hhmm(peak.time)}`;
  }
  $("peak-line").textContent = line;
}

function setScore(score) {
  const s = $("score");
  s.textContent = score == null ? "–" : String(score);
  s.style.color = score == null ? "" : scoreColor(score);
  $("hero").style.setProperty("--glow", score == null ? "transparent" : scoreColor(score));
}

/** Tonight / Tomorrow: a groove with a green thumb that slides, like the app's segments. */
function renderNightSwitch() {
  const seg = $("night-seg");
  seg.hidden = !hasTomorrow();
  const on = showTomorrow && hasTomorrow() ? 1 : 0;
  seg.dataset.pos = String(on);
  seg.querySelectorAll("button").forEach((b, i) => b.setAttribute("aria-selected", i === on ? "true" : "false"));
}
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
      "aria-label": `${hhmm(h.time)}, score ${h.score}, ${CHANCE[h.chance] || ""}`,
      "aria-pressed": selected === h.time ? "true" : "false",
      onclick: () => { selected = selected === h.time ? null : h.time; render(); },
    },
      el("span", { class: "value" }, h.score),
      el("span", { class: "track" }, el("span", { class: "fill", style: `height:${pct}%;background:${scoreColor(h.score)}` })),
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

function renderNow() {
  const c = data.current;
  const n = data.tonight;
  let state, tone;
  if (c.lookUp) { state = "Look up now"; tone = "go"; }
  else if (!c.isDark) { state = n.darkFrom && data.now < n.darkFrom ? `Dark from ${hhmm(n.darkFrom)}` : "Too light right now"; tone = "quiet"; }
  else if (c.bz == null || !c.bzFresh) { state = "No live solar wind"; tone = "quiet"; }
  else { state = "Watching the sky"; tone = "watch"; }
  $("now-state").textContent = state;
  $("now-state").dataset.tone = tone;
  $("now-score").textContent = c.score;
  $("now-score").style.color = scoreColor(c.score);

  const lines = [];
  if (c.bz == null) lines.push("Solar wind data unavailable");
  else if (!c.bzFresh) lines.push("Solar wind data is stale");
  else {
    const dir = c.bz <= -3 ? "field pointing south, good" : c.bz >= 3 ? "pointing north, quiet" : "neutral";
    lines.push(`Bz ${signed(c.bz)} nT · ${dir}${c.etaMinutes != null ? ` · here in ~${c.etaMinutes} min` : ""}`);
  }
  if (c.bzSustained != null) lines.push(`Arriving now: Bz ${signed(c.bzSustained)} nT on average`);
  lines.push(`Clouds here: ${cloudText(c.cloud)}`);
  if (c.clearer) lines.push(`Clearer at ${c.clearer.name} (${cloudText(c.clearer.cloud)})`);
  $("now-lines").replaceChildren(...lines.map((l) => el("li", {}, l)));
  renderSpark();
}

function renderSpark() {
  const box = $("spark");
  const pts = data.solarWind || [];
  if (pts.length < 6) { box.hidden = true; return; }
  box.hidden = false;
  const w = 300, hgt = 64;
  const t0 = pts[0][0], t1 = pts[pts.length - 1][0];
  const max = Math.max(5, ...pts.map((p) => Math.abs(p[1])));
  const x = (t) => ((t - t0) / (t1 - t0 || 1)) * w;
  const y = (v) => hgt / 2 - (v / max) * (hgt / 2 - 4);
  const line = pts.map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)} ${y(p[1]).toFixed(1)}`).join("");
  const area = `${line}L${w} ${hgt / 2}L0 ${hgt / 2}Z`;
  box.innerHTML = "";
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${w} ${hgt}`);
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "Bz at the satellite over the last two hours; below the line is south, which is good");
  const mk = (tag, attrs) => { const n = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v); return n; };
  const clipId = "below";
  const defs = mk("defs", {});
  const clip = mk("clipPath", { id: clipId });
  clip.append(mk("rect", { x: 0, y: hgt / 2, width: w, height: hgt / 2 }));
  defs.append(clip);
  svg.append(defs);
  svg.append(mk("path", { d: area, class: "spark-south", "clip-path": `url(#${clipId})` }));
  svg.append(mk("line", { x1: 0, x2: w, y1: hgt / 2, y2: hgt / 2, class: "spark-zero" }));
  svg.append(mk("path", { d: line, class: "spark-line" }));
  box.append(svg, el("p", { class: "quiet small" }, "Bz at the satellite, last 2 h · shaded = south (good)"));
}

let pickedSpot = null;
let mapResize = 0;
addEventListener("resize", () => { clearTimeout(mapResize); mapResize = setTimeout(() => data && renderSpots(), 150); });

function renderSpots() {
  const meta = new Map((data.spots || []).map((s) => [s.id, s]));
  const pk = night()?.peak;
  const h = selected != null ? hourAt(selected) : (pk ? hourAt(pk.time) : null);
  const ranked = h?.spots?.length ? h.spots : (data.spots || []).map((s) => [s.id, null, null, null]);
  const top = ranked.find((r) => (r[1] ?? 0) > 0);
  const stayPut = top && top[0] === "home";
  const at = h ? hhmm(h.time) : null;
  $("spots-title").textContent = at ? `Where to go at ${at}` : "Where to go";
  $("spots-sub").textContent = !h ? "" : !top ? "No clear, dark spot nearby at this hour"
    : stayPut ? "Best right where you are" : "Each place has its own clouds";
  renderSpotMap(meta, ranked, top);
  const list = $("spots");
  list.replaceChildren();
  ranked.slice(0, TV ? 5 : 9).forEach(([id, score, cloud], i) => {
    const s = meta.get(id);
    if (!s) return;
    const here = id === "home";
    const best = top && id === top[0] && !here;
    const detail = here
      ? (s.dark ? "you're here" : "you're here · town lights dim faint aurora")
      : `${Math.round(s.distanceKm)} km · ≈ ${drive(s.distanceKm)} min drive`;
    const name = here ? data.place.name : s.name;
    const row = el("li", { class: "spot" + (best ? " best" : "") + (here ? " here" : "") },
      el("span", { class: "spot-score", style: score != null ? `color:${scoreColor(score)}` : "" }, score ?? "–"),
      el("span", { class: "spot-body" },
        el("span", { class: "spot-name" }, name),
        el("span", { class: "spot-detail" }, cloud != null ? `${cloudText(cloud)} · ${detail}` : detail)),
    );
    if (!here && !TV) {
      const href = `https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}`;
      row.append(el("a", { class: "go gp-tinted", href, target: "_blank", rel: "noopener", "aria-label": `Directions to ${s.name}` }, "Go"));
    }
    list.append(row);
  });
}

function renderSpotMap(meta, ranked, top) {
  const box = $("spot-map"), pick = $("spot-pick");
  const home = data.place;
  if (TV || !home || home.lat == null || !(data.spots || []).length) { box.hidden = true; pick.hidden = true; return; }
  box.hidden = false;
  const scores = new Map(ranked.filter((r) => r[1] != null).map((r) => [r[0], r[1]]));
  const clouds = new Map(ranked.map((r) => [r[0], r[2]]));
  const spots = [{ id: "home", lat: home.lat, lon: home.lon }, ...data.spots.filter((s) => s.id !== "home")];
  if (pickedSpot && !meta.has(pickedSpot)) pickedSpot = null;
  renderMap(box, {
    home, spots, scores, colorFor: scoreColor,
    bestId: top && top[0] !== "home" ? top[0] : null,
    selectedId: pickedSpot,
    onSelect: (id) => { pickedSpot = pickedSpot === id ? null : id; renderSpots(); },
  });
  const s = pickedSpot && meta.get(pickedSpot);
  if (!s) { pick.hidden = true; return; }
  const score = scores.get(s.id), cloud = clouds.get(s.id);
  const bits = [`≈ ${drive(s.distanceKm)} min drive`, `${Math.round(s.distanceKm)} km`];
  if (cloud != null) bits.push(cloudText(cloud));
  const href = `https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}`;
  pick.replaceChildren(
    el("span", { class: "pick-body" },
      el("span", { class: "spot-name" }, s.name),
      el("span", { class: "spot-detail" }, bits.join(" · "))),
    el("span", { class: "pick-score", style: score != null ? `color:${scoreColor(score)}` : "" }, score ?? "–"),
    el("a", { class: "go gp-tinted", href, target: "_blank", rel: "noopener" }, "Directions"),
  );
  pick.hidden = false;
}

function renderOutlook() {
  const box = $("outlook");
  box.replaceChildren();
  (data.outlook || []).forEach((d, i) => {
    const label = i === 0 ? "Today" : i === 1 ? "Tomorrow" : weekday(d.dayStart);
    const kp = d.maxKp;
    const word = kp == null ? "no forecast" : kp >= 5 ? "storm" : kp >= 4 ? "active" : kp >= 3 ? "unsettled" : "quiet";
    box.append(el("div", { class: "day" },
      el("span", { class: "day-name" }, label),
      el("span", { class: "blocks", "aria-hidden": "true" },
        ...d.blocks.map((b) => el("span", { class: "block", style: `background:${kpColor(b.kp)};opacity:${0.35 + Math.min(1, b.kp / 6) * 0.65}` }))),
      el("span", { class: "day-kp" }, kp == null ? "–" : `Kp ${kp.toFixed(1)}`, el("span", { class: "quiet" }, ` ${word}`)),
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
  btn.textContent = "Finding you…";
  navigator.geolocation.getCurrentPosition(
    (p) => {
      store.set("ljos.pos", { lat: p.coords.latitude, lon: p.coords.longitude });
      btn.disabled = false;
      selected = null;
      load();
    },
    () => {
      btn.disabled = false;
      renderHeader();
      showProblem("Location is off for this site, so this shows Njarðvík. You can allow it in your browser settings.");
    },
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 10 * MIN },
  );
}

// ---------------------------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------------------------
if (!TV) $("locate").addEventListener("click", locate);
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

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => { navigator.serviceWorker.register("/sw.js").catch(() => {}); });
}
