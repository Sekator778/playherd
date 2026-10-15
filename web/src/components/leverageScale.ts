/**
 * The leverage slider's rules once the caps are in the hundreds and thousands (economy stage C, C2; Р-92).
 *
 * - **Default ceiling.** The slider goes to x100 (or the market's cap when that is lower). Above it a "High leverage" switch,
 *   confirmed by the player, unlocks the slider up to the highest leverage the stake allows.
 * - **Stake tiers.** A bigger stake allows less leverage (`maxLeverageForStake`, from the liquidation preview): the dial's
 *   ceiling never exceeds it.
 * - **Scale.** Up to a ceiling of x100 the slider is linear, one step per whole x (as it always was). Above it the track is
 *   LOGARITHMIC over {@link SLIDER_STEPS} positions — equal distances on the track are equal multiples of leverage, so x2 – x100
 *   and x100 – x1200 both stay usable with a thumb — and the value it gives is rounded to a round number: whole x up to x100,
 *   steps of 5 up to x500, of 10 up to x1000, of 50 above. The cap itself is always reachable (the last position).
 *
 * Pure functions, so each rule has a test.
 */
import { LEVERAGE_MIN } from "../api/contract";

/** The most leverage the slider offers before the player turns "High leverage" on. */
export const DEFAULT_LEVERAGE_LIMIT = 100;

/** Positions of the logarithmic track. */
export const SLIDER_STEPS = 1000;

/** Whether a slider that ends at `max` is drawn on the logarithmic track. */
export function usesLogScale(max: number): boolean {
  return max > DEFAULT_LEVERAGE_LIMIT;
}

/** The slider's ceiling before the stake's tier is applied: the cap, or x100 while "High leverage" is off. */
export function leverageLimit(cap: number, high: boolean): number {
  return high ? cap : Math.min(cap, DEFAULT_LEVERAGE_LIMIT);
}

/** The highest leverage the dial offers: the limit, lowered to what the stake allows (`null`: not known yet). Never below the minimum. */
export function dialCeiling(cap: number, high: boolean, stakeMax: number | null): number {
  const limited = Math.min(leverageLimit(cap, high), stakeMax ?? Infinity);
  return Math.max(LEVERAGE_MIN, limited);
}

/** Whether "High leverage" has anything to unlock: the cap, as far as the stake allows, is above x100. */
export function highLeverageAvailable(cap: number, stakeMax: number | null): boolean {
  return Math.min(cap, stakeMax ?? Infinity) > DEFAULT_LEVERAGE_LIMIT;
}

/** The leverage a slip uses: the player's choice (`null`: untouched, so `fallback`), within the dial's ceiling. */
export function effectiveLeverage(choice: number | null, fallback: number, cap: number, high: boolean, stakeMax: number | null): number {
  return Math.min(choice ?? fallback, dialCeiling(cap, high, stakeMax));
}

/** A leverage rounded to the grid the logarithmic track hands out (see the file comment). */
export function snapLeverage(value: number): number {
  const step = value <= DEFAULT_LEVERAGE_LIMIT ? 1 : value <= 500 ? 5 : value <= 1000 ? 10 : 50;
  return Math.round(value / step) * step;
}

/** The distance between two neighbouring values of the grid at `value`, going up or down. */
function gridStep(value: number, up: boolean): number {
  const probe = up ? value : value - 1;
  return probe < DEFAULT_LEVERAGE_LIMIT ? 1 : probe < 500 ? 5 : probe < 1000 ? 10 : 50;
}

/** Where on the track (0..SLIDER_STEPS) a leverage sits. */
export function leverageToPosition(value: number, min: number, max: number): number {
  if (max <= min) return 0;
  const fraction = Math.log(Math.max(min, Math.min(max, value)) / min) / Math.log(max / min);
  return Math.round(fraction * SLIDER_STEPS);
}

/** The leverage at a track position: 0 is the minimum, the last position is exactly `max`, the rest are on the grid. */
export function positionToLeverage(position: number, min: number, max: number): number {
  if (position <= 0) return min;
  if (position >= SLIDER_STEPS) return max;
  const raw = min * Math.pow(max / min, position / SLIDER_STEPS);
  return Math.max(min, Math.min(max, snapLeverage(raw)));
}

/**
 * The leverage after the thumb moved to `position`, given the one it was on. A keyboard step moves one position, which on
 * the coarse grid can round back to where it was — the thumb would stick — so a move that rounds to nothing goes one grid
 * step the way it moved instead.
 */
export function leverageAfterMove(position: number, current: number, min: number, max: number): number {
  const next = positionToLeverage(position, min, max);
  if (next !== current) return next;
  const currentPosition = leverageToPosition(current, min, max);
  if (position > currentPosition) return Math.min(max, current + gridStep(current, true));
  if (position < currentPosition) return Math.max(min, current - gridStep(current, false));
  return current;
}
