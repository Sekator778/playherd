import { useState, type CSSProperties } from "react";
import type { MarketDto } from "../api/contract";
import { symbolMeta } from "../api/marketMeta";

/**
 * The round badge of a market (lobby card, room header, positions strip): the source's own image when
 * it sends one (`MarketDto.iconUrl`, `ODDS` markets), otherwise a glyph on a colour derived from the
 * symbol (`api/marketMeta.ts`'s `symbolMeta` — a colour and a letter, no logo). A source image that
 * fails to load falls back to the glyph rather than leaving a broken-image box. Decorative: the
 * symbol is always printed next to it, so the badge is `aria-hidden`.
 */
export function MarketIcon({
  market,
  size = 44,
  className = "",
}: {
  market: Pick<MarketDto, "symbol" | "iconUrl">;
  size?: number;
  className?: string;
}) {
  const meta = symbolMeta(market.symbol);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const url = market.iconUrl && market.iconUrl !== failedUrl ? market.iconUrl : null;
  const style = { "--mk": meta.color, width: size, height: size, fontSize: Math.round(size * 0.42) } as CSSProperties;
  return (
    <span className={`mk-icon ${className}`} style={style} aria-hidden="true">
      {url ? (
        <img src={url} alt="" width={size} height={size} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedUrl(url)} />
      ) : (
        <span className="mk-icon__glyph">{meta.glyph}</span>
      )}
    </span>
  );
}
