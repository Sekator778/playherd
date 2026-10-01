import { FlameIcon } from "./icons";
import "./StreakBadge.css";

/**
 * A player's win streak (Р-71, `PublicProfileDto.streak`): the wins in a row over settled rounds, and the
 * best the player has had. A win is a round that paid out more than the stake; a flat round neither adds to
 * the streak nor ends it; any loss — a liquidation included — resets it to zero. Said plainly, without
 * urging: no countdown to "keep it alive", no "don't lose it" — a streak is a record, not a leash.
 *
 * Built for zero as well: no streak yet is a quiet, outlined flame with a line on how one starts, not an
 * empty space and not a shamed "0".
 */
export function StreakBadge({ current, best }: { current: number; best: number }) {
  const active = current > 0;
  return (
    <div
      className={`streak ${active ? "streak--on" : "streak--off"}`}
      role="group"
      aria-label={`Win streak: ${current} in a row now, best ${best}`}
    >
      <FlameIcon className="streak__flame" />
      <div className="streak__body">
        <p className="streak__main">
          {active ? (
            <>
              <span className="streak__num mono">{current}</span> {current === 1 ? "win in a row" : "wins in a row"}
            </>
          ) : (
            "No win streak right now"
          )}
        </p>
        <p className="streak__sub">
          Best: <span className="mono">{best}</span>
          {active ? " · a loss resets it, a flat round doesn't" : best > 0 ? " · the next win starts a new one" : " · a win starts one"}
        </p>
      </div>
    </div>
  );
}
