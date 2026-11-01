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
import type { BetDto, LiveBetDto, PlayerSummaryDto } from "../api/contract";

/** A list row; `own` marks the viewer's own bet ("You"). */
export type LiveBetRow = LiveBetDto & { own: boolean };

/**
 * The server embeds only the newest `RECENT_BETS_LIMIT` bets, so with more players than that the viewer's own bet can
 * fall off the list. `viewer` + `myBet` (known exactly) put it back: when the feed holds no row of the viewer, the
 * own bet is appended at the end — it is older than every row the capped feed kept, which is exactly its place in the
 * newest-first order. A row the feed does hold is kept in place and takes the bet's current side/leverage/stake.
 * Without `viewer` nothing is added or marked. The list grows by the own row only.
 */
export function liveBetRows(
  recentBets: readonly LiveBetDto[],
  viewerId: string | null | undefined,
  myBet: BetDto | null | undefined,
  viewer?: PlayerSummaryDto | null,
): LiveBetRow[] {
  const seen = new Set<string>();
  const rows: LiveBetRow[] = [];
  for (const bet of recentBets) {
    const id = bet.player.playerId;
    if (seen.has(id)) continue; // an older version of a bet the player has changed since
    seen.add(id);
    if (id === viewerId && !myBet) continue; // the viewer's own bet was cancelled
    if (id === viewerId && myBet && viewer) {
      rows.push({ ...bet, direction: myBet.direction, leverage: myBet.leverage, stake: myBet.stake, own: true });
    } else {
      rows.push({ ...bet, own: false });
    }
  }
  if (viewer && myBet && viewerId && !seen.has(viewerId)) {
    rows.push({ player: viewer, direction: myBet.direction, leverage: myBet.leverage, stake: myBet.stake, atMs: 0, own: true });
  }
  return rows;
}

/**
 * The first `count` of an already ranked list, plus the viewer's own entry when the ranking puts it lower: the list
 * grows by that one entry and the entry keeps its rank order (it is below everything shown).
 */
export function topWithOwn<T>(ranked: readonly T[], count: number, isOwn: (item: T) => boolean): T[] {
  const top = ranked.slice(0, count);
  if (top.some(isOwn)) return top;
  const own = ranked.find(isOwn);
  return own ? [...top, own] : top;
}
