import type { CSSProperties } from "react";
import { Link } from "react-router-dom";
import type { EpochMs, MarketDto } from "../api/contract";
import { symbolMeta } from "../api/marketMeta";
import { formatDuration, formatPercent, formatPrice, market24hMove } from "../format";
import { Countdown, Skeleton } from "./Common";
import { FlameIcon, LockIcon, StarIcon } from "./icons";
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";
import { MarketIcon } from "./MarketIcon";
import { openBetSheet } from "./betSheetState";
import { useProMode } from "./proMode";
import { formatAgo, formatStartsIn, formatTimeLeft, trend, TREND_GLYPH } from "./marketFormat";
import { backupPriceNote, backupPriceOf, crowdSplitOf, herdSideOf, isOpenMarket, kickoffOf, leadSeries, oddsMarketPhrase, subtitleParts } from "./marketModel";
import "./MarketCard.css";

/** The live crowd split (Р-62) as a slim UP/DOWN bar with its percentages and the player count — the
 *  lobby card's answer to "where is the herd going?" without opening the room. Stake-weighted, like the
 *  arena; an empty round says so instead of showing an invented 50/50. */
export function CrowdSplitBar({
  upStake,
  downStake,
  symbol,
  players,
  invite = true,
}: {
  upStake: string;
  downStake: string;
  symbol: string;
  /** Players in the round (`MarketSeriesDto.participantCount`), printed between the two percentages. */
  players?: number;
  /** An empty round invites the first bet — unless the market cannot take one right now (no price yet). */
  invite?: boolean;
}) {
  const t = useT();
  const split = crowdSplitOf({ upStake, downStake });
  if (!split) {
    return (
      <div className="mk-split mk-split--empty" role="img" aria-label={t("card.noBetsAria", { symbol })}>
        <div className="mk-split__bar" />
        <p className="mk-split__empty">{invite ? t("card.noBetsInvite") : t("card.noBets")}</p>
      </div>
    );
  }
  return (
    <div
      className="mk-split"
      role="img"
      aria-label={t("card.splitAria", { symbol, up: split.upPct, down: split.downPct }) + (players !== undefined ? t("card.splitPlayers", { count: players }) : "")}
    >
      <div className="mk-split__labels mono" aria-hidden="true">
        <span className="mk-split__label mk-split__label--up">▲ UP {split.upPct}%</span>
        {players !== undefined && (
          <span className="mk-split__players">
            <span className="mk-live-dot" /> {t("card.playersLabel", { count: players })}
          </span>
        )}
        <span className="mk-split__label mk-split__label--down">{split.downPct}% DOWN ▼</span>
      </div>
      <div className="mk-split__bar" aria-hidden="true">
        <span className="mk-split__fill mk-split__fill--up" style={{ flexBasis: `${split.upPct}%` }} />
        <span className="mk-split__fill mk-split__fill--down" style={{ flexBasis: `${split.downPct}%` }} />
      </div>
    </div>
  );
}

/** The herd badge — the market's own crowd account (Р-5, Т-6) in one chip, signed and arrowed so the
 *  colour is never the only signal. A market where the crowd has not traded yet says so instead of a "0.0%". */
export function HerdBadge({ market }: { market: MarketDto }) {
  const t = useT();
  const { roundsTraded, returnPct } = market.crowd;
  const to = `/stats?m=${encodeURIComponent(market.id)}`;
  if (roundsTraded === 0) {
    return (
      <Link to={to} className="mk-herd mk-herd--none" title={t("card.herdNoneTitle")}>
        {t("card.herdNone")}
      </Link>
    );
  }
  const tone = trend(returnPct);
  const text = formatPercent(returnPct, { sign: true });
  return (
    <Link
      to={to}
      className={`mk-herd mk-herd--${tone}`}
      title={t("card.herdTitle")}
      aria-label={t("card.herdAria", { symbol: market.symbol, text, count: roundsTraded })}
    >
      <span aria-hidden="true">{TREND_GLYPH[tone]}</span> {t("card.herd")} <span className="mono">{text}</span>
    </Link>
  );
}

/** The line under an odds card's ticker: what the percentage is the probability OF ("Spain to win"), then the context
 *  ("· UEFA Nations League"). On a narrow card the context gives way first and the outcome wraps rather than being cut
 *  (first tester pass, D7: "Jasmine Paolini to …" left the percentage with no owner). */
function OddsSubline({ market }: { market: MarketDto }) {
  const t = useT();
  const parts = subtitleParts(market, t);
  // No subtitle: nothing to add — the question below is the title, and says it once.
  if (!parts) return null;
  return (
    <p className="mk-card__title mk-card__title--odds">
      <span className="mk-card__outcome">{parts.outcome}</span>
      {parts.context && (
        <>
          {" "}
          <span className="mk-card__context">· {parts.context}</span>
        </>
      )}
    </p>
  );
}

interface MarketCardProps {
  market: MarketDto;
  /** The server's "now" (`useServerNow`) — every countdown on the card reads against it. */
  nowMs: EpochMs;
  favorite: boolean;
  onToggleFavorite: (marketId: string) => void;
  /** Wears the HOT tag (the lobby's Hot sort puts it on the biggest pools). */
  hot?: boolean;
}

/**
 * A lobby market card (Р-69) — what the Polymarket-style list needs and then some (Р-71): icon, ticker,
 * title (for `ODDS` the question and the outcome), the live price (`ODDS` as a percentage) with its 24 h
 * change, the live UP/DOWN split with the player count, the countdown to the close of betting of the
 * shortest series, the herd badge and a favourite star, plus two big buttons that open the room with a
 * side preselected. The whole card is one link to the room (the title's stretched link); the buttons,
 * star and badge sit above it.
 *
 * An `ODDS` market's 24 h change is in percentage points (`+1.0 pt`), a perp's a percentage. A sports game says
 * "Starts in 2h 05m" before kick-off and wears a LIVE mark after it (`MarketDto.eventStartsAtMs`).
 *
 * Designed states: stale price (kept, dimmed, with its age), no price yet (buttons off — such a round
 * could not settle), closed market (no buttons; the server's own reason when it gave one — `closeReason`,
 * `closedAtMs` — else "Closed — open bets refunded"), and a price that is the BACKUP exchange's (Р-76: the market's
 * main exchange has no fresh price): "Kraken price" under the number, with the reason on hover and for a screen reader.
 */
export function MarketCard({ market, nowMs, favorite, onToggleFavorite, hot = false }: MarketCardProps) {
  const t = useT();
  // The default view opens the bet sheet over the lobby (Р-98); the Pro view keeps the links into the room and its full slip.
  const pro = useProMode();
  const meta = symbolMeta(market.symbol);
  const series = leadSeries(market);
  const open = isOpenMarket(market);
  const price = market.price;
  const stale = open && price?.stale === true;
  const backupPrice = open ? backupPriceOf(market, t) : null;
  const move = market24hMove(market, price, t);
  const changeTrend = trend(move.value);
  const kickoff = kickoffOf(market, nowMs);
  const herdSide = series ? herdSideOf(series) : null;
  const room = `/play/${encodeURIComponent(market.id)}`;
  const canBet = open && price !== null;
  const odds = market.kind === "ODDS";
  const endsInMs = market.endsAtMs !== null ? market.endsAtMs - nowMs : null;
  const titleId = `mk-${market.id}-title`;
  const star = (
    <button
      type="button"
      className="mk-star"
      aria-pressed={favorite}
      aria-label={favorite ? t("card.favRemove", { symbol: market.symbol }) : t("card.favAdd", { symbol: market.symbol })}
      onClick={() => onToggleFavorite(market.id)}
    >
      <StarIcon filled={favorite} />
    </button>
  );
  return (
    <article
      className={`mk-card ${open ? "" : "mk-card--closed"} ${stale ? "mk-card--stale" : ""} ${hot ? "mk-card--hot" : ""}`}
      style={{ "--mk": meta.color } as CSSProperties}
      aria-labelledby={titleId}
      data-market-id={market.id}
    >
      <div className="mk-card__head">
        <MarketIcon market={market} />
        <div className="mk-card__id">
          <div className="mk-card__namerow">
            <h3 className="mk-card__symbol" id={titleId}>
              <Link to={room} className="mk-card__link" aria-label={t(open ? "card.linkAria" : "card.linkAriaClosed", { symbol: market.symbol, title: market.title })}>
                {market.symbol}
              </Link>
            </h3>
            {star}
            {odds && (
              <span className="mk-tag mk-tag--odds" title={t("card.oddsTitle", { what: oddsMarketPhrase(market.source, t) })}>
                {t("parts.oddsTag")}
              </span>
            )}
            {kickoff?.state === "live" && (
              <span className="mk-tag mk-tag--live" title={t("card.liveTitle")}>
                <span className="mk-live-dot" aria-hidden="true" /> {t("card.liveTag")}
              </span>
            )}
            {hot && (
              <span className="mk-tag mk-tag--hot">
                <FlameIcon className="mk-tag__icon" /> {t("card.hot")}
              </span>
            )}
          </div>
          {!odds && <p className="mk-card__title">{market.title}</p>}
        </div>
        <div className="mk-card__price">
          {price ? (
            <>
              <span className="mk-card__price-num mono">{formatPrice(price.price, market)}</span>
              <span className={`mk-card__change mono mk-card__change--${changeTrend}`}>
                <span aria-hidden="true">{TREND_GLYPH[changeTrend]}</span>{" "}
                <span className="visually-hidden">{move.unit === "pt" ? t("card.changeHiddenPts") : t("card.changeHidden")}</span>
                {move.text}
                <span className="mk-card__change-unit"> {t("card.h24")}</span>
              </span>
              {stale && (
                <span className="mk-card__stale" role="status">
                  {t("card.stale", { ago: formatAgo(nowMs - price.asOfMs, t) })}
                </span>
              )}
              {backupPrice && (
                <span className="mk-card__venue" title={backupPriceNote(backupPrice, t)}>
                  {t("card.venue", { name: backupPrice.name })}
                  <span className="visually-hidden"> — {backupPriceNote(backupPrice, t)}</span>
                </span>
              )}
              {!open && <span className="mk-card__stale">{t("card.lastPrice")}</span>}
            </>
          ) : (
            <>
              <span className="mk-card__price-num mk-card__price-num--none mono" aria-label={t("card.noPriceAria")}>
                —
              </span>
              <span className="mk-card__stale">{t("card.waitingPrice")}</span>
            </>
          )}
        </div>
      </div>

      {odds && (
        <div className="mk-card__about">
          <OddsSubline market={market} />
          <p className="mk-card__question">{market.title}</p>
        </div>
      )}

      {open && series ? (
        <>
          <CrowdSplitBar upStake={series.upStake} downStake={series.downStake} symbol={market.symbol} players={series.participantCount} invite={canBet} />
          <div className="mk-card__meta">
            <span className="mk-card__meta-item">
              <span className="mono">{formatDuration(series.durationSec, t)}</span>
              <span aria-hidden="true"> · </span>
              <Countdown nowMs={nowMs} targetMs={series.bettingClosesAtMs} template="card.closesIn" calm />
            </span>
            {kickoff?.state === "upcoming" && (
              <span className="mk-card__meta-item mk-card__meta-item--faint" title={t("card.startsInTitle")}>
                {t("card.startsIn", { time: formatStartsIn(kickoff.inMs, t) })}
              </span>
            )}
            {odds && endsInMs !== null && endsInMs > 0 && (
              <span className="mk-card__meta-item mk-card__meta-item--faint" title={t("card.eventEndsTitle")}>
                {t("card.eventEnds", { time: formatTimeLeft(endsInMs, t) })}
              </span>
            )}
            <HerdBadge market={market} />
          </div>
          <div className="mk-card__actions">
            {canBet ? (
              <>
                {(["UP", "DOWN"] as const).map((side) => {
                  const lower = side.toLowerCase();
                  const className = `mk-dir mk-dir--${lower} ${herdSide === side ? "mk-dir--herd" : ""}`;
                  const label = t(herdSide === side ? (side === "UP" ? "card.betUpHerd" : "card.betDownHerd") : side === "UP" ? "card.betUp" : "card.betDown", {
                    symbol: market.symbol,
                  });
                  const body = (
                    <>
                      <span aria-hidden="true">{side === "UP" ? "▲" : "▼"}</span> {side}
                      {herdSide === side && <span className="mk-dir__herd">{t("card.herdTag")}</span>}
                    </>
                  );
                  return pro || !series ? (
                    <Link key={side} to={`${room}?side=${lower}`} className={className} aria-label={label}>
                      {body}
                    </Link>
                  ) : (
                    <button
                      key={side}
                      type="button"
                      className={className}
                      aria-label={label}
                      aria-haspopup="dialog"
                      onClick={() => openBetSheet({ marketId: market.id, interval: series.interval, direction: side })}
                    >
                      {body}
                    </button>
                  );
                })}
              </>
            ) : (
              <>
                <button type="button" className="mk-dir mk-dir--up" disabled aria-label={t("card.betUpWait", { symbol: market.symbol })}>
                  <span aria-hidden="true">▲</span> UP
                </button>
                <button type="button" className="mk-dir mk-dir--down" disabled aria-label={t("card.betDownWait", { symbol: market.symbol })}>
                  <span aria-hidden="true">▼</span> DOWN
                </button>
              </>
            )}
          </div>
        </>
      ) : open ? (
        // An open market the server lists with no round series: nothing to bet on yet, and it is not closed.
        <>
          <p className="mk-card__none">{t("card.noRounds")}</p>
          <div className="mk-card__foot">
            <HerdBadge market={market} />
          </div>
        </>
      ) : (
        <>
          <div className="mk-closed" role="note">
            <LockIcon className="mk-closed__icon" />
            {market.closeReason ? (
              // The server said why, in plain words — shown as it is, never reworded.
              <div>
                <p>
                  <strong>{t("card.closed")}</strong>
                  {market.closedAtMs ? ` ${formatAgo(nowMs - market.closedAtMs, t)}` : ""} — {market.closeReason}
                </p>
                <p className="mk-closed__sub">{t("card.closedSub")}</p>
              </div>
            ) : (
              <p>
                <Rich text={t("card.closedNoReason")} />
              </p>
            )}
          </div>
          <div className="mk-card__foot">
            <HerdBadge market={market} />
          </div>
        </>
      )}
    </article>
  );
}

/** Card-shaped placeholder while the first `markets()` response is on its way. */
export function MarketCardSkeleton() {
  return (
    <div className="mk-card mk-card--skeleton" aria-hidden="true">
      <div className="mk-card__head">
        <Skeleton width={44} height={44} style={{ borderRadius: "50%" }} />
        <div className="mk-card__id" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <Skeleton width={90} height={18} />
          <Skeleton width={130} height={12} />
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 8, alignItems: "flex-end" }}>
          <Skeleton width={100} height={20} />
          <Skeleton width={64} height={12} />
        </div>
      </div>
      <Skeleton height={8} style={{ marginTop: 18 }} />
      <Skeleton width="60%" height={12} style={{ marginTop: 12 }} />
      <div className="mk-card__actions" style={{ marginTop: 14 }}>
        <Skeleton height={48} style={{ borderRadius: "var(--radius-md)" }} />
        <Skeleton height={48} style={{ borderRadius: "var(--radius-md)" }} />
      </div>
    </div>
  );
}
