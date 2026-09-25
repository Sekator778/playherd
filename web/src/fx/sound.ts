/**
 * WebAudio-synthesized sound effects (`implementation-plan.md` §4) — no audio files, zero bundle
 * weight, no license question. One lazily-created `AudioContext`, resumed on first real call
 * (browsers suspend it until a user gesture — every call site here is itself inside a gesture
 * handler: a tap-dismiss, a bet submit that triggers a later moment, etc.). All four functions are
 * no-ops when `sfxEnabled` is off (`fxSettings.ts`, default `false`).
 */
import type { Tier } from "./resultEvent";
import { getSfxEnabled } from "./fxSettings";

type AudioContextCtor = typeof AudioContext;

let ctxSingleton: AudioContext | null = null;

function getCtx(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const Ctor: AudioContextCtor | undefined =
    window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext;
  if (!Ctor) return null;
  if (!ctxSingleton) ctxSingleton = new Ctor();
  if (ctxSingleton.state === "suspended") ctxSingleton.resume().catch(() => {});
  return ctxSingleton;
}

function blip(ctx: AudioContext, atSec: number, freq: number, durSec: number, gain: number, type: OscillatorType): void {
  const osc = ctx.createOscillator();
  const g = ctx.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, ctx.currentTime + atSec);
  g.gain.setValueAtTime(0, ctx.currentTime + atSec);
  g.gain.linearRampToValueAtTime(gain, ctx.currentTime + atSec + 0.004);
  g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + atSec + durSec);
  osc.connect(g).connect(ctx.destination);
  osc.start(ctx.currentTime + atSec);
  osc.stop(ctx.currentTime + atSec + durSec + 0.02);
}

function noiseBurst(ctx: AudioContext, atSec: number, durSec: number, freqLow: number, freqHigh: number, gain: number): void {
  const size = Math.max(1, Math.floor(ctx.sampleRate * durSec));
  const buffer = ctx.createBuffer(1, size, ctx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < size; i++) data[i] = Math.random() * 2 - 1;
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  const filter = ctx.createBiquadFilter();
  filter.type = "bandpass";
  filter.frequency.value = (freqLow + freqHigh) / 2;
  filter.Q.value = 1.1;
  const g = ctx.createGain();
  g.gain.setValueAtTime(gain, ctx.currentTime + atSec);
  g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + atSec + durSec);
  src.connect(filter).connect(g).connect(ctx.destination);
  src.start(ctx.currentTime + atSec);
}

function thud(ctx: AudioContext, atSec: number, freq: number, durSec: number, gain: number): void {
  const osc = ctx.createOscillator();
  const g = ctx.createGain();
  osc.type = "sine";
  osc.frequency.setValueAtTime(freq, ctx.currentTime + atSec);
  osc.frequency.exponentialRampToValueAtTime(Math.max(20, freq * 0.6), ctx.currentTime + atSec + durSec);
  g.gain.setValueAtTime(gain, ctx.currentTime + atSec);
  g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + atSec + durSec);
  osc.connect(g).connect(ctx.destination);
  osc.start(ctx.currentTime + atSec);
  osc.stop(ctx.currentTime + atSec + durSec + 0.02);
}

const WIN_BLIPS: Record<Tier, number> = { 1: 6, 2: 12, 3: 18, 4: 26 };

/** Coin jingle (WIN) — N treble blips by tier (§4/result-moment.md §2), T3+ adds a metallic tail,
 *  T4 adds a short sub-boom. */
export function playWinJingle(tier: Tier): void {
  if (!getSfxEnabled()) return;
  const ctx = getCtx();
  if (!ctx) return;
  const count = WIN_BLIPS[tier];
  const spacing = 0.045;
  for (let i = 0; i < count; i++) {
    const freq = 1800 + Math.random() * 800 * (1 + (Math.random() - 0.5) * 0.3);
    blip(ctx, i * spacing, freq, 0.05, 0.07, i % 2 === 0 ? "triangle" : "square");
    noiseBurst(ctx, i * spacing, 0.03, 3000, 5000, 0.025);
  }
  const tailAt = count * spacing;
  if (tier >= 3) noiseBurst(ctx, tailAt, 0.18, 2200, 6000, 0.05);
  if (tier === 4) thud(ctx, tailAt + 0.05, 70, 0.2, 0.09);
}

/** "Тад" (LOSS, settlement) — a single low burst, deeper/longer by tier. */
export function playLossThud(tier: Tier): void {
  if (!getSfxEnabled()) return;
  const ctx = getCtx();
  if (!ctx) return;
  const freq = 120 - tier * 10;
  const dur = 0.15 + tier * 0.1;
  thud(ctx, 0, freq, dur, 0.08 + tier * 0.025);
  noiseBurst(ctx, 0, dur * 0.8, 60, 300, 0.04);
}

/** Crack + thud (LIQUIDATION) — `tier + 1` micro-clicks, then the same low burst as LOSS. */
export function playLiquidationCrack(tier: Tier): void {
  if (!getSfxEnabled()) return;
  const ctx = getCtx();
  if (!ctx) return;
  const clicks = tier + 1;
  for (let i = 0; i < clicks; i++) {
    noiseBurst(ctx, i * 0.028, 0.02, 2000 + tier * 500, 6000 + tier * 500, 0.07);
  }
  thud(ctx, clicks * 0.028 + 0.02, 95 - tier * 8, 0.15 + tier * 0.08, 0.09);
}

/** UI tick — leverage-dial haptic steps, EVEN moment, second-boundary countdown bounce. */
export function playTick(): void {
  if (!getSfxEnabled()) return;
  const ctx = getCtx();
  if (!ctx) return;
  blip(ctx, 0, 600, 0.02, 0.05, "sine");
}

/** Must run inside a user-gesture handler (browser autoplay policy) — the header/sound-toggle
 *  button and the result card's own toggle both call this when turning sound on. */
export function primeAudioContext(): void {
  getCtx();
}
