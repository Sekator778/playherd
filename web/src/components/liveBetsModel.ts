/**
 * The rows of a room's LIVE BETS (`RoundBetting.recentBets`): one per player — the bet that player holds NOW.
 *
 * The server's feed is append-only (`BetFeed`: an edit adds a row and never rewrites the earlier one, a cancel removes
 * nothing), so a player who changed their bet three times used to appear four times, and a cancelled bet stayed in the
 * list after the stake had been refunded (first tester pass, D4: "110, 150, 120, 300 for one bet"). What the round
 * holds is each player's NEWEST entry; the feed arrives newest first, so that is the first one met for a player.
 *
 * The viewer's own bet is known exactly (`RoundBetting.myBet`), so it settles their rows: no bet, no row — the
 * cancel the feed cannot know of. Another player's cancellation is not visible to a client at all (only the server
 * knows which of its rows still has a bet behind it); that stays until `recentBets` is built from the live bet book.
 */
import type { BetDto, LiveBetDto } from "../api/contract";

export function liveBetRows(
  recentBets: readonly LiveBetDto[],
  viewerId: string | null | undefined,
  myBet: BetDto | null | undefined,
): LiveBetDto[] {
  const seen = new Set<string>();
  const rows: LiveBetDto[] = [];
  for (const bet of recentBets) {
    const id = bet.player.playerId;
    if (seen.has(id)) continue; // an older version of a bet the player has changed since
    seen.add(id);
    if (id === viewerId && !myBet) continue; // the viewer's own bet was cancelled
    rows.push(bet);
  }
  return rows;
}
