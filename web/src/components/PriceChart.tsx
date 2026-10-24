import { useMemo, useState } from "react";
import type { EpochMs, MarketDto, PriceTickDto } from "../api/contract";
import { formatPrice } from "../format";
import { useT } from "../i18n";
import { ChartLegend } from "./chart/ChartLegend";
import { ChartToolbar } from "./chart/ChartToolbar";
import { FALLBACK_WIDTH, PAD_BASE, bubbleWidth, chartHeight, inView, makeFrame, yDomain } from "./chart/frame";
import { GridAxes } from "./chart/overlays/GridAxes";
import { LastPrice } from "./chart/overlays/LastPrice";
import { ReferenceLines } from "./chart/overlays/ReferenceLines";
import { RoundStartMarker } from "./chart/overlays/RoundStartMarker";
import { SeriesLayer, buildSeries, describeSeries } from "./chart/renderers";
import { availableRanges, dataSpanMs, defaultRange, seriesExtent, sliceRange, toPoints, type RangeId } from "./chart/series";
import { allowedStyles } from "./chart/styles";
import { chartSummary } from "./chart/summary";
import { useChartStyle } from "./chart/useChartStyle";
import { useWidth } from "./chart/useWidth";
import { Skeleton } from "./Common";
import type { BackupPrice } from "./marketModel";
import type { ChartLine } from "./priceChartModel";
import "./PriceChart.css";

export interface PriceChartProps {
  market: Pick<MarketDto, "id" | "symbol" | "kind" | "priceDecimals">;
  /** Oldest first (`usePriceSeries`). */
  ticks: readonly PriceTickDto[];
  /** The latest reference price: drawn as the final point when it is newer than the last tick, so the
   *  chart's bubble and the room header never disagree. */
  current?: { price: string; asOfMs: EpochMs } | null;
  lines: readonly ChartLine[];
  /** The running round's start — a faint vertical marker where the open price was fixed. */
  roundStartMs?: EpochMs | null;
  loading: boolean;
  error: boolean;
  /** The source's own price is stale (`MarketPriceDto.stale`): the last point is old. */
  stale?: boolean;
  /** The market is taking prices (default). A closed market's chart is a record: its end point does not pulse. */
  live?: boolean;
  /** The latest price (`current`) is the BACKUP exchange's (`backupPriceOf`): the series is the main exchange's ticks
   *  alone (`PriceSeriesDto` carries no venue), so while it is silent the line stops and only the last point is the
   *  backup's — the legend says so. */
  backup?: BackupPrice | null;
}

/**
 * The room's live price chart (Р-70, Р-71): the market's ticks drawn in the chosen look (`chart/renderers`), with a
 * pulsing end point and the current price in a bubble, range chips for what the data covers, and — over it — the
 * lines that make it a betting chart rather than a finance one: the round's open price, the player's entry and
 * liquidation levels, and the herd's fainter pair. A liquidation price too far from the price to share its scale is
 * pinned to the chart's edge with an arrow and its distance, never silently dropped.
 *
 * Composition only (`docs/product/design/price-chart-architecture.md`): data → series of the chosen look → frame →
 * toolbar, then the SVG from the grid, the series, the overlays, and the legend. Each part lives in `chart/` and
 * knows only its own job. Hand-drawn SVG (no chart library): ~600 points, redrawn on each 2 s poll.
 */
export function PriceChart({ market, ticks, current, lines, roundStartMs, loading, error, stale = false, live = true, backup = null }: PriceChartProps) {
  const t = useT();
  const [measureRef, measured] = useWidth();
  const [picked, setPicked] = useState<RangeId | null>(null);
  const width = measured || FALLBACK_WIDTH;
  const height = chartHeight(width);
  const odds = market.kind === "ODDS";
  const price = (value: number) => formatPrice(value, market);
  const [style, setStyle] = useChartStyle(market.kind);

  const all = useMemo(() => {
    const points = toPoints(ticks);
    const now = current ? Number(current.price) : NaN;
    const last = points[points.length - 1];
    if (current && Number.isFinite(now) && (!last || current.asOfMs > last.atMs)) points.push({ atMs: current.asOfMs, price: now });
    return points;
  }, [ticks, current]);

  const available = useMemo(() => availableRanges(dataSpanMs(all)), [all]);
  const range: RangeId | null = picked !== null && available.includes(picked) ? picked : defaultRange(available);
  const points = useMemo(() => sliceRange(all, range), [all, range]);
  const series = useMemo(() => buildSeries(style, all, range), [style, all, range]);

  if (loading && points.length === 0) return <Skeleton height={height} style={{ borderRadius: "var(--radius-lg)" }} />;

  if (points.length < 2) {
    return (
      <div className="pchart pchart--empty" role="status" style={{ height }}>
        <p>{error ? t("pchart.feedDown") : t("pchart.waiting")}</p>
      </div>
    );
  }

  const first = points[0];
  const last = points[points.length - 1];
  const extent = seriesExtent(series);
  const anchors = lines.filter((l) => l.tone !== "liq").map((l) => l.price);
  const optional = lines.filter((l) => l.tone === "liq").map((l) => l.price);
  const frame = makeFrame({
    width,
    height,
    pad: { ...PAD_BASE, right: bubbleWidth(price(last.price)) + 10 },
    fromMs: extent.fromMs,
    toMs: extent.toMs,
    domain: yDomain([extent.low, extent.high], anchors, optional),
  });

  const open = lines.find((l) => l.id === "open");
  const reference = open ? open.price : first.price;
  const tone = last.price > reference ? "up" : last.price < reference ? "down" : "flat";

  return (
    <figure className="pchart">
      <ChartToolbar
        available={available}
        range={range}
        onRange={setPicked}
        styles={allowedStyles(market.kind)}
        style={style}
        onStyle={setStyle}
        move={{ open: open ? open.price : null, last: last.price, odds, format: price }}
      />

      <div ref={measureRef} className="pchart__plot">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          width={width}
          height={height}
          role="img"
          aria-label={chartSummary({
            t,
            symbol: market.symbol,
            range,
            from: first.price,
            to: last.price,
            lines,
            format: price,
            look: describeSeries(series, t, price),
          })}
        >
          <GridAxes frame={frame} lastPrice={last.price} formatPrice={price} />
          <RoundStartMarker frame={frame} roundStartMs={roundStartMs} />
          <SeriesLayer series={series} frame={frame} tone={tone} />
          <ReferenceLines frame={frame} lines={lines} lastPrice={last.price} formatPrice={price} />
          <LastPrice frame={frame} atMs={last.atMs} price={last.price} text={price(last.price)} tone={tone} live={live} />
        </svg>
      </div>

      <ChartLegend lines={lines} showStart={inView(frame, roundStartMs)} backup={backup} stale={stale} error={error} endMs={frame.toMs} />
    </figure>
  );
}
