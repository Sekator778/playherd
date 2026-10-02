import { useCallback, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import { useClock } from "../api/useClock";
import { useReferencePrice } from "../api/useReferencePrice";
import { useMarket } from "../api/useMarkets";
import { useMyPositions } from "../api/useMyPositions";
import type { RoundDto } from "../api/contract";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { AnchorLine } from "../components/AnchorLine";
import { BetSlip } from "../components/BetSlip";
import { Countdown, ErrorState, OracleDisclosureLine, RoundStatusBadge, Skeleton } from "../components/Common";
import { MarketIcon } from "../components/MarketIcon";
import { OwnPositionCard } from "../components/OwnPositionCard";
import { CancellationNotice, CrowdPositionCard, PositionsList } from "../components/RoundParts";
import { OwnBetWaiting, WaitingForOpenCard } from "../components/WaitingForOpen";
import { formatDuration, formatPlayAmount, formatPrice, type PriceFormatMarket } from "../format";
import "./RoundPage.css";

const POLL_MS = 2000;

/** Shown while the market list has not arrived: plain USD, as for BTC. */
const FALLBACK_PRICE_MARKET: PriceFormatMarket = { kind: "PERP", priceDecimals: 2 };

export function RoundPage() {
  const { roundId } = useParams<{ roundId: string }>();
  const api = useApi();
  const { player } = useSession();
  const nowMs = useClock(1000);
  const [round, setRound] = useState<RoundDto | null>(null);
  const [error, setError] = useState(false);
  // The round's market: its price scale drives every price and P&L figure below.
  const { market } = useMarket(round?.marketId);
  // Live reference price for the RUNNING phase's unrealized P&L / % to liquidation (task: "live
  // unrealized P&L ... updating every poll") — same interval as the round poll below.
  const referencePrice = useReferencePrice(round?.marketId, POLL_MS);
  // The player's own bets: while a round has no opening price yet its public `positions` list is empty, and
  // `myPositions()` is the only place that still knows the player is in it.
  const { positions: myPositions } = useMyPositions();
  const priceText = (price: string) => formatPrice(price, market ?? FALLBACK_PRICE_MARKET);

  // Reset to loading only when navigating to a *different* round (during render, not in an
  // effect) — not on every tick's refetch of the same round, which would flash the skeleton
  // every second.
  const [lastRoundId, setLastRoundId] = useState(roundId);
  if (roundId !== lastRoundId) {
    setLastRoundId(roundId);
    setRound(null);
    setError(false);
  }

  const load = useCallback(() => {
    if (!roundId) return;
    api
      .round(roundId)
      .then((r) => {
        setRound(r);
        setError(false);
      })
      .catch(() => setError(true));
  }, [api, roundId]);

  usePoll(load, POLL_MS, [load]);

  if (error && !round) return <ErrorState onRetry={load} />;
  if (!round) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={40} width={160} />
        <Skeleton height={240} />
      </div>
    );
  }

  // Which market and how long a round: the round's own window says the length (never "Hourly"/"Daily").
  const length = formatDuration((round.window.endMs - round.window.startMs) / 1000);

  if (round.phase === "CANCELLED") {
    return (
      <CancellationNotice
        cancellation={round.cancellation}
        resultsHash={round.resultsHash}
        roundId={round.roundId}
        context={`${round.asset} · ${length}`}
      />
    );
  }

  const ownPosition =
    round.phase !== "BETTING" && player ? round.positions.find((p) => p.playerId === player.playerId) : undefined;
  // A RUNNING round the source has not given an opening price for yet: no positions, no crowd position, no
  // open price — one calm notice instead (`RoundRunning.openPrice`).
  const waitingOpen = round.phase === "RUNNING" && round.openPrice === null;
  const ownWaiting = waitingOpen && player ? myPositions.find((p) => p.roundId === round.roundId) : undefined;

  // Round-start reveal (live-round.md §3): the hero card and the positions stage below both play
  // a one-shot flip-in the moment they first mount in a post-BETTING shape — a `key` that only
  // changes once, at the BETTING→RUNNING transition, forces that mount (React would otherwise
  // keep reusing the same DOM node across phases and never replay the entrance).
  const revealedKey = round.phase === "BETTING" ? "betting" : "revealed";

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div key={`hero-${revealedKey}`} className={`card card--hero stack ${revealedKey === "revealed" ? "reveal-flip-in" : ""}`} style={{ gap: 8 }}>
        <div className="round-head">
          <Link to={`/play/${encodeURIComponent(round.marketId)}`} className="round-head__market" aria-label={`${round.asset}, ${length} round — open the market`}>
            {market && <MarketIcon market={market} size={28} />}
            <strong>{round.asset}</strong>
            <span className="round-head__length">· {length}</span>
          </Link>
          <RoundStatusBadge phase={round.phase} />
        </div>
        {round.phase === "BETTING" && (
          <Countdown nowMs={nowMs} targetMs={round.window.startMs} prefix="Closes in " />
        )}
        {round.phase === "RUNNING" && <Countdown nowMs={nowMs} targetMs={round.window.endMs} prefix="Resolves in " />}
        {round.phase === "SETTLED" && (
          <h2>
            {round.asset} closed{" "}
            {Number(round.closePrice) === Number(round.openPrice)
              ? "FLAT"
              : Number(round.closePrice) > Number(round.openPrice)
                ? "UP"
                : "DOWN"}{" "}
            — {priceText(round.openPrice)} → {priceText(round.closePrice)}
          </h2>
        )}
      </div>

      {ownWaiting && <OwnBetWaiting position={ownWaiting} hero />}

      {ownPosition &&
        (round.phase === "SETTLED" && ownPosition.payout !== null && !ownPosition.liquidated ? (
          // Net result (>0 won, <0 lost, =0 even) headline, never `payout > 0` alone (Alex's report,
          // 2026-09-25 — see `api/outcome.ts`). `stake` prefers the server-sent exact value.
          (() => {
            const stake = positionStakeStr(ownPosition);
            const net = netFromPayoutAndStake(ownPosition.payout, stake);
            return (
              <div className="card card--hero own-outcome">
                <div className="net-result">
                  <span className={outcomeClassName(outcomeFromNet(net))}>{formatPlayAmount(net, { sign: true })} PLAY</span>
                </div>
                <p className="net-result__secondary">
                  stake {formatPlayAmount(stake)} → paid {formatPlayAmount(ownPosition.payout)}
                </p>
              </div>
            );
          })()
        ) : (
          // RUNNING: live unrealized P&L, the room left before liquidation (pulsing as it closes in); a
          // liquidated position (running or settled) reads as the whole stake gone (Р-7).
          <OwnPositionCard position={ownPosition} referencePrice={referencePrice} market={market} hero />
        ))}

      <div className="two-col">
        <div className="stack" style={{ gap: 16 }}>
          {round.phase === "BETTING" && (
            <>
              <p>{round.participantCount} players have voted</p>
              {/* Р-62 (replaces Р-22): the crowd split is now live during betting, not just at
               *  round start — optional fields, so this line simply doesn't render against a
               *  `RealApi` build that predates Р-62. */}
              {round.upStake !== undefined && round.downStake !== undefined && (Number(round.upStake) > 0 || Number(round.downStake) > 0) && (
                <p className="mono muted">
                  {Math.round((Number(round.upStake) / (Number(round.upStake) + Number(round.downStake))) * 100)}% UP ·{" "}
                  {Math.round((Number(round.downStake) / (Number(round.upStake) + Number(round.downStake))) * 100)}% DOWN
                </p>
              )}
              <BetSlip
                round={round}
                nowMs={nowMs}
                onChanged={load}
                noPlayerHint="You can still watch this round without one."
              />
            </>
          )}
          {waitingOpen && (
            <div className="stack" style={{ gap: 16 }}>
              {/* The player's own bet (above) already says it; one notice is enough. */}
              {!ownWaiting && <WaitingForOpenCard symbol={round.asset} length={length} />}
              <OracleDisclosureLine source={market?.source} />
            </div>
          )}
          {(round.phase === "SETTLED" || (round.phase === "RUNNING" && !waitingOpen)) && (
            <div className="stack reveal-flip-in" style={{ gap: 16 }}>
              {round.openPrice !== null && <p className="mono">Open price: {priceText(round.openPrice)}</p>}
              <OracleDisclosureLine source={market?.source} />
              {round.phase === "SETTLED" && (
                <AnchorLine
                  roundId={round.roundId}
                  resultsHash={round.resultsHash}
                  anchor={round.oracleAnchor}
                  symbol={round.asset}
                  oracleIndex={market?.oracleIndex}
                />
              )}
              <h3>All positions</h3>
              <PositionsList
                positions={round.positions}
                settled={round.phase === "SETTLED"}
                referencePrice={referencePrice}
                market={market}
              />
            </div>
          )}
        </div>
        <div className="stack" style={{ gap: 16 }}>
          {round.phase !== "BETTING" && !waitingOpen && (
            <CrowdPositionCard phase={round.phase} crowd={round.crowd} referencePrice={referencePrice} market={market} />
          )}
          {round.phase === "BETTING" && (
            <div className="card">
              <p className="muted">The crowd's position is revealed when this round starts.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
