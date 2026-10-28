/*!
 * Glasspane 2.0.0 — glass from a smoky sheet to a solid slab, plus modals, sheets and toasts.
 * Spec: SPEC.md · Rules: RULES.md · Tokens: tokens/tokens.json
 *
 *   <link rel="stylesheet" href="glasspane.css">
 *   <script src="glasspane.js" defer></script>
 *   <div class="gp gp-smoky">…</div>
 *   Glasspane.init();                          // lens every .gp, follow the light
 *   await Glasspane.confirm('Delete this site?', { danger: true });
 *   Glasspane.toast('Saved');
 *
 * Refraction layer (Chromium only): each glass element gets its own SVG filter used inside
 * backdrop-filter. A canvas-drawn map pushes what is behind the element inward across an edge
 * band, strongest at the rim, following the element's exact size and corner radius; red, green
 * and blue are bent by slightly different amounts for a fringe at the rim. Technique from
 * korbenmade/js/glass.js, generalised in Ljós/web/liquid.js, now driven by the thickness axis.
 * Safari and Firefox keep frost, smoke, rim, depth and shadow from glasspane.css.
 */
(function (root) {
  'use strict';

  const NS = 'http://www.w3.org/2000/svg';
  const doc = root.document;
  const canRefract = () =>
    navigator.userAgentData?.brands?.some((b) => /Chromium/.test(b.brand)) ?? /Chrome\//.test(navigator.userAgent);
  const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
  const reducedTransparency = () => matchMedia('(prefers-reduced-transparency: reduce)').matches;

  const state = {
    svg: null,
    uid: 0,
    quality: 2, // 2 lens + fringe, 1 lens, 0 controls only, -1 off
    items: new Map(), // el -> { id, key, f, maps }
    refract: false,
    raf: 0,
  };

  /* ───────── Small helpers ───────── */

  const node = (name, attrs = {}) => {
    const n = doc.createElementNS(NS, name);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
    return n;
  };
  const num = (v, d = 0) => { const n = parseFloat(v); return Number.isFinite(n) ? n : d; };
  const read = (el) => {
    const cs = getComputedStyle(el);
    const t = Math.min(1, Math.max(0, num(cs.getPropertyValue('--gp-t'), 0.3)));
    return {
      t,
      refract: num(cs.getPropertyValue('--gp-refract'), 1),
      disp: num(cs.getPropertyValue('--gp-disp'), 1),
      r: num(cs.borderTopLeftRadius),
      band: 4 + 28 * t,
    };
  };

  /* ───────── Refraction map ───────── */

  /**
   * Map for a w×h rounded rect, corner radius r, edge band `band`. 128 = no shift.
   * Profile k = (1 - depth/band)^p: thin glass (p≈2) bends only right at the rim,
   * thick glass (p→1) bends a broader, rounder edge.
   */
  function drawMap(w, h, r, band, t) {
    const c = doc.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    const img = ctx.createImageData(w, h);
    const d = img.data;
    const hx = w / 2, hy = h / 2;
    r = Math.min(r, hx, hy);
    band = Math.min(band, hx, hy);
    const p = 2 - t;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4;
        d[i + 2] = 128; d[i + 3] = 255;
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
        const depth = -dist;
        let ox = 0, oy = 0;
        if (depth > 0 && depth < band) {
          const k = Math.pow(1 - depth / band, p);
          ox = -nx * k; oy = -ny * k; // sample inward: the rim pulls the scene toward the middle
        }
        d[i] = 128 + ox * 127; d[i + 1] = 128 + oy * 127;
      }
    }
    ctx.putImageData(img, 0, 0);
    return c.toDataURL();
  }

  const CHANNELS = [
    ['r', 0, '1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0'],
    ['g', 0.5, '0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0'],
    ['b', 1, '0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0'],
  ];

  /** bend = refraction × (10 + 36t); dispersion spreads G and B by up to +8% / +16%. */
  const bendFor = (v) => v.refract * (10 + 36 * v.t);

  function buildFilter(el, it) {
    const box = el.getBoundingClientRect();
    const w = Math.max(1, Math.round(box.width)), h = Math.max(1, Math.round(box.height));
    const v = read(el);
    const fringe = state.quality >= 2 && v.disp > 0.01;
    // the map only depends on geometry; bend and fringe can change without a redraw
    const mapKey = `${w}x${h}x${v.r}x${v.band.toFixed(1)}x${v.t.toFixed(2)}`;
    const key = `${mapKey}|${fringe}`;
    const bend = bendFor(v);
    if (it.key === key && it.f) { setBend(it, bend, v.disp); return; }
    it.key = key;
    it.f?.remove();
    const f = node('filter', { id: it.id, x: 0, y: 0, width: w, height: h, filterUnits: 'userSpaceOnUse', 'color-interpolation-filters': 'sRGB' });
    if (it.mapKey !== mapKey) { it.map = drawMap(w, h, v.r, v.band, v.t); it.mapKey = mapKey; }
    f.append(node('feImage', { href: it.map, x: 0, y: 0, width: w, height: h, preserveAspectRatio: 'none', result: 'map' }));
    it.maps = [];
    if (fringe) {
      for (const [c, spread, m] of CHANNELS) {
        const dm = node('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', scale: 0, xChannelSelector: 'R', yChannelSelector: 'G', result: `d${c}` });
        dm.dataset.spread = spread;
        it.maps.push(dm);
        f.append(dm, node('feColorMatrix', { in: `d${c}`, type: 'matrix', values: m, result: c }));
      }
      f.append(node('feBlend', { in: 'r', in2: 'g', mode: 'screen', result: 'rg' }), node('feBlend', { in: 'rg', in2: 'b', mode: 'screen' }));
    } else {
      const dm = node('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', scale: 0, xChannelSelector: 'R', yChannelSelector: 'G' });
      dm.dataset.spread = 0;
      it.maps.push(dm);
      f.append(dm);
    }
    state.svg.append(f);
    it.f = f;
    setBend(it, bend, v.disp);
  }

  function setBend(it, bend, disp) {
    for (const dm of it.maps) dm.setAttribute('scale', (bend * (1 + 0.16 * disp * num(dm.dataset.spread))).toFixed(1));
  }

  function apply(el, it) {
    const big = el.getBoundingClientRect().width * el.getBoundingClientRect().height > 160000;
    const on = state.refract && read(el).refract > 0.01 &&
      (state.quality >= 1 || (state.quality === 0 && !big));
    if (on) {
      buildFilter(el, it);
      el.style.setProperty('--gp-lens', `url(#${it.id})`);
    } else {
      el.style.removeProperty('--gp-lens');
    }
  }

  const resizer = typeof ResizeObserver !== 'undefined'
    ? new ResizeObserver((entries) => { for (const e of entries) { const it = state.items.get(e.target); if (it) apply(e.target, it); } })
    : null;

  /** Give one element the lens. Safe to call twice. */
  function lens(el) {
    if (!el || state.items.has(el)) return;
    const it = { id: `gp-lens-${++state.uid}`, key: '' };
    state.items.set(el, it);
    if (state.refract) apply(el, it);
    resizer?.observe(el);
  }

  function unlens(el) {
    const it = state.items.get(el);
    if (!it) return;
    it.f?.remove();
    el.style.removeProperty('--gp-lens');
    resizer?.unobserve(el);
    state.items.delete(el);
  }

  /** Re-read every glass element's variables (call after changing --gp-* at runtime). */
  function refresh() {
    cancelAnimationFrame(state.raf);
    state.raf = requestAnimationFrame(() => {
      for (const [el, it] of state.items) {
        if (!el.isConnected) { unlens(el); continue; }
        apply(el, it);
      }
    });
  }

  /* ───────── Frame-rate guard (from Ljós liquid.js) ───────── */

  function watchFrames() {
    let frames = 0, slow = 0, last = 0, running = false;
    const sample = (t) => {
      if (last) { frames++; if (t - last > 24) slow++; }
      last = t;
      if (frames < 90) requestAnimationFrame(sample);
      else {
        running = false;
        if (slow / frames > 0.25 && state.quality > -1) {
          state.quality--;
          for (const [el, it] of state.items) { it.key = ''; apply(el, it); }
          root.dispatchEvent(new CustomEvent('glasspane:quality', { detail: state.quality }));
        }
      }
    };
    const start = () => {
      if (running || state.quality < 0) return;
      running = true; frames = 0; slow = 0; last = 0;
      requestAnimationFrame(sample);
    };
    setTimeout(start, 1500);
    let wait = 0;
    root.addEventListener('scroll', () => { clearTimeout(wait); wait = setTimeout(start, 120); }, { passive: true });
  }

  /* ───────── Moving light ───────── */

  function followLight(target = doc.documentElement) {
    const set = (deg) => target.style.setProperty('--gp-light', `${deg.toFixed(0)}deg`);
    const onMove = ({ clientX }) => set(130 + (clientX / root.innerWidth) * 60);
    const onTilt = ({ gamma }) => { if (gamma != null) set(160 + Math.max(-30, Math.min(30, gamma))); };
    root.addEventListener('pointermove', onMove, { passive: true });
    root.addEventListener('deviceorientation', onTilt, { passive: true });
    return () => { root.removeEventListener('pointermove', onMove); root.removeEventListener('deviceorientation', onTilt); };
  }

  /* ───────── Springs ───────── */

  const SPRINGS = {
    snappy: { stiffness: 420, damping: 32, mass: 1 },
    gentle: { stiffness: 260, damping: 26, mass: 1 },
    wobbly: { stiffness: 300, damping: 18, mass: 1 },
  };

  /** Animate x from `from` to `to` with a damped spring; onUpdate(x). Resolves when settled. */
  function spring(from, to, onUpdate, opts = {}) {
    const { stiffness, damping, mass } = { ...SPRINGS.gentle, ...(typeof opts === 'string' ? SPRINGS[opts] : opts) };
    let x = from, v = opts.velocity || 0, last = performance.now(), id = 0, stopped = false;
    if (reducedMotion()) { onUpdate(to); return Object.assign(Promise.resolve(), { stop() {} }); }
    const p = new Promise((resolve) => {
      const step = (now) => {
        if (stopped) return resolve();
        // real elapsed time in fixed 1/240 s steps, so slow frames don't slow the motion down
        let dt = Math.min(0.1, (now - last) / 1000); last = now;
        while (dt > 0) {
          const h = Math.min(dt, 1 / 240); dt -= h;
          const a = (-stiffness * (x - to) - damping * v) / mass;
          v += a * h; x += v * h;
        }
        onUpdate(x);
        if (Math.abs(v) < 0.01 && Math.abs(x - to) < 0.001) { onUpdate(to); resolve(); }
        else id = requestAnimationFrame(step);
      };
      id = requestAnimationFrame(step);
    });
    p.stop = () => { stopped = true; cancelAnimationFrame(id); };
    return p;
  }

  /* ───────── Panes: modals and sheets on native <dialog> ───────── */

  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  let lastPointer = null;
  doc.addEventListener('pointerdown', (e) => { lastPointer = { x: e.clientX, y: e.clientY, t: performance.now() }; }, true);

  /**
   * Open a pane. Resolves with the value of the action pressed, or undefined when dismissed.
   *   kind: 'modal' | 'sheet'
   *   title, message (text), body (HTML string or Node), actions: [{ label, value, primary, danger }]
   *   material: 'solid' (default) | 'smoky' | 'liquid' | 'frosted'
   *   from: element the modal grows out of (defaults to the last thing tapped)
   *   input: { label, value, placeholder, validate(v) → error string } turns it into a prompt
   */
  function open(opts = {}) {
    const kind = opts.kind === 'sheet' ? 'sheet' : 'modal';
    const dlg = doc.createElement('dialog');
    dlg.className = `gp-pane gp-pane-${kind}`;
    const card = doc.createElement('div');
    card.className = `gp gp-${opts.material || 'solid'} gp-card`;
    card.setAttribute('role', 'document');
    const titleId = `gp-t-${++state.uid}`;
    if (opts.title) dlg.setAttribute('aria-labelledby', titleId);
    card.innerHTML =
      (kind === 'sheet' ? '<div class="gp-grip" aria-hidden="true"></div>' : '') +
      (opts.title ? `<h2 class="gp-title" id="${titleId}">${esc(opts.title)}</h2>` : '') +
      (opts.message ? `<p class="gp-msg">${esc(opts.message)}</p>` : '') +
      '<div class="gp-body"></div>' +
      (opts.input ? `<label class="gp-field"><span>${esc(opts.input.label || '')}</span><input class="gp gp-frosted gp-input" value="${esc(opts.input.value || '')}" placeholder="${esc(opts.input.placeholder || '')}"><em class="gp-err" aria-live="polite"></em></label>` : '') +
      '<div class="gp-actions"></div>';
    const body = card.querySelector('.gp-body');
    if (opts.body instanceof Node) body.append(opts.body); else if (opts.body) body.innerHTML = opts.body; else body.remove();
    const acts = card.querySelector('.gp-actions');
    const actions = opts.actions || [{ label: 'OK', value: true, primary: true }];
    let firstFocus = null;
    for (const a of actions) {
      const b = doc.createElement('button');
      b.type = 'button';
      b.className = `gp-btn gp-press ${a.primary ? (a.danger ? 'gp-btn-danger' : 'gp-btn-primary') : 'gp gp-liquid'}`;
      b.textContent = a.label;
      b.addEventListener('click', () => finish(a.value, a));
      acts.append(b);
      // danger confirms start on the safe choice
      if (opts.danger ? !a.primary : a.primary) firstFocus ||= b;
    }
    if (!actions.length) acts.remove();
    dlg.append(card);
    doc.body.append(dlg);

    let done = false, anim = null, resolve;
    const result = new Promise((r) => { resolve = r; });
    const input = card.querySelector('.gp-input');

    // where the modal grows from
    const origin = (() => {
      const r = opts.from?.getBoundingClientRect?.();
      if (r) return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      if (lastPointer && performance.now() - lastPointer.t < 1500) return lastPointer;
      return null;
    })();

    const setP = (p) => {
      if (kind === 'sheet') {
        card.style.transform = `translate3d(0, ${(1 - p) * 105}%, 0)`;
      } else {
        const s = 0.86 + 0.14 * p;
        let tx = 0, ty = 0;
        if (origin) {
          const r = card.getBoundingClientRect();
          const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
          tx = (origin.x - cx) * (1 - p) * 0.6; ty = (origin.y - cy) * (1 - p) * 0.6;
        }
        card.style.transform = `translate3d(${tx}px, ${ty}px, 0) scale(${s})`;
        card.style.opacity = Math.min(1, Math.max(0, p * 1.6));
      }
      dlg.style.setProperty('--gp-dim', Math.min(1, Math.max(0, p)));
    };

    function finish(value, action) {
      if (done) return;
      if (input && action && action.primary) {
        const err = opts.input.validate?.(input.value);
        if (err) { card.querySelector('.gp-err').textContent = err; input.focus(); shake(card); return; }
        value = input.value;
      }
      done = true;
      anim?.stop();
      const from = currentP;
      anim = spring(from, 0, (p) => { currentP = p; setP(p); }, kind === 'sheet' ? { ...SPRINGS.snappy, velocity: flick } : SPRINGS.snappy);
      anim.then(() => { dlg.close(); unlens(card); dlg.remove(); opts.returnFocus?.focus?.(); resolve(value); });
    }

    let currentP = 0, flick = 0;
    dlg.addEventListener('cancel', (e) => { e.preventDefault(); if (opts.dismissible !== false) finish(undefined); });
    dlg.addEventListener('click', (e) => { if (e.target === dlg && opts.dismissible !== false) finish(undefined); });
    dlg.addEventListener('keydown', (e) => { if (e.key === 'Enter' && input && e.target === input) { e.preventDefault(); finish(undefined, actions.find((a) => a.primary)); } });

    if (kind === 'sheet') dragToDismiss(card, (v) => { flick = v; finish(undefined); }, (p) => { currentP = p; setP(p); }, () => currentP, () => {
      anim?.stop(); anim = spring(currentP, 1, (p) => { currentP = p; setP(p); }, SPRINGS.wobbly);
    });

    dlg.showModal();
    setP(0);
    lens(card);
    (input || firstFocus)?.focus({ preventScroll: true });
    anim = spring(0, 1, (p) => { currentP = p; setP(p); refreshOne(card); }, kind === 'sheet' ? SPRINGS.gentle : SPRINGS.wobbly);
    anim.then(() => apply(card, state.items.get(card) || {}));
    result.close = (v) => finish(v);
    result.element = card;
    return result;
  }

  // the lens follows the card's box; transforms don't change it, so one rebuild is enough
  function refreshOne(el) { const it = state.items.get(el); if (it && !it.f && state.refract) apply(el, it); }

  function shake(el) {
    if (reducedMotion()) return;
    const base = el.style.transform;
    spring(1, 0, (x) => { el.style.transform = `${base} translateX(${Math.sin(x * 18) * x * 10}px)`; }, { stiffness: 200, damping: 20 })
      .then(() => { el.style.transform = base; });
  }

  function dragToDismiss(card, onDismiss, setP, getP, onReturn) {
    let startY = 0, lastY = 0, lastT = 0, vy = 0, h = 1, dragging = false;
    card.addEventListener('pointerdown', (e) => {
      if (e.target.closest('button, input, textarea, select, a, [data-gp-nodrag]')) return;
      dragging = true; startY = lastY = e.clientY; lastT = performance.now(); h = card.offsetHeight || 1;
      card.setPointerCapture(e.pointerId);
    });
    card.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const now = performance.now();
      vy = (e.clientY - lastY) / Math.max(1, now - lastT); lastY = e.clientY; lastT = now;
      let dy = e.clientY - startY;
      if (dy < 0) dy = -Math.sqrt(-dy) * 3; // rubber band upward
      setP(1 - dy / (h * 1.05));
    });
    const end = () => {
      if (!dragging) return;
      dragging = false;
      if (getP() < 0.72 || vy > 0.6) onDismiss(-vy * 3); else onReturn();
    };
    card.addEventListener('pointerup', end);
    card.addEventListener('pointercancel', end);
  }

  const modal = (o) => open({ ...o, kind: 'modal' });
  const sheet = (o) => open({ ...o, kind: 'sheet' });
  const alert = (message, o = {}) => open({ title: o.title, message, actions: [{ label: o.ok || 'OK', value: true, primary: true }], ...o });
  const confirm = (message, o = {}) => open({
    title: o.title, message, danger: o.danger, ...o,
    actions: [{ label: o.cancel || 'Cancel', value: false }, { label: o.ok || (o.danger ? 'Delete' : 'OK'), value: true, primary: true, danger: o.danger }],
  }).then((v) => v === true);
  const prompt = (message, o = {}) => open({
    title: o.title, message, ...o, input: { label: o.label, value: o.value, placeholder: o.placeholder, validate: o.validate },
    actions: [{ label: o.cancel || 'Cancel', value: null }, { label: o.ok || 'OK', value: true, primary: true }],
  }).then((v) => (v === undefined || v === null ? null : v));

  /* ───────── Toasts ───────── */

  let toaster = null;
  function toast(message, o = {}) {
    if (typeof message === 'object') { o = message; message = o.message; }
    if (!toaster) {
      toaster = doc.createElement('div');
      toaster.className = 'gp-toaster';
      toaster.setAttribute('aria-live', 'polite');
      doc.body.append(toaster);
    }
    // keep toasts above an open modal
    const top = [...doc.querySelectorAll('dialog[open]')].pop();
    (top || doc.body).append(toaster);

    const t = doc.createElement('div');
    t.className = `gp gp-${o.material || 'liquid'} gp-toast${o.type ? ` gp-toast-${o.type}` : ''}`;
    t.setAttribute('role', o.type === 'error' ? 'alert' : 'status');
    const icon = { success: '✓', error: '!', loading: '' }[o.type] ?? '';
    t.innerHTML = `${o.type ? `<span class="gp-toast-icon" aria-hidden="true">${icon}</span>` : ''}<span class="gp-toast-text">${o.title ? `<b>${esc(o.title)}</b> ` : ''}${esc(message || '')}</span>`;
    if (o.action) {
      const b = doc.createElement('button');
      b.type = 'button'; b.className = 'gp-toast-action'; b.textContent = o.action.label;
      b.addEventListener('click', () => { o.action.onClick?.(); close(); });
      t.append(b);
    }
    toaster.prepend(t);
    lens(t);

    let timer = 0, closed = false;
    const duration = o.duration ?? (o.type === 'error' ? 6000 : o.type === 'loading' ? Infinity : 3200);
    const arm = () => { clearTimeout(timer); if (duration !== Infinity) timer = setTimeout(close, duration); };
    t.addEventListener('pointerenter', () => clearTimeout(timer));
    t.addEventListener('pointerleave', arm);
    spring(0, 1, (p) => { t.style.transform = `translate3d(0, ${(1 - p) * -24}px, 0) scale(${0.9 + 0.1 * p})`; t.style.opacity = Math.min(1, p * 2); }, SPRINGS.wobbly);
    arm();

    // swipe up or sideways to dismiss
    let sx = 0, sy = 0, drag = false;
    t.addEventListener('pointerdown', (e) => { if (e.target.closest('button')) return; drag = true; sx = e.clientX; sy = e.clientY; t.setPointerCapture(e.pointerId); clearTimeout(timer); });
    t.addEventListener('pointermove', (e) => { if (drag) t.style.transform = `translate3d(${e.clientX - sx}px, ${Math.min(0, e.clientY - sy)}px, 0)`; });
    t.addEventListener('pointerup', (e) => {
      if (!drag) return; drag = false;
      if (Math.abs(e.clientX - sx) > 80 || sy - e.clientY > 30) close(); else { t.style.transform = ''; arm(); }
    });

    function close() {
      if (closed) return; closed = true; clearTimeout(timer);
      const h = t.offsetHeight;
      spring(1, 0, (p) => {
        t.style.opacity = p; t.style.transform = `translate3d(0, ${(1 - p) * -12}px, 0) scale(${0.92 + 0.08 * p})`;
        t.style.marginBottom = `${-(1 - p) * (h + 8)}px`;
      }, SPRINGS.snappy).then(() => { unlens(t); t.remove(); });
    }
    function update(next = {}) {
      Object.assign(o, next);
      t.className = `gp gp-${o.material || 'liquid'} gp-toast${o.type ? ` gp-toast-${o.type}` : ''}`;
      const ic = t.querySelector('.gp-toast-icon');
      if (ic) ic.textContent = { success: '✓', error: '!', loading: '' }[o.type] ?? '';
      t.querySelector('.gp-toast-text').textContent = next.message ?? message;
      if (next.duration !== undefined || o.type !== 'loading') { clearTimeout(timer); timer = setTimeout(close, next.duration ?? 2600); }
      refresh();
    }
    return { close, update, element: t };
  }
  toast.success = (m, o = {}) => toast(m, { ...o, type: 'success' });
  toast.error = (m, o = {}) => toast(m, { ...o, type: 'error' });
  toast.loading = (m, o = {}) => toast(m, { ...o, type: 'loading' });
  toast.promise = (p, { loading = 'Working…', success = 'Done', error = 'Something went wrong' } = {}) => {
    const t = toast.loading(loading);
    p.then((v) => t.update({ type: 'success', message: typeof success === 'function' ? success(v) : success }),
      (e) => t.update({ type: 'error', message: typeof error === 'function' ? error(e) : error }));
    return p;
  };

  /* ───────── Declarative attributes ───────── */

  function wireAttributes() {
    doc.addEventListener('click', async (e) => {
      const opener = e.target.closest('[data-gp-open]');
      if (opener) {
        const tpl = doc.querySelector(opener.getAttribute('data-gp-open'));
        if (tpl) {
          e.preventDefault();
          const content = tpl instanceof HTMLTemplateElement ? tpl.content.cloneNode(true) : tpl.cloneNode(true);
          open({ kind: opener.getAttribute('data-gp-kind') || 'modal', title: opener.getAttribute('data-gp-title'), body: content,
            material: opener.getAttribute('data-gp-material') || undefined, from: opener, returnFocus: opener,
            actions: [{ label: opener.getAttribute('data-gp-close') || 'Done', value: true, primary: true }] });
        }
      }
      const conf = e.target.closest('[data-gp-confirm]');
      if (conf && !conf.dataset.gpConfirmed) {
        e.preventDefault(); e.stopPropagation();
        const ok = await confirm(conf.getAttribute('data-gp-confirm'), { danger: conf.hasAttribute('data-gp-danger'), from: conf, returnFocus: conf });
        if (ok) { conf.dataset.gpConfirmed = '1'; conf.click(); delete conf.dataset.gpConfirmed; }
      }
    }, true);
  }

  /* ───────── Entry ───────── */

  /**
   * Start Glasspane.
   *   selector: what to lens (default '.gp')
   *   light: follow the pointer / tilt with the rim light (default true)
   *   watch: lens .gp elements added later (default true)
   */
  function init(o = {}) {
    if (state.inited) return api;
    state.inited = true;
    const selector = o.selector || '.gp';
    state.refract = canRefract() && !reducedTransparency() && o.refraction !== false;
    if (state.refract) {
      state.svg = node('svg', { width: 0, height: 0, 'aria-hidden': 'true', focusable: 'false' });
      state.svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none';
      doc.body.append(state.svg);
      watchFrames();
    }
    doc.documentElement.classList.toggle('gp-refracts', state.refract);
    doc.querySelectorAll(selector).forEach(lens);
    if (o.watch !== false && typeof MutationObserver !== 'undefined') {
      new MutationObserver((muts) => {
        for (const m of muts) for (const n of m.addedNodes) {
          if (n.nodeType !== 1) continue;
          if (n.matches(selector)) lens(n);
          n.querySelectorAll?.(selector).forEach(lens);
        }
      }).observe(doc.body, { childList: true, subtree: true });
    }
    if (o.light !== false) state.stopLight = followLight();
    wireAttributes();
    return api;
  }

  /** Apply a preset's variables to an element (or :root). */
  const PRESETS = ['liquid', 'smoky', 'solid', 'frosted'];
  function preset(el, name) {
    if (!PRESETS.includes(name)) throw new Error(`Glasspane: unknown preset "${name}" (use ${PRESETS.join(', ')})`);
    PRESETS.forEach((p) => el.classList.remove(`gp-${p}`));
    el.classList.add('gp', `gp-${name}`);
    refresh();
  }

  const api = {
    version: '2.0.0',
    init, lens, unlens, refresh, preset, followLight,
    open, modal, sheet, alert, confirm, prompt, toast, spring,
    get refracts() { return state.refract; },
    get quality() { return state.quality; },
  };
  root.Glasspane = api;
  root.glass ||= api; // v1 name
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
