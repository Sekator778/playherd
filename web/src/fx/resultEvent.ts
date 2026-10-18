/**
 * Result-moment event model and tier math (`docs/product/design/result-moment.md` §0/§1/§2).
 * Pure functions only (`implementation-plan.md` §2: "вынесены отдельно от React ради
 * unit-тестов") — `useSettlementWatcher.ts` and `unseenResults.ts` are the callers, and own the
 * dedup `Set` and the previous-round `Map` this module treats as inputs, not state.
 */
import type { Interval, MarketId, MarketKind, ProfileRoundEntryDto, PublicPositionDto, RoundDto, RoundId } from "../api/contract";
import { BTC_MARKET_ID } from "../api/clock";
import { netFromPayoutAndStake, positionStakeStr } from "../api/outcome";

export type ResultKind = "WIN" | "LOSS" | "LIQUIDATION" | "EVEN";
export type Tier = 1 | 2 | 3 | 4;

export interface ResultEvent {
  /** `${roundId}:${playerId}` — the dedup key (§1). */
  id: string;
  kind: ResultKind;
  roundId: RoundId;
  /** The market the round was played in (Р-69) — `symbol` is its card ticker ("BTC", "NVDA",
   *  "GER") as the server reported it at settlement, for any text that names the market. */
  marketId: MarketId;
  symbol: string;
  /** The market's kind when the source of the event knows it (`ProfileRoundEntryDto.kind`, a round's own `kind`): an
   *  `ODDS` result is worded as odds ("GER odds"). Absent → the card reads it off the market list, if the market is on it. */
  marketKind?: MarketKind;
  /** The exchange the round ran on (`RoundBase.source`), when the event comes from a round (a live settlement) — a history
   *  row of `unseenResults()` carries none. With the market's backup (`MarketDto.backupSource`) the card can say that the
   *  round settled on the backup exchange (Р-76). */
  venue?: RoundDto["source"];
  interval: Interval;
  /** The real length of the round in seconds (`ProfileRoundEntryDto.durationSec`, or the live round's own window):
   *  the card names the series by it ("3 MIN" — never "Hourly"/"Daily"). Absent from a server that predates it:
   *  the card then reads the market's series, and says nothing about the length once the market has left the list. */
  durationSec?: number;
  /** `payout - stake`, signed — the ONLY number the card's headline ever shows (§0, §5). */
  netPlay: number;
  stakePlay: number;
  /** The bet's total fees (herd + network) as kept at settlement (`ProfileRoundEntryDto.fees`): the card adds "fees X" to its "Staked → returned" line.
   *  Absent for a round recorded without them: no fees line. */
  feesPlay?: number;
  leverage: number;
  balanceBefore: number;
  balanceAfter: number;
  atMs: number;
  tier: Tier;
  /** LIQUIDATION only: told the moment `myPositions()` first reported the position liquidated, while its round is
   *  STILL RUNNING (Р-50: "ликвидация — сразу в середине раунда"). The card says so: the stake is lost, the
   *  round is not over. */
  midRound?: boolean;
  /** The settlement of a round whose liquidation the player has ALREADY been shown (`midRound`): there is
   *  nothing new to celebrate or mourn, so it is a quiet one-line summary (`QuietNotes`) — no flash, no stamp,
   *  no sound, no vibration — and it still says the loss, signed. */
  quiet?: boolean;
  /** A quiet note for a liquidated bet whose round was CANCELLED after the player was told it was lost: every bet in a
   *  cancelled round is refunded, liquidated ones too (Р-23), so the stake is back (`stakePlay`; 0 when this build does
   *  not know it). Never a win or a loss — `netPlay` is 0. */
  refunded?: boolean;
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

/**
 * Rebuilds a `ResultEvent` from a settled `ProfileRoundEntryDto` (`myProfile().history`/
 * `unseenResults()`) instead of a live BETTING→RUNNING→SETTLED diff — the "while you were away"
 * path (`fx/useAwayReplay.ts`), where the whole thing already happened before this session ever
 * observed the round. Tier math is identical to `detectResultEvent`'s settlement branch; the only
 * difference is where the inputs come from. `opts.balanceBefore`/`balanceAfter` are the caller's
 * job (`useAwayReplay.ts` chains them across a batch of unseen entries back from the player's
 * current real balance) — this function does no balance bookkeeping of its own.
 */
export function resultEventFromHistoryEntry(
  entry: ProfileRoundEntryDto,
  playerId: string,
  opts: { balanceBefore: number; balanceAfter: number },
): ResultEvent {
  const id = `${entry.roundId}:${playerId}`;
  const stake = Number(entry.stake);
  const base = {
    id,
    roundId: entry.roundId,
    marketId: entry.marketId ?? BTC_MARKET_ID, // a server that predates markets sends no marketId: it is BTC's
    symbol: entry.asset,
    marketKind: entry.kind,
    interval: entry.interval,
    durationSec: entry.durationSec,
    stakePlay: stake,
    feesPlay: entry.fees != null && Number.isFinite(Number(entry.fees)) ? Number(entry.fees) : undefined,
    leverage: entry.leverage,
    balanceBefore: opts.balanceBefore,
    balanceAfter: opts.balanceAfter,
    atMs: entry.settledAtMs,
  };

  // Liquidation always wins over settlement-LOSS, same rule `detectResultEvent` enforces live
  // (§1's dedup) — balance is already fully reflected by the time this fires (the stake was
  // debited at placeBet, not at settlement), so before/after are equal regardless of what the
  // caller passed in.
  if (entry.liquidated) {
    return {
      ...base,
      kind: "LIQUIDATION",
      netPlay: -stake,
      balanceBefore: opts.balanceAfter,
      tier: liquidationTier(entry.leverage),
    };
  }

  const net = netFromPayoutAndStake(entry.payout, entry.stake);
  if (net > 0) {
    const multiplier = stake > 0 ? Number(entry.payout) / stake : 1;
    return { ...base, kind: "WIN", netPlay: net, tier: applyLeverageKicker(winTier(multiplier), entry.leverage) };
  }
  if (net < 0) {
    const stakeShare = opts.balanceBefore > 0 ? stake / opts.balanceBefore : 1;
    return { ...base, kind: "LOSS", netPlay: net, tier: applyLeverageKicker(lossTier(stakeShare), entry.leverage) };
  }
  return { ...base, kind: "EVEN", netPlay: 0, tier: 1 };
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
      marketId: curr.marketId ?? BTC_MARKET_ID, // see resultEventFromHistoryEntry
      symbol: curr.asset,
      marketKind: curr.kind,
      interval,
      durationSec: (curr.window.endMs - curr.window.startMs) / 1000,
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
      marketId: curr.marketId ?? BTC_MARKET_ID, // see resultEventFromHistoryEntry
      symbol: curr.asset,
      marketKind: curr.kind,
      venue: curr.source,
      interval,
      durationSec: (curr.window.endMs - curr.window.startMs) / 1000,
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
