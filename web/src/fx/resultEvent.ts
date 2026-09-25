/**
 * Result-moment event model and tier math (`docs/product/design/result-moment.md` §0/§1/§2).
 * Pure functions only (`implementation-plan.md` §2: "вынесены отдельно от React ради
 * unit-тестов") — `useSettlementWatcher.ts` is the only caller, and owns the dedup `Set` and the
 * previous-round `Map` this module treats as inputs, not state.
 */
import type { Interval, PublicPositionDto, RoundDto, RoundId } from "../api/contract";
import { netFromPayoutAndStake, positionStakeStr } from "../api/outcome";

export type ResultKind = "WIN" | "LOSS" | "LIQUIDATION" | "EVEN";
export type Tier = 1 | 2 | 3 | 4;

export interface ResultEvent {
  /** `${roundId}:${playerId}` — the dedup key (§1). */
  id: string;
  kind: ResultKind;
  roundId: RoundId;
  interval: Interval;
  /** `payout - stake`, signed — the ONLY number the card's headline ever shows (§0, §5). */
  netPlay: number;
  stakePlay: number;
  leverage: number;
  balanceBefore: number;
  balanceAfter: number;
  atMs: number;
  tier: Tier;
}

// ---------------------------------------------------------------------------
// Tier tables (result-moment.md §2)
// ---------------------------------------------------------------------------

/** WIN tier by payout/stake multiplier. */
export function winTier(multiplier: number): Tier {
  if (multiplier < 1.5) return 1;
  if (multiplier < 3) return 2;
  if (multiplier < 8) return 3;
  return 4;
}

/** Settlement-LOSS tier by `stake / balanceBefore` — NOT for a LIQUIDATION, which tiers
 *  directly off leverage instead (see {@link liquidationTier}). */
export function lossTier(stakeShare: number): Tier {
  if (stakeShare < 0.05) return 1;
  if (stakeShare < 0.15) return 2;
  if (stakeShare < 0.4) return 3;
  return 4;
}

/** LIQUIDATION tier — direct leverage buckets, already "leverage-aware" from scratch, so it
 *  does not take the kicker below. */
export function liquidationTier(leverage: number): Tier {
  if (leverage < 10) return 1;
  if (leverage < 20) return 2;
  if (leverage < 30) return 3;
  return 4;
}

/**
 * Leverage kicker (§2): "выжил/поймал на топ-плече" (WIN or settlement-LOSS at leverage ≥ 30)
 * gets a heavier tier regardless of the raw amount — bumps by one, floors at T2, ceilings at T4.
 * Not applied to LIQUIDATION (its own table already tiers off leverage directly, §2's own text:
 * "liquidations получают своё, более тяжёлое обращение" — via {@link liquidationTier}, not this).
 */
export function applyLeverageKicker(tier: Tier, leverage: number): Tier {
  if (leverage < 30) return tier;
  return Math.min(4, Math.max(2, tier + 1)) as Tier;
}

function positionOf(round: RoundDto, playerId: string): PublicPositionDto | undefined {
  if (round.phase !== "RUNNING" && round.phase !== "SETTLED") return undefined;
  return round.positions.find((p) => p.playerId === playerId);
}

/** True if `round` is a phase/shape where this player could have PLAY at stake in it — used by
 *  `useSettlementWatcher` to decide whether a round that just rolled out of `home()`'s `.running`
 *  slot (see that file's header comment) is worth following up on with a direct `round()` call. */
export function roundHasPlayerStake(round: RoundDto, playerId: string): boolean {
  if (round.phase === "BETTING") return round.myBet?.playerId === playerId;
  if (round.phase === "RUNNING" || round.phase === "SETTLED") {
    return round.positions.some((p) => p.playerId === playerId);
  }
  return false;
}

export interface DetectParams {
  /** The same round's previous observed state, or `undefined` the first time it's seen (e.g.
   *  resolved via the `useSettlementWatcher` "pending settlement" follow-up, §1 header). */
  prev: RoundDto | undefined;
  curr: RoundDto;
  playerId: string;
  interval: Interval;
  /** `(roundId:playerId)` already produced a `ResultEvent` this session (§1's dedup key) — the
   *  caller owns this set, this function only reads it. */
  alreadyEmitted: boolean;
  /**
   * The player's account balance as most recently observed. предложение (no source pins this
   * exactly): `PositionDto.stake` is debited at `placeBet` time, not at settlement
   * (result-moment.md §2's LIQUIDATION note — "Баланс уже списан при постановке ставки"), so the
   * only balance change AT settlement is `+payout`, not `+net`. Using
   * `balanceBefore = currentBalance - netPlay` keeps the card's own "coins fly in / shards fall
   * out" animation internally consistent with the number the honesty redline requires it to show
   * (§5: always `net`, never `payout`) without needing a second, differently-timed account
   * snapshot right at the settlement instant.
   */
  currentBalance: number;
  nowMs: number;
}

export function detectResultEvent(params: DetectParams): ResultEvent | null {
  const { prev, curr, playerId, interval, alreadyEmitted, currentBalance, nowMs } = params;
  if (alreadyEmitted) return null;

  const currPos = positionOf(curr, playerId);
  if (!currPos) return null;
  const prevPos = prev ? positionOf(prev, playerId) : undefined;

  const id = `${curr.roundId}:${playerId}`;
  const stake = Number(positionStakeStr(currPos));

  // Mid-round liquidation, the instant it's first reported (§0) — independent of settlement.
  if (currPos.liquidated && !prevPos?.liquidated) {
    return {
      id,
      kind: "LIQUIDATION",
      roundId: curr.roundId,
      interval,
      netPlay: -stake,
      stakePlay: stake,
      leverage: currPos.leverage,
      balanceBefore: currentBalance,
      balanceAfter: currentBalance, // no change here — already debited at placeBet (§2)
      atMs: nowMs,
      tier: liquidationTier(currPos.leverage),
    };
  }

  // Settlement, the first time this round is seen SETTLED.
  const wasSettled = prev?.phase === "SETTLED";
  if (curr.phase === "SETTLED" && !wasSettled) {
    // Already fired as LIQUIDATION above (this tick or an earlier one) — dedup (§1): a
    // liquidated position's settlement never produces a second moment for the same result.
    if (currPos.liquidated) return null;
    if (currPos.payout === null) return null; // defensive — shouldn't happen once SETTLED

    const net = netFromPayoutAndStake(currPos.payout, positionStakeStr(currPos));
    const balanceBefore = currentBalance - net;
    const base = {
      id,
      roundId: curr.roundId,
      interval,
      netPlay: net,
      stakePlay: stake,
      leverage: currPos.leverage,
      balanceBefore,
      balanceAfter: currentBalance,
      atMs: nowMs,
    };

    if (net > 0) {
      const multiplier = stake > 0 ? Number(currPos.payout) / stake : 1;
      return { ...base, kind: "WIN", tier: applyLeverageKicker(winTier(multiplier), currPos.leverage) };
    }
    if (net < 0) {
      const stakeShare = balanceBefore > 0 ? stake / balanceBefore : 1;
      return { ...base, kind: "LOSS", tier: applyLeverageKicker(lossTier(stakeShare), currPos.leverage) };
    }
    // EVEN — result-moment.md §0 flags this as "предложение": the contract doesn't document a
    // condition under which a single player's `payout === stake` occurs. Defensive, one fixed
    // tier (no escalation table exists for it).
    return { ...base, kind: "EVEN", tier: 1 };
  }

  return null;
}
