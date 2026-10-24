import { useEffect, useId, useMemo, useState } from "react";
import type { EpochMs, MarketDto, PriceTickDto } from "../api/contract";
import { formatClock, formatPrice, timeZoneNote } from "../format";
import { useT, type TKey } from "../i18n";
import {
  availableRanges,
  axisValues,
  dataSpanMs,
  defaultRange,
  describeMoneyMove,
  describeMove,
  moveSinceOpen,
  placeOnScale,
  sliceRange,
  spreadLabels,
  toPoints,
  yDomain,
  CHART_RANGES,
  type ChartLine,
  type RangeId,
} from "./priceChartModel";
import { Skeleton } from "./Common";
import type { BackupPrice } from "./marketModel";
import "./PriceChart.css";

/** Tall enough to read on a desktop, short enough on a phone that the arena below it stays in the first screen. */
const HEIGHT_WIDE = 224;
const HEIGHT_NARROW = 196;
const NARROW_BELOW = 420;
const PAD_BASE = { left: 8, top: 16, bottom: 26 };
/** Approximate advance of one character of the 11 px bold tabular text used in the price bubble… */
const BUBBLE_CHAR_PX = 6.7;
/** …and of the 10.5 px bold capitals-and-digits text of a reference-line label. */
const REF_CHAR_PX = 7;
/** Width used before the first measurement (and under jsdom, which never reports one). */
const FALLBACK_WIDTH = 340;

/** The width of the element a callback ref is attached to. A callback ref (and state for the element), not
 *  a ref object read in a mount effect: the plot's container only exists once there are prices to draw, so
 *  the observer has to attach whenever that element appears — an observer set up at mount never saw it, and
 *  the chart stayed at its fallback width inside a wider box. */
const RANGE_LABEL: Record<RangeId, TKey> = { "1M": "pchart.r1M", "5M": "pchart.r5M", "15M": "pchart.r15M" };

function useWidth(): [(element: HTMLDivElement | null) => void, number] {
  const [element, setElement] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const w = Math.round(entries[0]?.contentRect.width ?? 0);
      if (w > 0) setWidth(w);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [element]);
  return [setElement, width];
}

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
 * The room's live price chart (Р-70, Р-71): the market's ticks as a line with a pulsing end point and the
 * current price in a bubble, range chips for what the data covers, and — over it — the lines that make it
 * a betting chart rather than a finance one: the round's open price, the player's entry and liquidation
 * levels, and the herd's fainter pair. A liquidation price too far from the price to share its scale is
 * pinned to the chart's edge with an arrow and its distance, never silently dropped.
 *
 * Hand-drawn SVG (no chart library): ~600 points, one path, redrawn on each 2 s poll.
 */
export function PriceChart({ market, ticks, current, lines, roundStartMs, loading, error, stale = false, live = true, backup = null }: PriceChartProps) {
  const t = useT();
  const [measureRef, measured] = useWidth();
  const gradientId = useId();
  const [picked, setPicked] = useState<RangeId | null>(null);
  const width = measured || FALLBACK_WIDTH;
  const HEIGHT = width < NARROW_BELOW ? HEIGHT_NARROW : HEIGHT_WIDE;
  const odds = market.kind === "ODDS";
  const price = (value: number) => formatPrice(value, market);

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

  if (loading && points.length === 0) return <Skeleton height={HEIGHT} style={{ borderRadius: "var(--radius-lg)" }} />;

  if (points.length < 2) {
    return (
      <div className="pchart pchart--empty" role="status" style={{ height: HEIGHT }}>
        <p>{error ? t("pchart.feedDown") : t("pchart.waiting")}</p>
      </div>
    );
  }

  const first = points[0];
  const last = points[points.length - 1];
  const x0 = first.atMs;
  const x1 = last.atMs;
  // The current-price bubble sits in a gutter on the right sized to its text, so "$117,654.12" and
  // "$0.094534" both fit without squeezing the plot more than they need to.
  const bubbleText = price(last.price);
  const bubbleW = Math.max(56, Math.round(bubbleText.length * BUBBLE_CHAR_PX + 14));
  const PAD = { ...PAD_BASE, right: bubbleW + 10 };
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const anchors = lines.filter((l) => l.tone !== "liq").map((l) => l.price);
  const optional = lines.filter((l) => l.tone === "liq").map((l) => l.price);
  const domain = yDomain(
    points.map((p) => p.price),
    anchors,
    optional,
  );
  const x = (ms: EpochMs) => PAD.left + ((ms - x0) / (x1 - x0 || 1)) * plotW;
  const y = (value: number) => PAD.top + ((domain.max - value) / (domain.max - domain.min || 1)) * plotH;

  const open = lines.find((l) => l.id === "open");
  const reference = open ? open.price : first.price;
  const up = last.price > reference;
  const down = last.price < reference;
  const tone = up ? "up" : down ? "down" : "flat";

  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.atMs).toFixed(1)} ${y(p.price).toFixed(1)}`).join(" ");
  const area = `${path} L${x(last.atMs).toFixed(1)} ${PAD.top + plotH} L${x(first.atMs).toFixed(1)} ${PAD.top + plotH} Z`;

  // Lines: placed on the scale (or pinned to an edge), labels spread so none overlaps another.
  const placed = lines.map((line) => {
    const placement = placeOnScale(line.price, domain);
    return { line, placement, y: PAD.top + placement.t * plotH };
  });
  const labelY = spreadLabels(
    placed.map((p) => p.y - 9),
    17,
    PAD.top - 4,
    PAD.top + plotH - 14,
  );

  const startInView = roundStartMs != null && roundStartMs > x0 && roundStartMs < x1;
  const bubbleY = Math.min(PAD.top + plotH - 10, Math.max(PAD.top + 10, y(last.price)));
  // An axis label the bubble would cover is left out rather than drawn half-hidden under it.
  const axis = axisValues(domain).filter((value) => Math.abs(y(value) - bubbleY) > 15);
  const showSeconds = x1 - x0 <= 20 * 60_000;
  const move = open ? describeMove(moveSinceOpen(open.price, last.price, odds)) : null;
  const money = open && !odds && move && move.tone !== "flat" ? describeMoneyMove(open.price, last.price, price) : null;
  const moveText = move ? (money ? `${money} (${move.text})` : move.text) : null;
  const moveTone = move ? move.tone : "flat";

  const summary =
    t("pchart.summary", {
      symbol: market.symbol,
      range: range ? t(RANGE_LABEL[range]) : t("pchart.recent"),
      from: price(first.price),
      to: price(last.price),
    }) +
    (open ? `; ${t("pchart.sumOpen", { price: price(open.price) })}` : "") +
    lines
      .filter((l) => l.tone === "liq")
      .map((l) => `; ${l.label.toLowerCase()} ${price(l.price)}`)
      .join("");

  return (
    <figure className="pchart">
      <div className="pchart__bar">
        <div className="chip-row pchart__chips" role="tablist" aria-label={t("chart.rangeAria")}>
          {CHART_RANGES.filter((r) => available.includes(r.id)).map((r) => (
            <button
              key={r.id}
              type="button"
              role="tab"
              aria-selected={range === r.id}
              className="chip chip--small"
              onClick={() => setPicked(r.id)}
            >
              {t(RANGE_LABEL[r.id])}
            </button>
          ))}
          {available.length === 0 && <span className="pchart__live faint">{t("pchart.live")}</span>}
        </div>
        {moveText && (
          <p className={`pchart__move pchart__move--${moveTone} mono`}>
            <span aria-hidden="true">{moveTone === "up" ? "▲" : moveTone === "down" ? "▼" : "•"}</span> {moveText}{" "}
            <span className="pchart__move-note">{t("pchart.vsOpen")}</span>
          </p>
        )}
      </div>

      <div ref={measureRef} className="pchart__plot">
        <svg viewBox={`0 0 ${width} ${HEIGHT}`} width={width} height={HEIGHT} role="img" aria-label={summary}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={`var(--pchart-${tone})`} stopOpacity="0.28" />
              <stop offset="100%" stopColor={`var(--pchart-${tone})`} stopOpacity="0" />
            </linearGradient>
          </defs>

          {axisValues(domain).map((value) => (
            <line key={value} x1={PAD.left} x2={PAD.left + plotW} y1={y(value)} y2={y(value)} className="pchart__grid" />
          ))}
          {axis.map((value) => (
            <text key={value} x={width - 6} y={y(value) + 3.5} textAnchor="end" className="pchart__axis">
              {price(value)}
            </text>
          ))}
          {[0, 0.5, 1].map((f) => (
            <text
              key={f}
              x={PAD.left + f * plotW}
              y={HEIGHT - 7}
              textAnchor={f === 0 ? "start" : f === 1 ? "end" : "middle"}
              className="pchart__axis"
            >
              {formatClock(x0 + f * (x1 - x0), { seconds: showSeconds })}
            </text>
          ))}

          {startInView && <line x1={x(roundStartMs)} x2={x(roundStartMs)} y1={PAD.top} y2={PAD.top + plotH} className="pchart__start" />}

          <path d={area} fill={`url(#${gradientId})`} />
          <path d={path} className={`pchart__line pchart__line--${tone}`} fill="none" />

          {placed.map(({ line, placement, y: ly }, i) => {
            // A line beyond the scale is pinned to that edge and says how far off it is, in words.
            const away = placement.off
              ? ` · ${t(placement.off === "above" ? "pchart.above" : "pchart.below", {
                  pct: ((Math.abs(line.price - last.price) / (Math.abs(last.price) || 1)) * 100).toFixed(1),
                })}`
              : "";
            const text = `${line.label} ${price(line.price)}${away}`;
            const boxW = Math.min(plotW - 8, text.length * REF_CHAR_PX + 14);
            return (
              <g key={line.id} className={`pchart__ref pchart__ref--${line.tone} ${line.faint ? "pchart__ref--faint" : ""}`}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={ly} y2={ly} className="pchart__ref-line" />
                <rect x={PAD.left + 4} y={labelY[i]} width={boxW} height={16} rx={4} className="pchart__ref-box" />
                <text x={PAD.left + 10} y={labelY[i] + 11.5} className="pchart__ref-text">
                  {text}
                </text>
              </g>
            );
          })}

          <circle cx={x(last.atMs)} cy={y(last.price)} r={4.5} className={`pchart__dot pchart__dot--${tone}`} />
          {live && <circle cx={x(last.atMs)} cy={y(last.price)} r={4.5} className={`pchart__pulse pchart__pulse--${tone}`} />}
          <g className={`pchart__bubble pchart__bubble--${tone}`}>
            <rect x={width - bubbleW - 4} y={bubbleY - 10} width={bubbleW} height={20} rx={5} />
            <text x={width - bubbleW / 2 - 4} y={bubbleY + 4} textAnchor="middle">
              {bubbleText}
            </text>
          </g>
        </svg>
      </div>

      <figcaption className="pchart__legend faint">
        {open && (
          <span>
            <i className={`pchart__swatch pchart__swatch--${open.tone}`} aria-hidden="true" />{" "}
            {open.tone === "entry" ? t("pchart.openEntry") : t("pchart.openAll")}
          </span>
        )}
        {startInView && (
          <span>
            <i className="pchart__swatch pchart__swatch--start" aria-hidden="true" /> {t("pchart.start")}
          </span>
        )}
        {lines.some((l) => l.id === "own-liq") && (
          <span>
            <i className="pchart__swatch pchart__swatch--liq" aria-hidden="true" /> {t("pchart.ownLiq")}
          </span>
        )}
        {lines.some((l) => l.id === "crowd-liq") && (
          <span>
            <i className="pchart__swatch pchart__swatch--liq pchart__swatch--faint" aria-hidden="true" /> {t("pchart.herdLiq")}
          </span>
        )}
        {backup && (
          <span className="pchart__venue">
            {t("pchart.venue", { name: backup.name, primary: backup.primaryName ?? t("source.mainExchange") })}
          </span>
        )}
        {stale && <span className="pchart__stale">{t("pchart.stale")}</span>}
        {error && !stale && <span className="pchart__stale">{t("pchart.reconnecting")}</span>}
        {/* The axis labels carry no zone of their own: it is said here, so a player does not read them off their own clock (first tester pass, D9). */}
        <span className="pchart__zone">{t("pchart.zone", { zone: timeZoneNote(x1, t) })}</span>
      </figcaption>
    </figure>
  );
}
