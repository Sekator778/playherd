/**
 * The fixed-payout mode ("Win or lose", Р-106, `docs/product/fixed_payout_rules.md`): the pure rules the bet sheet and the result screens share.
 * A right guess pays 2 x the stake, a wrong one nothing, a tie (close equal to open) gives the stake back; the platform fee (3 % of the stake,
 * rounded UP to 0.001 PLAY) and the network fee (0.1 PLAY) are charged on top of the stake and kept on every outcome. The server computes them;
 * the quote here is the same arithmetic, shown before the player confirms.
 */
import type { BetMode, EconomyRulesDto, MarketKind } from "../api/contract";

/** What a new player sees first: the fixed mode (rules point 9). */
export const DEFAULT_BET_MODE: BetMode = "FIXED";

/** The platform fee in percent when the server's rules do not name it (a mock or a server that predates the mode). */
export const DEFAULT_FIXED_FEE_PERCENT = "3";
/** The flat network fee in PLAY when the server's rules do not name it. */
export const DEFAULT_NETWORK_FEE_PLAY = "0.1";

const STORAGE_KEY = "herd.betMode";

/** A bet's mode as the API carries it: absent or null is the percentage mode. */
export function isFixedMode(bet: { mode?: BetMode | null } | null | undefined): boolean {
  return bet?.mode === "FIXED";
}

/** The fixed mode is for every market but the odds markets (rules point 4). */
export function fixedModeAvailable(kind: MarketKind | undefined): boolean {
  return kind !== "ODDS";
}

/** The mode the sheet uses: the player's choice, forced to the percentage mode where the fixed one is not offered. */
export function effectiveBetMode(chosen: BetMode, kind: MarketKind | undefined): BetMode {
  return fixedModeAvailable(kind) ? chosen : "PERCENTAGE";
}

/** The mode the player chose last time; the default for a player who never chose (storage may be missing or blocked). */
export function loadBetMode(): BetMode {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored === "FIXED" || stored === "PERCENTAGE" ? stored : DEFAULT_BET_MODE;
  } catch {
    return DEFAULT_BET_MODE;
  }
}

export function saveBetMode(mode: BetMode): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // a private window: the choice is kept for this visit only
  }
}

export interface FixedQuote {
  /** What a right guess adds to the balance beyond the stake: +S. */
  win: number;
  /** What a wrong guess takes: -S. */
  lose: number;
  /** The platform fee, the network fee and both together, PLAY. Charged on top of the stake, kept whatever happens. */
  platformFee: number;
  networkFee: number;
  fee: number;
  /** Stake plus both fees: what leaves the balance. */
  totalDebit: number;
}

/**
 * The quote for a stake: the platform fee is `percent` of it rounded UP to 0.001 PLAY, worked out in whole thousandths so that no float error
 * can move a fee by a unit ("33.333" at 3 % is 0.99999 -> 1.000). `null` for a stake that is not a positive number.
 */
export function fixedQuote(stake: number, economy: Pick<EconomyRulesDto, "networkFeePlay" | "fixedPlatformFeePercent"> | null | undefined): FixedQuote | null {
  if (!Number.isFinite(stake) || stake <= 0) return null;
  const stakeMojos = Math.round(stake * 1000);
  const percentMilli = Math.round(Number(economy?.fixedPlatformFeePercent ?? DEFAULT_FIXED_FEE_PERCENT) * 1000);
  const networkMojos = Math.round(Number(economy?.networkFeePlay ?? DEFAULT_NETWORK_FEE_PLAY) * 1000);
  // percent / 100 of the stake = stakeMojos * percentMilli / 100 000, rounded up
  const platformMojos = Math.floor((stakeMojos * percentMilli + 99_999) / 100_000);
  return {
    win: stakeMojos / 1000,
    lose: -stakeMojos / 1000,
    platformFee: platformMojos / 1000,
    networkFee: networkMojos / 1000,
    fee: (platformMojos + networkMojos) / 1000,
    totalDebit: (stakeMojos + platformMojos + networkMojos) / 1000,
  };
}

export type FixedOutcome = "win" | "lose" | "tie";

/** The result of a fixed bet from its net (`payout - stake`): +S a win, -S a loss, 0 a tie. */
export function fixedOutcome(net: number): FixedOutcome {
  return net > 0 ? "win" : net < 0 ? "lose" : "tie";
}
