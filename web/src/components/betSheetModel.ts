/**
 * The bet sheet's pure rules (D-98): the stake chips, the three risk levels clamped to what the market and the stake allow, and the plain-words
 * example ("BTC -1% -> you win +10 ..."). The numbers come from the same liquidation preview the full slip uses; nothing is computed here that the
 * server would not agree with — the payout is stake x boost x the price move (`wave1-log.md`, 2026-10-16), and the wipeout price is the preview's.
 */
import { LEVERAGE_MIN, MIN_STAKE_PLAY, type Direction } from "../api/contract";
import { dialCeiling } from "./leverageScale";
import { defaultLeverage } from "./betSlipModel";

/** The stake chips the sheet offers besides "other". */
export const SHEET_STAKES = [100, 500, 1000] as const;

export type RiskId = "safer" | "normal" | "bold";

/** The three levels, as the boost each asks for before the market's and the stake's limits are applied. */
export const RISK_WANTED: ReadonlyArray<{ id: RiskId; wanted: number }> = [
  { id: "safer", wanted: 3 },
  { id: "normal", wanted: 10 },
  { id: "bold", wanted: 25 },
];

export interface RiskOption {
  id: RiskId;
  /** The boost this level really gives: what it asked for, lowered to the market's cap and to what the stake allows. */
  leverage: number;
}

/**
 * The levels on offer. Each is clamped to the market's own cap (per round length) and to the highest boost the stake allows; a level that the
 * clamp makes identical to a milder one is dropped, so the sheet never shows two buttons for one setting or a boost the server would refuse.
 */
export function riskOptions(cap: number, stakeMax: number | null): RiskOption[] {
  const ceiling = dialCeiling(cap, false, stakeMax);
  const out: RiskOption[] = [];
  for (const { id, wanted } of RISK_WANTED) {
    const leverage = Math.max(LEVERAGE_MIN, Math.min(wanted, ceiling));
    if (out.length > 0 && out[out.length - 1].leverage === leverage) continue;
    out.push({ id, leverage });
  }
  return out;
}

/**
 * The level the sheet opens on: "Normal x10", except on a market whose cap is x10 or lower, where x10 would be the cap itself — there the
 * slip has opened on x3 since the first tester pass (D2, `defaultLeverage`), and the sheet follows it.
 */
export function defaultRiskId(cap: number): RiskId {
  return defaultLeverage(cap) >= 10 ? "normal" : "safer";
}

/** The option for a chosen level; when the level was dropped as a duplicate, the nearest milder one that was kept. */
export function optionFor(options: readonly RiskOption[], id: RiskId): RiskOption {
  const order = RISK_WANTED.map((r) => r.id);
  const wantedIndex = order.indexOf(id);
  let best = options[0];
  for (const o of options) if (order.indexOf(o.id) <= wantedIndex) best = o;
  return best;
}

export interface Example {
  /** The price move the two example lines use, as a fraction (0.01 = 1%). */
  step: number;
  /** What the player nets on the favourable move / on the unfavourable one. With `fees` known both are AFTER the fees (`afterFees`): the
   *  fees are paid whatever happens, so a win is the gain minus the fees and a loss is the loss plus the fees. Without them, the gross figures. */
  win: number;
  lose: number;
  afterFees: boolean;
  /** The move that wipes the bet out, as a fraction, and the side of the price it is on: the price rising ("up") or falling ("down"). */
  wipeoutMove: number;
  wipeoutSide: "up" | "down";
}

const SMALL_STEPS = [0.005, 0.002, 0.001, 0.0005, 0.0002, 0.0001];

/**
 * The example's numbers. Wins and losses are the stake times the boost times the move, and — once the preview names the fees — net of them
 * (win = gain − fees, loss = −loss − fees). The move is 1% unless the wipeout line is nearer than
 * 2% (a high boost): then it is the largest round move that is no more than half the way to the line, so the example never shows a loss
 * the wipeout would already have taken.
 */
export function exampleFor(direction: Direction, stake: number, leverage: number, percentToLiquidation: number, fees: number | null = null): Example {
  let step = 0.01;
  if (percentToLiquidation < 0.02) step = SMALL_STEPS.find((s) => s <= percentToLiquidation / 2) ?? percentToLiquidation / 2;
  const amount = stake * leverage * step;
  // The fee line comes from the server's preview (the sheet never works a fee out itself); here it only moves the two figures.
  const paid = fees !== null && Number.isFinite(fees) && fees > 0 ? fees : null;
  return {
    step,
    win: amount - (paid ?? 0),
    lose: -amount - (paid ?? 0),
    afterFees: paid !== null,
    wipeoutMove: percentToLiquidation,
    wipeoutSide: direction === "DOWN" ? "up" : "down",
  };
}

/** Whether a typed or chosen stake is a bet at all: a number of at least the minimum stake. */
export function stakeIsValid(stake: string): boolean {
  const n = Number(stake);
  return stake !== "" && Number.isFinite(n) && n >= Number(MIN_STAKE_PLAY);
}

/** A price move for the example lines: "-1%", "+8.6%", with a real minus sign. */
export function signedMove(fraction: number, text: string): string {
  return `${fraction < 0 ? "−" : "+"}${text}`;
}
