/**
 * Pair room (`/play/:marketId` — a market id such as `BTC`, `xyz_NVDA`, `PM900104`, matched ignoring case so
 * an old `/play/btc` link still lands) — concept A's arena (Р-61, `docs/product/design/concepts.md`) for any
 * of the markets (Р-69), with what Herd adds on top of the reference (Р-71,
 * `docs/product/design/markets-lobby.md`):
 *
 * - the live price (rolling digits) with its 24 h change, a favourite star, and a live price chart with the
 *   round's open price and — over a position — entry and liquidation lines, the herd's as a fainter pair;
 *   a crypto market says which exchange its price is from when that is its BACKUP ("Kraken price", Р-76: the main
 *   exchange has no fresh price), names the backup in the oracle line, and a running round that runs on the backup
 *   says so (a round runs entirely on one exchange, chosen when it starts);
 * - the two series of rounds as chips named by their real length, a dot on the one where the player has bets;
 * - the arena (UP/DOWN halves as wide as the live split), «With the herd» / «Against the herd» and the bet
 *   slip with the leverage dial capped at the market's own maximum;
 * - for an `ODDS` market, one honest line: you bet on how the odds move, not on who wins;
 * - a RUNNING round: own position with live P&L and the distance to liquidation, the crowd's position, the
 *   biggest movers — or, while the round has no opening price yet (`RoundRunning.openPrice === null`), one calm
 *   "Waiting for the opening price…" in their place, with no figure at all; a closed market gets its own state
 *   (the server's reason as it sent it, `closeReason`);
 * - an `ODDS` market's 24 h change in percentage points; a sports game's "Starts in 2h 05m" / LIVE mark.
 *
 * `?side=up|down` (from a lobby card's UP/DOWN) preselects the direction, `?series=daily` the longer series.
 * Two round contexts, as before (Р-19: "всегда один идущий и один открытый для ставок раунд"): `betting`
 * (the arena and slip act on this one) and `running` (revealed positions, the crowd's live position).
 * `result-moment.md`'s fx are wired globally in `Layout.tsx` (`ResultMomentHost`), not here.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { isEngineNotReady } from "../api/apiErrors";
import { usePoll } from "../api/usePoll";
import { useReferencePrice } from "../api/useReferencePrice";
import { useMarket } from "../api/useMarkets";
import { useMyPositions } from "../api/useMyPositions";
import { usePriceSeries } from "../api/usePriceSeries";
import { useFavorites } from "../api/favorites";
import type {
  Direction,
  Interval,
  LiveBetDto,
  MarketDto,
  MarketReferenceDto,
  PublicPositionDto,
  RoundDto,
  RoundId,
  RoundsPairDto,
} from "../api/contract";
import { LONG, SHORT, unrealizedPnl } from "../api/liquidation";
import { outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { BetSlip } from "../components/BetSlip";
import { BotBadge, Countdown, ErrorState, OracleDisclosureLine, RoundVenueNote, Skeleton } from "../components/Common";
import { CrowdPositionCard, IntervalTabs } from "../components/RoundParts";
import { Avatar } from "../components/Avatar";
import { ArrowLeftIcon, DirectionGlyph, LockIcon, StarIcon } from "../components/icons";
import { OwnPositionCard } from "../components/OwnPositionCard";
import { MarketIcon } from "../components/MarketIcon";
import { MarketRail } from "../components/MarketRail";
import { PriceChart } from "../components/PriceChart";
import { buildChartLines } from "../components/priceChartModel";
import { formatStartsIn, trend, TREND_GLYPH } from "../components/marketFormat";
import { rememberMarket } from "../components/lastMarket";
import {
  backupPriceNote,
  backupPriceOf,
  crowdSplitOf,
  isOpenMarket,
  kickoffOf,
  oddsMarketPhrase,
  subtitleOf,
  type CrowdSplit,
} from "../components/marketModel";
import { OwnBetWaiting, WaitingForOpenCard } from "../components/WaitingForOpen";
import { useServerNow } from "../components/useServerNow";
import { RollingDigits } from "../fx/RollingDigits";
import { formatDateTimeUtc, formatDuration, formatPlayAmount, formatPrice, market24hMove } from "../format";
import "./PairRoomPage.css";

const POLL_MS = 2000;

/** The live crowd split (Р-62) of a round, by stake — from the BETTING round's `upStake`/`downStake`, or,
 *  once revealed, from the RUNNING round's own positions (the same fact, later in the round's life).
 *  `null` when there is no stake yet or the optional fields are absent (a server that predates Р-62):
 *  the arena then draws an even split without percentages — never a fabricated lean. */
function roundSplit(round: RoundDto): CrowdSplit | null {
  if (round.phase === "BETTING") return crowdSplitOf(round);
  if (round.phase === "RUNNING" || round.phase === "SETTLED") {
    let up = 0;
    let down = 0;
    for (const p of round.positions) {
      const stake = Number(positionStakeStr(p));
      if (p.direction === "UP") up += stake;
      else down += stake;
    }
    return crowdSplitOf({ upStake: String(up), downStake: String(down) });
  }
  return null;
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
function topMovers(
  positions: PublicPositionDto[],
  referencePrice: MarketReferenceDto | null,
  count: number,
  market: MarketDto,
): Mover[] {
  const withLive: Mover[] = [];
  for (const p of positions) {
    if (p.liquidated) {
      withLive.push({ position: p, live: -Number(positionStakeStr(p)) });
      continue;
    }
    if (!referencePrice) continue;
    const live = unrealizedPnl(p.size, p.entryPrice, p.direction === "UP" ? LONG : SHORT, referencePrice.price, market);
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
  market,
}: {
  positions: PublicPositionDto[];
  referencePrice: MarketReferenceDto | null;
  roundId: RoundId;
  market: MarketDto;
}) {
  const movers = topMovers(positions, referencePrice, 4, market);
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

/** "←" — one step back through the app's own history when there is one (the lobby on the panel the player
 *  came from), else to the market's own panel of the lobby. */
function BackLink({ fallback }: { fallback: string }) {
  const navigate = useNavigate();
  const { key } = useLocation();
  return (
    <Link
      to={fallback}
      className="room-back"
      aria-label="Back"
      onClick={(e) => {
        if (key !== "default") {
          e.preventDefault();
          navigate(-1);
        }
      }}
    >
      <ArrowLeftIcon />
    </Link>
  );
}

function FavoriteButton({ market }: { market: MarketDto }) {
  const { isFavorite, toggle } = useFavorites();
  const on = isFavorite(market.id);
  return (
    <button
      type="button"
      className="room-star"
      aria-pressed={on}
      aria-label={on ? `Remove ${market.symbol} from favorites` : `Add ${market.symbol} to favorites`}
      onClick={() => toggle(market.id)}
    >
      <StarIcon filled={on} />
    </button>
  );
}

function RoomSkeleton() {
  return (
    <div className="room-root stack" style={{ gap: 16 }}>
      <Skeleton height={40} />
      <Skeleton height={56} />
      <Skeleton height={224} />
      <Skeleton height={128} />
    </div>
  );
}

export function PairRoomPage() {
  const { marketId } = useParams<{ marketId: string }>();
  const { market, loading, error, refresh } = useMarket(marketId);

  if (!market) {
    if (loading && error) return <ErrorState onRetry={refresh} />;
    if (loading) return <RoomSkeleton />;
    return (
      <div className="state-card">
        <p className="state-card__title">Unknown market.</p>
        <Link to="/" className="btn btn--secondary">
          Back to Lobby
        </Link>
      </div>
    );
  }
  // Keyed by market: moving to another room (a card of the rail, an old link) starts it from scratch —
  // no rounds, direction or chart of the market it came from.
  return <Room key={market.id} market={market} />;
}

function Room({ market }: { market: MarketDto }) {
  const marketId = market.id;
  const api = useApi();
  const { player } = useSession();
  const [searchParams] = useSearchParams();
  const nowMs = useServerNow(1000);
  const seriesParam = searchParams.get("series");
  const sideParam = searchParams.get("side");
  const [interval, setInterval_] = useState<Interval>(() => (seriesParam?.toLowerCase() === "daily" ? "DAILY" : "HOURLY"));
  const [direction, setDirection] = useState<Direction>(() => (sideParam?.toLowerCase() === "down" ? "DOWN" : "UP"));
  // A link into THIS room with another series or side (a card of the positions strip while the room is
  // already open) must take effect — the room stays mounted when only the query string changes. Done
  // during render, the documented "adjust state when a prop changes" pattern, and only when the param
  // itself changes, so the player's own taps on the chips and the arena are never overridden.
  const [seenParams, setSeenParams] = useState({ series: seriesParam, side: sideParam });
  if (seenParams.series !== seriesParam || seenParams.side !== sideParam) {
    setSeenParams({ series: seriesParam, side: sideParam });
    if (seenParams.series !== seriesParam && seriesParam) setInterval_(seriesParam.toLowerCase() === "daily" ? "DAILY" : "HOURLY");
    if (seenParams.side !== sideParam && sideParam) setDirection(sideParam.toLowerCase() === "down" ? "DOWN" : "UP");
  }
  const [pairData, setPairData] = useState<RoundsPairDto | null>(null);
  const [error, setError] = useState(false);
  // A closed market has no price to read (the server answers 503 `PRICE_UNAVAILABLE` for it, every two seconds, in the console):
  // its room shows the last price from the list and asks for nothing.
  const referencePrice = useReferencePrice(isOpenMarket(market) ? marketId : null, POLL_MS);
  const series = usePriceSeries(marketId);
  const { positions: myPositions } = useMyPositions();

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
  // one series to the other" (two unrelated round-id namespaces) using one comparison — no separate
  // reset needed on an `interval` change, which would mean writing this ref during render (disallowed,
  // `react-hooks/refs` — refs are only ever read/written inside effects/handlers here).
  const prevBettingRef = useRef<{ interval: Interval; betting: Extract<RoundsPairDto["betting"], { phase: "BETTING" }> } | null>(null);
  const lockedOverlayTimeoutRef = useRef<number | null>(null);

  const load = useCallback(() => {
    api
      .rounds(interval, marketId)
      .then((p) => {
        setPairData(p);
        setError(false);
      })
      // A restarting server (503) is not a failure of the room: the rounds shown stay (or the skeleton does), the poll
      // asks again in two seconds and the banner says why.
      .catch((err) => {
        if (!isEngineNotReady(err)) setError(true);
      });
  }, [api, interval, marketId]);

  usePoll(load, POLL_MS, [load]);

  useEffect(() => {
    if (!pairData) return;
    const nextBetting = pairData.betting.phase === "BETTING" ? pairData.betting : null;
    const prev = prevBettingRef.current;
    if (prev && prev.interval === interval && nextBetting && prev.betting.roundId !== nextBetting.roundId && prev.betting.myBet) {
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

  // Stats opens on the market the player came from.
  useEffect(() => rememberMarket(marketId), [marketId]);

  const open = isOpenMarket(market);
  const odds = market.kind === "ODDS";
  const running = pairData?.running;
  const betting = pairData?.betting;

  // The chart's horizontal lines: the running round's open price (every bet's entry, Р-8), the player's
  // own position and the crowd's — liquidation levels included. Only what the chosen series has.
  const runningRound = running?.phase === "RUNNING" ? running : null;
  const ownPosition = runningRound && player ? runningRound.positions.find((p) => p.playerId === player.playerId) : undefined;
  // No opening price yet: nothing of the running round can be drawn or computed — no line, no round-start
  // marker (it marks where the open price was fixed), no positions, no herd position.
  const waitingOpen = runningRound !== null && runningRound.openPrice === null;
  // The player's bet in such a round is known to `myPositions()` only — the round's own list is empty.
  const ownWaiting = waitingOpen && player ? myPositions.find((p) => p.roundId === runningRound.roundId) : undefined;
  const lines = useMemo(
    () =>
      open && runningRound
        ? buildChartLines({
            openPrice: runningRound.openPrice,
            own: ownPosition && !ownPosition.liquidated ? ownPosition : null,
            crowd: runningRound.crowd,
          })
        : [],
    [open, runningRound, ownPosition],
  );

  // How many of the player's bets are open per series — the dot on a series chip.
  const openCounts = useMemo(() => {
    const counts: Partial<Record<Interval, number>> = {};
    for (const p of myPositions) if (p.marketId === marketId) counts[p.interval] = (counts[p.interval] ?? 0) + 1;
    return counts;
  }, [myPositions, marketId]);

  const seriesLabel = (which: Interval) => {
    const s = market.series.find((x) => x.interval === which);
    return s ? formatDuration(s.durationSec) : undefined;
  };

  const current = referencePrice ?? (market.price ? { price: market.price.price, asOfMs: market.price.asOfMs } : null);
  const move = market24hMove(market, market.price);
  const changeTrend = trend(move.value);
  const kickoff = kickoffOf(market, nowMs);
  const stale = open && market.price?.stale === true;
  // The shown price is the backup exchange's while the main one has no fresh price (`MarketPriceDto.venue`, Р-76).
  const backupPrice = open ? backupPriceOf(market) : null;
  const symbolText = market.kind === "PERP" ? `${market.symbol}/USD` : market.symbol;

  const header = (
    <>
      <header className="room-head">
        <BackLink fallback={`/?c=${market.category}`} />
        <MarketIcon market={market} size={40} />
        <div className="room-head__id">
          <div className="room-head__name">
            <h1 className="room-head__symbol">{symbolText}</h1>
            {odds && (
              <span className="mk-tag mk-tag--odds" title={`${oddsMarketPhrase(market.source)}: the price is a probability`}>
                ODDS
              </span>
            )}
            {kickoff?.state === "live" && (
              <span className="mk-tag mk-tag--live" title="The game has kicked off">
                <span className="mk-live-dot" aria-hidden="true" /> LIVE
              </span>
            )}
            {!open && <span className="mk-tag">CLOSED</span>}
          </div>
          <p className="room-head__title">{market.title}</p>
          {subtitleOf(market) && <p className="room-head__sub">{subtitleOf(market)}</p>}
          {kickoff?.state === "upcoming" && (
            <p className="room-head__sub room-head__kickoff">
              Starts in <span className="mono">{formatStartsIn(kickoff.inMs)}</span>
            </p>
          )}
        </div>
        <FavoriteButton market={market} />
      </header>

      <div className="room-pricebar">
        <div className="room-pricebar__main">
          {current ? (
            <RollingDigits className="room-price__num mono" value={Number(current.price)} format={(n) => formatPrice(n, market)} durationMs={450} />
          ) : (
            <span className="room-price__num room-price__num--none">—</span>
          )}
          <span className={`room-change room-change--${changeTrend} mono`}>
            <span aria-hidden="true">{TREND_GLYPH[changeTrend]}</span>{" "}
            <span className="visually-hidden">{move.unit === "pt" ? "24 hour change in percentage points " : "24 hour change "}</span>
            {move.text} <span className="room-change__unit">24h</span>
          </span>
          {stale && <span className="room-stale">Price is stale</span>}
          {backupPrice && (
            <span className="room-venue" title={backupPriceNote(backupPrice)}>
              {backupPrice.name} price
              <span className="visually-hidden"> — {backupPriceNote(backupPrice)}</span>
            </span>
          )}
          {!open && <span className="room-stale">Last price</span>}
        </div>
      </div>

      {odds && open && (
        <p className="room-odds-note" role="note">
          You bet on how the odds move during the round — not on who wins. UP: the odds end the round higher than they started;
          DOWN: lower.
        </p>
      )}
    </>
  );

  const chart = (
    <PriceChart
      market={market}
      ticks={series.ticks}
      current={current}
      lines={lines}
      roundStartMs={runningRound && !waitingOpen ? runningRound.window.startMs : null}
      loading={series.loading}
      error={series.error}
      stale={stale}
      live={open}
      backup={backupPrice}
    />
  );

  if (!open) {
    return (
      <div className="room-root stack" style={{ gap: 16 }}>
        {header}
        {chart}
        <OracleDisclosureLine source={market.source} backup={market.backupSource} />
        <div className="card room-closed">
          <div className="room-closed__title">
            <LockIcon />
            <h2>This market is closed</h2>
          </div>
          <p>
            It takes no more bets. Any bet that was still waiting for its round, or in a round that had not ended, was cancelled and{" "}
            <strong>refunded in full</strong>; rounds that had already ended were settled as usual.
          </p>
          {/* Why, in the server's own words when it gave them (`closeReason`); else the closed round's reason, if any. */}
          {market.closeReason ? (
            <p className="room-closed__reason">
              <strong>{market.closeReason}</strong>
            </p>
          ) : (
            betting && betting.phase === "CANCELLED" && <p className="muted">{betting.cancellation.reason}</p>
          )}
          {market.closedAtMs ? <p className="faint room-closed__when">Closed {formatDateTimeUtc(market.closedAtMs)}</p> : null}
          <div className="room-closed__actions">
            <Link to={`/?c=${market.category}`} className="btn btn--primary">
              Open markets in this panel
            </Link>
            <Link to={`/stats?m=${encodeURIComponent(market.id)}`} className="btn btn--secondary">
              How the herd did here
            </Link>
          </div>
        </div>
        <MarketRail market={market} />
      </div>
    );
  }

  if (error && !pairData) {
    return (
      <div className="room-root stack" style={{ gap: 16 }}>
        {header}
        <ErrorState onRetry={load} />
      </div>
    );
  }
  if (!pairData || !running || !betting) {
    return (
      <div className="room-root stack" style={{ gap: 16 }}>
        {header}
        {chart}
        <Skeleton height={128} />
        <Skeleton height={220} />
      </div>
    );
  }

  const split = roundSplit(betting.phase === "BETTING" ? betting : running);
  const upPct = split ? split.upPct : 50;

  return (
    <div className="room-root">
      {header}
      {chart}

      {runningRound && player && ownPosition && (
        <div className="room-own">
          <OwnPositionCard position={ownPosition} referencePrice={referencePrice} market={market} />
        </div>
      )}
      {ownWaiting && (
        <div className="room-own">
          <OwnBetWaiting position={ownWaiting} />
        </div>
      )}

      <div className="room-series">
        <IntervalTabs
          value={interval}
          onChange={setInterval_}
          labels={{ HOURLY: seriesLabel("HOURLY"), DAILY: seriesLabel("DAILY") }}
          openCounts={openCounts}
        />
        <div className="room-countdown">
          <Countdown
            nowMs={nowMs}
            targetMs={betting.phase === "BETTING" ? betting.window.startMs : running.window.endMs}
            prefix={betting.phase === "BETTING" ? "Closes in " : "Resolves in "}
          />
        </div>
      </div>
      <OracleDisclosureLine source={market.source} backup={market.backupSource} />
      {(running.phase === "RUNNING" || running.phase === "SETTLED") && <RoundVenueNote source={running.source} market={market} phase={running.phase} />}

      <div className="room-arena-wrap">
        <div className={`room-arena ${lockedOverlay ? "room-arena--locked" : ""}`}>
          <button
            type="button"
            className={`room-arena__side room-arena__side--up ${direction === "UP" ? "room-arena__side--selected" : ""}`}
            style={{ flexBasis: `${upPct}%` }}
            onClick={() => setDirection("UP")}
            disabled={betting.phase !== "BETTING"}
            aria-pressed={direction === "UP"}
          >
            <DirectionGlyph direction="UP" />
            <span>UP</span>
            {split && <span className="room-arena__pct mono">{split.upPct}%</span>}
          </button>
          <button
            type="button"
            className={`room-arena__side room-arena__side--down ${direction === "DOWN" ? "room-arena__side--selected" : ""}`}
            style={{ flexBasis: `${100 - upPct}%` }}
            onClick={() => setDirection("DOWN")}
            disabled={betting.phase !== "BETTING"}
            aria-pressed={direction === "DOWN"}
          >
            <DirectionGlyph direction="DOWN" />
            <span>DOWN</span>
            {split && <span className="room-arena__pct mono">{split.downPct}%</span>}
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
          <p className="muted room-arena__count">
            {betting.participantCount} {betting.participantCount === 1 ? "player has" : "players have"} voted
            {betting.upCount !== undefined && betting.downCount !== undefined && (
              <>
                {" "}
                · <span className="room-arena__count-up">{betting.upCount} UP</span> / <span className="room-arena__count-down">{betting.downCount} DOWN</span>
              </>
            )}
          </p>
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

      {running.phase === "RUNNING" &&
        (waitingOpen ? (
          // The player's own bet (above) already says it; one notice is enough.
          ownWaiting ? null : <WaitingForOpenCard symbol={market.symbol} length={seriesLabel(interval)} />
        ) : (
          <>
            <h3 className="room-section-title">Crowd account</h3>
            <CrowdPositionCard phase="RUNNING" crowd={running.crowd} referencePrice={referencePrice} market={market} />
            <h3 className="room-section-title">Live round</h3>
            <LiveRoundStrip positions={running.positions} referencePrice={referencePrice} roundId={running.roundId} market={market} />
          </>
        ))}
      {running.phase === "SETTLED" && <p className="muted">Settled.</p>}

      <p className="room-links">
        <Link to={`/round/${running.roundId}`}>View full round →</Link>
        <Link to={`/stats?m=${encodeURIComponent(market.id)}`}>How the herd is doing on {market.symbol} →</Link>
      </p>

      <MarketRail market={market} />
    </div>
  );
}
