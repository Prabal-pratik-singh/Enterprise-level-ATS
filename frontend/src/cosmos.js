// The sky behind the ATS dashboard — ported from the author's portfolio /
// sahayak "cosmos": deep space on one canvas (three parallax star layers, a
// Milky Way band, twinkling and glinting stars, shooting stars, a ringed gas
// giant, a moon and a far crescent), three nebula clouds that lean away from
// the cursor, a spotlight and grid that ease toward the pointer, film grain,
// and the 3D card tilt. Plain DOM + one animation loop — nothing here
// re-renders React. Colours come from CSS tokens (--nebula-1/2/3, --ink,
// --bg1, --bg2) so the theme stays in one place (index.css).

const PAD = 96
const WHITE = [255, 255, 255]
const SPOT = 640 // px, matches .spot / .gridspot in CSS
const CELL = 32

// ---------- small helpers ----------

function el(tag, className, parent = document.body) {
  const node = document.createElement(tag)
  node.className = className
  node.setAttribute('aria-hidden', 'true')
  parent.appendChild(node)
  return node
}

function hexToRgb(hex, fallback) {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim())
  if (!m) return fallback
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

const mix = (a, b, t) => [
  Math.round(a[0] + (b[0] - a[0]) * t),
  Math.round(a[1] + (b[1] - a[1]) * t),
  Math.round(a[2] + (b[2] - a[2]) * t),
]
const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`

function readColors() {
  const s = getComputedStyle(document.documentElement)
  const v = (name, fb) => hexToRgb(s.getPropertyValue(name), fb)
  return {
    trace: v('--nebula-1', [76, 201, 240]),
    violet: v('--nebula-2', [139, 92, 246]),
    pink: v('--nebula-3', [255, 107, 214]),
    text: v('--ink', [238, 242, 255]),
    panel: v('--bg1', [13, 17, 40]),
    panel2: v('--bg2', [18, 23, 56]),
  }
}

// ---------- pointer (shared by the sky, the nebulae and the spotlight) ----------

const pointer = { x: 0.5, y: 0.5, cx: 0, cy: 0, active: false }
const pointerListeners = new Set()

function initPointer() {
  const root = document.documentElement
  let raf = 0
  const publish = () => {
    raf = 0
    root.style.setProperty('--px', pointer.x.toFixed(3))
    root.style.setProperty('--py', pointer.y.toFixed(3))
    for (const fn of pointerListeners) fn(pointer)
  }
  window.addEventListener(
    'mousemove',
    (e) => {
      pointer.cx = e.clientX
      pointer.cy = e.clientY
      pointer.x = e.clientX / Math.max(1, window.innerWidth)
      pointer.y = e.clientY / Math.max(1, window.innerHeight)
      pointer.active = true
      if (!raf) raf = requestAnimationFrame(publish)
    },
    { passive: true },
  )
  root.addEventListener('mouseleave', () => {
    pointer.active = false
    if (!raf) raf = requestAnimationFrame(publish)
  })
}

// ---------- the starfield canvas ----------

function initStarfield(canvas, reduced, coarse) {
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  let W = 1
  let H = 1
  let dpr = 1
  let LW = 1
  let LH = 1
  let layers = []
  let planets = null
  let twinkles = []
  const shooters = []
  let nextShot = 0
  let raf = 0
  let last = 0
  let C = readColors()
  const par = { x: 0, y: 0 }
  let seed = 7
  const rnd = () => {
    seed = (seed * 16807) % 2147483647
    return (seed - 1) / 2147483646
  }
  const gauss = () => (rnd() + rnd() + rnd() - 1.5) / 1.5

  const make = (w, h) => {
    const c = document.createElement('canvas')
    c.width = Math.ceil(w * dpr)
    c.height = Math.ceil(h * dpr)
    return c
  }

  function build() {
    seed = 7
    LW = W + PAD * 2
    LH = H + PAD * 2
    const counts = coarse ? [150, 95, 45] : [320, 200, 100]
    const pars = [5, 12, 24]
    const drifts = [1.4, 2.6, 4.2]
    const scrolls = [0.02, 0.05, 0.09]
    const sizes = [
      [0.5, 1.0],
      [0.8, 1.5],
      [1.1, 2.2],
    ]
    const alphas = [
      [0.3, 0.6],
      [0.45, 0.85],
      [0.65, 1],
    ]
    const ang = -0.52
    const cx = LW / 2
    const cy = LH / 2
    const tint1 = mix(WHITE, C.trace, 0.4)
    const tint2 = mix(WHITE, C.violet, 0.45)
    const tint3 = mix(WHITE, C.pink, 0.35)
    layers = []
    twinkles = []
    for (let L = 0; L < 3; L++) {
      const c = make(LW, LH)
      const g = c.getContext('2d')
      g.scale(dpr, dpr)
      if (L === 0) {
        // Milky Way haze
        g.save()
        g.translate(cx, cy)
        g.rotate(ang)
        const band = g.createLinearGradient(0, -LH * 0.24, 0, LH * 0.24)
        band.addColorStop(0, 'rgba(0,0,0,0)')
        band.addColorStop(0.35, rgba(mix(C.violet, C.trace, 0.5), 0.05))
        band.addColorStop(0.5, rgba(mix(C.text, C.trace, 0.5), 0.075))
        band.addColorStop(0.65, rgba(mix(C.violet, C.pink, 0.4), 0.05))
        band.addColorStop(1, 'rgba(0,0,0,0)')
        g.fillStyle = band
        g.fillRect(-LW, -LH * 0.24, LW * 2, LH * 0.48)
        g.restore()
      }
      for (let i = 0; i < counts[L]; i++) {
        let x
        let y
        if (rnd() < 0.55) {
          const along = (rnd() - 0.5) * Math.hypot(LW, LH) * 1.1
          const off = gauss() * LH * 0.17
          x = cx + Math.cos(ang) * along - Math.sin(ang) * off
          y = cy + Math.sin(ang) * along + Math.cos(ang) * off
        } else {
          x = rnd() * LW
          y = rnd() * LH
        }
        const r = sizes[L][0] + rnd() * (sizes[L][1] - sizes[L][0])
        const a = alphas[L][0] + rnd() * (alphas[L][1] - alphas[L][0])
        const cr = rnd()
        const col = cr < 0.55 ? WHITE : cr < 0.75 ? tint1 : cr < 0.9 ? tint2 : tint3
        g.fillStyle = rgba(col, a)
        g.beginPath()
        g.arc(x, y, r, 0, Math.PI * 2)
        g.fill()
        if (L === 2 && rnd() < 0.09) {
          // bright star with a four-point glint
          g.strokeStyle = rgba(col, a * 0.5)
          g.lineWidth = 0.8
          g.beginPath()
          g.moveTo(x - r * 4.5, y)
          g.lineTo(x + r * 4.5, y)
          g.moveTo(x, y - r * 4.5)
          g.lineTo(x, y + r * 4.5)
          g.stroke()
          const gl = g.createRadialGradient(x, y, 0, x, y, r * 6)
          gl.addColorStop(0, rgba(col, a * 0.35))
          gl.addColorStop(1, rgba(col, 0))
          g.fillStyle = gl
          g.beginPath()
          g.arc(x, y, r * 6, 0, Math.PI * 2)
          g.fill()
        }
        if (L > 0 && twinkles.length < (coarse ? 30 : 80) && rnd() < 0.3) {
          twinkles.push({ x, y, r, phase: rnd() * Math.PI * 2, speed: 0.7 + rnd() * 1.8, c: col, layer: L })
        }
      }
      layers.push({ canvas: c, par: pars[L], drift: drifts[L], scroll: scrolls[L] })
    }
    planets = buildPlanets()
  }

  function buildPlanets() {
    const c = make(LW, LH)
    const g = c.getContext('2d')
    g.scale(dpr, dpr)
    const m = Math.min(W, H)
    // ringed giant rising bottom-right, mostly off screen
    drawPlanet(g, PAD + W * 1.0, PAD + H * 1.04, m * 0.36, {
      base: mix(C.panel2, C.violet, 0.28),
      tint: C.trace,
      rings: true,
      tilt: -0.34,
      bands: true,
      light: -2.4,
    })
    // a moon, upper left
    drawPlanet(g, PAD + W * 0.09, PAD + H * 0.19, m * 0.055, {
      base: mix(C.panel, C.text, 0.1),
      tint: mix(C.text, C.trace, 0.5),
      light: -2.2,
      craters: true,
    })
    // a far crescent, upper right
    drawPlanet(g, PAD + W * 0.86, PAD + H * 0.14, m * 0.028, {
      base: mix(C.panel2, C.pink, 0.25),
      tint: C.pink,
      light: -0.7,
      crescent: true,
    })
    return c
  }

  function drawPlanet(g, x, y, r, o) {
    const light = o.light ?? -2.3
    const lx = x + Math.cos(light) * r * 0.5
    const ly = y + Math.sin(light) * r * 0.5
    const ring = (front) => {
      g.save()
      g.translate(x, y)
      g.rotate(o.tilt ?? -0.3)
      if (front) {
        g.beginPath()
        g.rect(-r * 3, 0, r * 6, r * 3)
        g.clip()
      }
      g.scale(1, 0.3)
      const inner = r * 1.32
      const outer = r * 2.35
      const grad = g.createRadialGradient(0, 0, inner, 0, 0, outer)
      grad.addColorStop(0, rgba(o.tint, 0))
      grad.addColorStop(0.06, rgba(o.tint, 0.2))
      grad.addColorStop(0.3, rgba(mix(o.tint, o.base, 0.45), 0.3))
      grad.addColorStop(0.4, rgba(o.tint, 0.03))
      grad.addColorStop(0.48, rgba(o.tint, 0.03))
      grad.addColorStop(0.56, rgba(mix(o.tint, WHITE, 0.3), 0.26))
      grad.addColorStop(0.8, rgba(o.tint, 0.14))
      grad.addColorStop(1, rgba(o.tint, 0))
      g.fillStyle = grad
      g.beginPath()
      g.arc(0, 0, outer, 0, Math.PI * 2)
      g.arc(0, 0, inner, 0, Math.PI * 2, true)
      g.fill('evenodd')
      g.restore()
    }
    if (o.rings) ring(false)

    const body = g.createRadialGradient(lx, ly, r * 0.05, x, y, r)
    body.addColorStop(0, rgba(mix(o.base, o.tint, 0.45), 1))
    body.addColorStop(0.45, rgba(o.base, 1))
    body.addColorStop(1, rgba(mix(o.base, [0, 0, 0], 0.7), 1))
    g.fillStyle = body
    g.beginPath()
    g.arc(x, y, r, 0, Math.PI * 2)
    g.fill()

    g.save()
    g.beginPath()
    g.arc(x, y, r, 0, Math.PI * 2)
    g.clip()
    if (o.bands) {
      g.save()
      g.translate(x, y)
      g.rotate((o.tilt ?? 0) * 0.6)
      for (let i = -5; i <= 5; i++) {
        const yy = i * r * 0.17 + (i % 2) * r * 0.03
        const dark = i % 2 === 0
        g.fillStyle = dark ? 'rgba(0,0,0,0.13)' : rgba(o.tint, 0.06)
        g.fillRect(-r, yy - r * 0.05, 2 * r, r * 0.1)
      }
      g.restore()
    }
    if (o.craters) {
      seed = 42
      for (let i = 0; i < 7; i++) {
        const ccx = x + (rnd() - 0.5) * r * 1.4
        const ccy = y + (rnd() - 0.5) * r * 1.4
        const ccr = r * (0.08 + rnd() * 0.14)
        g.fillStyle = 'rgba(0,0,0,0.22)'
        g.beginPath()
        g.arc(ccx, ccy, ccr, 0, Math.PI * 2)
        g.fill()
        g.strokeStyle = rgba(o.tint, 0.18)
        g.lineWidth = 0.8
        g.beginPath()
        g.arc(ccx, ccy, ccr, Math.PI * 0.9, Math.PI * 1.9)
        g.stroke()
      }
    }
    // night side
    const dx = x - Math.cos(light) * r * 0.55
    const dy = y - Math.sin(light) * r * 0.55
    const night = g.createRadialGradient(dx, dy, r * 0.15, dx, dy, r * 1.5)
    night.addColorStop(0, `rgba(0,0,0,${o.crescent ? 0.95 : 0.8})`)
    night.addColorStop(0.5, `rgba(0,0,0,${o.crescent ? 0.85 : 0.4})`)
    night.addColorStop(1, 'rgba(0,0,0,0)')
    g.fillStyle = night
    g.fillRect(x - r, y - r, 2 * r, 2 * r)
    g.restore()

    // rim light and atmosphere
    g.strokeStyle = rgba(o.tint, 0.32)
    g.lineWidth = 1.2
    g.beginPath()
    g.arc(x, y, r, 0, Math.PI * 2)
    g.stroke()
    const halo = g.createRadialGradient(x, y, r * 0.98, x, y, r * 1.14)
    halo.addColorStop(0, rgba(o.tint, 0.18))
    halo.addColorStop(1, rgba(o.tint, 0))
    g.fillStyle = halo
    g.beginPath()
    g.arc(x, y, r * 1.14, 0, Math.PI * 2)
    g.fill()

    if (o.rings) ring(true)
  }

  // screen positions where a size-wide tile must be drawn to cover [0, view]
  function tiles(offset, size, view) {
    const o = ((offset % size) + size) % size
    const out = []
    for (const k of [-1, 0]) {
      const p = -PAD + o + k * size
      if (p < view && p + size > 0) out.push(p)
    }
    return out
  }

  function render(now, scroll) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, W, H)
    const t = now / 1000
    const positions = []
    for (let i = 0; i < layers.length; i++) {
      const L = layers[i]
      const xs = tiles(t * L.drift - par.x * L.par * 2, LW, W)
      const ys = tiles(-scroll * L.scroll - par.y * L.par * 1.4, LH, H)
      positions.push({ xs, ys })
      for (const px of xs) for (const py of ys) ctx.drawImage(L.canvas, px, py, LW, LH)
    }
    if (!reduced) {
      for (const tw of twinkles) {
        const p = positions[tw.layer]
        const a = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * tw.speed + tw.phase))
        for (const px of p.xs) {
          for (const py of p.ys) {
            const sx = px + tw.x
            const sy = py + tw.y
            if (sx < -4 || sy < -4 || sx > W + 4 || sy > H + 4) continue
            ctx.fillStyle = rgba(tw.c, a)
            ctx.beginPath()
            ctx.arc(sx, sy, tw.r * (0.9 + 0.5 * a), 0, Math.PI * 2)
            ctx.fill()
          }
        }
      }
      for (let i = shooters.length - 1; i >= 0; i--) {
        const s = shooters[i]
        const age = (now - s.born) / 1000
        const k = age / (s.life / 1000)
        if (k >= 1) {
          shooters.splice(i, 1)
          continue
        }
        const hx = s.x + s.vx * age
        const hy = s.y + s.vy * age
        const len = 130
        const n = Math.hypot(s.vx, s.vy)
        const tx = hx - (s.vx / n) * len
        const ty = hy - (s.vy / n) * len
        const fade = Math.sin(k * Math.PI)
        const grad = ctx.createLinearGradient(hx, hy, tx, ty)
        grad.addColorStop(0, rgba(WHITE, 0.95 * fade))
        grad.addColorStop(0.3, rgba(mix(WHITE, C.trace, 0.5), 0.5 * fade))
        grad.addColorStop(1, rgba(C.trace, 0))
        ctx.strokeStyle = grad
        ctx.lineWidth = 1.6
        ctx.lineCap = 'round'
        ctx.beginPath()
        ctx.moveTo(hx, hy)
        ctx.lineTo(tx, ty)
        ctx.stroke()
      }
    }
    if (planets) {
      const sy = -PAD - par.y * 22 - Math.min(scroll, 2400) * 0.03
      ctx.drawImage(planets, -PAD - par.x * 34, sy, LW, LH)
    }
  }

  function frame(now) {
    raf = requestAnimationFrame(frame)
    if (coarse && now - last < 32) return
    last = now
    const tx = pointer.active ? pointer.x - 0.5 : 0
    const ty = pointer.active ? pointer.y - 0.5 : 0
    par.x += (tx - par.x) * 0.04
    par.y += (ty - par.y) * 0.04
    if (!coarse && now > nextShot) {
      nextShot = now + 6000 + Math.random() * 8000
      shooters.push({
        x: W * (0.1 + Math.random() * 0.8),
        y: H * Math.random() * 0.35,
        vx: (Math.random() < 0.5 ? -1 : 1) * (520 + Math.random() * 300),
        vy: 260 + Math.random() * 220,
        born: now,
        life: 700,
      })
    }
    render(now, window.scrollY)
  }

  const start = () => {
    if (reduced || raf) return
    last = 0
    raf = requestAnimationFrame(frame)
  }
  const stop = () => {
    cancelAnimationFrame(raf)
    raf = 0
  }

  let resizeTimer = 0
  const resize = () => {
    W = Math.max(1, window.innerWidth)
    H = Math.max(1, window.innerHeight)
    dpr = Math.min(window.devicePixelRatio || 1, coarse ? 1.5 : 2)
    canvas.width = Math.round(W * dpr)
    canvas.height = Math.round(H * dpr)
    C = readColors()
    build()
    render(performance.now(), reduced ? 0 : window.scrollY)
  }
  window.addEventListener('resize', () => {
    window.clearTimeout(resizeTimer)
    resizeTimer = window.setTimeout(resize, 150)
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') stop()
    else start()
  })

  resize()
  nextShot = performance.now() + 3000
  start()
}

// ---------- spotlight + grid, easing toward the pointer ----------

function initSpotlight(spot, grid) {
  let x = window.innerWidth / 2
  let y = window.innerHeight / 3
  let tx = x
  let ty = y
  let raf = 0
  const loop = () => {
    x += (tx - x) * 0.1
    y += (ty - y) * 0.1
    const t = `translate3d(${x.toFixed(1)}px, ${y.toFixed(1)}px, 0)`
    spot.style.transform = t
    grid.style.transform = t
    const ox = -((x - SPOT / 2) % CELL)
    const oy = -((y - SPOT / 2) % CELL)
    grid.style.backgroundPosition = `${ox.toFixed(1)}px ${oy.toFixed(1)}px`
    if (Math.abs(tx - x) + Math.abs(ty - y) > 0.3) raf = requestAnimationFrame(loop)
    else raf = 0
  }
  pointerListeners.add((p) => {
    const on = p.active ? '1' : '0'
    spot.style.opacity = on
    grid.style.opacity = on
    if (!p.active) return
    tx = p.cx
    ty = p.cy
    if (!raf) raf = requestAnimationFrame(loop)
  })
}

// ---------- 3D card tilt ----------

function initTilt() {
  const TILT = 8 // degrees at the edge of a small card
  let tilted = null
  const untilt = () => {
    if (tilted) {
      tilted.style.setProperty('--rx', '0deg')
      tilted.style.setProperty('--ry', '0deg')
      tilted = null
    }
  }
  document.addEventListener(
    'pointermove',
    (e) => {
      const card = e.target instanceof Element ? e.target.closest('.card') : null
      if (card !== tilted) untilt()
      if (!card) return
      tilted = card
      const r = card.getBoundingClientRect()
      // small cards tilt fully; wall-sized panels barely move
      const damp = Math.min(1, 420 / Math.max(r.width, r.height))
      const px = (e.clientX - r.left) / r.width - 0.5
      const py = (e.clientY - r.top) / r.height - 0.5
      card.style.setProperty('--rx', `${(-py * TILT * damp).toFixed(2)}deg`)
      card.style.setProperty('--ry', `${(px * TILT * damp).toFixed(2)}deg`)
    },
    { passive: true },
  )
  document.addEventListener('pointerleave', untilt)
}

// ---------- boot ----------

let booted = false

export function initCosmos() {
  if (booted) return // HMR / StrictMode guard: one sky is plenty
  booted = true

  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const fine = window.matchMedia('(pointer: fine)').matches

  // paint order = DOM order (all z-index 0, behind #root at z-index 1)
  const stars = el('canvas', 'stars')
  const wrap = el('div', 'aurora-wrap')
  el('div', 'aurora aurora-a', wrap)
  el('div', 'aurora aurora-b', wrap)
  el('div', 'aurora aurora-c', wrap)
  const grid = el('div', 'gridspot')
  const spot = el('div', 'spot')
  el('div', 'noise')

  initPointer()
  initStarfield(stars, reduced, !fine)
  if (fine && !reduced) {
    initSpotlight(spot, grid)
    initTilt()
  }
}
