// Sky lab: a hidden panel for trying every live-sky effect without waiting for the weather.
// Open it by tapping the wordmark five times quickly, or typing "sky" on a keyboard.
// Esc or the close button hides it. Nothing here changes the forecast, only the background.

const CONTROLS = [
  // [key, label, min, max, step, unit]
  ["activity", "Aurora activity", 0, 1, 0.01, ""],
  ["kp", "Kp", 0, 9, 0.1, ""],
  ["bz", "Solar wind Bz", -20, 10, 0.5, " nT"],
  ["cloudLow", "Low cloud", 0, 100, 1, "%"],
  ["cloudMid", "Mid cloud", 0, 100, 1, "%"],
  ["cloudHigh", "High cloud", 0, 100, 1, "%"],
  ["windDir", "Wind from", 0, 359, 1, "°"],
  ["wind", "Wind speed", 0, 25, 0.5, " m/s"],
  ["rain", "Rain", 0, 8, 0.1, " mm/h"],
  ["snow", "Snow", 0, 3, 0.05, " cm/h"],
  ["visibility", "Visibility", 200, 40000, 100, " m"],
  ["moonAlt", "Moon height", -10, 70, 1, "°"],
  ["moonAz", "Moon direction", 0, 359, 1, "°"],
  ["moonIllum", "Moon lit", 0, 1, 0.01, ""],
  ["sunAlt", "Sun height", -40, 10, 0.5, "°"],
  ["sunAz", "Sun direction", 0, 359, 1, "°"],
  ["hoursAhead", "Stars, hours ahead", 0, 12, 0.25, " h"],
];

const BASE = { activity: 0.5, kp: 3.5, bz: -2, cloudLow: 0, cloudMid: 0, cloudHigh: 0, windDir: 250, wind: 6, rain: 0, snow: 0,
  visibility: 30000, moonAlt: 25, moonAz: 160, moonIllum: 0.6, sunAlt: -30, sunAz: 0, hoursAhead: 0, waxing: true };

const PRESETS = {
  "Clear, full moon": { moonIllum: 1, moonAlt: 35, moonAz: 175, activity: 0.45, kp: 3.3 },
  "Dark, new moon": { moonIllum: 0.02, moonAlt: -5, activity: 0.55, kp: 3.7 },
  "Big storm": { activity: 1, kp: 7.3, bz: -16, moonAlt: -5 },
  "Snow": { cloudLow: 55, cloudMid: 30, cloudHigh: 20, snow: 1.2, visibility: 2500, wind: 8, windDir: 10, activity: 0.35 },
  "Rain": { cloudLow: 40, cloudMid: 75, cloudHigh: 40, rain: 3, wind: 14, windDir: 225, visibility: 9000, activity: 0.3 },
  "Fog": { cloudLow: 85, visibility: 600, wind: 2, activity: 0.3, moonIllum: 0.5, moonAlt: 15 },
  "High cloud veil": { cloudHigh: 70, moonIllum: 0.8, moonAlt: 30, activity: 0.5 },
  "Dusk": { sunAlt: -5, sunAz: 255, cloudHigh: 35, activity: 0.3, moonAlt: 12, moonAz: 110, moonIllum: 0.4 },
};

let state = null; // null = live sky
let panel = null;
let changed = () => {};

/** The hour with the lab's values on top, or the hour itself when the lab is off. */
export function labHour(hour) {
  if (!state || !hour) return hour;
  const s = state;
  return {
    ...hour,
    activity: s.activity, kp: s.kp,
    cloudLow: s.cloudLow, cloudMid: s.cloudMid, cloudHigh: s.cloudHigh,
    cloud: Math.round(Math.max(s.cloudLow, s.cloudMid, s.cloudHigh * 0.5)),
    windDir: s.windDir, wind: s.wind, rain: s.rain, snow: s.snow, visibility: s.visibility,
    moonAlt: s.moonAlt, moonAz: s.moonAz, moonIllum: s.moonIllum, sunAlt: s.sunAlt, sunAz: s.sunAz,
  };
}
/** Bz, phase direction and star time with the lab's values on top. */
export function labExtras({ bz, waxing, now }) {
  if (!state) return { bz, waxing, now };
  return { bz: state.bz, waxing: state.waxing, now: now + state.hoursAhead * 3600000 };
}

/** Sets up the hidden triggers. [onChange] re-renders the sky. */
export function skyLab(trigger, onChange) {
  changed = onChange;
  let taps = [];
  trigger?.addEventListener("click", () => {
    const t = Date.now();
    taps = taps.filter((x) => t - x < 3000).concat(t);
    if (taps.length >= 5) { taps = []; open(); }
  });
  let typed = "";
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && panel && !panel.hidden) { panel.hidden = true; return; }
    if (e.target instanceof HTMLInputElement) return;
    typed = (typed + e.key.toLowerCase()).slice(-3);
    if (typed === "sky") open();
  });
}

function open() {
  if (!panel) build();
  panel.hidden = false;
}

function build() {
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = "/skylab.css";
  document.head.append(link);

  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; };
  panel = el("aside", "skylab");
  panel.setAttribute("aria-label", "Sky lab");
  const head = el("div", "skylab-head");
  const title = el("p", "skylab-title", "Sky lab");
  const mode = el("span", "skylab-mode", "Live");
  const close = el("button", "skylab-close", "Close");
  close.type = "button";
  close.addEventListener("click", () => { panel.hidden = true; });
  head.append(title, mode, close);

  const presets = el("div", "skylab-presets");
  const live = el("button", "skylab-chip on", "Live");
  live.type = "button";
  const chips = [live];
  live.addEventListener("click", () => { state = null; sync(); });
  presets.append(live);
  for (const [name, p] of Object.entries(PRESETS)) {
    const b = el("button", "skylab-chip", name);
    b.type = "button";
    b.addEventListener("click", () => { state = { ...BASE, ...p }; sync(name); });
    chips.push(b);
    presets.append(b);
  }

  const list = el("div", "skylab-list");
  const inputs = {};
  for (const [key, label, min, max, step, unit] of CONTROLS) {
    const row = el("label", "skylab-row");
    const name = el("span", "skylab-name", label);
    const val = el("span", "skylab-val");
    const input = document.createElement("input");
    Object.assign(input, { type: "range", min, max, step });
    input.addEventListener("input", () => {
      state = { ...(state || BASE), [key]: Number(input.value) };
      sync("Custom");
    });
    row.append(name, val, input);
    list.append(row);
    inputs[key] = { input, val, unit, step };
  }
  const wax = el("label", "skylab-row skylab-check");
  const waxBox = document.createElement("input");
  waxBox.type = "checkbox";
  waxBox.addEventListener("change", () => { state = { ...(state || BASE), waxing: waxBox.checked }; sync("Custom"); });
  wax.append(waxBox, el("span", "skylab-name", "Waxing (lit on the right)"));
  list.append(wax);

  panel.append(head, presets, list);
  document.body.append(panel);

  function sync(name) {
    const s = state || BASE;
    mode.textContent = state ? name || "Custom" : "Live";
    for (const c of chips) c.classList.toggle("on", state ? c.textContent === name : c === live);
    for (const [key, { input, val, unit, step }] of Object.entries(inputs)) {
      input.value = String(s[key]);
      const v = s[key];
      val.textContent = (step < 1 ? Number(v).toFixed(step < 0.1 ? 2 : 1) : Math.round(v)) + unit;
    }
    waxBox.checked = !!s.waxing;
    panel.classList.toggle("is-live", !state);
    changed();
  }
  sync();
}
