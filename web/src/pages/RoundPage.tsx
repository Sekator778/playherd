import { useCallback, useState } from "react";
import { useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import { useClock } from "../api/useClock";
import { useReferencePrice } from "../api/useReferencePrice";
import type { RoundDto } from "../api/contract";
import { LONG, SHORT, percentToLiquidation, unrealizedPnl } from "../api/liquidation";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { BetSlip } from "../components/BetSlip";
import {
  Countdown,
  ErrorState,
  OracleDisclosureLine,
  ResultsHashLine,
  RoundStatusBadge,
  Skeleton,
} from "../components/Common";
import { CancellationNotice, CrowdPositionCard, PositionsList } from "../components/RoundParts";
import { formatPercent, formatPlayAmount, formatUsd } from "../format";

const POLL_MS = 2000;

export function RoundPage() {
  const { roundId } = useParams<{ roundId: string }>();
  const api = useApi();
  const { player } = useSession();
  const nowMs = useClock(1000);
  const [round, setRound] = useState<RoundDto | null>(null);
  const [error, setError] = useState(false);
  // Live reference price for the RUNNING phase's unrealized P&L / % to liquidation (task: "live
  // unrealized P&L ... updating every poll") — same interval as the round poll below.
  const referencePrice = useReferencePrice(POLL_MS);

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

  if (round.phase === "CANCELLED") {
    return <CancellationNotice cancellation={round.cancellation} resultsHash={round.resultsHash} roundId={round.roundId} />;
  }

  const ownPosition =
    round.phase !== "BETTING" && player ? round.positions.find((p) => p.playerId === player.playerId) : undefined;

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="stack" style={{ gap: 8 }}>
        <RoundStatusBadge phase={round.phase} />
        {round.phase === "BETTING" && (
          <Countdown nowMs={nowMs} targetMs={round.window.startMs} prefix="Closes in " />
        )}
        {round.phase === "RUNNING" && <Countdown nowMs={nowMs} targetMs={round.window.endMs} prefix="Resolves in " />}
        {round.phase === "SETTLED" && (
          <h2>
            BTC closed{" "}
            {Number(round.closePrice) === Number(round.openPrice)
              ? "FLAT"
              : Number(round.closePrice) > Number(round.openPrice)
                ? "UP"
                : "DOWN"}{" "}
            — {formatUsd(round.openPrice)} → {formatUsd(round.closePrice)}
          </h2>
        )}
      </div>

      {ownPosition &&
        (() => {
          // Net result (>0 won, <0 lost, =0 even) headline, never `payout > 0` alone (Alex's
          // report, 2026-09-25 — see `api/outcome.ts`). `stake` prefers the server-sent exact
          // value, falling back to a derivation only if it's absent.
          const stake = positionStakeStr(ownPosition);
          if (ownPosition.liquidated) {
            const net = -Number(stake);
            return (
              <div className="card own-outcome own-outcome--liquidated">
                <div className="net-result">
                  <span className="outcome-lost">
                    {formatPlayAmount(net, { decimals: 2, sign: true })} PLAY · liquidated
                  </span>
                </div>
                <p className="net-result__secondary">You lost your whole stake.</p>
              </div>
            );
          }
          if (round.phase === "SETTLED" && ownPosition.payout !== null) {
            const net = netFromPayoutAndStake(ownPosition.payout, stake);
            return (
              <div className="card own-outcome">
                <div className="net-result">
                  <span className={outcomeClassName(outcomeFromNet(net))}>
                    {formatPlayAmount(net, { decimals: 2, sign: true })} PLAY
                  </span>
                </div>
                <p className="net-result__secondary">
                  stake {formatPlayAmount(stake, { decimals: 0 })} → paid{" "}
                  {formatPlayAmount(ownPosition.payout, { decimals: 2 })}
                </p>
              </div>
            );
          }
          // RUNNING, not liquidated: live unrealized P&L from the current reference price
          // (task: "so players can see they are winning" — previously just "Your position is
          // live.", with no number at all).
          const side = ownPosition.direction === "UP" ? LONG : SHORT;
          const live = referencePrice
            ? unrealizedPnl(ownPosition.size, ownPosition.entryPrice, side, referencePrice.price)
            : null;
          const pctToLiq = referencePrice
            ? percentToLiquidation(referencePrice.price, ownPosition.liquidationPrice)
            : null;
          return (
            <div className="card own-outcome">
              {live ? (
                <div className="net-result">
                  <span className={outcomeClassName(outcomeFromNet(live.toNumber()))}>
                    {formatPlayAmount(live.toNumber(), { decimals: 2, sign: true })} PLAY
                  </span>{" "}
                  <span className="net-result__secondary">unrealized</span>
                </div>
              ) : (
                <p className="muted">Your position is live.</p>
              )}
              {pctToLiq !== null && <p className="mono muted">{formatPercent(pctToLiq)} to liquidation</p>}
            </div>
          );
        })()}

      <div className="two-col">
        <div className="stack" style={{ gap: 16 }}>
          {round.phase === "BETTING" && (
            <>
              <p>{round.participantCount} players have voted</p>
              <BetSlip
                round={round}
                nowMs={nowMs}
                onChanged={load}
                noPlayerHint="You can still watch this round without one."
              />
            </>
          )}
          {(round.phase === "RUNNING" || round.phase === "SETTLED") && (
            <>
              <p className="mono">Open price: {formatUsd(round.openPrice)}</p>
              <OracleDisclosureLine />
              {round.phase === "SETTLED" && <ResultsHashLine roundId={round.roundId} resultsHash={round.resultsHash} />}
              <h3>All positions</h3>
              <PositionsList
                positions={round.positions}
                settled={round.phase === "SETTLED"}
                referencePrice={referencePrice}
              />
            </>
          )}
        </div>
        <div className="stack" style={{ gap: 16 }}>
          {round.phase !== "BETTING" && (
            <CrowdPositionCard phase={round.phase} crowd={round.crowd} referencePrice={referencePrice} />
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
