/**
 * "How much of my balance is tied up right now" — the other half of Alex's report
 * (2026-09-25): the header balance pill showed only the free balance (`myAccount().balance`),
 * so a player who had just won a round but already staked the payout on the next one saw a
 * balance *lower* than their starter pack, with nothing explaining why. Sums the player's stake
 * across every round still in play (the open betting round and the running round, per series) —
 * built entirely from `rounds()` (task item: "use data the API already returns: rounds +
 * myAccount/bets"), no contract change needed for this part.
 */
import type { RoundsPairDto } from "./contract";
import { positionStakeStr } from "./outcome";

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

/** Both series (Hourly + Daily) combined — what the header's "+{N} in play" figure shows. */
export function totalInPlayStake(playerId: string, hourly: RoundsPairDto, daily: RoundsPairDto): number {
  return inPlayStakeForSeries(playerId, hourly) + inPlayStakeForSeries(playerId, daily);
}
