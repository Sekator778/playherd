/**
 * The mid-round LIQUIDATION moment (D-50: "liquidation right in the middle of the round, a crack and the stamp
 * LIQUIDATED"). The moment fires the first time `myPositions()` reports a RUNNING position as liquidated
 * (`useLiquidationWatcher.ts`) — not when its round settles, which can be many minutes later — and says what is
 * true at that point: the stake is gone (D-7) and the round is still running.
 *
 * "Once" has to hold across reloads and tabs, so the fact that a round's liquidation was announced is kept in
 * `localStorage` (a key per player and round, pruned after a week). A claim is taken ATOMICALLY across tabs where the
 * browser allows it — under a Web Lock named for the round (`navigator.locks`, `ifAvailable`: a tab that finds the
 * lock held knows another tab is claiming this very instant and stays silent). Without Web Locks (an insecure page,
 * e.g. the stand opened by its LAN address on a phone; an old browser) it falls back to read, write, read back — which
 * leaves a window of about a millisecond between two tabs that claim in the same instant. Storage that cannot be used
 * — private mode, a quota, or a storage that DROPS writes without saying so (the read-back finds nothing) — falls
 * back to a per-page memory: no repeat within the page, which is the most that can be promised then.
 *
 * When the round later settles, `unseenResults.ts` asks {@link liquidationMomentShown}: if the player has already
 * been told, the settlement is a quiet one-line summary ({@link quietSummaryOf}) instead of a second flash,
 * stamp and shake for the same loss.
 *
 * And when the round is instead CANCELLED, every bet in it comes back — liquidated ones too (D-23) — which contradicts
 * what the moment said ("your whole stake is lost… unless the round is cancelled"). Cancelled rounds create no
 * history row, so `unseenResults()` never lists them: the watcher follows up every announced liquidation whose
 * position has left `myPositions()` ({@link pendingFollowUps}), asks the round what became of it, and — when it was
 * cancelled — says so once, quietly ({@link refundedNoteEvent}); {@link closeFollowUp} is that "once".
 */
import type { Interval, MarketId, MyPositionDto } from "../api/contract";
import { liquidationTier, type ResultEvent } from "./resultEvent";

const KEY_PREFIX = "herd.liqMoment.";
/** The follow-up of a claimed liquidation is closed (its round settled, or the cancellation was told): one key per round. */
const CLOSED_PREFIX = "herd.liqClosed.";
/** A claim is only needed until its round has settled — minutes to a day on any real series; a week is generous. */
const KEEP_MS = 7 * 24 * 3_600_000;
/** How many follow-ups one pass looks at: a catch-up after a long absence must not fire hundreds of requests at once. */
const MAX_FOLLOW_UPS_PER_PASS = 10;

/** Identifies this page among the tabs that share the storage. */
const TAB_TOKEN = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

/** What the claim remembers of the position, so a note written long after (a reload, another day) can still name it. */
export interface LiquidationInfo {
  stake: number;
  symbol: string;
  marketId: MarketId;
  interval: Interval;
  durationSec?: number;
  leverage: number;
}

interface Claim {
  at: number;
  token: string;
  info?: LiquidationInfo;
}

/** Claims taken when storage cannot be used: per page load (the value is what the claim remembers). */
const memoryClaims = new Map<string, Claim>();
let pruned = false;

function keyOf(prefix: string, playerId: string, roundId: string): string {
  return `${prefix}${playerId}:${roundId}`;
}

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null; // some browsers throw on the property access itself in private mode
  }
}

function readClaim(raw: string | null): Claim | null {
  if (raw === null) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<Claim> | null;
    if (!parsed || typeof parsed.at !== "number" || typeof parsed.token !== "string") return null;
    return { at: parsed.at, token: parsed.token, info: isInfo(parsed.info) ? parsed.info : undefined };
  } catch {
    return null;
  }
}

function isInfo(value: unknown): value is LiquidationInfo {
  const v = value as Partial<LiquidationInfo> | null | undefined;
  return !!v && typeof v.stake === "number" && Number.isFinite(v.stake) && typeof v.symbol === "string" && typeof v.marketId === "string" && typeof v.interval === "string" && typeof v.leverage === "number";
}

/** Drops claims older than {@link KEEP_MS} (and unreadable ones) — once per page load, so it costs nothing per tick. */
function pruneOldClaims(store: Storage): void {
  if (pruned) return;
  pruned = true;
  const stale: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const key = store.key(i);
    if (!key || (!key.startsWith(KEY_PREFIX) && !key.startsWith(CLOSED_PREFIX))) continue;
    const claim = readClaim(store.getItem(key));
    if (!claim || Date.now() - claim.at > KEEP_MS) stale.push(key);
  }
  for (const key of stale) store.removeItem(key);
}

/** Whether this player has already been shown the liquidation moment of this round (in this browser). */
export function liquidationMomentShown(playerId: string, roundId: string): boolean {
  return exists(keyOf(KEY_PREFIX, playerId, roundId));
}

function exists(key: string): boolean {
  if (memoryClaims.has(key)) return true;
  const store = storage();
  if (!store) return false;
  try {
    return store.getItem(key) !== null;
  } catch {
    return false;
  }
}

/** The per-page claim: no repeat within this page load, which is all storage that cannot be used can promise. */
function claimInMemory(key: string, claim: Claim): boolean {
  if (memoryClaims.has(key)) return false;
  memoryClaims.set(key, claim);
  return true;
}

/** Read, write, read back: the claim without Web Locks. The write that did not stick is not a loss — see the header. */
function claimInStorage(store: Storage, key: string, claim: Claim): boolean {
  try {
    if (store.getItem(key) !== null) return false;
    store.setItem(key, JSON.stringify(claim));
    const written = readClaim(store.getItem(key));
    pruneOldClaims(store);
    // Nothing (or nothing readable) where this page just wrote: the storage drops writes. It cannot arbitrate — use the page.
    if (!written) return claimInMemory(key, claim);
    return written.token === claim.token;
  } catch {
    return claimInMemory(key, claim); // storage refused the write: still no repeat within this page
  }
}

/** `navigator.locks`, where there is one (secure contexts only; jsdom has none). */
function webLocks(): LockManager | null {
  try {
    return typeof navigator !== "undefined" && navigator.locks && typeof navigator.locks.request === "function" ? navigator.locks : null;
  } catch {
    return null;
  }
}

/**
 * Takes a claim: `true` for exactly one caller across reloads and tabs, `false` for everyone who comes after (or lost
 * the race). Atomic across tabs under a Web Lock where the browser has them; see the module header for the fallbacks.
 */
async function claimOnce(prefix: string, playerId: string, roundId: string, info?: LiquidationInfo): Promise<boolean> {
  const key = keyOf(prefix, playerId, roundId);
  const claim: Claim = { at: Date.now(), token: TAB_TOKEN, info };
  if (memoryClaims.has(key)) return false;
  const store = storage();
  if (!store) return claimInMemory(key, claim);
  const locks = webLocks();
  if (locks) {
    try {
      return await locks.request(`herd:${key}`, { ifAvailable: true }, async (lock) => {
        // Held by another tab: it is claiming this very instant and will tell the player.
        if (lock === null) return false;
        return claimInStorage(store, key, claim);
      });
    } catch {
      // The lock manager refused (a sandboxed frame, say): the plain claim below.
    }
  }
  return claimInStorage(store, key, claim);
}

/**
 * Takes the right to show the moment for this round. `info` is what the claim remembers of the position (its stake and
 * market), for the note a cancellation may call for later.
 */
export function claimLiquidationMoment(playerId: string, roundId: string, info?: LiquidationInfo): Promise<boolean> {
  return claimOnce(KEY_PREFIX, playerId, roundId, info);
}

/**
 * Closes the follow-up of an announced liquidation — its round settled, or the cancellation has been told: `true` for
 * the one caller (across tabs and reloads) that closes it, who therefore says whatever there is to say; `false` after.
 */
export function closeFollowUp(playerId: string, roundId: string): Promise<boolean> {
  return claimOnce(CLOSED_PREFIX, playerId, roundId);
}

/** A liquidation the player was shown, whose round's fate has not been dealt with yet. */
export interface PendingFollowUp {
  roundId: string;
  /** When the moment was claimed. */
  at: number;
  info?: LiquidationInfo;
}

/**
 * The announced liquidations of this player whose follow-up is still open (newest first, at most
 * {@link MAX_FOLLOW_UPS_PER_PASS}): what the watcher asks about once the position has left `myPositions()`.
 */
export function pendingFollowUps(playerId: string): PendingFollowUp[] {
  const found = new Map<string, PendingFollowUp>();
  const own = keyOf(KEY_PREFIX, playerId, "");
  for (const [key, claim] of memoryClaims) {
    if (key.startsWith(own)) found.set(key.slice(own.length), { roundId: key.slice(own.length), at: claim.at, info: claim.info });
  }
  const store = storage();
  if (store) {
    try {
      for (let i = 0; i < store.length; i++) {
        const key = store.key(i);
        if (!key || !key.startsWith(own)) continue;
        const claim = readClaim(store.getItem(key));
        if (!claim || Date.now() - claim.at > KEEP_MS) continue;
        const roundId = key.slice(own.length);
        found.set(roundId, { roundId, at: claim.at, info: claim.info });
      }
    } catch {
      // unreadable storage: only what this page itself claimed
    }
  }
  return [...found.values()]
    .filter((pending) => !exists(keyOf(CLOSED_PREFIX, playerId, pending.roundId)))
    .sort((a, b) => b.at - a.at)
    .slice(0, MAX_FOLLOW_UPS_PER_PASS);
}

/** What a claim remembers of a position. */
export function liquidationInfoOf(position: MyPositionDto): LiquidationInfo {
  return {
    stake: Number(position.stake),
    symbol: position.symbol,
    marketId: position.marketId,
    interval: position.interval,
    durationSec: Number.isFinite(position.durationSec) && position.durationSec > 0 ? position.durationSec : undefined,
    leverage: position.leverage,
  };
}

/**
 * The event of a position liquidated mid-round. The stake left the balance at `placeBet` and a liquidation pays
 * nothing back (D-7), so the headline is `−stake`, and the card prints no balance for it: `balanceBefore` and
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

/**
 * The quiet note for a liquidated bet whose round was CANCELLED: the stake is back (D-23). `info` is what the claim
 * remembered of the position — absent for a claim an older build wrote, and the note then names the round without a
 * stake rather than inventing one. `asset`/`durationSec` come from the cancelled round itself.
 */
export function refundedNoteEvent(
  round: { roundId: string; marketId: MarketId; asset: string; window: { interval: Interval; startMs: number; endMs: number } },
  playerId: string,
  info: LiquidationInfo | undefined,
  nowMs: number,
): ResultEvent {
  const stake = info?.stake ?? 0;
  return {
    id: `${round.roundId}:${playerId}:refund`,
    kind: "LIQUIDATION",
    roundId: round.roundId,
    marketId: round.marketId,
    symbol: round.asset,
    interval: round.window.interval,
    durationSec: (round.window.endMs - round.window.startMs) / 1000,
    netPlay: 0,
    stakePlay: stake,
    leverage: info?.leverage ?? 0,
    balanceBefore: 0,
    balanceAfter: 0,
    atMs: nowMs,
    tier: 1,
    quiet: true,
    refunded: true,
  };
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
    if (key?.startsWith(KEY_PREFIX) || key?.startsWith(CLOSED_PREFIX)) keys.push(key);
  }
  for (const key of keys) store.removeItem(key);
}
