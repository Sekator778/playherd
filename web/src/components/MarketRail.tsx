import { Link } from "react-router-dom";
import type { MarketDto } from "../api/contract";
import { useMarkets } from "../api/useMarkets";
import { categoryLabel } from "../api/marketMeta";
import { formatPrice, market24hMove } from "../format";
import { useT } from "../i18n";
import { MarketIcon } from "./MarketIcon";
import { trend, TREND_GLYPH } from "./marketFormat";
import { isOpenMarket } from "./marketModel";
import "./MarketRail.css";

/**
 * "More in Crypto" — a horizontal rail of the other open markets of the same category, at the bottom of
 * a room (Polymarket's "More perps" shelf): one tap to the next market without going back to the lobby.
 * Compact on purpose — icon, ticker, price, 24 h change; the lobby card is where the crowd split lives.
 */
export function MarketRail({ market }: { market: MarketDto }) {
  const t = useT();
  const { markets } = useMarkets();
  const others = markets.filter((m) => m.category === market.category && m.id !== market.id && isOpenMarket(m));
  if (others.length === 0) return null;
  const title = categoryLabel(market.category, t);
  return (
    <section className="mrail" aria-label={t("rail.moreIn", { title })}>
      <div className="mrail__head">
        <h3 className="room-section-title">{t("rail.moreIn", { title })}</h3>
        <Link to={`/?c=${market.category}`} className="mrail__all">
          {t("rail.seeAll")}
        </Link>
      </div>
      <ul className="mrail__list">
        {others.map((m) => {
          const move = market24hMove(m, m.price, t);
          const tone = trend(move.value);
          return (
            <li key={m.id}>
              <Link to={`/play/${encodeURIComponent(m.id)}`} className="mrail__card" aria-label={`${m.symbol} — ${m.title}`}>
                <MarketIcon market={m} size={32} />
                <span className="mrail__symbol">{m.symbol}</span>
                <span className="mrail__price mono">{m.price ? formatPrice(m.price.price, m) : "—"}</span>
                <span className={`mrail__change mrail__change--${tone} mono`}>
                  <span aria-hidden="true">{TREND_GLYPH[tone]}</span> {move.text}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
