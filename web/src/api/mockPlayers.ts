/**
 * Deterministically generated NPC players — the "~40 players, a season of history" the task
 * asks for. Every function here is a pure function of a seed, so the same player has the same
 * nickname/address/history on every render and every reload (Р-9 already tells us ratings can be
 * gamed in this toy-money MVP, so reproducible-but-fake history is an honest match for that).
 *
 * The signed-in demo player (via mock wallet connect, see `mockApi.ts`) is intentionally NOT one
 * of these NPCs — every connect is a brand-new player (`AuthSessionDto.isNewPlayer = true`), so
 * the first-time flow (`ux_spec.md` §10) is always the one being demoed.
 */
import type {
  PlayerSummaryDto,
  ProfileRoundEntryDto,
  PublicProfileDto,
  RichEntryDto,
  ProphetEntryDto,
  SeasonId,
  SeasonRecordDto,
  Interval,
  Direction,
} from "./contract";
import { RATING_ROUNDS_THRESHOLD } from "./contract";
import { pick, randFloat, randInt, rngFor, chance } from "./rng";
import { SIM_BASE_MS, windowFor, prevWindow, roundIdFor } from "./clock";
import { seasonIdOf, seasonRange } from "./season";

export const NPC_COUNT = 40;

const FIRST_SYLLABLES = [
  "sat", "moon", "diamond", "paper", "degen", "chia", "block", "hodl", "candle", "wick",
  "bull", "bear", "whale", "shrimp", "node", "hash", "orbit", "vault", "signal", "ledger",
];
const SECOND_SYLLABLES = [
  "hands", "walker", "farmer", "runner", "watcher", "rider", "hunter", "stacker", "surfer",
  "pilot", "wizard", "sage", "oracle", "trader", "goblin", "fox", "hawk", "wolf", "otter", "yak",
];

const BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

export function fakeAddress(rng: () => number): string {
  let out = "txch1";
  for (let i = 0; i < 58; i++) {
    out += BECH32_CHARSET[Math.floor(rng() * BECH32_CHARSET.length)];
  }
  return out;
}

function nicknameFor(rng: () => number, index: number): string {
  const first = pick(rng, FIRST_SYLLABLES);
  const second = pick(rng, SECOND_SYLLABLES);
  const suffix = index % 7 === 0 ? String(randInt(rng, 10, 99)) : "";
  return `${first}${second[0].toUpperCase()}${second.slice(1)}${suffix}`;
}

function displayNameFor(nickname: string, address: string): string {
  return `${nickname} (${address.slice(0, 6)}…${address.slice(-4)})`;
}

export function npcPlayer(index: number): PlayerSummaryDto {
  const rng = rngFor("npc-player", index);
  const address = fakeAddress(rng);
  const nickname = nicknameFor(rng, index);
  return {
    playerId: `npc-${index}`,
    nickname,
    walletAddress: address,
    displayName: displayNameFor(nickname, address),
  };
}

export function allNpcPlayers(): PlayerSummaryDto[] {
  return Array.from({ length: NPC_COUNT }, (_, i) => npcPlayer(i));
}

// ---------------------------------------------------------------------------
// Season history (drives Ratings + Profile) — generated once per player as a single reproducible
// timeline of past rounds, then sliced per season by `settledAtMs`.
// ---------------------------------------------------------------------------

type HistoryEntry = ProfileRoundEntryDto;

function generateHistory(index: number): HistoryEntry[] {
  const rng = rngFor("npc-history", index);
  const count = randInt(rng, 4, 42);
  const entries: HistoryEntry[] = [];
  // Walk backward from "today" so entries land on real hourly/daily window boundaries.
  let hourlyWindow = prevWindow(windowFor("HOURLY", SIM_BASE_MS));
  let dailyWindow = prevWindow(windowFor("DAILY", SIM_BASE_MS));
  for (let i = 0; i < count; i++) {
    const interval: Interval = chance(rng, 0.75) ? "HOURLY" : "DAILY";
    const window = interval === "HOURLY" ? hourlyWindow : dailyWindow;
    if (interval === "HOURLY") hourlyWindow = prevWindow(hourlyWindow);
    else dailyWindow = prevWindow(dailyWindow);

    const direction: Direction = chance(rng, 0.5) ? "UP" : "DOWN";
    const leverage = Math.min(40, Math.max(2, Math.round(randFloat(rng, 2, 16))));
    const stake = randInt(rng, 1, 20) * 100;

    const liquidationChance = Math.min(0.55, 0.04 + leverage / 90);
    const liquidated = chance(rng, liquidationChance);

    let payout: number;
    let directionCorrect: boolean;
    if (liquidated) {
      payout = 0;
      directionCorrect = chance(rng, 0.5);
    } else if (chance(rng, 0.52)) {
      // won
      payout = stake * randFloat(rng, 1.1, 1.1 + leverage * 0.05);
      directionCorrect = chance(rng, 0.92);
    } else {
      // lost, not liquidated
      payout = stake * randFloat(rng, 0, 0.6);
      directionCorrect = chance(rng, 0.15);
    }

    entries.push({
      roundId: roundIdFor(window),
      interval,
      settledAtMs: window.endMs - 1,
      direction,
      leverage,
      stake: stake.toFixed(3),
      liquidated,
      directionCorrect,
      payout: payout.toFixed(3),
    });
  }
  return entries.sort((a, b) => b.settledAtMs - a.settledAtMs);
}

const historyCache = new Map<number, HistoryEntry[]>();

function historyForIndex(index: number): HistoryEntry[] {
  let cached = historyCache.get(index);
  if (!cached) {
    cached = generateHistory(index);
    historyCache.set(index, cached);
  }
  return cached;
}

export const CURRENT_SEASON: SeasonId = seasonIdOf(SIM_BASE_MS);

/** Shared by NPC and the signed-in demo player: a `SeasonRecordDto` is just a season-bounded
 *  slice of a `ProfileRoundEntryDto[]` history plus the three fields that history alone can't
 *  supply (Р-16/Р-18 — deposits/withdrawals don't come from round outcomes). */
export function computeSeasonRecord(
  history: ProfileRoundEntryDto[],
  season: SeasonId,
  extra: { baselineBalance: string; deposited: string; withdrawn: string },
): SeasonRecordDto {
  const { startMs, endMs } = seasonRange(season);
  const entries = history.filter((e) => e.settledAtMs >= startMs && e.settledAtMs < endMs);
  return {
    season,
    baselineBalance: extra.baselineBalance,
    deposited: extra.deposited,
    withdrawn: extra.withdrawn,
    roundsPlayed: entries.length,
    directionEligibleRounds: entries.length, // no flat rounds in this mock generator
    directionCorrectRounds: entries.filter((e) => e.directionCorrect === true).length,
  };
}

function seasonRecordFor(index: number, season: SeasonId): SeasonRecordDto {
  const rng = rngFor("npc-baseline", index, season);
  return computeSeasonRecord(historyForIndex(index), season, {
    baselineBalance: randFloat(rng, 4000, 16000).toFixed(3),
    deposited: "0.000",
    withdrawn: "0.000",
  });
}

export function npcPublicProfile(index: number): PublicProfileDto {
  const history = historyForIndex(index);
  const seasonRecord = history.length > 0 ? seasonRecordFor(index, CURRENT_SEASON) : null;
  return { player: npcPlayer(index), seasonRecord, history };
}

export function findNpcIndexByPlayerId(playerId: string): number | null {
  const match = /^npc-(\d+)$/.exec(playerId);
  if (!match) return null;
  const index = Number(match[1]);
  return index >= 0 && index < NPC_COUNT ? index : null;
}

// ---------------------------------------------------------------------------
// Ratings (RatingBook — Р-9, Р-18, Р-28, Р-30)
// ---------------------------------------------------------------------------

export function richRatings(season: SeasonId): RichEntryDto[] {
  const rows = Array.from({ length: NPC_COUNT }, (_, i) => {
    const record = seasonRecordFor(i, season);
    const { startMs, endMs } = seasonRange(season);
    const growth = historyForIndex(i)
      .filter((e) => e.settledAtMs >= startMs && e.settledAtMs < endMs)
      .reduce((sum, e) => sum + Number(e.payout) - Number(e.stake), 0);
    return { player: npcPlayer(i), growth, roundsPlayed: record.roundsPlayed };
  }).filter((r) => r.roundsPlayed >= RATING_ROUNDS_THRESHOLD);
  rows.sort((a, b) => b.growth - a.growth);
  return rows.map((r, i) => ({
    rank: i + 1,
    player: r.player,
    growth: r.growth.toFixed(3),
    roundsPlayed: r.roundsPlayed,
  }));
}

export function prophetRatings(season: SeasonId): ProphetEntryDto[] {
  const rows = Array.from({ length: NPC_COUNT }, (_, i) => {
    const record = seasonRecordFor(i, season);
    return { player: npcPlayer(i), record };
  }).filter((r) => r.record.roundsPlayed >= RATING_ROUNDS_THRESHOLD);
  rows.sort((a, b) => {
    const accA = a.record.directionCorrectRounds / Math.max(1, a.record.directionEligibleRounds);
    const accB = b.record.directionCorrectRounds / Math.max(1, b.record.directionEligibleRounds);
    return accB - accA;
  });
  return rows.map((r, i) => ({
    rank: i + 1,
    player: r.player,
    accuracy: r.record.directionCorrectRounds / Math.max(1, r.record.directionEligibleRounds),
    eligibleRounds: r.record.directionEligibleRounds,
    roundsPlayed: r.record.roundsPlayed,
  }));
}

export { seasonRecordFor };
