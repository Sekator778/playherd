import { Link } from "react-router-dom";
import type {
  CancellationDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  Interval,
  PublicPositionDto,
} from "../api/contract";
import { formatPlayAmount, formatUsd } from "../format";
import { BotBadge, EmptyState, ResultsHashLine, RoundStatusBadge } from "./Common";
import { DirectionGlyph } from "./icons";

export function IntervalTabs({ value, onChange }: { value: Interval; onChange: (v: Interval) => void }) {
  return (
    <div className="tabs" role="tablist" aria-label="Round interval">
      <button type="button" role="tab" aria-selected={value === "HOURLY"} onClick={() => onChange("HOURLY")}>
        Hourly
      </button>
      <button type="button" role="tab" aria-selected={value === "DAILY"} onClick={() => onChange("DAILY")}>
        Daily
      </button>
    </div>
  );
}

export function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat-tile">
      <div className="stat-tile__label">{label}</div>
      <div className="stat-tile__value">{value}</div>
    </div>
  );
}

export function PositionsList({ positions, settled }: { positions: PublicPositionDto[]; settled: boolean }) {
  if (positions.length === 0) return <EmptyState>No positions in this round.</EmptyState>;
  return (
    <ul className="list-card">
      {positions.map((p) => {
        const won = settled && !p.liquidated && p.payout !== null && Number(p.payout) > 0;
        const lost = settled && !p.liquidated && p.payout !== null && Number(p.payout) === 0;
        return (
          <li key={p.playerId} className="list-row">
            <span>
              <DirectionGlyph direction={p.direction} /> {p.direction} · x{p.leverage} ·{" "}
              <Link to={`/profile/${p.playerId}`}>{p.player.displayName}</Link> <BotBadge isBot={p.player.isBot} />
            </span>
            <span>
              {p.liquidated && <span className="outcome-liquidated">Liquidated</span>}
              {won && <span className="outcome-won">Won {formatPlayAmount(p.payout!, { decimals: 0 })} PLAY</span>}
              {lost && <span className="outcome-lost">Lost</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

interface CrowdPositionCardProps {
  phase: "RUNNING" | "SETTLED";
  crowd: CrowdRoundPositionDto | CrowdRoundResultDto | null;
}

export function CrowdPositionCard({ phase, crowd }: CrowdPositionCardProps) {
  if (!crowd || (phase === "SETTLED" && "traded" in crowd && !crowd.traded)) {
    return (
      <div className="card">
        <h3>Crowd account's position</h3>
        <EmptyState>The crowd tied this round — no position was opened.</EmptyState>
      </div>
    );
  }

  if (phase === "RUNNING") {
    const c = crowd as CrowdRoundPositionDto;
    return (
      <div className="card">
        <h3>Crowd account's position</h3>
        <p>
          <DirectionGlyph direction={c.direction} /> {c.direction} · x{c.leverage}
        </p>
        <p className="mono muted">Margin: {formatUsd(c.margin)}</p>
        <p className="mono muted">Entry price: {formatUsd(c.entryPrice)}</p>
        <p className="mono muted">Liquidation price: {formatUsd(c.liquidationPrice)}</p>
      </div>
    );
  }

  const c = crowd as CrowdRoundResultDto;
  return (
    <div className="card">
      <h3>Crowd account's position</h3>
      {c.liquidated && <p className="outcome-liquidated">Crowd account liquidated this round.</p>}
      {c.direction && (
        <p>
          <DirectionGlyph direction={c.direction} /> {c.direction} · x{c.leverage}
        </p>
      )}
      <p className="mono muted">Capital before: {formatUsd(c.capitalBefore)}</p>
      <p className="mono muted">Capital after: {formatUsd(c.capitalAfter)}</p>
    </div>
  );
}

export function CancellationNotice({
  cancellation,
  resultsHash,
  roundId,
}: {
  cancellation: CancellationDto;
  resultsHash?: string;
  roundId: string;
}) {
  return (
    <div className="card">
      <RoundStatusBadge phase="CANCELLED" />
      <h3 style={{ marginTop: 12 }}>This round was cancelled.</h3>
      <p>{cancellation.reason}</p>
      <p className="muted">
        All {cancellation.refundedBetCount} bets were refunded, including any that would have been liquidated.
      </p>
      <ResultsHashLine roundId={roundId} resultsHash={resultsHash} />
    </div>
  );
}
