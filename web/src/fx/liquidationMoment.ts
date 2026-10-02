/**
 * The mid-round LIQUIDATION moment (Р-50: "ликвидация — сразу в середине раунда, «трещина» и штамп
 * LIQUIDATED"). The moment fires the first time `myPositions()` reports a RUNNING position as liquidated
 * (`useLiquidationWatcher.ts`) — not when its round settles, which can be many minutes later — and says what is
 * true at that point: the stake is gone (Р-7) and the round is still running.
 *
 * "Once" has to hold across reloads and tabs, so the fact that a round's liquidation was announced is kept in
 * `localStorage` (a key per player and round, pruned after a week). Two tabs polling at the same time both look
 * and both write a token; the one whose token is still there when it reads back owns the moment, the other stays
 * silent. Storage that cannot be used (private mode, quota) falls back to a per-page memory: no repeat within
 * the page, which is the most that can be promised then.
 *
 * When the round later settles, `unseenResults.ts` asks {@link liquidationMomentShown}: if the player has already
 * been told, the settlement is a quiet one-line summary ({@link quietSummaryOf}) instead of a second flash,
 * stamp and shake for the same loss.
 */
import type { MyPositionDto } from "../api/contract";
import { liquidationTier, type ResultEvent } from "./resultEvent";

const KEY_PREFIX = "herd.liqMoment.";
/** A claim is only needed until its round has settled — minutes to a day on any real series; a week is generous. */
const KEEP_MS = 7 * 24 * 3_600_000;

/** Identifies this page among the tabs that share the storage. */
const TAB_TOKEN = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
/** Claims taken when storage cannot be used: per page load. */
const memoryClaims = new Set<string>();
let pruned = false;

function keyOf(playerId: string, roundId: string): string {
  return `${KEY_PREFIX}${playerId}:${roundId}`;
}

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null; // some browsers throw on the property access itself in private mode
  }
}

interface Claim {
  at: number;
  token: string;
}

function readClaim(raw: string | null): Claim | null {
  if (raw === null) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<Claim> | null;
    return parsed && typeof parsed.at === "number" && typeof parsed.token === "string" ? { at: parsed.at, token: parsed.token } : null;
  } catch {
    return null;
  }
}

/** Drops claims older than {@link KEEP_MS} (and unreadable ones) — once per page load, so it costs nothing per tick. */
function pruneOldClaims(store: Storage): void {
  if (pruned) return;
  pruned = true;
  const stale: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const key = store.key(i);
    if (!key || !key.startsWith(KEY_PREFIX)) continue;
    const claim = readClaim(store.getItem(key));
    if (!claim || Date.now() - claim.at > KEEP_MS) stale.push(key);
  }
  for (const key of stale) store.removeItem(key);
}

/** Whether this player has already been shown the liquidation moment of this round (in this browser). */
export function liquidationMomentShown(playerId: string, roundId: string): boolean {
  const key = keyOf(playerId, roundId);
  if (memoryClaims.has(key)) return true;
  const store = storage();
  if (!store) return false;
  try {
    return store.getItem(key) !== null;
  } catch {
    return false;
  }
}

/**
 * Takes the right to show the moment for this round: `true` for exactly one caller across reloads and tabs, `false`
 * for everyone who comes after (or lost the race).
 */
export function claimLiquidationMoment(playerId: string, roundId: string): boolean {
  const key = keyOf(playerId, roundId);
  const claimInMemory = (): boolean => {
    if (memoryClaims.has(key)) return false;
    memoryClaims.add(key);
    return true;
  };
  const store = storage();
  if (!store) return claimInMemory();
  try {
    if (store.getItem(key) !== null) return false;
    store.setItem(key, JSON.stringify({ at: Date.now(), token: TAB_TOKEN } satisfies Claim));
    const winner = readClaim(store.getItem(key));
    pruneOldClaims(store);
    return winner?.token === TAB_TOKEN;
  } catch {
    return claimInMemory(); // storage refused the write: still no repeat within this page
  }
}

/**
 * The event of a position liquidated mid-round. The stake left the balance at `placeBet` and a liquidation pays
 * nothing back (Р-7), so the headline is `−stake`, and the card prints no balance for it: `balanceBefore` and
 * `balanceAfter` are 0 here instead of a guess (no request is made to learn them — the moment must not wait).
 */
export function liquidationMomentEvent(position: MyPositionDto, playerId: string, nowMs: number): ResultEvent {
  const stake = Number(position.stake);
  return {
    id: `${position.roundId}:${playerId}`,
    kind: "LIQUIDATION",
    roundId: position.roundId,
    marketId: position.marketId,
    symbol: position.symbol,
    interval: position.interval,
    durationSec: Number.isFinite(position.durationSec) && position.durationSec > 0 ? position.durationSec : undefined,
    netPlay: -stake,
    stakePlay: stake,
    leverage: position.leverage,
    balanceBefore: 0,
    balanceAfter: 0,
    atMs: nowMs,
    tier: liquidationTier(position.leverage),
    midRound: true,
  };
}

/** The settlement of a liquidation the player has already been told about, as the quiet summary it deserves. */
export function quietSummaryOf(event: ResultEvent): ResultEvent {
  return { ...event, midRound: undefined, quiet: true };
}

/** Forgets every claim — for a test that wants a clean slate; the app never needs it. */
export function resetLiquidationClaimsForTests(): void {
  memoryClaims.clear();
  pruned = false;
  const store = storage();
  if (!store) return;
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const key = store.key(i);
    if (key?.startsWith(KEY_PREFIX)) keys.push(key);
  }
  for (const key of keys) store.removeItem(key);
}
