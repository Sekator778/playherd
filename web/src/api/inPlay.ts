/**
 * "How much of my balance is tied up right now" — the other half of Alex's report
 * (2026-09-25): the header balance pill showed only the free balance (`myAccount().balance`),
 * so a player who had just won a round but already staked the payout on the next one saw a
 * balance *lower* than their starter pack, with nothing explaining why.
 *
 * The figure is `myAccount().inPlay` whenever the server sends it — every stake still in a betting
 * or running round, in ANY market ({@link accountInPlay}). Only for a server that predates the field
 * does {@link loadInPlay} fall back to summing the player's stake over `rounds()` — the open betting
 * round and the running round, per series — which sees the BTC pair only (`rounds` without a
 * market), so it under-reports once a player is in other markets.
 */
import type { Api, MyPositionDto, PlayerAccountDto, RoundsPairDto } from "./contract";
import { positionStakeStr } from "./outcome";

/** The server's own in-play figure, or `null` when this server does not send one. */
export function accountInPlay(account: PlayerAccountDto): number | null {
  return account.inPlay === undefined ? null : Number(account.inPlay);
}

/** What the header's "+{N} in play" shows: the account's figure when present, otherwise the BTC-only
 *  sum over `rounds()` ({@link totalInPlayStake}). */
export async function loadInPlay(api: Api, playerId: string, account: PlayerAccountDto): Promise<number> {
  const fromAccount = accountInPlay(account);
  if (fromAccount !== null) return fromAccount;
  const [hourly, daily] = await Promise.all([api.rounds("HOURLY"), api.rounds("DAILY")]);
  return totalInPlayStake(playerId, hourly, daily);
}

/** This series' contribution: the reserved bet on the open (BETTING) round, plus the position
 *  already opened on the running round, if the player has either. A round that's SETTLED or
 *  CANCELLED (or an early-cancelled betting round, `RoundCancelled`) contributes nothing — its
 *  stake has already been paid out or refunded. */
export function inPlayStakeForSeries(playerId: string, pair: RoundsPairDto): number {
  let total = 0;
  if (pair.betting.phase === "BETTING" && pair.betting.myBet) {
    total += Number(pair.betting.myBet.stake);
  }
  if (pair.running.phase === "RUNNING") {
    const own = pair.running.positions.find((p) => p.playerId === playerId);
    if (own) total += Number(positionStakeStr(own));
  }
  return total;
}

/** Both series (Hourly + Daily) of one pair combined — the fallback behind the header's "+{N} in play". */
export function totalInPlayStake(playerId: string, hourly: RoundsPairDto, daily: RoundsPairDto): number {
  return inPlayStakeForSeries(playerId, hourly) + inPlayStakeForSeries(playerId, daily);
}

/**
 * The stake of the player's positions that a liquidation has ALREADY taken while their round is still running
 * (`MyPositionDto.liquidated`). The server counts it as "in play" until the round settles — nothing is paid
 * back, it just has not been booked yet — but the player has been told it is lost (the mid-round LIQUIDATION
 * moment, Р-7/Р-50), so a header that went on saying "+300 in play" would suggest it could still come back.
 */
export function liquidatedStake(positions: readonly MyPositionDto[]): number {
  return positions.filter((p) => p.phase === "RUNNING" && p.liquidated).reduce((sum, p) => sum + Number(p.stake), 0);
}

/** The header's in-play figure with what a liquidation already took left out — never below zero (the server's
 *  figure and the positions list come from two polls, so they can disagree for a moment). */
export function inPlayExcludingLiquidated(inPlay: number, positions: readonly MyPositionDto[]): number {
  return Math.max(0, inPlay - liquidatedStake(positions));
}
