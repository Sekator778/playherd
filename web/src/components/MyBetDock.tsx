import { useState } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import type { MarketDto, MyPositionDto } from "../api/contract";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { useMarkets } from "../api/useMarkets";
import { useMyPositions } from "../api/useMyPositions";
import { formatCountdown, formatPercent, formatPlayAmount, formatPrice, rollingPlayFormat, type PriceFormatMarket } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
import { useT } from "../i18n";
import { liquidationLiveCardClass } from "./heat";
import { ChevronIcon, CrackIcon, DirectionGlyph } from "./icons";
import { LiveLiqBar } from "./LiquidationBar";
import { myBetState, newestPosition, otherPositions } from "./myBetModel";
import { useMyBetDockVisible } from "./useMyBetDockVisible";
import { positionRoomPath } from "./positionsStripModel";
import { useServerNow } from "./useServerNow";
import "./MyBetDock.css";

/** Plain USD with cents — what a price is printed with before the market list has arrived. */
const FALLBACK: PriceFormatMarket = { kind: "PERP", priceDecimals: 2 };

/**
 * "My bet" — the player's newest open bet, pinned above the tab bar on every page (Р-98; first tester P9: "I see the crowd's data, but not
 * my own position"). It answers P9's three questions without a scroll: the price now, which side and how much, and how far the wipeout line
 * (the liquidation price) is — in words and in a bar. While the round has not started it says that the bet is placed, when it starts, and that
 * it can be cancelled for free until then (true: both fees come back). A tap opens it: the entry price, what the wipeout line means, the way to
 * the round and to the other open bets. Signed out, or with nothing open, it renders nothing. Not shown in the Pro view, which keeps the
 * older "Your bets" strip at the top of the page.
 */
export function MyBetDock() {
  const { positions } = useMyPositions();
  const visible = useMyBetDockVisible();
  // The cheap part first: signed out, nothing open or Pro, and the dock costs nothing — it does not start the markets poll (`DockBody` does).
  if (!visible) return null;
  return <DockBody positions={positions} />;
}

function DockBody({ positions }: { positions: readonly MyPositionDto[] }) {
  const t = useT();
  const api = useApi();
  const { byId } = useMarkets();
  const { refresh } = useMyPositions();
  const nowMs = useServerNow(1000);
  const [open, setOpen] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const position = newestPosition(positions);
  if (!position) return null;
  const others = otherPositions(positions, position);
  const market: MarketDto | undefined = byId.get(position.marketId);
  const fmt: PriceFormatMarket = market ?? FALLBACK;
  const priceNow = market?.price?.price ?? null;
  const state = myBetState(position, priceNow, market);
  const side = position.direction.toLowerCase();
  const timeLeft = formatCountdown(position.untilMs - nowMs);
  const stakeText = formatPlayAmount(position.stake);
  const pulse = state.kind === "live" ? liquidationLiveCardClass(state.read.roomFraction) : "";
  const wipeoutPrice = position.liquidationPrice !== null ? formatPrice(position.liquidationPrice, fmt) : null;

  const cancel = async () => {
    setCancelling(true);
    setCancelError(null);
    try {
      await api.cancelBet(position.roundId);
      setConfirmCancel(false);
      setOpen(false);
      refresh();
    } catch (err) {
      setCancelError(mutationErrorText(err, t("dock.cancelFail"), t));
    } finally {
      setCancelling(false);
    }
  };

  const moodWord =
    state.kind === "wiped"
      ? t("dock.wipedOut")
      : state.kind === "live"
        ? t(state.mood === "winning" ? "dock.winning" : state.mood === "losing" ? "dock.losing" : "dock.even")
        : null;
  const moodClass =
    state.kind === "wiped" ? "outcome-lost" : state.kind === "live" ? outcomeClassName(outcomeFromNet(state.read.pnl)) : "";

  return (
    <section
      className={`my-bet-dock my-bet-dock--${side} ${state.kind === "wiped" ? "my-bet-dock--wiped" : ""} ${pulse}`}
      aria-label={t("dock.aria")}
      data-state={state.kind}
    >
      <button
        type="button"
        className="my-bet-dock__main"
        aria-expanded={open}
        title={t(open ? "dock.collapse" : "dock.expand")}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="my-bet-dock__row">
          <span className={`my-bet-dock__side my-bet-dock__side--${side}`}>
            <DirectionGlyph direction={position.direction} /> {position.direction}
          </span>
          <span className="my-bet-dock__what mono">
            {position.symbol} · {stakeText} PLAY · {t("dock.boost", { leverage: position.leverage })}
          </span>
          {moodWord && state.kind !== "live" && (
            <span className={`my-bet-dock__mood ${moodClass}`}>
              {state.kind === "wiped" && <CrackIcon className="my-bet-dock__crack" />}
              {moodWord}
            </span>
          )}
          {others.length > 0 && (
            <span className="my-bet-dock__more mono" aria-label={t("dock.moreAria", { count: others.length })}>
              {t("dock.more", { count: others.length })}
            </span>
          )}
          <ChevronIcon open={open} className="my-bet-dock__chevron" />
        </span>

        {state.kind === "live" && (
          <>
            <span className="my-bet-dock__row my-bet-dock__row--figures">
              <span className="my-bet-dock__now mono">
                {t("dock.priceNow", { symbol: position.symbol })} {priceNow ? formatPrice(priceNow, fmt) : "—"}
              </span>
              <span className={`my-bet-dock__pnl mono ${moodClass}`}>
                <span className="my-bet-dock__mood-word">{moodWord}</span>{" "}
                <RollingDigits value={state.read.pnl} durationMs={400} format={rollingPlayFormat(state.read.pnl, { sign: true, unit: true })} />
              </span>
            </span>
            <LiveLiqBar roomFraction={state.read.roomFraction} pctToLiquidation={state.read.pctToLiquidation} thin />
            <span className="my-bet-dock__row my-bet-dock__row--line mono">
              <span>{wipeoutPrice ? t("dock.wipeoutLine", { price: wipeoutPrice }) : ""}</span>
              <span className={state.near ? "my-bet-dock__near" : ""}>
                {state.near ? `${t("dock.near")} · ` : ""}
                {t("dock.away", { pct: formatPercent(state.read.pctToLiquidation) })}
              </span>
            </span>
          </>
        )}
        {state.kind === "waiting" && <span className="my-bet-dock__note">{t("dock.placedStarts", { time: timeLeft })}</span>}
        {state.kind === "waitingOpen" && <span className="my-bet-dock__note">{t("dock.waitingOpen")}</span>}
        {state.kind === "noPrice" && <span className="my-bet-dock__note">{t("dock.waitingPrice")}</span>}
        {state.kind === "wiped" && (
          <span className="my-bet-dock__pnl mono outcome-lost">{formatPlayAmount(-Number(position.stake), { sign: true })} PLAY</span>
        )}
      </button>

      {open && (
        <div className="my-bet-dock__details">
          {position.entryPrice !== null && <p className="mono">{t("dock.entered", { price: formatPrice(position.entryPrice, fmt) })}</p>}
          {state.kind === "wiped" ? (
            <p>{t("dock.lost", { stake: stakeText })}</p>
          ) : (
            wipeoutPrice !== null && <p>{t("dock.consequence", { symbol: position.symbol, stake: stakeText })}</p>
          )}

          {position.phase === "BETTING" &&
            (confirmCancel ? (
              <div className="my-bet-dock__confirm">
                <p>{t("dock.cancelConfirm", { amount: stakeText })}</p>
                <div className="my-bet-dock__buttons">
                  <button type="button" className="btn btn--secondary" onClick={() => setConfirmCancel(false)} disabled={cancelling}>
                    {t("dock.keep")}
                  </button>
                  <button type="button" className="btn btn--danger" onClick={() => void cancel()} disabled={cancelling}>
                    {cancelling ? t("dock.cancelling") : t("dock.cancelYes")}
                  </button>
                </div>
                {cancelError && <p className="field-error">{cancelError}</p>}
              </div>
            ) : (
              <button type="button" className="btn btn--secondary btn--block" onClick={() => setConfirmCancel(true)}>
                {t("dock.cancel")}
              </button>
            ))}

          <Link to={positionRoomPath(position)} className="btn btn--secondary btn--block">
            {t("dock.openRound")}
          </Link>

          {others.length > 0 && (
            <div className="my-bet-dock__others">
              <p className="section-title">{t("dock.others")}</p>
              <ul>
                {others.map((o) => (
                  <li key={o.roundId}>
                    <Link to={positionRoomPath(o)} className="mono">
                      <DirectionGlyph direction={o.direction} /> {o.direction} · {o.symbol} · {formatPlayAmount(o.stake)} PLAY · {t("dock.boost", { leverage: o.leverage })}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
