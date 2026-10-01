import type { MarketDto, MarketReferenceDto, PublicPositionDto } from "../api/contract";
import { outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { formatPlayAmount, formatPrice, type PriceFormatMarket } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
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
  hero = false,
}: {
  position: PublicPositionDto;
  referencePrice: MarketReferenceDto | null;
  market: MarketDto | undefined;
  /** `card--hero`: the round page's own-position card is the top of that screen. */
  hero?: boolean;
}) {
  const heroClass = hero ? "card--hero" : "";
  if (position.liquidated) {
    return (
      <div className={`card ${heroClass} own-outcome own-outcome--liquidated`}>
        <div className="net-result">
          <span className="outcome-lost">{formatPlayAmount(-Number(positionStakeStr(position)), { sign: true })} PLAY · liquidated</span>
        </div>
        <p className="net-result__secondary">You lost your whole stake.</p>
      </div>
    );
  }
  const read = referencePrice && market ? liveRead(position, referencePrice.price, market) : null;
  const priceText = (price: string) => formatPrice(price, (market ?? FALLBACK) as PriceFormatMarket);
  return (
    <div className={`card ${heroClass} own-outcome ${read ? liquidationLiveCardClass(read.roomFraction) : ""}`}>
      <p className="faint">
        Your position: <DirectionGlyph direction={position.direction} /> {position.direction} · x{position.leverage}
        {market ? ` · ${market.symbol}` : ""}
      </p>
      {read ? (
        <div className="net-result">
          <span className={outcomeClassName(outcomeFromNet(read.pnl))}>
            <RollingDigits value={read.pnl} durationMs={400} format={(n) => `${formatPlayAmount(n, { sign: true })} PLAY`} />
          </span>{" "}
          <span className="net-result__secondary">unrealized</span>
        </div>
      ) : (
        <p className="muted">Your position is live.</p>
      )}
      {read && <LiveLiqBar roomFraction={read.roomFraction} pctToLiquidation={read.pctToLiquidation} />}
      <p className="mono muted live-prices">
        Entry {priceText(position.entryPrice)} · Liquidation {priceText(position.liquidationPrice)}
      </p>
    </div>
  );
}

/** Plain USD with cents — what a price is printed with before the market list has arrived. */
const FALLBACK: PriceFormatMarket = { kind: "PERP", priceDecimals: 2 };
