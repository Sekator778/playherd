import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import type {
  CancellationDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  Direction,
  Interval,
  MarketReferenceDto,
  PublicPositionDto,
} from "../api/contract";
import { LONG, SHORT, percentToLiquidation, sizeForMargin, unrealizedPnl, type Side } from "../api/liquidation";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { formatPercent, formatPlayAmount, formatUsd } from "../format";
import { Avatar } from "./Avatar";
import { BotBadge, EmptyState, ResultsHashLine, RoundStatusBadge } from "./Common";
import { DirectionGlyph } from "./icons";
import { RollingDigits } from "../fx/RollingDigits";

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

/** A `StatTile` for a number that changes between polls (live-round.md §5: Crowd/Home's
 *  Capital/This-season tiles) — rolls through `RollingDigits` and flashes its border win/loss
 *  colored for one cycle on each change, instead of just silently redrawing to the new text.
 *  `value: null` (no data yet, e.g. no season return recorded) falls back to a plain "—", no
 *  roll/flash. */
export function LiveStatTile({ label, value, format }: { label: string; value: number | null; format: (n: number) => string }) {
  const prevRef = useRef(value);
  const [flashClass, setFlashClass] = useState("");

  useEffect(() => {
    if (value === null || prevRef.current === value) return;
    if (prevRef.current !== null) setFlashClass(value > prevRef.current ? "value-flash-up" : "value-flash-down");
    prevRef.current = value;
    const t = window.setTimeout(() => setFlashClass(""), 450);
    return () => window.clearTimeout(t);
  }, [value]);

  return (
    <div className={`stat-tile ${flashClass}`}>
      <div className="stat-tile__label">{label}</div>
      <div className="stat-tile__value">{value === null ? "—" : <RollingDigits value={value} durationMs={400} format={format} />}</div>
    </div>
  );
}

/**
 * Positions as a "stage", split into UP/DOWN camps (`docs/product/design/direction.md` §4
 * "Stage/split-camp"; audit.md's Round section: "толпа раскололась на два лагеря... это
 * отсортированный список транзакций" was the complaint — the fix is two always-visible,
 * color-washed panels, never one merged grey list, even when one side is empty (Р-22).
 */
export function PositionsList({
  positions,
  settled,
  referencePrice,
}: {
  positions: PublicPositionDto[];
  settled: boolean;
  /** RUNNING only: live reference price for each position's unrealized P&L (task: "live
   *  unrealized P&L ... updating every poll"). Undefined/null while SETTLED or not yet loaded. */
  referencePrice?: MarketReferenceDto | null;
}) {
  if (positions.length === 0) return <EmptyState>No positions in this round.</EmptyState>;
  const up = positions.filter((p) => p.direction === "UP");
  const down = positions.filter((p) => p.direction === "DOWN");
  return (
    <div className="stage">
      <StagePanel direction="UP" positions={up} settled={settled} referencePrice={referencePrice} />
      <StagePanel direction="DOWN" positions={down} settled={settled} referencePrice={referencePrice} />
    </div>
  );
}

function StagePanel({
  direction,
  positions,
  settled,
  referencePrice,
}: {
  direction: Direction;
  positions: PublicPositionDto[];
  settled: boolean;
  referencePrice?: MarketReferenceDto | null;
}) {
  const side = direction.toLowerCase();
  return (
    <div className={`stage__panel stage__panel--${side}`}>
      <div className={`stage__heading stage__heading--${side}`}>
        <span>
          <DirectionGlyph direction={direction} /> {direction}
        </span>
        <span className="stage__heading__count">{positions.length}</span>
      </div>
      {positions.length === 0 ? (
        <p className="stage__empty muted">No one yet</p>
      ) : (
        <ul className="stage__list">
          {positions.map((p, index) => {
            // Net result (>0 won, <0 lost, =0 even) — never `payout > 0` alone, which showed a
            // losing position that still got some equity back as a "win" (Alex's report,
            // 2026-09-25). `positionStakeStr` prefers the server-sent exact stake, falling back
            // to a (lossy) derivation only if it's absent (`api/outcome.ts`).
            const stake = positionStakeStr(p);
            const net = settled && p.payout !== null ? netFromPayoutAndStake(p.payout, stake) : null;
            const live =
              !settled && referencePrice
                ? unrealizedPnl(p.size, p.entryPrice, p.direction === "UP" ? LONG : SHORT, referencePrice.price)
                : null;
            const value = settled && net !== null ? net : live ? live.toNumber() : null;
            return (
              <li
                key={p.playerId}
                className="player-chip player-chip--cascade"
                style={{ "--stagger-index": index } as CSSProperties}
              >
                <Avatar name={p.player.nickname} className="player-chip__avatar" />
                <Link to={`/profile/${p.playerId}`} className="player-chip__name">
                  {p.player.displayName}
                </Link>
                <BotBadge isBot={p.player.isBot} />
                <span className="player-chip__lev mono">x{p.leverage}</span>
                {value !== null && (
                  <span className={`player-chip__result ${p.liquidated ? "outcome-lost" : outcomeClassName(outcomeFromNet(value))}`}>
                    {formatPlayAmount(value, { sign: true })}
                    {p.liquidated && " · liq"}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

interface CrowdPositionCardProps {
  phase: "RUNNING" | "SETTLED";
  crowd: CrowdRoundPositionDto | CrowdRoundResultDto | null;
  /** RUNNING only — same reference price passed to `PositionsList` (task: "live unrealized P&L
   *  ... of the crowd account"). */
  referencePrice?: MarketReferenceDto | null;
}

export function CrowdPositionCard({ phase, crowd, referencePrice }: CrowdPositionCardProps) {
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
    const side: Side = c.direction === "UP" ? LONG : SHORT;
    // `CrowdRoundPositionDto` has no `size` field, but `margin` is exactly the input the server
    // fed into the same `sizeForMargin` formula to get it (`mockRounds.ts`'s
    // `crowdRunningPosition`) — recomputing it here reproduces that size exactly, unlike
    // `PositionDto.stake`'s case (there the DTO only exposes the *output*, `size`, so recovering
    // the input needs a lossy inverse — see `outcome.ts`).
    const size = referencePrice ? sizeForMargin(c.margin, c.leverage, c.entryPrice) : null;
    const live = size ? unrealizedPnl(size, c.entryPrice, side, referencePrice!.price) : null;
    const pctToLiq = referencePrice ? percentToLiquidation(referencePrice.price, c.liquidationPrice) : null;
    return (
      <div className="card">
        <h3>Crowd account's position</h3>
        <p>
          <DirectionGlyph direction={c.direction} /> {c.direction} · x{c.leverage}
        </p>
        {live && (
          <div className="net-result">
            <span className={outcomeClassName(outcomeFromNet(live.toNumber()))}>
              {formatUsd(live.toNumber(), { sign: true })}
            </span>{" "}
            <span className="net-result__secondary">unrealized</span>
          </div>
        )}
        {pctToLiq !== null && <p className="mono muted">{formatPercent(pctToLiq)} to liquidation</p>}
        <p className="mono muted">Margin: {formatUsd(c.margin)}</p>
        <p className="mono muted">Entry price: {formatUsd(c.entryPrice)}</p>
        <p className="mono muted">Liquidation price: {formatUsd(c.liquidationPrice)}</p>
      </div>
    );
  }

  const c = crowd as CrowdRoundResultDto;
  const net = Number(c.capitalAfter) - Number(c.capitalBefore);
  return (
    <div className="card">
      <h3>Crowd account's position</h3>
      {c.liquidated && <p className="outcome-liquidated">Crowd account liquidated this round.</p>}
      {c.direction && (
        <p>
          <DirectionGlyph direction={c.direction} /> {c.direction} · x{c.leverage}
        </p>
      )}
      <div className="net-result">
        <span className={c.liquidated ? "outcome-lost" : outcomeClassName(outcomeFromNet(net))}>
          {formatUsd(net, { sign: true })}
        </span>
      </div>
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
