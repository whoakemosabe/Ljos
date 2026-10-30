// The Ljós sky, ported from the Android AGSL shader (ui/Aurora.kt) to WebGL.
// The sky *is* the forecast: curtain brightness and height follow activity, storms tint
// violet, cloud rolls in as fog, and the real moon sits in the sky at its phase.
//
// The live sky (startSky with { rich: true }, on the phone page and the lobby, matching the
// Android app): the moon and twilight sit at their real compass directions, a bright moon
// washes out faint aurora, cloud comes in three layers drifting with the wind, rain, snow and
// haze follow the weather, the aurora reddens and brightens with the data, and real stars sit
// where they are. The moon itself is drawn at full screen resolution on its own small canvas
// (moonFace below), since the sky renders at reduced resolution. The same mapping lives in
// core SkyView.kt for the app; keep the two in step.

const VERT = `
attribute vec2 p;
void main() { gl_Position = vec4(p, 0.0, 1.0); }
`;

const FRAG = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 iResolution;
uniform float iTime;
uniform float activity;
uniform float cloud;
uniform float moonIllum;
uniform float moonUp;
uniform vec2 moonPos;
uniform float storm;
uniform float daylight;
uniform float dusk;
uniform float pixel;
// Rich sky (lobby). All zero on the phone.
uniform float rich;
uniform float waxing;
uniform float moonWash;
uniform float cloudLow;
uniform float cloudMid;
uniform float cloudHigh;
uniform vec2 drift;
uniform float rain;
uniform float rainSlant;
uniform float snow;
uniform float haze;
uniform vec2 sunPos;
uniform float bzBoost;
uniform float redFringe;
uniform float pinkEdge;
uniform vec3 stars[14];

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash(i);
  float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0));
  float d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
  float v = 0.0;
  float amp = 0.5;
  for (int i = 0; i < 4; i++) {
    v += amp * noise(p);
    p = p * 2.03 + vec2(1.7, 9.2);
    amp *= 0.5;
  }
  return v;
}

void main() {
  // Same orientation as the Android shader: y = 0 at the top of the screen.
  vec2 fragCoord = vec2(gl_FragCoord.x, iResolution.y - gl_FragCoord.y);
  vec2 uv = fragCoord / iResolution;
  float aspect = iResolution.x / iResolution.y;
  float t = iTime * 0.04;
  vec3 col = mix(vec3(0.012, 0.020, 0.050), vec3(0.030, 0.060, 0.110), smoothstep(0.0, 0.7, uv.y));

  float day = daylight;
  vec3 dayCol = mix(vec3(0.075, 0.13, 0.24), vec3(0.15, 0.20, 0.32), smoothstep(0.0, 0.8, uv.y));
  col = mix(col, dayCol, day);
  col += (1.0 - rich) * dusk * vec3(0.22, 0.10, 0.06) * smoothstep(0.15, 0.6, uv.y) * (1.0 - smoothstep(0.6, 0.95, uv.y));
  // Rich: twilight glows on the horizon where the sun actually is, and a high moon lights the sky.
  vec2 sp = vec2((uv.x - sunPos.x) * aspect, (uv.y - sunPos.y) * 2.4);
  col += rich * dusk * vec3(0.55, 0.24, 0.10) * exp(-length(sp) * 1.3) * smoothstep(0.0, 0.38, uv.y);
  col += rich * moonWash * vec3(0.035, 0.05, 0.08) * (1.0 - smoothstep(0.0, 0.7, uv.y));
  float glow = (0.08 + 1.05 * pow(clamp(activity, 0.0, 1.0), 1.3)) * (1.0 - 0.95 * smoothstep(0.0, 0.7, day));
  glow *= 1.0 + rich * (0.6 * bzBoost - 0.4 * moonWash);
  float height = 0.65 + 0.8 * clamp(activity, 0.0, 1.0);

  for (int k = 0; k < 3; k++) {
    float fk = float(k);
    float x = uv.x * aspect * (1.2 + fk * 0.35) + fk * 5.3;
    float wave = fbm(vec2(x * 0.9 + t * (1.0 + fk * 0.4), t * 0.7 + fk * 3.1));
    float base = 0.22 + fk * 0.075 + (wave - 0.5) * 0.22;
    float d = uv.y - base;
    float band = d < 0.0 ? exp(d * (7.0 - fk) / height) : exp(-d * 45.0);
    float rays = 0.45 + 0.55 * fbm(vec2(x * 7.0 + t * 3.0, uv.y * 1.2 - t));
    float a = band * rays * glow * (0.75 - fk * 0.18);
    vec3 green = vec3(0.24, 1.0, 0.63);
    vec3 violet = vec3(0.62, 0.45, 1.0);
    vec3 c = mix(green, violet, clamp(-d * 4.0 + fk * 0.2 + storm * 0.6, 0.0, 1.0));
    // Rich: strong nights get red tops and a pink lower edge, as they do in photos.
    c = mix(c, vec3(1.0, 0.28, 0.32), rich * redFringe * smoothstep(0.08, 0.32, -d));
    c = mix(c, vec3(1.0, 0.45, 0.78), rich * pinkEdge * exp(-abs(d + 0.006) * 55.0));
    col += c * a;
  }

  // Moon: soft halo plus a disc with its real phase.
  vec2 mp = vec2((uv.x - moonPos.x) * aspect, uv.y - moonPos.y);
  float md = length(mp);
  col += vec3(0.85, 0.82, 0.70) * exp(-md * 9.0) * 0.35 * moonIllum * moonUp;
  float r = 0.032;
  float disc = smoothstep(r, r - 0.003, md);
  // Rich: the disc is drawn sharp on its own canvas instead (moonFace); only the halo is here.
  float shadow = smoothstep(r, r - 0.003, length(mp - vec2(moonIllum * 2.0 * r, 0.0)));
  col = mix(col, vec3(0.95, 0.92, 0.80), disc * (1.0 - shadow) * moonUp * (1.0 - rich));

  // Stars dim under a bright moon and vanish behind cloud. Sized in CSS pixels.
  float s = hash(floor(fragCoord / (3.0 * pixel)));
  float twinkle = 0.6 + 0.4 * sin(iTime * 2.0 + s * 40.0);
  float starDim = (1.0 - 0.75 * moonIllum * moonUp) * (1.0 - clamp(cloud, 0.0, 1.0)) * (1.0 - smoothstep(0.05, 0.45, day));
  starDim *= 1.0 - rich * 0.55 * moonWash;
  col += vec3(step(0.9982, s) * twinkle * (1.0 - smoothstep(0.2, 0.6, uv.y)) * 0.8 * starDim);

  if (rich > 0.5) {
    // Real bright stars (Big Dipper, Polaris, Cassiopeia, Capella, Vega) where they are now.
    for (int i = 0; i < 14; i++) {
      vec3 st = stars[i];
      if (st.z > 0.0) {
        vec2 dp = (fragCoord - st.xy * iResolution) / pixel;
        float d2 = dot(dp, dp);
        col += vec3(0.88, 0.92, 1.0) * st.z * starDim * (exp(-d2 * 0.45) + 0.12 * exp(-sqrt(d2) * 0.5));
      }
    }

    // Cloud in three layers, drifting the way the wind blows: a thin high veil, a mid deck,
    // and dense low cloud that moves fastest. Each catches some moonlight.
    vec2 dr = drift * iTime * 0.003;
    if (cloudHigh > 0.02) {
      float hiN = fbm(vec2((uv.x * aspect + dr.x * 0.6) * 1.1, uv.y * 7.0 + dr.y));
      float hi = clamp(cloudHigh * (hiN * 1.4 - 0.25), 0.0, 1.0) * (1.0 - smoothstep(0.15, 0.65, uv.y));
      col = mix(col, vec3(0.13, 0.14, 0.18) + vec3(0.26, 0.25, 0.22) * moonWash + vec3(0.30, 0.14, 0.07) * dusk, hi * 0.6);
    }
    if (cloudMid > 0.02) {
      float miN = fbm(vec2((uv.x * aspect + dr.x) * 2.0, uv.y * 3.0 + dr.y) + 3.7);
      float mi = clamp(cloudMid * (0.6 + 0.9 * miN) - 0.15, 0.0, 1.0);
      col = mix(col, vec3(0.095, 0.105, 0.13) + vec3(0.14, 0.14, 0.12) * moonWash, mi * 0.8);
    }
    if (cloudLow > 0.02) {
      float loN = fbm(vec2((uv.x * aspect + dr.x * 1.7) * 2.6, uv.y * 2.2 + dr.y) + 7.1);
      float lo = clamp(cloudLow * (0.45 + 0.9 * loN) - 0.15, 0.0, 1.0) * smoothstep(0.02, 0.3, uv.y);
      // Low cloud: darker cores with lighter, moonlit tops so the shapes read against the night.
      vec3 loCol = mix(vec3(0.05, 0.055, 0.07), vec3(0.11, 0.12, 0.145), smoothstep(0.35, 0.8, loN)) + vec3(0.10, 0.10, 0.09) * moonWash;
      col = mix(col, loCol, lo * 0.92);
    }

    // Haze and fog from poor visibility.
    col = mix(col, vec3(0.10, 0.11, 0.13) + vec3(0.08) * moonWash, haze * 0.6);

    // Rain: thin streaks slanted by the wind. Snow: two layers of drifting flakes.
    vec2 px = fragCoord / pixel;
    if (rain > 0.0) {
      vec2 q = vec2(px.x + px.y * rainSlant, px.y - iTime * 650.0);
      vec2 cs = vec2(16.0, 72.0);
      vec2 cell = floor(q / cs);
      vec2 f = fract(q / cs);
      float x = 0.1 + 0.8 * hash(cell + 1.3);
      float line = step(hash(cell), rain) * smoothstep(0.09, 0.0, abs(f.x - x)) * smoothstep(0.0, 0.35, f.y) * smoothstep(1.0, 0.6, f.y);
      col += vec3(0.55, 0.62, 0.75) * line * 0.2;
    }
    if (snow > 0.0) {
      for (int L = 0; L < 2; L++) {
        float fl = float(L);
        float sz = 34.0 + fl * 26.0;
        vec2 q = px;
        q.y -= iTime * (24.0 + fl * 16.0);
        q.x -= drift.x * iTime * (10.0 + fl * 6.0) + sin(iTime * 0.6 + q.y * 0.02 + fl * 2.0) * 7.0;
        vec2 cell = floor(q / sz);
        vec2 f = fract(q / sz) - 0.5;
        vec2 off = vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
        float d = length((f - off * 0.7) * sz);
        col += vec3(0.86, 0.89, 0.95) * step(hash(cell + fl * 17.0), snow) * smoothstep(2.6 + fl * 1.6, 0.4, d) * (0.45 + fl * 0.25);
      }
    }
  } else {
    // Cloud as slow drifting fog, faintly moonlit.
    float fogN = fbm(vec2(uv.x * 2.2 * aspect + t * 1.5, uv.y * 3.0 - t * 0.4));
    float fogAmt = clamp(cloud * (0.6 + 0.6 * fogN) - 0.08, 0.0, 1.0);
    vec3 fogCol = vec3(0.075, 0.09, 0.12) + vec3(0.10, 0.10, 0.09) * moonIllum * moonUp;
    col = mix(col, fogCol, fogAmt * 0.92);
  }

  col *= mix(1.0, 0.18, smoothstep(0.38, 0.95, uv.y));
  gl_FragColor = vec4(col, 1.0);
}
`;

const KEYS = ["activity", "cloud", "moonIllum", "moonUp", "moonX", "moonY", "storm", "daylight", "dusk",
  "waxing", "moonWash", "cloudLow", "cloudMid", "cloudHigh", "driftX", "driftY", "rain", "rainSlant", "snow", "haze",
  "sunX", "sunY", "bzBoost", "redFringe", "pinkEdge"];
const RICH_ZERO = { waxing: 0, moonWash: 0, cloudLow: 0, cloudMid: 0, cloudHigh: 0, driftX: 0, driftY: 0, rain: 0, rainSlant: 0,
  snow: 0, haze: 0, sunX: 0.5, sunY: 0.5, bzBoost: 0, redFringe: 0, pinkEdge: 0 };

// Bright stars as [right ascension in hours, declination in degrees, brightness 0..1].
const STARS = [
  [11.062, 61.75, 0.9], [11.031, 56.38, 0.75], [11.897, 53.69, 0.7], [12.257, 57.03, 0.55], [12.900, 55.96, 0.9],
  [13.399, 54.93, 0.8], [13.792, 49.31, 0.85], // Big Dipper
  [2.530, 89.26, 0.8], // Polaris
  [0.675, 56.54, 0.75], [0.153, 59.15, 0.7], [0.945, 60.72, 0.75], [1.430, 60.24, 0.65], [1.907, 63.67, 0.6], // Cassiopeia
  [5.278, 46.0, 1.0], // Capella
];
const RAD = Math.PI / 180;
/**
 * Compass direction to screen x. The view faces [viewAz] (the moon when it's up, else north),
 * which sits at [anchor] across the screen; east is to the right of north, as when you face it.
 */
const wrap180 = (a) => ((((a + 180) % 360) + 360) % 360) - 180;
const azToX = (az, viewAz = 0, anchor = 0.5) => anchor + wrap180(az - viewAz) / 360;
/** Which way the view faces: toward the moon when it's up, else north (where the aurora is). */
const viewFor = (hour) => ((hour?.moonAlt ?? -1) > 0 && hour?.moonAz != null ? hour.moonAz : 0);
/** [horizon] is the screen height of the horizon, [top] of straight overhead (0 = top). */
const altToY = (alt, horizon, top) => horizon - (Math.max(-10, Math.min(70, alt)) / 70) * (horizon - top);
const clamp01 = (v) => Math.min(1, Math.max(0, v));

/** Where each bright star is right now, as screen positions with brightness (0 = hidden). */
export function starPositions(ms, lat, lon, { horizon = 0.42, top = 0.03, anchor = 0.5, viewAz = 0 } = {}) {
  const jd = ms / 86400000 + 2440587.5;
  const lst = (280.46061837 + 360.98564736629 * (jd - 2451545) + lon) * RAD;
  const phi = lat * RAD;
  return STARS.map(([ra, dec, mag]) => {
    const h = lst - ra * 15 * RAD;
    const d = dec * RAD;
    const alt = Math.asin(Math.sin(phi) * Math.sin(d) + Math.cos(phi) * Math.cos(d) * Math.cos(h)) / RAD;
    const az = (Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(phi) - Math.tan(d) * Math.cos(phi)) / RAD + 180) % 360;
    const x = azToX(az, viewAz, anchor);
    return alt > 3 && x > -0.05 && x < 1.05 ? [x, altToY(alt, horizon, top), mag] : [0, 0, 0];
  });
}

/**
 * The lobby's richer sky for one hour: real sun and moon directions, cloud layers, wind, rain,
 * snow, haze, and aurora colour from the live data. Merge over skyFor() and pass to a sky
 * started with { rich: true }.
 */
export function skyDetail(hour, { bz = null, waxing = false, horizon = 0.42, top = 0.03, anchor = 0.5, lat = null, lon = null, starsAt = null } = {}) {
  if (!hour) return { ...RICH_ZERO };
  const out = { ...RICH_ZERO, waxing: waxing ? 1 : 0 };
  const viewAz = viewFor(hour);
  if (hour.moonAz != null && hour.moonAlt != null) {
    out.moonX = azToX(hour.moonAz, viewAz, anchor);
    out.moonY = Math.max(top, altToY(hour.moonAlt, horizon, top));
  }
  if (lat != null && lon != null) out.stars = starPositions(starsAt ?? Date.now(), lat, lon, { horizon, top, anchor, viewAz });
  out.moonWash = (hour.moonAlt ?? -1) > 0 ? (hour.moonIllum ?? 0) * clamp01(hour.moonAlt / 30) : 0;
  if (hour.sunAz != null) { out.sunX = azToX(hour.sunAz, viewAz, anchor); out.sunY = horizon; }
  const pct = (v) => (v == null || v < 0 ? null : v / 100);
  const low = pct(hour.cloudLow), mid = pct(hour.cloudMid), high = pct(hour.cloudHigh);
  if (low == null && mid == null && high == null) out.cloudMid = Math.max(0, hour.cloud ?? 0) / 100;
  else { out.cloudLow = low ?? 0; out.cloudMid = mid ?? 0; out.cloudHigh = high ?? 0; }
  if (hour.windDir != null) {
    // Wind comes *from* windDir; cloud moves the other way. East is to the right.
    const toward = (hour.windDir + 180) * RAD;
    const speed = Math.min(20, hour.wind ?? 5) / 10;
    out.driftX = Math.sin(toward) * speed;
    out.driftY = -Math.cos(toward) * speed * 0.15;
    out.rainSlant = Math.max(-0.35, Math.min(0.35, -Math.sin(toward) * Math.min(20, hour.wind ?? 5) / 40));
  } else out.driftX = 0.4;
  // Rain and snow only fall from real low or mid cloud, so the sky never contradicts the cloud bars.
  const wet = Math.max(out.cloudLow || 0, out.cloudMid || 0) >= 0.3;
  if (wet && hour.rain > 0.05) out.rain = 0.08 + 0.4 * clamp01(hour.rain / 4);
  if (wet && hour.snow > 0.02) out.snow = 0.1 + 0.4 * clamp01(hour.snow / 1.5);
  if (hour.visibility != null) out.haze = clamp01((8000 - hour.visibility) / 7000);
  const act = hour.activity ?? 0;
  out.pinkEdge = clamp01((act - 0.6) / 0.3);
  out.redFringe = clamp01(((hour.kp ?? 0) - 5.5) / 2);
  out.bzBoost = bz != null ? clamp01(-bz / 12) : 0;
  return out;
}

/** Sky inputs for one forecast hour, matching the app's skyFor(). */
export function skyFor(hour, hours, portrait = window.innerHeight > window.innerWidth) {
  if (!hour) return { activity: 0.25, cloud: 0, moonIllum: 0, moonUp: 0, moonX: 0.75, moonY: 0.2, storm: 0, daylight: 0, dusk: 0 };
  const first = hours && hours.length > 1 ? hours[0].time : hour.time;
  const last = hours && hours.length > 1 ? hours[hours.length - 1].time : hour.time + 1;
  const frac = Math.min(1, Math.max(0, (hour.time - first) / (last - first || 1)));
  const sun = hour.sunAlt ?? -20;
  const clamp = (v) => Math.min(1, Math.max(0, v));
  return {
    activity: hour.activity ?? 0.25,
    cloud: Math.max(0, hour.cloud ?? 0) / 100,
    moonIllum: hour.moonIllum ?? 0,
    moonUp: (hour.moonAlt ?? -1) > 0 ? 1 : 0,
    moonX: 0.15 + 0.7 * frac,
    // On a tall phone screen the big score sits where the app's moon would; keep it above.
    moonY: portrait
      ? 0.14 - (Math.min(40, Math.max(0, hour.moonAlt ?? 0)) / 40) * 0.07
      : 0.32 - (Math.min(40, Math.max(0, hour.moonAlt ?? 0)) / 40) * 0.22,
    storm: clamp(((hour.kp ?? 0) - 4.5) / 2),
    daylight: clamp((sun + 12) / 12),
    dusk: clamp(1 - Math.abs(sun + 4) / 6),
  };
}

/**
 * Starts the sky on [canvas]. Returns { set(sky) } to move it to a new hour; values ease over
 * ~0.7 s so scrubbing hours morphs the sky. Renders at reduced resolution (it's soft anyway),
 * pauses when the page is hidden, and draws one still frame under prefers-reduced-motion.
 * Falls back to a CSS gradient if WebGL isn't available.
 */
// ---------------------------------------------------------------------------------------------
// The moon's face, drawn per pixel at full resolution: a lit sphere with the real phase, the
// maria and a few bright craters (positions on the near side, north up), and faint earthshine.
// Same model as the app's shader (Aurora.kt moonAlbedo).

const MARIA = [
  [-0.55, -0.05, 0.42, 0.15], [-0.28, -0.45, 0.28, 0.19], [0.18, -0.38, 0.19, 0.19], [0.32, -0.10, 0.22, 0.19],
  [0.68, -0.30, 0.12, 0.21], [0.60, 0.12, 0.15, 0.14], [0.40, 0.26, 0.11, 0.14], [-0.18, 0.36, 0.2, 0.14],
  [-0.48, 0.30, 0.11, 0.14],
];
const fract = (v) => v - Math.floor(v);
const hash2 = (x, y) => fract(Math.sin(x * 127.1 + y * 311.7) * 43758.5453);
function noise2(x, y) {
  const ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
  const ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
  const a = hash2(ix, iy), b = hash2(ix + 1, iy), c = hash2(ix, iy + 1), d = hash2(ix + 1, iy + 1);
  return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
}
const blob = (x, y, cx, cy, r) => Math.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (r * r));
function moonAlbedo(x, y) {
  let m = 0;
  for (const [cx, cy, r, k] of MARIA) m += k * blob(x, y, cx, cy, r);
  m += 0.10 * blob(x * 0.5, y, -0.02, -0.70, 0.14);
  const bright = 0.14 * blob(x, y, -0.12, 0.72, 0.04) + 0.08 * blob(x, y, -0.30, -0.12, 0.035);
  const tex = (noise2(x * 6, y * 6) - 0.5) * 0.07 + (noise2(x * 22, y * 22) - 0.5) * 0.025;
  return Math.min(1.1, Math.max(0, 1.0 - m + bright + tex));
}

/** Paints the moon into [ctx] as a [size]-pixel square. [illum] 0..1, [waxing] lit on the right. */
export function moonFace(ctx, size, illum, waxing) {
  const img = ctx.createImageData(size, size);
  const px = img.data;
  const inc = Math.acos(Math.min(1, Math.max(-1, 2 * illum - 1)));
  const lx = (waxing ? 1 : -1) * Math.sin(inc), lz = Math.cos(inc);
  const r = size / 2, aa = 1.5 / r;
  const smooth = (e0, e1, v) => { const t = Math.min(1, Math.max(0, (v - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
  for (let j = 0; j < size; j++) {
    for (let i = 0; i < size; i++) {
      const x = (i + 0.5 - r) / r, y = (j + 0.5 - r) / r;
      const d = Math.hypot(x, y);
      if (d > 1) continue;
      const z = Math.sqrt(Math.max(0, 1 - d * d));
      const ndl = x * lx + z * lz;
      const lit = smooth(-0.04, 0.10, ndl);
      const alb = moonAlbedo(x, y);
      const shade = lit * (0.78 + 0.22 * smooth(0, 0.45, ndl));
      const k = (i + j * size) * 4;
      px[k] = Math.min(255, 255 * (1.0 * alb * shade + 0.10 * alb * (1 - lit)));
      px[k + 1] = Math.min(255, 255 * (0.98 * alb * shade + 0.11 * alb * (1 - lit)));
      px[k + 2] = Math.min(255, 255 * (0.92 * alb * shade + 0.14 * alb * (1 - lit)));
      px[k + 3] = 255 * smooth(1, 1 - aa, d);
    }
  }
  ctx.putImageData(img, 0, 0);
}

export function startSky(canvas, { scale = 0.5, rich = false, maxWidth = Infinity, moonSize = 0.034, fps = 60, pauseOnScroll = false } = {}) {
  const gl = canvas.getContext("webgl", { antialias: false, alpha: false, preserveDrawingBuffer: false, powerPreference: "low-power" });
  const target = { ...skyFor(null), ...RICH_ZERO };
  let stars = new Float32Array(14 * 3);
  const current = { ...target };
  if (!gl) {
    document.documentElement.classList.add("no-webgl");
    return { set(sky) { Object.assign(target, sky); } };
  }

  const compile = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) || "shader");
    return s;
  };
  let prog;
  try {
    prog = gl.createProgram();
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error("link");
  } catch (e) {
    document.documentElement.classList.add("no-webgl");
    return { set(sky) { Object.assign(target, sky); } };
  }
  gl.useProgram(prog);
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, "p");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const u = (name) => gl.getUniformLocation(prog, name);
  const uni = {
    res: u("iResolution"), time: u("iTime"), activity: u("activity"), cloud: u("cloud"),
    moonIllum: u("moonIllum"), moonUp: u("moonUp"), moonPos: u("moonPos"), storm: u("storm"),
    daylight: u("daylight"), dusk: u("dusk"), pixel: u("pixel"),
    rich: u("rich"), waxing: u("waxing"), moonWash: u("moonWash"), cloudLow: u("cloudLow"), cloudMid: u("cloudMid"),
    cloudHigh: u("cloudHigh"), drift: u("drift"), rain: u("rain"), rainSlant: u("rainSlant"), snow: u("snow"), haze: u("haze"),
    sunPos: u("sunPos"), bzBoost: u("bzBoost"), redFringe: u("redFringe"), pinkEdge: u("pinkEdge"), stars: u("stars"),
  };

  // Rich: the sharp moon on its own canvas just above the sky.
  let moon = null, moonCtx = null, moonKey = "";
  if (rich) {
    moon = document.createElement("canvas");
    moon.setAttribute("aria-hidden", "true");
    moon.style.cssText = "position:fixed;left:0;top:0;z-index:0;pointer-events:none;opacity:0;will-change:transform,opacity";
    canvas.after(moon);
    moonCtx = moon.getContext("2d");
  }
  const placeMoon = () => {
    if (!moon) return;
    const dpr = window.devicePixelRatio || 1;
    const css = Math.max(8, Math.round(canvas.clientHeight * moonSize * 2));
    const size = Math.round(css * dpr);
    const key = `${size}|${current.moonIllum.toFixed(3)}|${current.waxing > 0.5}`;
    if (key !== moonKey) {
      moonKey = key;
      moon.width = size; moon.height = size;
      moon.style.width = `${css}px`; moon.style.height = `${css}px`;
      moonFace(moonCtx, size, current.moonIllum, current.waxing > 0.5);
    }
    const x = current.moonX * canvas.clientWidth - css / 2;
    const y = current.moonY * canvas.clientHeight - css / 2;
    moon.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`;
    // Behind cloud and haze the moon fades; by day it's pale.
    const hidden = Math.min(0.92, current.cloudMid * 0.75 + current.cloudLow * 0.5 + current.cloudHigh * 0.2 + current.haze * 0.5);
    moon.style.opacity = String(current.moonUp * (1 - hidden) * (1 - 0.6 * current.daylight));
  };

  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  let running = true;
  let raf = 0;
  const start = performance.now();
  let last = start;

  // On big screens, cap the drawing size: the sky is soft, and TV GPUs are weak.
  let px = scale;
  const resize = () => {
    px = Math.min(scale, maxWidth / Math.max(1, canvas.clientWidth));
    const w = Math.max(1, Math.round(canvas.clientWidth * px));
    const h = Math.max(1, Math.round(canvas.clientHeight * px));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
      gl.viewport(0, 0, w, h);
    }
  };

  const draw = (now) => {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    const k = reduce.matches ? 1 : 1 - Math.exp(-dt / 0.25);
    for (const key of KEYS) current[key] += (target[key] - current[key]) * k;
    resize();
    gl.uniform2f(uni.res, canvas.width, canvas.height);
    gl.uniform1f(uni.time, reduce.matches ? 40 : (now - start) / 1000);
    gl.uniform1f(uni.activity, current.activity);
    gl.uniform1f(uni.cloud, current.cloud);
    gl.uniform1f(uni.moonIllum, current.moonIllum);
    gl.uniform1f(uni.moonUp, current.moonUp);
    gl.uniform2f(uni.moonPos, current.moonX, current.moonY);
    gl.uniform1f(uni.storm, current.storm);
    gl.uniform1f(uni.daylight, current.daylight);
    gl.uniform1f(uni.dusk, current.dusk);
    gl.uniform1f(uni.pixel, px);
    gl.uniform1f(uni.rich, rich ? 1 : 0);
    if (rich) {
      for (const k of ["waxing", "moonWash", "cloudLow", "cloudMid", "cloudHigh", "rain", "rainSlant", "snow", "haze", "bzBoost", "redFringe", "pinkEdge"]) {
        gl.uniform1f(uni[k], current[k]);
      }
      gl.uniform2f(uni.drift, current.driftX, current.driftY);
      gl.uniform2f(uni.sunPos, current.sunX, current.sunY);
      gl.uniform3fv(uni.stars, stars);
    }
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    placeMoon();
  };

  // The aurora moves slowly, so phones draw it at [fps] (30) and hold it still while the page
  // scrolls: every glass pane blurs what's behind it, so a moving sky means re-blurring them all.
  const frameMs = 1000 / fps;
  let lastDraw = 0;
  let scrollingUntil = 0;
  if (pauseOnScroll) addEventListener("scroll", () => { scrollingUntil = performance.now() + 180; }, { passive: true });
  const loop = (now) => {
    if (now >= scrollingUntil && now - lastDraw >= frameMs - 4) { lastDraw = now; draw(now); }
    if (running && !reduce.matches) raf = requestAnimationFrame(loop);
  };
  const resume = () => {
    cancelAnimationFrame(raf);
    last = performance.now();
    raf = requestAnimationFrame(loop);
  };
  document.addEventListener("visibilitychange", () => {
    running = document.visibilityState === "visible";
    if (running) resume();
  });
  reduce.addEventListener?.("change", resume);
  window.addEventListener("resize", () => { if (reduce.matches) requestAnimationFrame(draw); });
  resume();

  return {
    set(sky) {
      const { stars: list, ...rest } = sky;
      Object.assign(target, rest);
      if (list) stars = new Float32Array(list.flat());
      if (reduce.matches) requestAnimationFrame(draw);
    },
  };
}
