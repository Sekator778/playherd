import type { MyPositionDto } from "../api/contract";
import { formatPlayAmount } from "../format";
import { useT } from "../i18n";
import { DirectionGlyph } from "./icons";
import { isFixedMode } from "./betModeModel";
import "./WaitingForOpen.css";

/**
 * "Waiting for the opening price…" — the state of a RUNNING round that has no opening price yet
 * (`RoundRunning.openPrice === null`: a market listed moments ago, or a source outage across the round
 * boundary) and of a position in it (`MyPositionDto.entryPrice === null`). The server opens no positions and
 * the crowd has none until the price is known, so there is nothing true to print: no price, no P&L, no
 * liquidation price or meter, never a zero (`contract.ts`). Calm on purpose — nothing is wrong that the player
 * can fix, and nothing is promised beyond what the contract says: the numbers appear when the price does.
 */

/** A small pulsing dot (still under reduced motion: the word carries the state, the dot is decoration). */
function WaitingDot() {
  return <span className="waiting-open__dot" aria-hidden="true" />;
}

/** The round-level notice: what is missing and what comes back once it arrives. Replaces every figure of a
 *  running round — the crowd's position, the list of players, the open price line. */
export function WaitingForOpenCard({ symbol, length }: { symbol?: string; length?: string }) {
  const t = useT();
  return (
    <div className="card waiting-open" role="status">
      <div className="waiting-open__head">
        <WaitingDot />
        <h3>{t("waiting.title")}</h3>
      </div>
      {(symbol || length) && (
        <p className="faint waiting-open__ctx">
          {symbol}
          {symbol && length ? " · " : ""}
          {length}
        </p>
      )}
      <p className="muted waiting-open__text">
        {t("waiting.card")}
      </p>
    </div>
  );
}

/** The player's own bet in such a round (`myPositions()` knows it; the round's `positions` list is empty). It
 *  carries the explanation itself, so a page that shows it does not also show {@link WaitingForOpenCard}. */
export function OwnBetWaiting({
  position,
  hero = false,
}: {
  position: Pick<MyPositionDto, "direction" | "leverage" | "stake" | "symbol" | "mode">;
  hero?: boolean;
}) {
  const t = useT();
  return (
    <div className={`card ${hero ? "card--hero" : ""} own-outcome waiting-open waiting-open--own`} role="status">
      <p className="faint">
        {t("betSlip.yourBet")} <DirectionGlyph direction={position.direction} /> {position.direction} · {isFixedMode(position) ? t("mode.winOrLose") : `x${position.leverage}`} · {position.symbol} ·{" "}
        {formatPlayAmount(position.stake)} PLAY
      </p>
      <div className="waiting-open__head">
        <WaitingDot />
        <p className="waiting-open__headline">{t("waiting.title")}</p>
      </div>
      <p className="muted waiting-open__text">
        {t("waiting.own")}
      </p>
    </div>
  );
}
