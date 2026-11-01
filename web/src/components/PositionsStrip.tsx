import { useState } from "react";
import { Link } from "react-router-dom";
import { useSession } from "../api/ApiContext";
import type { MarketDto, MyPositionDto } from "../api/contract";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { useMarkets } from "../api/useMarkets";
import { useMyPositions } from "../api/useMyPositions";
import { formatCountdown, formatDuration, formatPlayAmount, rollingPlayFormat } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
import { ChevronIcon, CrackIcon, DirectionGlyph } from "./icons";
import { liquidationLiveCardClass } from "./heat";
import { LiveLiqBar } from "./LiquidationBar";
import { isWaitingForOpen, liveReadOfPosition, type LiveRead } from "./livePosition";
import { useT, type TFn } from "../i18n";
import { positionRoomPath, readStripCollapsed, writeStripCollapsed } from "./positionsStripModel";
import { useServerNow } from "./useServerNow";
import { isFixedMode } from "./betModeModel";
import "./PositionsStrip.css";

/** "3 MIN" — the position's own round length (`MyPositionDto.durationSec`), so the label survives the market
 *  leaving `markets()`; nothing at all if a server sent none, never "NaN SEC". */
function lengthLabel(position: MyPositionDto, t: TFn): string | null {
  return Number.isFinite(position.durationSec) && position.durationSec > 0 ? formatDuration(position.durationSec, t) : null;
}

export function PositionCard({ position, market, nowMs }: { position: MyPositionDto; market: MarketDto | undefined; nowMs: number }) {
  const t = useT();
  const read: LiveRead | null = liveReadOfPosition(position, market?.price?.price, market);
  const length = lengthLabel(position, t);
  const waitingOpen = isWaitingForOpen(position);
  const betting = position.phase === "BETTING";
  const timeLeft = formatCountdown(position.untilMs - nowMs);
  const liquidated = position.phase === "RUNNING" && position.liquidated;
  const pulse = read ? liquidationLiveCardClass(read.roomFraction) : "";
  const sideClass = position.direction.toLowerCase();
  const fixed = isFixedMode(position);
  const label =
    (fixed
      ? t("mode.ariaBase", { symbol: position.symbol, direction: position.direction, stake: formatPlayAmount(position.stake) })
      : t("pos.ariaBase", { symbol: position.symbol, direction: position.direction, leverage: position.leverage, stake: formatPlayAmount(position.stake) })) +
    (liquidated
      ? `, ${t("pos.ariaLiquidated")}`
      : betting
        ? `, ${t("pos.ariaStarts", { time: timeLeft })}`
        : waitingOpen
          ? `, ${t("pos.ariaWaiting")}`
          : read
            ? `, ${t("pos.ariaLive", { pnl: formatPlayAmount(read.pnl, { sign: true }), pct: `${(read.pctToLiquidation * 100).toFixed(1)}%` })}`
            : "");
  return (
    <Link
      to={positionRoomPath(position)}
      className={`pos-card pos-card--${sideClass} ${liquidated ? "pos-card--liquidated" : ""} ${pulse}`}
      aria-label={label}
    >
      <span className="pos-card__top">
        <span className={`pos-card__side pos-card__side--${sideClass}`}>
          <DirectionGlyph direction={position.direction} /> {position.symbol}
        </span>
        <span className="pos-card__lev mono">{fixed ? t("mode.short") : `x${position.leverage}`}</span>
        <span className="pos-card__time mono">{betting ? t("pos.starts", { time: timeLeft }) : t("pos.ends", { time: timeLeft })}</span>
      </span>

      {liquidated ? (
        <span className="pos-card__pnl pos-card__pnl--liq">
          <CrackIcon className="pos-card__crack" />
          <span className="mono">{formatPlayAmount(-Number(position.stake), { sign: true })} PLAY</span>
          <span className="pos-card__note">{t("pos.liquidated")}</span>
        </span>
      ) : betting ? (
        <span className="pos-card__pnl pos-card__pnl--wait">
          <span className="pos-card__note">{t("pos.betPlaced")}</span>
        </span>
      ) : waitingOpen ? (
        // The round has no opening price yet: no entry, no P&L, no meter — never a zero.
        <span className="pos-card__pnl pos-card__pnl--wait">
          <span className="pos-card__note pos-card__note--waiting">
            <span className="pos-card__waiting-dot" aria-hidden="true" />
            {t("waiting.title")}
          </span>
        </span>
      ) : fixed ? (
        // D-106, "Win or lose": no live P&L and no wipeout meter - what a win and a loss are worth, and where the price stands
        <span className="pos-card__pnl pos-card__pnl--wait">
          <span className="pos-card__note mono">{t("mode.ownRunning", { win: formatPlayAmount(position.stake), lose: formatPlayAmount(position.stake) })}</span>
        </span>
      ) : read ? (
        <>
          <span className={`pos-card__pnl mono ${outcomeClassName(outcomeFromNet(read.pnl))}`}>
            <RollingDigits value={read.pnl} durationMs={400} format={rollingPlayFormat(read.pnl, { sign: true, unit: true })} />
          </span>
          <LiveLiqBar roomFraction={read.roomFraction} pctToLiquidation={read.pctToLiquidation} thin />
        </>
      ) : (
        <span className="pos-card__pnl pos-card__pnl--wait">
          <span className="pos-card__note">{t("pos.waitingPrice")}</span>
        </span>
      )}

      <span className="pos-card__foot mono">
        {t("pos.foot", { stake: formatPlayAmount(position.stake) })}
        {length ? ` · ${length}` : ""}
      </span>
    </Link>
  );
}

/**
 * "Your bets" — the signed-in player's every pending bet and open position across ALL markets in one strip
 * (D-71: Polymarket keeps them inside a section, Herd keeps them in view on every screen): symbol,
 * direction, leverage, stake, time left and — for a running one — the live P&L and a thin distance-to-
 * liquidation meter. A tap opens that market's room on that series. Collapsible to one line, remembered.
 *
 * It sits at the top of the page content (in `Layout`), NOT fixed to the screen: it scrolls away with the
 * page, so it can never sit on top of a bet slip's primary button on a phone. Renders nothing when signed
 * out or when nothing is open. A liquidated position is shown as plainly as a winning one (the whole stake,
 * with a crack and the word) until its round settles and it leaves the list.
 */
export function PositionsStrip() {
  const { player } = useSession();
  const { positions } = useMyPositions();
  // The cheap part first: signed out, or nothing open, and the strip costs nothing — in particular it does
  // not start the markets poll (`StripBody` below does) on every page of the app for every visitor.
  if (!player || positions.length === 0) return null;
  return <StripBody positions={positions} />;
}

function StripBody({ positions }: { positions: readonly MyPositionDto[] }) {
  const t = useT();
  const { byId } = useMarkets();
  const nowMs = useServerNow(1000);
  const [collapsed, setCollapsed] = useState(readStripCollapsed);

  // The sum of the running positions' live P&L (only those that can be read), for the one-line summary.
  const reads = positions.map((p) => liveReadOfPosition(p, byId.get(p.marketId)?.price?.price, byId.get(p.marketId)));
  const liveReads = reads.filter((r): r is LiveRead => r !== null);
  const liveTotal = liveReads.reduce((sum, r) => sum + r.pnl, 0);
  const liquidatedCount = positions.filter((p) => p.phase === "RUNNING" && p.liquidated).length;
  const total = positions.length;

  const toggle = () => {
    const next = !collapsed;
    setCollapsed(next);
    writeStripCollapsed(next);
  };

  return (
    <section className="pos-strip" aria-label={t("pos.stripAria")}>
      <button type="button" className="pos-strip__head" aria-expanded={!collapsed} aria-controls="pos-strip-list" onClick={toggle}>
        <span className="pos-strip__title">
          {t("pos.title")} <span className="pos-strip__count mono">{total}</span>
        </span>
        <span className="pos-strip__summary">
          {liveReads.length > 0 && (
            <span className={`mono ${outcomeClassName(outcomeFromNet(liveTotal))}`}>
              {t("pos.summaryLive", { amount: formatPlayAmount(liveTotal, { sign: true }) })}
            </span>
          )}
          {liquidatedCount > 0 && <span className="pos-strip__liq">{t("pos.liqCount", { count: liquidatedCount })}</span>}
        </span>
        <ChevronIcon open={!collapsed} className="pos-strip__chevron" />
      </button>
      {!collapsed && (
        <ul id="pos-strip-list" className="pos-strip__list">
          {positions.map((p) => (
            <li key={p.roundId}>
              <PositionCard position={p} market={byId.get(p.marketId)} nowMs={nowMs} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
