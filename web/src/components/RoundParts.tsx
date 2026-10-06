import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import type {
  CancellationDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  Direction,
  Interval,
  MarketDto,
  MarketReferenceDto,
  PublicPositionDto,
} from "../api/contract";
import { LONG, SHORT, percentToLiquidation, sizeForMargin, unrealizedPnl, type Side } from "../api/liquidation";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { formatPercent, formatPlayAmount, formatPrice, formatUsd, type PriceFormatMarket } from "../format";
import { Avatar } from "./Avatar";
import { BotBadge, EmptyState, ResultsHashLine, RoundStatusBadge } from "./Common";
import { DirectionGlyph } from "./icons";
import { RollingDigits } from "../fx/RollingDigits";

/** `labels` name the two series by their real length (`formatDuration(MarketSeriesDto.durationSec)`,
 *  "3 MIN" / "15 MIN"). A series with no label (a market the server lists without it) is called what it is —
 *  the shorter or the longer one — never "Hourly"/"Daily", which are wrong for a 3-minute round.
 *  `openCounts` puts a dot on a series where the player has bets open (the count is spoken, not only drawn). */
export function IntervalTabs({
  value,
  onChange,
  labels,
  openCounts,
}: {
  value: Interval;
  onChange: (v: Interval) => void;
  labels?: Partial<Record<Interval, string>>;
  openCounts?: Partial<Record<Interval, number>>;
}) {
  const tab = (interval: Interval, fallback: string) => {
    const open = openCounts?.[interval] ?? 0;
    return (
      <button type="button" role="tab" aria-selected={value === interval} onClick={() => onChange(interval)}>
        {labels?.[interval] ?? fallback}
        {open > 0 && <span className="tabs__dot" role="img" aria-label={`${open} of your bets open in this series`} />}
      </button>
    );
  };
  return (
    <div className="tabs" role="tablist" aria-label="Round interval">
      {tab("HOURLY", "Shorter")}
      {tab("DAILY", "Longer")}
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
  market,
}: {
  positions: PublicPositionDto[];
  settled: boolean;
  /** RUNNING only: live reference price for each position's unrealized P&L (task: "live
   *  unrealized P&L ... updating every poll"). Undefined/null while SETTLED or not yet loaded. */
  referencePrice?: MarketReferenceDto | null;
  /** The market the positions are in — its price scale drives the P&L math (BTC when omitted). */
  market?: MarketDto;
}) {
  if (positions.length === 0) return <EmptyState>No positions in this round.</EmptyState>;
  const up = positions.filter((p) => p.direction === "UP");
  const down = positions.filter((p) => p.direction === "DOWN");
  return (
    <div className="stage">
      <StagePanel direction="UP" positions={up} settled={settled} referencePrice={referencePrice} market={market} />
      <StagePanel direction="DOWN" positions={down} settled={settled} referencePrice={referencePrice} market={market} />
    </div>
  );
}

function StagePanel({
  direction,
  positions,
  settled,
  referencePrice,
  market,
}: {
  direction: Direction;
  positions: PublicPositionDto[];
  settled: boolean;
  referencePrice?: MarketReferenceDto | null;
  market?: MarketDto;
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
                ? unrealizedPnl(p.size, p.entryPrice, p.direction === "UP" ? LONG : SHORT, referencePrice.price, market)
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

/**
 * The crowd account trades PAPER dollars — pretend money that is neither PLAY nor real — and every card that shows its dollars says so
 * under its heading. The herd's block read "Margin: $10,026.74" in dollars next to PLAY everywhere else, with nothing to say whose
 * dollars they were (first tester pass, D12).
 */
export function PaperMoneyNote() {
  return (
    <p className="paper-note faint">
      <span className="paper-tag">PAPER $</span> Pretend dollars: the herd's paper money — not PLAY, not real.
    </p>
  );
}

interface CrowdPositionCardProps {
  phase: "RUNNING" | "SETTLED";
  crowd: CrowdRoundPositionDto | CrowdRoundResultDto | null;
  /** RUNNING only — same reference price passed to `PositionsList` (task: "live unrealized P&L
   *  ... of the crowd account"). */
  referencePrice?: MarketReferenceDto | null;
  /** The market of the round — price scale and display format (BTC / plain USD when omitted). */
  market?: MarketDto;
  /** How to print prices when `format` is better known than `market` (a round page: the round's own `kind` and
   *  `priceDecimals`, which are right even when the market has left the list). Wins over `market`'s. */
  format?: PriceFormatMarket;
}

export function CrowdPositionCard({ phase, crowd, referencePrice, market, format }: CrowdPositionCardProps) {
  const printer = format ?? market;
  const price = (p: string) => (printer ? formatPrice(p, printer) : formatUsd(p));
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
    const size = referencePrice ? sizeForMargin(c.margin, c.leverage, c.entryPrice, market) : null;
    const live = size ? unrealizedPnl(size, c.entryPrice, side, referencePrice!.price, market) : null;
    const pctToLiq = referencePrice ? percentToLiquidation(referencePrice.price, c.liquidationPrice) : null;
    return (
      <div className="card">
        <h3>Crowd account's position</h3>
        <PaperMoneyNote />
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
        <p className="mono muted">Entry price: {price(c.entryPrice)}</p>
        <p className="mono muted">Liquidation price: {price(c.liquidationPrice)}</p>
      </div>
    );
  }

  const c = crowd as CrowdRoundResultDto;
  const net = Number(c.capitalAfter) - Number(c.capitalBefore);
  // Traded, but the price closed where it opened: neither a win nor a loss (`MarketCrowdBriefDto.roundsWon`/`lastOutcome`
  // FLAT) — said in words, so a zero is never mistaken for a missing number.
  const flat = !c.liquidated && Math.round(net * 100) === 0;
  return (
    <div className="card">
      <h3>Crowd account's position</h3>
      <PaperMoneyNote />
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
        {flat && <span className="net-result__secondary"> flat</span>}
      </div>
      {flat && <p className="muted">The price closed where it opened — neither a win nor a loss for the crowd account.</p>}
      <p className="mono muted">Capital before: {formatUsd(c.capitalBefore)}</p>
      <p className="mono muted">Capital after: {formatUsd(c.capitalAfter)}</p>
    </div>
  );
}

export function CancellationNotice({
  cancellation,
  resultsHash,
  roundId,
  context,
}: {
  cancellation: CancellationDto;
  resultsHash?: string;
  roundId: string;
  /** Which market and series the round was in ("GER · 3 MIN") — a cancelled round has no hero card to say so. */
  context?: string;
}) {
  return (
    <div className="card">
      <RoundStatusBadge phase="CANCELLED" />
      {context && <p className="faint round-context">{context}</p>}
      <h3 style={{ marginTop: 12 }}>This round was cancelled.</h3>
      <p>{cancellation.reason}</p>
      <p className="muted">
        All {cancellation.refundedBetCount} bets were refunded, including any that would have been liquidated.
      </p>
      <ResultsHashLine roundId={roundId} resultsHash={resultsHash} />
    </div>
  );
}
