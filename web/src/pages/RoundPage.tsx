import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import type { RoundDto } from "../api/contract";
import { BetSlip } from "../components/BetSlip";
import { Countdown, ErrorState, OracleDisclosureLine, RoundStatusBadge, Skeleton } from "../components/Common";
import { CancellationNotice, CrowdPositionCard, PositionsList } from "../components/RoundParts";
import { formatPlayAmount, formatUsd } from "../format";

export function RoundPage() {
  const { roundId } = useParams<{ roundId: string }>();
  const api = useApi();
  const { player } = useSession();
  const nowMs = useClock(1000);
  const [round, setRound] = useState<RoundDto | null>(null);
  const [error, setError] = useState(false);

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

  useEffect(() => {
    load();
  }, [load, nowMs]);

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
    return <CancellationNotice cancellation={round.cancellation} />;
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

      {ownPosition && (
        <div className={`card ${ownPosition.liquidated ? "own-outcome own-outcome--liquidated" : "own-outcome"}`}>
          {ownPosition.liquidated ? (
            <p className="outcome-liquidated">LIQUIDATED — you lost your whole stake.</p>
          ) : round.phase === "SETTLED" && ownPosition.payout !== null ? (
            Number(ownPosition.payout) > 0 ? (
              <p className="outcome-won">You won {formatPlayAmount(ownPosition.payout, { decimals: 0 })} PLAY</p>
            ) : (
              <p className="outcome-lost">You lost your stake</p>
            )
          ) : (
            <p className="muted">Your position is live.</p>
          )}
        </div>
      )}

      <div className="two-col">
        <div className="stack" style={{ gap: 16 }}>
          {round.phase === "BETTING" && (
            <>
              <p>{round.participantCount} players have voted</p>
              <div className="desktop-only">
                <BetSlip
                  round={round}
                  nowMs={nowMs}
                  onChanged={load}
                  noPlayerHint="You can still watch this round without one."
                />
              </div>
              <div className="mobile-only card">
                <p className="muted">Open on desktop to bet</p>
              </div>
            </>
          )}
          {(round.phase === "RUNNING" || round.phase === "SETTLED") && (
            <>
              <p className="mono">Open price: {formatUsd(round.openPrice)}</p>
              <OracleDisclosureLine />
              <h3>All positions</h3>
              <PositionsList positions={round.positions} settled={round.phase === "SETTLED"} />
            </>
          )}
        </div>
        <div className="stack" style={{ gap: 16 }}>
          {round.phase !== "BETTING" && <CrowdPositionCard phase={round.phase} crowd={round.crowd} />}
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
