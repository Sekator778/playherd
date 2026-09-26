/**
 * Pair room (`/play/:asset`) — task IA: "concept A arena: big UP/DOWN whose widths reflect the
 * LIVE crowd split per Р-62, stake chips, leverage dial, liquidation bar, countdown, live bet
 * feed with bot badges..., crowd-account position, result moment fx". Concept A chosen per Р-61
 * (`docs/product/design/concepts.md`).
 *
 * Two round contexts, same as `HomePage.tsx`/`RoundPage.tsx` used before it (Р-19: "всегда один
 * идущий и один открытый для ставок раунд"): `betting` (the upcoming round, still taking
 * stakes — the arena and bet slip act on this one) and `running` (the round already locked in and
 * playing out — its revealed positions and the crowd account's live position, once it exists).
 * `result-moment.md`'s fx are wired globally in `Layout.tsx` (`ResultMomentHost`), not here.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import { useClock } from "../api/useClock";
import { useReferencePrice } from "../api/useReferencePrice";
import type {
  Direction,
  LiveBetDto,
  MarketReferenceDto,
  PublicPositionDto,
  RoundBetting,
  RoundDto,
  RoundId,
  RoundsPairDto,
} from "../api/contract";
import { pairById } from "../api/pairs";
import { LONG, SHORT, percentToLiquidation, unrealizedPnl } from "../api/liquidation";
import { outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { BetSlip } from "../components/BetSlip";
import { BotBadge, Countdown, ErrorState, OracleDisclosureLine, Skeleton } from "../components/Common";
import { CrowdPositionCard, IntervalTabs } from "../components/RoundParts";
import { Avatar } from "../components/Avatar";
import { DirectionGlyph, LockIcon } from "../components/icons";
import { liquidationLiveCardClass } from "../components/LiquidationBar";
import { RollingDigits } from "../fx/RollingDigits";
import { formatPercent, formatPlayAmount, formatUsd } from "../format";
import "./PairRoomPage.css";

const POLL_MS = 2000;

/** Live crowd split (Р-62) as UP/DOWN percentages of total stake — works from either the
 *  BETTING round's `upStake`/`downStake` (Р-62's live split during betting) or, once revealed,
 *  the RUNNING round's own positions (same underlying fact, later in the round's life). Falls
 *  back to an even 50/50 read (never a fabricated lean) when there is no stake yet or the
 *  optional fields are absent (a `RealApi` build that predates Р-62). */
function crowdSplit(round: RoundDto): { upPct: number; total: number } {
  let up = 0;
  let down = 0;
  if (round.phase === "BETTING") {
    up = Number(round.upStake ?? "0");
    down = Number(round.downStake ?? "0");
  } else if (round.phase === "RUNNING" || round.phase === "SETTLED") {
    for (const p of round.positions) {
      const stake = Number(positionStakeStr(p));
      if (p.direction === "UP") up += stake;
      else down += stake;
    }
  }
  const total = up + down;
  if (total <= 0) return { upPct: 50, total: 0 };
  return { upPct: Math.round((up / total) * 100), total };
}

function LiveBetFeed({ bets }: { bets: LiveBetDto[] }) {
  return (
    <ul className="room-feed">
      {bets.map((b, i) => (
        <li key={`${b.player.playerId}-${b.atMs}-${i}`} className={`room-feed__row room-feed__row--${b.direction.toLowerCase()}`}>
          <DirectionGlyph direction={b.direction} />
          <span className="room-feed__name">{b.player.displayName}</span>
          <BotBadge isBot={b.player.isBot} />
          <span className="faint">x{b.leverage}</span>
          <span className="room-feed__stake mono">{formatPlayAmount(b.stake)}</span>
        </li>
      ))}
    </ul>
  );
}

/** One revealed position plus its live PLAY P&L (or the fixed -stake loss once liquidated) — the
 *  shared shape `topMovers` ranks and `LiveRoundStrip` renders as a chip. */
interface Mover {
  position: PublicPositionDto;
  live: number;
}

/** Fix (player review, item 1): the room used to dump the RUNNING round's entire `PositionsList`
 *  "stage" (both UP/DOWN camps, every player, unconditionally) under the arena — a financial
 *  ledger sitting permanently on a screen that's supposed to feel like a game, not a terminal
 *  (Alex: "у нас оно как список какой-то, финансовый терминал"). Picks the few positions with the
 *  biggest live swing (winning or losing — never hides a loss, same honesty rule as everywhere
 *  else) so the compact strip below the arena reads like a highlight reel, not a spreadsheet; the
 *  full, unfiltered list is still one tap away via "See all positions" (`/round/:id`). A
 *  liquidated position is treated as a fixed `-stake` swing so a big liquidation can still surface
 *  here instead of silently dropping out once `live` would otherwise be null. */
function topMovers(positions: PublicPositionDto[], referencePrice: MarketReferenceDto | null, count: number): Mover[] {
  const withLive: Mover[] = [];
  for (const p of positions) {
    if (p.liquidated) {
      withLive.push({ position: p, live: -Number(positionStakeStr(p)) });
      continue;
    }
    if (!referencePrice) continue;
    const live = unrealizedPnl(p.size, p.entryPrice, p.direction === "UP" ? LONG : SHORT, referencePrice.price);
    withLive.push({ position: p, live: live.toNumber() });
  }
  return withLive.sort((a, b) => Math.abs(b.live) - Math.abs(a.live)).slice(0, count);
}

/** Compact "live round" strip (player review item 1) — players count + a handful of top-movers
 *  chips (avatar + signed live PLAY), replacing the old always-on full positions stage. The
 *  crowd's own position keeps its existing `CrowdPositionCard` right above this (unchanged). */
function LiveRoundStrip({
  positions,
  referencePrice,
  roundId,
}: {
  positions: PublicPositionDto[];
  referencePrice: MarketReferenceDto | null;
  roundId: RoundId;
}) {
  const movers = topMovers(positions, referencePrice, 4);
  return (
    <div className="room-live-strip">
      <div className="room-live-strip__row">
        <span className="muted">
          {positions.length} {positions.length === 1 ? "player" : "players"} in this round
        </span>
        <Link to={`/round/${roundId}`} className="room-live-strip__link">
          See all positions →
        </Link>
      </div>
      {movers.length > 0 && (
        <ul className="room-movers">
          {movers.map((m) => (
            <li key={m.position.playerId} className={`room-mover-chip room-mover-chip--${m.position.direction.toLowerCase()}`}>
              <Avatar name={m.position.player.nickname} className="room-mover-chip__avatar" />
              <BotBadge isBot={m.position.player.isBot} />
              <span className={`room-mover-chip__result mono ${outcomeClassName(outcomeFromNet(m.live))}`}>
                {formatPlayAmount(m.live, { sign: true })}
                {m.position.liquidated && " · liq"}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The signed-in player's own RUNNING position, live P&L and distance to liquidation — same math
 *  `RoundPage.tsx` already uses (`api/liquidation.ts`'s pure functions), condensed for the room's
 *  narrower layout. */
function OwnLivePosition({
  playerId,
  round,
  referencePrice,
}: {
  playerId: string;
  round: Extract<RoundDto, { phase: "RUNNING" }>;
  referencePrice: MarketReferenceDto | null;
}) {
  const own = round.positions.find((p) => p.playerId === playerId);
  if (!own) return null;
  if (own.liquidated) {
    return (
      <div className="card own-outcome own-outcome--liquidated">
        <div className="net-result">
          <span className="outcome-lost">{formatPlayAmount(-Number(positionStakeStr(own)), { sign: true })} PLAY · liquidated</span>
        </div>
        <p className="net-result__secondary">You lost your whole stake.</p>
      </div>
    );
  }
  const side = own.direction === "UP" ? LONG : SHORT;
  const live = referencePrice ? unrealizedPnl(own.size, own.entryPrice, side, referencePrice.price) : null;
  const pctToLiq = referencePrice ? percentToLiquidation(referencePrice.price, own.liquidationPrice) : null;
  const pulseClass = pctToLiq !== null ? liquidationLiveCardClass(pctToLiq) : "";
  return (
    <div className={`card own-outcome ${pulseClass}`}>
      <p className="faint">
        Your position: <DirectionGlyph direction={own.direction} /> {own.direction} · x{own.leverage}
      </p>
      {live ? (
        <div className="net-result">
          <span className={outcomeClassName(outcomeFromNet(live.toNumber()))}>
            <RollingDigits value={live.toNumber()} durationMs={400} format={(n) => `${formatPlayAmount(n, { sign: true })} PLAY`} />
          </span>{" "}
          <span className="net-result__secondary">unrealized</span>
        </div>
      ) : (
        <p className="muted">Your position is live.</p>
      )}
      {pctToLiq !== null && <p className="mono muted">{formatPercent(pctToLiq)} to liquidation</p>}
    </div>
  );
}

function PairRoomComingSoon({ symbol, name }: { symbol: string; name: string }) {
  return (
    <div className="state-card">
      <p className="state-card__title">{`${symbol} — coming soon`}</p>
      <p className="muted">{name} isn't playable yet. BTC is live today.</p>
      <Link to="/" className="btn btn--secondary">
        Back to Lobby
      </Link>
    </div>
  );
}

export function PairRoomPage() {
  const { asset } = useParams<{ asset: string }>();
  const pair = pairById(asset);
  const api = useApi();
  const { player } = useSession();
  const nowMs = useClock(1000);
  const [interval, setInterval_] = useState<"HOURLY" | "DAILY">("HOURLY");
  const [pairData, setPairData] = useState<RoundsPairDto | null>(null);
  const [error, setError] = useState(false);
  const [direction, setDirection] = useState<Direction>("UP");
  const referencePrice = useReferencePrice(POLL_MS);
  // Fix (player review item 3, `docs/product/decisions.md` Р-59/Р-62): the round a player bet on
  // doesn't vanish when its betting window closes — per Р-19's conveyor ("всегда один идущий и
  // один открытый для ставок раунд"), `RoundsPairDto.betting` simply rolls over to the NEXT round
  // the instant the one the player bet on starts running. The arena, bound to `betting` so it can
  // double as the bet slip's direction picker for that next round, used to just silently start
  // showing that brand-new round's fresh, fully bright split — no acknowledgement the player's own
  // round had locked at all (Alex: "на экране телефона я вообще ничего этого не заметил"). This
  // remembers the just-closed round's `myBet` across that rollover so a brief locked overlay can
  // stamp over the arena.
  const [lockedOverlay, setLockedOverlay] = useState<{ direction: Direction } | null>(null);
  // Carries the series (`interval`) alongside the last-seen betting round, purely so the effect
  // below can tell "the round I bet on just rolled over" apart from "the player switched from
  // Hourly to Daily" (two unrelated round-id namespaces) using one comparison — no separate reset
  // needed on an `interval` change, which would mean writing this ref during render (disallowed,
  // `react-hooks/refs` — refs are only ever read/written inside effects/handlers here).
  const prevBettingRef = useRef<{ interval: "HOURLY" | "DAILY"; betting: RoundBetting } | null>(null);
  const lockedOverlayTimeoutRef = useRef<number | null>(null);

  const load = useCallback(() => {
    if (!pair || pair.status !== "live") return;
    api
      .rounds(interval)
      .then((p) => {
        setPairData(p);
        setError(false);
      })
      .catch(() => setError(true));
  }, [api, interval, pair]);

  usePoll(load, POLL_MS, [load]);

  useEffect(() => {
    if (!pairData) return;
    const nextBetting = pairData.betting.phase === "BETTING" ? pairData.betting : null;
    const prev = prevBettingRef.current;
    if (
      prev &&
      prev.interval === interval &&
      nextBetting &&
      prev.betting.roundId !== nextBetting.roundId &&
      prev.betting.myBet
    ) {
      setLockedOverlay({ direction: prev.betting.myBet.direction });
      if (lockedOverlayTimeoutRef.current !== null) window.clearTimeout(lockedOverlayTimeoutRef.current);
      lockedOverlayTimeoutRef.current = window.setTimeout(() => setLockedOverlay(null), 2600);
    }
    prevBettingRef.current = nextBetting ? { interval, betting: nextBetting } : null;
  }, [pairData, interval]);

  useEffect(
    () => () => {
      if (lockedOverlayTimeoutRef.current !== null) window.clearTimeout(lockedOverlayTimeoutRef.current);
    },
    [],
  );

  if (!pair) {
    return (
      <div className="state-card">
        <p className="state-card__title">Unknown market.</p>
        <Link to="/" className="btn btn--secondary">
          Back to Lobby
        </Link>
      </div>
    );
  }
  if (pair.status !== "live") {
    return <PairRoomComingSoon symbol={pair.symbol} name={pair.name} />;
  }
  if (error && !pairData) return <ErrorState onRetry={load} />;
  if (!pairData) {
    return (
      <div className="room-root stack" style={{ gap: 16 }}>
        <Skeleton height={40} />
        <Skeleton height={128} />
        <Skeleton height={220} />
      </div>
    );
  }

  const { running, betting } = pairData;
  const split = crowdSplit(betting.phase === "BETTING" ? betting : running);
  const price = referencePrice ? formatUsd(referencePrice.price) : "—";
  const countdownTargetMs = betting.phase === "BETTING" ? betting.window.startMs : running.window.endMs;
  const countdownPrefix = betting.phase === "BETTING" ? "Closes in " : "Resolves in ";

  return (
    <div className="room-root">
      <div className="room-top">
        <Link to="/" className="room-back" aria-label="Back to lobby">
          ←
        </Link>
        <div className="room-price">
          <span className="room-price__symbol">{pair.symbol}/USD</span>
          <span className="room-price__num mono">{price}</span>
        </div>
        <div className="room-countdown">
          <Countdown nowMs={nowMs} targetMs={countdownTargetMs} prefix={countdownPrefix} />
        </div>
      </div>

      <IntervalTabs value={interval} onChange={setInterval_} />
      <OracleDisclosureLine />

      <div className="room-arena-wrap">
        <div className={`room-arena ${lockedOverlay ? "room-arena--locked" : ""}`}>
          <button
            type="button"
            className={`room-arena__side room-arena__side--up ${direction === "UP" ? "room-arena__side--selected" : ""}`}
            style={{ flexBasis: `${split.upPct}%` }}
            onClick={() => setDirection("UP")}
            disabled={betting.phase !== "BETTING"}
          >
            <DirectionGlyph direction="UP" />
            <span>UP</span>
            {split.total > 0 && <span className="room-arena__pct mono">{split.upPct}%</span>}
          </button>
          <button
            type="button"
            className={`room-arena__side room-arena__side--down ${direction === "DOWN" ? "room-arena__side--selected" : ""}`}
            style={{ flexBasis: `${100 - split.upPct}%` }}
            onClick={() => setDirection("DOWN")}
            disabled={betting.phase !== "BETTING"}
          >
            <DirectionGlyph direction="DOWN" />
            <span>DOWN</span>
            {split.total > 0 && <span className="room-arena__pct mono">{100 - split.upPct}%</span>}
          </button>
        </div>
        {lockedOverlay && (
          <button
            type="button"
            className="room-arena__lock"
            onClick={() => setLockedOverlay(null)}
            aria-label={`Round running — you picked ${lockedOverlay.direction}. Tap to dismiss.`}
          >
            <LockIcon className="room-arena__lock-icon" />
            <span className="room-arena__lock-label">Round running</span>
            <span className={`room-arena__lock-side room-arena__lock-side--${lockedOverlay.direction.toLowerCase()}`}>
              <DirectionGlyph direction={lockedOverlay.direction} /> You picked {lockedOverlay.direction}
            </span>
          </button>
        )}
      </div>

      {betting.phase === "BETTING" ? (
        <>
          <p className="muted room-arena__count">{betting.participantCount} players have voted</p>
          <BetSlip round={betting} nowMs={nowMs} onChanged={load} direction={direction} onDirectionChange={setDirection} hideDirectionPicker />
          {betting.recentBets && betting.recentBets.length > 0 && (
            <>
              <h3 className="room-section-title">Live bets</h3>
              <LiveBetFeed bets={betting.recentBets} />
            </>
          )}
        </>
      ) : (
        <div className="card room-cancelled">
          <h3>This round was cancelled.</h3>
          <p className="muted">{betting.cancellation.reason}</p>
        </div>
      )}

      {running.phase === "RUNNING" && (
        <>
          {player && <OwnLivePosition playerId={player.playerId} round={running} referencePrice={referencePrice} />}
          <h3 className="room-section-title">Crowd account</h3>
          <CrowdPositionCard phase="RUNNING" crowd={running.crowd} referencePrice={referencePrice} />
          <h3 className="room-section-title">Live round</h3>
          <LiveRoundStrip positions={running.positions} referencePrice={referencePrice} roundId={running.roundId} />
        </>
      )}
      {running.phase === "SETTLED" && <p className="muted">Settled.</p>}

      <p style={{ marginTop: 16 }}>
        <Link to={`/round/${running.roundId}`}>View full round →</Link>
      </p>
    </div>
  );
}
