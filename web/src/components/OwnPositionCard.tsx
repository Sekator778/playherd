import type { MarketDto, MarketReferenceDto, PublicPositionDto } from "../api/contract";
import { outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { formatPlayAmount, formatPrice, rollingPlayFormat, type PriceFormatMarket } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
import { useT } from "../i18n";
import { DirectionGlyph } from "./icons";
import { liquidationLiveCardClass } from "./heat";
import { LiveLiqBar } from "./LiquidationBar";
import { liveRead } from "./livePosition";

/**
 * The signed-in player's own RUNNING position, live (live-round.md §2): unrealized P&L rolling with every
 * price tick, the meter of how much room is left before liquidation (pulsing as it closes in), and the
 * prices that matter — entry and liquidation, in the market's own scale. A liquidated position is stated
 * as plainly as a win would be: the whole stake, gone (Р-7).
 */
export function OwnPositionCard({
  position,
  referencePrice,
  market,
  format,
  hero = false,
  closePrice = null,
}: {
  position: PublicPositionDto;
  referencePrice: MarketReferenceDto | null;
  market: MarketDto | undefined;
  /** How to print the prices when `market` is not known (a round page: the round's own `kind`/`priceDecimals`). */
  format?: PriceFormatMarket;
  /** `card--hero`: the round page's own-position card is the top of that screen. */
  hero?: boolean;
  /** The price the round CLOSED at, once it has: a liquidated bet is explained against it ("this round closed at …"). */
  closePrice?: string | null;
}) {
  const t = useT();
  const heroClass = hero ? "card--hero" : "";
  const priceText = (price: string) => formatPrice(price, market ?? format ?? FALLBACK);
  if (position.liquidated) {
    // The page used to say only "liquidated, you lost your whole stake" under a round that closed a point away from where it
    // opened (first tester pass, D6): no liquidation price, and not a word that it is the path of the price DURING the round
    // that liquidates, not its close. Both are said now, in the market's own words ("odds" for an odds market).
    const whyKey = (market ?? format ?? FALLBACK).kind === "ODDS" ? "own.whyOdds" : "own.whyPrice";
    return (
      <div className={`card ${heroClass} own-outcome own-outcome--liquidated`}>
        <div className="net-result">
          <span className="outcome-lost">{t("room.liquidatedLine", { amount: formatPlayAmount(-Number(positionStakeStr(position)), { sign: true }) })}</span>
        </div>
        <p className="net-result__secondary">{t("room.lostWholeStake")}</p>
        <p className="mono muted live-prices">
          {t("own.entryLiq", { entry: priceText(position.entryPrice), liq: priceText(position.liquidationPrice) })}
        </p>
        <p className="muted own-outcome__why">
          {t(whyKey, {
            level: priceText(position.liquidationPrice),
            closed: closePrice ? t("own.closedAt", { price: priceText(closePrice) }) : "",
          })}
        </p>
      </div>
    );
  }
  const read = referencePrice && market ? liveRead(position, referencePrice.price, market) : null;
  return (
    <div className={`card ${heroClass} own-outcome ${read ? liquidationLiveCardClass(read.roomFraction) : ""}`}>
      <p className="faint">
        {t("room.yourPosition")} <DirectionGlyph direction={position.direction} /> {position.direction} · x{position.leverage}
        {market ? ` · ${market.symbol}` : ""}
      </p>
      {read ? (
        <div className="net-result">
          <span className={outcomeClassName(outcomeFromNet(read.pnl))}>
            <RollingDigits value={read.pnl} durationMs={400} format={rollingPlayFormat(read.pnl, { sign: true, unit: true })} />
          </span>{" "}
          <span className="net-result__secondary">{t("room.unrealized")}</span>
        </div>
      ) : (
        <p className="muted">{t("room.positionLive")}</p>
      )}
      {read && <LiveLiqBar roomFraction={read.roomFraction} pctToLiquidation={read.pctToLiquidation} />}
      <p className="mono muted live-prices">
        {t("own.entryLive", { entry: priceText(position.entryPrice), liq: priceText(position.liquidationPrice) })}
      </p>
    </div>
  );
}

/** Plain USD with cents — what a price is printed with before the market list has arrived. */
const FALLBACK: PriceFormatMarket = { kind: "PERP", priceDecimals: 2 };
