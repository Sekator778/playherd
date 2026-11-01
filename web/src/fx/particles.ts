/**
 * Hand-rolled canvas particle engine (`implementation-plan.md` §1: "Our own canvas module" —
 * chosen over `canvas-confetti`/`tsparticles`, see that file for the comparison). Three sprite
 * kinds share one pool/loop: `coin` (WIN, flies a quadratic-bezier arc from the result card to the
 * header balance pill), `spark`/`confetti` (WIN burst, and the leverage dial's ambient heat
 * particles), `shard` (LOSS/LIQUIDATION, falls with gravity). Perf budget (§6): one
 * `position:fixed` canvas, `devicePixelRatio` capped at 2, a fixed-size object pool (no
 * `push`/`splice` in the rAF loop), only `transform`/`opacity`-equivalent canvas ops, and the
 * canvas element itself is created on first use and removed the moment nothing is active.
 */

export type SpriteKind = "coin" | "spark" | "confetti" | "shard";

interface ArcState {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
  cx: number;
  cy: number;
  t: number;
  durationMs: number;
  onArrive?: () => void;
}

interface Particle {
  active: boolean;
  kind: SpriteKind;
  x: number;
  y: number;
  vx: number;
  vy: number;
  gravity: number;
  rotation: number;
  vr: number;
  size: number;
  color: string;
  life: number;
  maxLife: number;
  arc?: ArcState;
}

const POOL_SIZE = 220; // implementation-plan.md §6: T4 WIN max ~170 simultaneous, headroom kept

function blankParticle(): Particle {
  return {
    active: false,
    kind: "spark",
    x: 0,
    y: 0,
    vx: 0,
    vy: 0,
    gravity: 0,
    rotation: 0,
    vr: 0,
    size: 4,
    color: "#ffffff",
    life: 0,
    maxLife: 0,
  };
}

const pool: Particle[] = Array.from({ length: POOL_SIZE }, blankParticle);

let canvasEl: HTMLCanvasElement | null = null;
let ctx2d: CanvasRenderingContext2D | null = null;
let rafId: number | null = null;
let lastFrameMs = 0;

function resizeCanvas(): void {
  if (!canvasEl || !ctx2d) return;
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  canvasEl.width = Math.round(window.innerWidth * dpr);
  canvasEl.height = Math.round(window.innerHeight * dpr);
  ctx2d.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function ensureCanvas(): CanvasRenderingContext2D | null {
  if (typeof document === "undefined") return null;
  if (canvasEl && ctx2d) return ctx2d;
  canvasEl = document.createElement("canvas");
  canvasEl.setAttribute("aria-hidden", "true");
  Object.assign(canvasEl.style, {
    position: "fixed",
    inset: "0",
    width: "100vw",
    height: "100vh",
    pointerEvents: "none",
    // MUST stay above `.result-overlay`'s z-index (9500, fx.css) and its `body.fx-header-lift
    // .app-header` lift (9550) — otherwise every burst paints underneath the result-moment's
    // semi-opaque blurred backdrop and is invisible (confirmed live on device 2026-09-25 with
    // this at 9000: canvas painted below the overlay, zero visible particles on any WIN/LOSS).
    zIndex: "9600",
  });
  document.body.appendChild(canvasEl);
  ctx2d = canvasEl.getContext("2d");
  resizeCanvas();
  window.addEventListener("resize", resizeCanvas);
  return ctx2d;
}

function teardownCanvasIfIdle(): void {
  if (pool.some((p) => p.active)) return;
  if (rafId !== null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
  if (canvasEl) {
    window.removeEventListener("resize", resizeCanvas);
    canvasEl.remove();
  }
  canvasEl = null;
  ctx2d = null;
}

function acquireParticle(): Particle | null {
  return pool.find((p) => !p.active) ?? null;
}

function drawStar(c: CanvasRenderingContext2D, size: number): void {
  c.beginPath();
  for (let i = 0; i < 4; i++) {
    const outer = (i * Math.PI) / 2;
    const inner = outer + Math.PI / 4;
    c.lineTo(Math.cos(outer) * size, Math.sin(outer) * size);
    c.lineTo(Math.cos(inner) * size * 0.35, Math.sin(inner) * size * 0.35);
  }
  c.closePath();
  c.fill();
}

function drawParticle(c: CanvasRenderingContext2D, p: Particle): void {
  const lifeFrac = p.arc ? 1 : p.maxLife > 0 ? Math.max(0, 1 - p.life / p.maxLife) : 1;
  c.save();
  c.globalAlpha = lifeFrac;
  c.translate(p.x, p.y);
  c.rotate(p.rotation);
  c.fillStyle = p.color;
  if (p.kind === "coin") {
    c.beginPath();
    c.arc(0, 0, p.size, 0, Math.PI * 2);
    c.fill();
    c.strokeStyle = "rgba(0,0,0,0.35)";
    c.lineWidth = 1;
    c.stroke();
  } else if (p.kind === "spark") {
    drawStar(c, p.size);
  } else if (p.kind === "confetti") {
    c.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2);
  } else {
    // shard — an angular sliver, not a soft blob (result-moment.md §2's "shards")
    c.beginPath();
    c.moveTo(-p.size / 2, -p.size);
    c.lineTo(p.size / 2, -p.size * 0.25);
    c.lineTo(p.size / 4, p.size);
    c.lineTo(-p.size / 3, p.size * 0.45);
    c.closePath();
    c.fill();
  }
  c.restore();
}

function stepArc(p: Particle, arc: ArcState, dtMs: number): boolean {
  arc.t = Math.min(1, arc.t + dtMs / arc.durationMs);
  const t = arc.t;
  const mt = 1 - t;
  p.x = mt * mt * arc.x0 + 2 * mt * t * arc.cx + t * t * arc.x1;
  p.y = mt * mt * arc.y0 + 2 * mt * t * arc.cy + t * t * arc.y1;
  p.rotation += p.vr * (dtMs / 16);
  if (arc.t >= 1) {
    arc.onArrive?.();
    return true; // done
  }
  return false;
}

function frame(nowMs: number): void {
  const c = ctx2d;
  if (!c || !canvasEl) return;
  const dt = lastFrameMs ? Math.min(48, nowMs - lastFrameMs) : 16;
  lastFrameMs = nowMs;
  c.clearRect(0, 0, canvasEl.width, canvasEl.height);

  let anyActive = false;
  for (const p of pool) {
    if (!p.active) continue;
    p.life += dt;
    let done: boolean;
    if (p.arc) {
      done = stepArc(p, p.arc, dt);
    } else {
      p.vy += p.gravity * (dt / 16);
      p.x += p.vx * (dt / 16);
      p.y += p.vy * (dt / 16);
      p.rotation += p.vr * (dt / 16);
      done = p.life >= p.maxLife;
    }
    if (done) {
      p.active = false;
      continue;
    }
    anyActive = true;
    drawParticle(c, p);
  }

  if (anyActive) {
    rafId = requestAnimationFrame(frame);
  } else {
    rafId = null;
    lastFrameMs = 0;
    teardownCanvasIfIdle();
  }
}

function ensureLoopRunning(): void {
  ensureCanvas();
  if (rafId === null) {
    lastFrameMs = 0;
    rafId = requestAnimationFrame(frame);
  }
}

/** WIN: coins flying a quadratic-bezier arc from the result card to the header balance pill
 *  (result-moment.md §3), staggered `staggerMs` apart, each firing `onEachArrive` and the last
 *  firing `onAllArrive` too — the caller uses that to land the `RollingDigits` count-up exactly as
 *  the last coin lands. */
export function spawnCoinArc(
  fromRect: DOMRect,
  toRect: DOMRect,
  opts: { count: number; staggerMs: number; onEachArrive?: () => void; onAllArrive?: () => void },
): void {
  if (typeof window === "undefined" || opts.count <= 0) return;
  const x0 = fromRect.left + fromRect.width / 2;
  const y0 = fromRect.top + fromRect.height / 2;
  const x1 = toRect.left + toRect.width / 2;
  const y1 = toRect.top + toRect.height / 2;
  let arrived = 0;
  for (let i = 0; i < opts.count; i++) {
    window.setTimeout(() => {
      const p = acquireParticle();
      ensureLoopRunning();
      if (!p) return;
      const spread = 22;
      const cx = (x0 + x1) / 2 + (Math.random() - 0.5) * spread;
      const cy = Math.min(y0, y1) - 70 - Math.random() * 50;
      p.active = true;
      p.kind = "coin";
      p.x = x0;
      p.y = y0;
      p.rotation = 0;
      p.vr = 0.25 + Math.random() * 0.35;
      p.size = 6 + Math.random() * 3;
      p.color = "#ffd166";
      p.life = 0;
      p.maxLife = 0;
      p.arc = {
        x0,
        y0,
        x1,
        y1,
        cx,
        cy,
        t: 0,
        durationMs: 480 + Math.random() * 200,
        onArrive: () => {
          arrived += 1;
          opts.onEachArrive?.();
          if (arrived >= opts.count) opts.onAllArrive?.();
        },
      };
    }, i * opts.staggerMs);
  }
}

/** A radial burst from `originRect`'s center — WIN sparks/confetti, or a LOSS/LIQUIDATION shard
 *  spray. `shard` falls with gravity; `spark`/`confetti` pop outward and fade. */
export function burst(originRect: DOMRect, opts: { kind: Exclude<SpriteKind, "coin">; count: number; colors?: string[] }): void {
  if (typeof window === "undefined" || opts.count <= 0) return;
  ensureLoopRunning();
  const cx = originRect.left + originRect.width / 2;
  const cy = originRect.top + originRect.height / 2;
  const colors =
    opts.colors ??
    (opts.kind === "shard" ? ["#ff2e9f", "#b3006e"] : opts.kind === "confetti" ? ["#ffd166", "#3cff8a", "#9147ff"] : ["#ffd166", "#fff2c6"]);
  for (let i = 0; i < opts.count; i++) {
    const p = acquireParticle();
    if (!p) break;
    const angle = Math.random() * Math.PI * 2;
    const speed = opts.kind === "shard" ? 2 + Math.random() * 3 : 3 + Math.random() * 4;
    p.active = true;
    p.kind = opts.kind;
    p.arc = undefined;
    p.x = cx;
    p.y = cy;
    p.vx = Math.cos(angle) * speed;
    p.vy = Math.sin(angle) * speed - (opts.kind === "shard" ? 0 : 2);
    p.gravity = opts.kind === "shard" ? 0.32 : 0.16;
    p.rotation = Math.random() * Math.PI * 2;
    p.vr = (Math.random() - 0.5) * 0.4;
    p.size = opts.kind === "confetti" ? 5 + Math.random() * 3 : opts.kind === "shard" ? 6 + Math.random() * 4 : 3 + Math.random() * 2;
    p.color = colors[Math.floor(Math.random() * colors.length)];
    p.life = 0;
    p.maxLife = 900 + Math.random() * 700;
  }
}

/** Ambient low-rate emission near a moving target (the leverage dial's thumb in the Hot/Danger/
 *  Flame zones, `live-round.md` §1) — returns a stop function. `getRect` is called fresh on every
 *  tick so it can track a moving element (e.g. the slider thumb as the player drags it). */
export function startAmbientEmission(getRect: () => DOMRect | null, kind: "spark" | "shard", ratePerSec: number): () => void {
  if (typeof window === "undefined" || ratePerSec <= 0) return () => {};
  const intervalMs = 1000 / ratePerSec;
  const id = window.setInterval(() => {
    const rect = getRect();
    if (!rect) return;
    burst(rect, { kind, count: 1 });
  }, intervalMs);
  return () => window.clearInterval(id);
}
