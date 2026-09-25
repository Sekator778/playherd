import { useMemo, useState } from "react";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { CrowdHistoryPointDto } from "../api/contract";
import { formatUsd } from "../format";

const COLOR_CROWD = "#9147ff";
const COLOR_BTC = "#8b93a8";
const COLOR_ANTI = "#f5b93d";

const DAY_MS = 86_400_000;

type RangeKey = "1M" | "3M" | "1Y" | "ALL";
const RANGE_DAYS: Record<RangeKey, number | null> = { "1M": 30, "3M": 90, "1Y": 365, ALL: null };

function formatAxisDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

function TooltipContent({ active, payload, label }: { active?: boolean; payload?: { value: number; name: string; color: string }[]; label?: number }) {
  if (!active || !payload || !label) return null;
  return (
    <div className="chart-tooltip">
      <div className="faint">{formatAxisDate(label)}</div>
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
  /** compact = Home's inline preview (fixed 90d default + a "show all" toggle, no range tabs);
   *  full = the dedicated Crowd page (range tabs, season-boundary markers). */
  variant?: "compact" | "full";
}

export function CrowdChart({ history, variant = "full" }: CrowdChartProps) {
  const [range, setRange] = useState<RangeKey>(variant === "compact" ? "3M" : "3M");
  const [compactShowAll, setCompactShowAll] = useState(false);

  const effectiveRange: RangeKey = variant === "compact" && compactShowAll ? "ALL" : range;
  const days = RANGE_DAYS[effectiveRange];

  const visible = useMemo(() => {
    if (days === null || history.length === 0) return history;
    const cutoff = history[history.length - 1].atMs - days * DAY_MS;
    return history.filter((p) => p.atMs >= cutoff);
  }, [history, days]);

  const boundaries = useMemo(() => visible.filter((p) => p.seasonBoundary !== null), [visible]);

  return (
    <div className="crowd-chart">
      {variant === "full" ? (
        <div className="tabs" role="tablist" aria-label="Chart range">
          {(Object.keys(RANGE_DAYS) as RangeKey[]).map((key) => (
            <button key={key} type="button" role="tab" aria-selected={range === key} onClick={() => setRange(key)}>
              {key === "ALL" ? "All" : key}
            </button>
          ))}
        </div>
      ) : (
        <button type="button" className="btn btn--secondary" onClick={() => setCompactShowAll((v) => !v)}>
          {compactShowAll ? "Show last 3 months" : "Show full period"}
        </button>
      )}

      <div style={{ width: "100%", height: variant === "full" ? 360 : 240, marginTop: 12 }}>
        <ResponsiveContainer>
          <LineChart data={visible} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="#2b3346" strokeDasharray="3 3" />
            <XAxis dataKey="atMs" tickFormatter={formatAxisDate} stroke="#a7b0c4" minTickGap={40} tick={{ fontSize: 12 }} />
            <YAxis stroke="#a7b0c4" tick={{ fontSize: 12 }} tickFormatter={(v: number) => `$${Math.round(v / 1000)}k`} width={56} />
            <Tooltip content={<TooltipContent />} />
            <Legend
              formatter={(value: string) =>
                value === "crowdCapital" ? "Crowd account" : value === "btcHoldCapital" ? "Hold BTC" : "Anti-crowd"
              }
            />
            {boundaries.map((b) => (
              <ReferenceLine key={b.roundId} x={b.atMs} stroke="#3a4460" strokeDasharray="2 4" />
            ))}
            <Line type="monotone" dataKey="crowdCapital" name="crowdCapital" stroke={COLOR_CROWD} dot={false} strokeWidth={2.5} isAnimationActive={false} />
            <Line type="monotone" dataKey="btcHoldCapital" name="btcHoldCapital" stroke={COLOR_BTC} dot={false} strokeWidth={1.5} strokeDasharray="5 3" isAnimationActive={false} />
            <Line type="monotone" dataKey="antiCrowdCapital" name="antiCrowdCapital" stroke={COLOR_ANTI} dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
