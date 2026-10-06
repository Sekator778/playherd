import { useMemo, useState } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { DotItemDotProps } from "recharts/types/util/types";
import type { CrowdHistoryPointDto } from "../api/contract";
import { formatUsd, timeZoneNote } from "../format";
import { capitalAxis, crowdRanges, dedupeByTime, defaultCrowdRange, formatAxisTick, formatAxisTooltip } from "./crowdModel";

const COLOR_CROWD = "#9147ff";
const COLOR_HOLD = "#8b93a8";
const COLOR_ANTI = "#f5b93d";

function TooltipContent({
  active,
  payload,
  label,
  spanMs,
}: {
  active?: boolean;
  payload?: { value: number; name: string; color: string }[];
  label?: number;
  spanMs: number;
}) {
  if (!active || !payload || !label) return null;
  return (
    <div className="chart-tooltip">
      <div className="faint">{formatAxisTooltip(label, spanMs)}</div>
      {payload.map((p) => (
        <div key={p.name} style={{ color: p.color }}>
          {p.name}: {formatUsd(p.value)}
        </div>
      ))}
    </div>
  );
}

interface CrowdChartProps {
  history: CrowdHistoryPointDto[];
  /** compact = Home's inline preview (fixed default range + a "show all" toggle, no range tabs);
   *  full = the dedicated Crowd page (range tabs, season-boundary markers). */
  variant?: "compact" | "full";
  /** The market's ticker, for the "hold" line's legend ("Hold BTC", "Hold NVDA"): the line is a plain
   *  buy-and-hold of THAT market (Р-26, per market — Т-6). Without one the legend says "Hold the asset" — it
   *  never assumes BTC. */
  symbol?: string;
}

/**
 * Crowd account vs hold vs anti-crowd for one market (Р-17, Р-25, Р-26, Т-6). The horizontal axis is a real
 * time axis, and follows how much history there is (`crowdModel.ts`): over a few hours (a stand with
 * 3-minute rounds) it ticks with the time of day (UTC, named under the chart) and the range chips are 1H · 6H · 1D · All; over
 * months it ticks with dates and keeps 1M · 3M · 1Y · All — the open critique item "the chart's x axis must
 * show time of day when the data span is short".
 */
export function CrowdChart({ history: rawHistory, variant = "full", symbol }: CrowdChartProps) {
  // One point per instant (the rounds of two series that end together share a millisecond — see `dedupeByTime`).
  const history = useMemo(() => dedupeByTime(rawHistory), [rawHistory]);
  const [picked, setPicked] = useState<string | null>(null);
  const [compactShowAll, setCompactShowAll] = useState(false);

  const newest = history.length > 0 ? history[history.length - 1].atMs : 0;
  const spanMs = history.length > 1 ? newest - history[0].atMs : 0;
  const ranges = useMemo(() => crowdRanges(spanMs), [spanMs]);
  const chosen = ranges.find((r) => r.key === picked) ?? defaultCrowdRange(spanMs);
  const effective = variant === "compact" && compactShowAll ? ranges[ranges.length - 1] : chosen;

  const visible = useMemo(() => {
    if (effective.ms === null || history.length === 0) return history;
    const cutoff = newest - effective.ms;
    return history.filter((p) => p.atMs >= cutoff);
  }, [history, effective, newest]);

  // The axis format follows what is on screen, not the whole history: a "1H" view of a month-old account
  // ticks with times.
  const visibleSpanMs = visible.length > 1 ? visible[visible.length - 1].atMs - visible[0].atMs : 0;
  const boundaries = useMemo(() => visible.filter((p) => p.seasonBoundary !== null), [visible]);
  // Round ticks that cover what is drawn, labelled with as many decimals as it takes to tell them apart (`capitalAxis`).
  const capital = useMemo(
    () =>
      capitalAxis(
        visible.flatMap((p) => [Number(p.crowdCapital), Number(p.holdCapital), Number(p.antiCrowdCapital)]),
        variant === "full" ? 5 : 4,
      ),
    [visible, variant],
  );

  // live-round.md §5: the rightmost "crowd" point pops in (scale 0→1) when a new one arrives,
  // instead of the whole line just redrawing — every other point renders invisibly (r=0) so this
  // stays a single `dot` render prop rather than a second overlay series. Keyed by the point's
  // own `atMs` so React only remounts (and replays the CSS pop animation) when that point is
  // actually new, not on every unrelated re-render.
  const renderCrowdDot = ({ cx, cy, index, payload }: DotItemDotProps) => {
    const point = payload as CrowdHistoryPointDto | undefined;
    if (cx == null || cy == null || index !== visible.length - 1) return null;
    return <circle key={point?.atMs ?? index} className="crowd-chart__pop-dot" cx={cx} cy={cy} r={4} fill={COLOR_CROWD} />;
  };

  return (
    <div className="crowd-chart">
      {variant === "full" ? (
        ranges.length > 1 && (
          <div className="tabs" role="tablist" aria-label="Chart range">
            {ranges.map((r) => (
              <button key={r.key} type="button" role="tab" aria-selected={effective.key === r.key} onClick={() => setPicked(r.key)}>
                {r.label}
              </button>
            ))}
          </div>
        )
      ) : (
        <button type="button" className="btn btn--secondary" onClick={() => setCompactShowAll((v) => !v)}>
          {compactShowAll ? "Show the default range" : "Show full period"}
        </button>
      )}

      <div style={{ width: "100%", height: variant === "full" ? 360 : 240, marginTop: 12 }}>
        <ResponsiveContainer>
          <ComposedChart data={visible} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="#2b3346" strokeDasharray="3 3" />
            <XAxis
              dataKey="atMs"
              type="number"
              scale="time"
              domain={["dataMin", "dataMax"]}
              tickFormatter={(ms: number) => formatAxisTick(ms, visibleSpanMs)}
              stroke="#a7b0c4"
              minTickGap={40}
              tick={{ fontSize: 12 }}
            />
            <YAxis
              stroke="#a7b0c4"
              tick={{ fontSize: 12 }}
              tickFormatter={capital ? capital.format : (v: number) => `$${Math.round(v / 1000)}k`}
              width={capital ? capital.width : 56}
              domain={capital ? capital.domain : ["auto", "auto"]}
              ticks={capital?.ticks}
            />
            <Tooltip content={<TooltipContent spanMs={visibleSpanMs} />} />
            <Legend
              formatter={(value: string) =>
                value === "crowdCapital" ? "Crowd account" : value === "holdCapital" ? (symbol ? `Hold ${symbol}` : "Hold the asset") : "Anti-crowd"
              }
            />
            {boundaries.map((b) => (
              <ReferenceLine key={b.roundId} x={b.atMs} stroke="#3a4460" strokeDasharray="2 4" />
            ))}
            {/* Crowd — the "hero" series (direction.md §0): drop-shadow glow + a soft fill under
             *  the line, the other two series stay plain <Line>s so the chart doesn't turn into a
             *  wall of color (live-round.md §5). */}
            <Area
              type="monotone"
              dataKey="crowdCapital"
              name="crowdCapital"
              stroke={COLOR_CROWD}
              fill={COLOR_CROWD}
              fillOpacity={0.12}
              strokeWidth={2.5}
              dot={renderCrowdDot}
              activeDot={{ r: 4 }}
              isAnimationActive={false}
              className="crowd-chart__crowd-area"
            />
            <Line type="monotone" dataKey="holdCapital" name="holdCapital" stroke={COLOR_HOLD} dot={false} strokeWidth={1.5} strokeDasharray="5 3" isAnimationActive={false} />
            <Line type="monotone" dataKey="antiCrowdCapital" name="antiCrowdCapital" stroke={COLOR_ANTI} dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      {visibleSpanMs > 0 && variant === "full" && (
        <p className="faint crowd-chart__axis-note">
          Amounts are paper dollars. Dates and times are in {timeZoneNote(newest)}.
        </p>
      )}
    </div>
  );
}
