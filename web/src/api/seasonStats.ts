/**
 * Season-scoped highlights derived from a player's own settled-round history — pure functions,
 * no fabrication: every number here is a real `net = payout - stake` from an actual round the
 * player played (same math `outcome.ts`'s `netFromPayoutAndStake` and `ProfilePage.tsx`'s
 * `seasonBalanceChange` already use), just picking out the *best* one instead of summing all of
 * them. Built for the "Richest" leaderboard redesign (`docs/product/design/leaderboard.md`,
 * independent critique 2026-09-26: "reads as everyone loses") — the fix keeps every rank's real,
 * signed number exactly as-is (never repaint a loss as a win — `game-designer.md`'s honesty
 * rule), and instead gives the page real positive numbers to lead with when there are any this
 * season: the player's own best round, and their biggest comeback.
 */
import type { ProfileRoundEntryDto, SeasonId } from "./contract";
import { netFromPayoutAndStake } from "./outcome";
import { seasonRange } from "./season";

export interface SeasonHighlight {
  entry: ProfileRoundEntryDto;
  net: number;
}

/** `history` (newest first, per `PublicProfileDto`) sliced to one season — the same slicing
 *  `ProfilePage.tsx`'s `seasonBalanceChange` does inline; factored out here so every season-scoped
 *  stat (that one included) filters the same way. */
export function seasonHistory(history: ProfileRoundEntryDto[], season: SeasonId): ProfileRoundEntryDto[] {
  const { startMs, endMs } = seasonRange(season);
  return history.filter((h) => h.settledAtMs >= startMs && h.settledAtMs < endMs);
}

/** The single biggest net win this season — `null` when the player hasn't had one (never shown
 *  as a highlight when there isn't a real win to report; an all-losing season stays honestly
 *  silent on this tile rather than surfacing its "least-bad" round). */
export function bestSingleRound(history: ProfileRoundEntryDto[], season: SeasonId): SeasonHighlight | null {
  let best: SeasonHighlight | null = null;
  for (const entry of seasonHistory(history, season)) {
    const net = netFromPayoutAndStake(entry.payout, entry.stake);
    if (net > 0 && (!best || net > best.net)) best = { entry, net };
  }
  return best;
}

/** The biggest single-round win that landed right after a loss (or a liquidation, which is
 *  always a loss of the whole stake, Р-7) — `history` is newest-first, so the round
 *  chronologically right before `rounds[i]` is `rounds[i + 1]`. `null` when this season has no
 *  such pair (including "not enough rounds yet" and "never lost, so never came back either"). */
export function biggestComeback(history: ProfileRoundEntryDto[], season: SeasonId): SeasonHighlight | null {
  const rounds = seasonHistory(history, season);
  let best: SeasonHighlight | null = null;
  for (let i = 0; i < rounds.length - 1; i++) {
    const net = netFromPayoutAndStake(rounds[i].payout, rounds[i].stake);
    const priorNet = netFromPayoutAndStake(rounds[i + 1].payout, rounds[i + 1].stake);
    if (net > 0 && priorNet < 0 && (!best || net > best.net)) best = { entry: rounds[i], net };
  }
  return best;
}
