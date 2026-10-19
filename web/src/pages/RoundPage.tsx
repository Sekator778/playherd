import { useCallback, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { isEngineNotReady, isNotFound } from "../api/apiErrors";
import { usePoll } from "../api/usePoll";
import { useReferencePrice } from "../api/useReferencePrice";
import { useMarket } from "../api/useMarkets";
import { useMyPositions } from "../api/useMyPositions";
import type { RoundDto } from "../api/contract";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet, positionStakeStr } from "../api/outcome";
import { AnchorLine } from "../components/AnchorLine";
import { BetSlip } from "../components/BetSlip";
import { Countdown, ErrorState, OracleDisclosureLine, RoundStatusBadge, RoundVenueNote, Skeleton } from "../components/Common";
import { MarketIcon } from "../components/MarketIcon";
import { roundPriceInfo } from "../components/marketModel";
import { OwnPositionCard } from "../components/OwnPositionCard";
import { isFixedMode } from "../components/betModeModel";
import { CancellationNotice, CrowdPositionCard, PositionsList } from "../components/RoundParts";
import { useServerNow } from "../components/useServerNow";
import { OwnBetWaiting, WaitingForOpenCard } from "../components/WaitingForOpen";
import { formatDuration, formatPlayAmount, formatPrice } from "../format";
import { useT } from "../i18n";
import "./RoundPage.css";

const POLL_MS = 2000;

export function RoundPage() {
  const { roundId } = useParams<{ roundId: string }>();
  const api = useApi();
  const { player } = useSession();
  const t = useT();
  // The SERVER's clock, like the room's: a phone whose own clock is off must not close betting early or late here.
  const nowMs = useServerNow(1000);
  const [round, setRound] = useState<RoundDto | null>(null);
  const [error, setError] = useState<"failed" | "not-found" | null>(null);
  // The round's market, while it is still on the list: its icon, its oracle index, its leverage cap for the live math.
  // What prints the round's prices and names their source is the round's own (`roundPriceInfo`), not this.
  const { market } = useMarket(round?.marketId);
  // Live reference price for the RUNNING phase's unrealized P&L / % to liquidation (task: "live
  // unrealized P&L ... updating every poll") — same interval as the round poll below. Only a RUNNING round has any use for
  // it: a settled or cancelled one (of a market that may have closed, and then has no price) asks for nothing.
  const referencePrice = useReferencePrice(round?.phase === "RUNNING" ? round.marketId : null, POLL_MS);
  // The player's own bets: while a round has no opening price yet its public `positions` list is empty, and
  // `myPositions()` is the only place that still knows the player is in it.
  const { positions: myPositions } = useMyPositions();

  // Reset to loading only when navigating to a *different* round (during render, not in an
  // effect) — not on every tick's refetch of the same round, which would flash the skeleton
  // every second.
  const [lastRoundId, setLastRoundId] = useState(roundId);
  if (roundId !== lastRoundId) {
    setLastRoundId(roundId);
    setRound(null);
    setError(null);
  }

  // Answers with the request itself: the bet slip waits for the reloaded round before it says what happened to a bet.
  const load = useCallback(() => {
    if (!roundId) return;
    return api
      .round(roundId)
      .then((r) => {
        setRound(r);
        setError(null);
      })
      .catch((err) => {
        // The server rebuilding its state (503) is not a failure: keep what is on screen (or the skeleton) — the
        // poll asks again in two seconds and the banner says why nothing moves.
        if (isEngineNotReady(err)) return;
        setError(isNotFound(err) ? "not-found" : "failed");
      });
  }, [api, roundId]);

  usePoll(load, POLL_MS, [load]);

  if (error === "not-found" && !round) return <ErrorState message={t("round.notFound")} />;
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
  const length = formatDuration((round.window.endMs - round.window.startMs) / 1000, t);
  // The prices of this round are printed in ITS price scale and its source is named from ITS fields: a market that
  // has left `markets()` (an odds market closes often) still has rounds that must read right.
  const { format: priceFormat, source } = roundPriceInfo(round, market);
  const priceText = (price: string) => formatPrice(price, priceFormat);

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
          <Link to={`/play/${encodeURIComponent(round.marketId)}`} className="round-head__market" aria-label={t("round.headAria", { asset: round.asset, length })}>
            {market && <MarketIcon market={market} size={28} />}
            <strong>{round.asset}</strong>
            <span className="round-head__length">· {length}</span>
          </Link>
          <RoundStatusBadge phase={round.phase} />
        </div>
        {round.phase === "BETTING" && (
          <Countdown nowMs={nowMs} targetMs={round.window.startMs} template="room.closesIn" />
        )}
        {round.phase === "RUNNING" && <Countdown nowMs={nowMs} targetMs={round.window.endMs} template="room.resolvesIn" />}
        {round.phase === "SETTLED" && (
          <h2>
            {t("round.closedLine", {
              market: round.asset,
              direction:
                Number(round.closePrice) === Number(round.openPrice)
                  ? t("round.closedFlat")
                  : Number(round.closePrice) > Number(round.openPrice)
                    ? "UP"
                    : "DOWN",
              open: priceText(round.openPrice),
              close: priceText(round.closePrice),
            })}
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
                  <span className={outcomeClassName(outcomeFromNet(net))}>{isFixedMode(ownPosition) && net === 0 ? t("mode.tie") : `${formatPlayAmount(net, { sign: true })} PLAY`}</span>
                </div>
                <p className="net-result__secondary">
                  {t("round.stakePaid", { stake: formatPlayAmount(stake), paid: formatPlayAmount(ownPosition.payout) })}
                </p>
              </div>
            );
          })()
        ) : (
          // RUNNING: live unrealized P&L, the room left before liquidation (pulsing as it closes in); a
          // liquidated position (running or settled) reads as the whole stake gone (Р-7).
          <OwnPositionCard
            position={ownPosition}
            referencePrice={referencePrice}
            market={market}
            format={priceFormat}
            hero
            closePrice={round.phase === "SETTLED" ? round.closePrice : null}
          />
        ))}

      <div className="two-col">
        <div className="stack" style={{ gap: 16 }}>
          {round.phase === "BETTING" && (
            <>
              <p>{t("round.voted", { count: round.participantCount })}</p>
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
                noPlayerHint={t("round.noPlayerHint")}
              />
            </>
          )}
          {waitingOpen && (
            <div className="stack" style={{ gap: 16 }}>
              {/* The player's own bet (above) already says it; one notice is enough. */}
              {!ownWaiting && <WaitingForOpenCard symbol={round.asset} length={length} />}
              <OracleDisclosureLine source={source} />
            </div>
          )}
          {(round.phase === "SETTLED" || (round.phase === "RUNNING" && !waitingOpen)) && (
            <div className="stack reveal-flip-in" style={{ gap: 16 }}>
              {round.openPrice !== null && <p className="mono">{t("round.openPrice", { price: priceText(round.openPrice) })}</p>}
              <OracleDisclosureLine source={source} />
              {/* A round runs entirely on one exchange (Р-76): when it ran on the market's backup, it says so. */}
              <RoundVenueNote source={round.source} market={market} phase={round.phase} />
              {round.phase === "SETTLED" && (
                <AnchorLine
                  roundId={round.roundId}
                  resultsHash={round.resultsHash}
                  anchor={round.oracleAnchor}
                  symbol={round.asset}
                  oracleIndex={market?.oracleIndex}
                />
              )}
              <h3>{t("round.allPositions")}</h3>
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
            <CrowdPositionCard phase={round.phase} crowd={round.crowd} referencePrice={referencePrice} market={market} format={priceFormat} />
          )}
          {round.phase === "BETTING" && (
            <div className="card">
              <p className="muted">{t("round.crowdRevealed")}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
