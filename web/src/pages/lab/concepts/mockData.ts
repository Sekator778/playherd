/**
 * Shared mock data for the three /lab/concepts/{a,b,c} prototypes (Р-59 follow-up task — "Build
 * THREE distinct, clickable concept prototypes... mock data only, no server changes"). Every
 * number here is invented for the prototype, not read through `Api`/`useApi()` — these routes
 * stay independent of whichever `Api` implementation the surrounding stand happens to be built
 * with (mock or real, `api/apiMode.ts`), same as `LabPage.tsx` already does for the result-moment
 * previews. Nothing here is wired to a real round, a real balance, or a real session.
 *
 * Shapes deliberately echo `api/contract.ts`'s real DTOs (`PlayerSummaryDto`, `CrowdHistoryPointDto`,
 * `LeaderboardEntry`, `ResultTile`'s props) so the concept screens can reuse the same presentation
 * components (`Leaderboard.tsx`, `ResultTile.tsx`, `CrowdChart.tsx`, `LeverageDial.tsx`,
 * `LiquidationBar.tsx`) byte-for-byte, not reimplementations of them.
 */
import type { Direction, PlayerSummaryDto } from "../../../api/contract";
import { LONG, SHORT, liquidationPrice as calcLiquidationPrice, unrealizedPnl, percentToLiquidation, sizeForMargin } from "../../../api/liquidation";

// ---------------------------------------------------------------------------
// Pairs (multi-pair lobby — docs/research/multi_pair_architecture.md; BTC is the only real,
// tradeable pair today, task: "to show the multi-pair layout use clearly-labelled placeholder
// tiles like 'ETH — coming soon', never fake live data").
// ---------------------------------------------------------------------------

export type PairStatus = "live" | "coming-soon";

export interface PairMeta {
  id: string;
  symbol: string;
  name: string;
  status: PairStatus;
  /** Only meaningful for `status: "live"` — a placeholder tile never shows a player count
   *  (Р-22 is about revealed direction/amounts, but an invented count for a pair that doesn't
   *  exist yet would itself be a fabricated number, not covered by any decision). */
  players?: number;
  hot?: boolean;
}

export const PAIRS: PairMeta[] = [
  { id: "btc", symbol: "BTC", name: "Bitcoin", status: "live", players: 214, hot: true },
  { id: "eth", symbol: "ETH", name: "Ethereum", status: "coming-soon" },
  { id: "sol", symbol: "SOL", name: "Solana", status: "coming-soon" },
];

export function pairById(id: string | undefined): PairMeta {
  return PAIRS.find((p) => p.id === id) ?? PAIRS[0];
}

// ---------------------------------------------------------------------------
// A single mock round for the pair room — test-mode round lengths (Р-46: hourly=3min in test
// mode) reused here purely so the on-screen countdown feels like the real thing, not a made-up
// duration.
// ---------------------------------------------------------------------------

export type RoomPhase = "BETTING" | "RUNNING" | "SETTLED";

export const MOCK_ENTRY_PRICE = 83451.0; // same reference price LabPage.tsx already previews with
export const ROUND_MS = 3 * 60_000; // test-mode hourly round length (Р-46)

export interface MockPosition {
  nickname: string;
  isBot?: boolean;
  direction: Direction;
  leverage: number;
  stake: number;
}

const CROWD_ROSTER: MockPosition[] = [
  { nickname: "test1", direction: "UP", leverage: 8, stake: 400 },
  { nickname: "MoonBot-04", isBot: true, direction: "UP", leverage: 22, stake: 900 },
  { nickname: "sekator", direction: "UP", leverage: 12, stake: 250 },
  { nickname: "HODL-Bot-11", isBot: true, direction: "UP", leverage: 4, stake: 1200 },
  { nickname: "test3", direction: "DOWN", leverage: 14, stake: 10345 },
  { nickname: "FadeBot-02", isBot: true, direction: "DOWN", leverage: 18, stake: 600 },
  { nickname: "test2", direction: "DOWN", leverage: 10, stake: 9968 },
  { nickname: "ScalperBot-09", isBot: true, direction: "DOWN", leverage: 30, stake: 300 },
];

/** Deterministic small price wobble driven by wall-clock time only (no fetch, no RNG seed to
 *  persist) — purely cosmetic "the market is alive" motion for the room screen, never presented
 *  as a real feed (the round's honest entry/close prices are the fixed constants above/below). */
function wobblePrice(nowMs: number, amplitude: number): number {
  return MOCK_ENTRY_PRICE + Math.sin(nowMs / 4000) * amplitude + Math.sin(nowMs / 977) * (amplitude * 0.35);
}

export interface RoomState {
  phase: RoomPhase;
  startMs: number;
  endMs: number;
  openPrice: number;
  currentPrice: number;
  closePrice: number;
  participantCount: number;
  upStake: number;
  downStake: number;
  positions: MockPosition[];
  own: {
    direction: Direction;
    leverage: number;
    stake: number;
    entryPrice: number;
    liquidationPrice: number;
  };
}

/** Builds a consistent mock round for a given phase, "now" and the player's own hypothetical
 *  bet controls (leverage/direction/stake as currently set in the room's own bet slip). */
export function buildRoomState(
  phase: RoomPhase,
  nowMs: number,
  own: { direction: Direction; leverage: number; stake: number },
): RoomState {
  const startMs = nowMs - (phase === "BETTING" ? ROUND_MS / 3 : 0);
  const endMs = phase === "BETTING" ? startMs + ROUND_MS : nowMs + (phase === "RUNNING" ? ROUND_MS * 0.4 : 0);
  const currentPrice = phase === "BETTING" ? wobblePrice(nowMs, 18) : wobblePrice(nowMs, 90);
  const closePrice = MOCK_ENTRY_PRICE + 612; // fixed "BTC closed UP" outcome for the SETTLED screenshot
  const side = own.direction === "UP" ? LONG : SHORT;
  const size = sizeForMargin(own.stake, own.leverage, MOCK_ENTRY_PRICE);
  const liq = calcLiquidationPrice(size, MOCK_ENTRY_PRICE, own.leverage, side);

  return {
    phase,
    startMs,
    endMs,
    openPrice: MOCK_ENTRY_PRICE,
    currentPrice,
    closePrice,
    participantCount: CROWD_ROSTER.length + 1,
    upStake: CROWD_ROSTER.filter((p) => p.direction === "UP").reduce((s, p) => s + p.stake, 0) + (own.direction === "UP" ? own.stake : 0),
    downStake: CROWD_ROSTER.filter((p) => p.direction === "DOWN").reduce((s, p) => s + p.stake, 0) + (own.direction === "DOWN" ? own.stake : 0),
    positions: CROWD_ROSTER,
    own: { direction: own.direction, leverage: own.leverage, stake: own.stake, entryPrice: MOCK_ENTRY_PRICE, liquidationPrice: liq.toNumber() },
  };
}

export function ownLivePnl(room: RoomState): number {
  const side = room.own.direction === "UP" ? LONG : SHORT;
  const size = sizeForMargin(room.own.stake, room.own.leverage, room.own.entryPrice);
  return unrealizedPnl(size, room.own.entryPrice, side, room.currentPrice).toNumber();
}

export function ownPercentToLiquidation(room: RoomState): number {
  return percentToLiquidation(room.currentPrice, room.own.liquidationPrice);
}

// ---------------------------------------------------------------------------
// Stats screen — crowd chart / ratings / profile mock data.
// ---------------------------------------------------------------------------

function player(nickname: string, isBot = false): PlayerSummaryDto {
  return {
    playerId: nickname,
    nickname,
    walletAddress: "xch1qp0mockmockmockmockmockmockmockmockmockmqqqxw",
    displayName: isBot ? `${nickname} (xch1qp…qqqxw)` : `${nickname} (xch1qp…qqqxw)`,
    isBot,
  };
}

const CROWD_HISTORY_DAYS = 90;
export const MOCK_CROWD_HISTORY = Array.from({ length: CROWD_HISTORY_DAYS }, (_, i) => {
  const t = i / (CROWD_HISTORY_DAYS - 1);
  // Smooth invented walk, not a real market replay — good enough to show chart shape/behavior.
  const crowd = 100_000 * (1 + 0.42 * t + 0.06 * Math.sin(t * 11));
  const btc = 100_000 * (1 + 0.22 * t + 0.03 * Math.sin(t * 7 + 1));
  const anti = 100_000 * (1 - 0.18 * t + 0.04 * Math.sin(t * 9 + 2));
  return {
    roundId: `1h-mock-${i}`,
    atMs: Date.now() - (CROWD_HISTORY_DAYS - i) * 86_400_000,
    seasonBoundary: i === 30 ? "2026-08" : i === 60 ? "2026-09" : null,
    crowdCapital: crowd.toFixed(2),
    holdCapital: btc.toFixed(2),
    antiCrowdCapital: anti.toFixed(2),
  };
});

export const MOCK_RICH_ENTRIES = [
  { rank: 1, player: player("MoonBot-04", true), growth: 4820.5 },
  { rank: 2, player: player("sekator"), growth: 3110.0 },
  { rank: 3, player: player("test1"), growth: 1260.4 },
  { rank: 4, player: player("HODL-Bot-11", true), growth: -640.2 },
  { rank: 5, player: player("test2"), growth: -1980.75 },
];

export const MOCK_PROPHET_ENTRIES = [
  { rank: 1, player: player("test1"), accuracy: 0.71 },
  { rank: 2, player: player("MoonBot-04", true), accuracy: 0.64 },
  { rank: 3, player: player("sekator"), accuracy: 0.58 },
];

export const MOCK_MY_PROFILE = {
  player: player("sekator"),
  roundsPlayed: 34,
  accuracy: 0.58,
  balanceChange: 3110.0,
  history: [
    { roundId: "1h-mock-h1", interval: "HOURLY" as const, direction: "UP" as const, leverage: 14, net: 166.2, liquidated: false },
    { roundId: "1h-mock-h2", interval: "HOURLY" as const, direction: "DOWN" as const, leverage: 10, net: -114.39, liquidated: false },
    { roundId: "1h-mock-h3", interval: "DAILY" as const, direction: "UP" as const, leverage: 30, net: -500, liquidated: true },
    { roundId: "1h-mock-h4", interval: "HOURLY" as const, direction: "UP" as const, leverage: 6, net: 42.1, liquidated: false },
  ],
};

/** Concept B's "live show" activity feed — last few positions, phrased the same honest way the
 *  round's own `PositionsList` already shows them (direction/leverage/stake), just as a scrolling
 *  strip instead of a static two-column stage. Not a new kind of fact, a new arrangement of the
 *  same `CROWD_ROSTER` facts above. */
export const MOCK_ACTIVITY_FEED = CROWD_ROSTER.map((p) => ({
  text: `${p.nickname}${p.isBot ? " (bot)" : ""} bet ${p.stake.toLocaleString("en-US")} ${p.direction} · x${p.leverage}`,
  direction: p.direction,
}));
