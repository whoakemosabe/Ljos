// The Ljós sky, ported from the Android AGSL shader (ui/Aurora.kt) to WebGL.
// The sky *is* the forecast: curtain brightness and height follow activity, storms tint
// violet, cloud rolls in as fog, and the real moon sits in the sky at its phase.

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
  col += dusk * vec3(0.22, 0.10, 0.06) * smoothstep(0.15, 0.6, uv.y) * (1.0 - smoothstep(0.6, 0.95, uv.y));
  float glow = (0.08 + 1.05 * pow(clamp(activity, 0.0, 1.0), 1.3)) * (1.0 - 0.95 * smoothstep(0.0, 0.7, day));
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
    col += c * a;
  }

  // Moon: soft halo plus a disc with its real phase.
  vec2 mp = vec2((uv.x - moonPos.x) * aspect, uv.y - moonPos.y);
  float md = length(mp);
  col += vec3(0.85, 0.82, 0.70) * exp(-md * 9.0) * 0.35 * moonIllum * moonUp;
  float r = 0.032;
  float disc = smoothstep(r, r - 0.003, md);
  float shadow = smoothstep(r, r - 0.003, length(mp - vec2(moonIllum * 2.0 * r, 0.0)));
  col = mix(col, vec3(0.95, 0.92, 0.80), disc * (1.0 - shadow) * moonUp);

  // Stars dim under a bright moon and vanish behind cloud. Sized in CSS pixels.
  float s = hash(floor(fragCoord / (3.0 * pixel)));
  float twinkle = 0.6 + 0.4 * sin(iTime * 2.0 + s * 40.0);
  float starDim = (1.0 - 0.75 * moonIllum * moonUp) * (1.0 - clamp(cloud, 0.0, 1.0)) * (1.0 - smoothstep(0.05, 0.45, day));
  col += vec3(step(0.9982, s) * twinkle * (1.0 - smoothstep(0.2, 0.6, uv.y)) * 0.8 * starDim);

  // Cloud as slow drifting fog, faintly moonlit.
  float fogN = fbm(vec2(uv.x * 2.2 * aspect + t * 1.5, uv.y * 3.0 - t * 0.4));
  float fogAmt = clamp(cloud * (0.6 + 0.6 * fogN) - 0.08, 0.0, 1.0);
  vec3 fogCol = vec3(0.075, 0.09, 0.12) + vec3(0.10, 0.10, 0.09) * moonIllum * moonUp;
  col = mix(col, fogCol, fogAmt * 0.92);

  col *= mix(1.0, 0.18, smoothstep(0.38, 0.95, uv.y));
  gl_FragColor = vec4(col, 1.0);
}
`;

const KEYS = ["activity", "cloud", "moonIllum", "moonUp", "moonX", "moonY", "storm", "daylight", "dusk"];

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
export function startSky(canvas, { scale = 0.5 } = {}) {
  const gl = canvas.getContext("webgl", { antialias: false, alpha: false, preserveDrawingBuffer: false, powerPreference: "low-power" });
  const target = skyFor(null);
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
  };

  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  let running = true;
  let raf = 0;
  const start = performance.now();
  let last = start;

  const resize = () => {
    const w = Math.max(1, Math.round(canvas.clientWidth * scale));
    const h = Math.max(1, Math.round(canvas.clientHeight * scale));
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
    gl.uniform1f(uni.pixel, scale);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  const loop = (now) => {
    draw(now);
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
      Object.assign(target, sky);
      if (reduce.matches) requestAnimationFrame(draw);
    },
  };
}
