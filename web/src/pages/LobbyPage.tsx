/**
 * Lobby (`/`) — pair tiles (task IA: "routes / → Lobby (pair tiles; BTC live with REAL data from
 * the API... ETH/SOL tiles clearly 'Coming soon' without numbers)"). Concept A, chosen per Р-61
 * (`docs/product/design/concepts.md`) — same tile language the /lab prototype explored, wired to
 * the real `Api` here instead of `pages/lab/concepts/mockData.ts`'s invented numbers.
 */
import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import { useClock } from "../api/useClock";
import type { CrowdHistoryPointDto, HomeSummaryDto } from "../api/contract";
import { PAIRS } from "../api/pairs";
import { Countdown, ErrorState, Skeleton } from "../components/Common";
import { LockIcon } from "../components/icons";
import { formatUsd } from "../format";
import "./LobbyPage.css";

const POLL_MS = 2000;

/** Honest "last result" for the BTC tile — the crowd account's own net change on the most
 *  recently settled round, from real `crowdAccount()` history (already part of `home()`), never
 *  fabricated. `null` until at least two points exist (a single point has no "previous" to diff
 *  against) — the tile simply omits the line rather than showing a made-up zero. */
function lastRoundCrowdDelta(history: CrowdHistoryPointDto[]): number | null {
  if (history.length < 2) return null;
  const last = history[history.length - 1];
  const prev = history[history.length - 2];
  return Number(last.crowdCapital) - Number(prev.crowdCapital);
}

export function LobbyPage() {
  const api = useApi();
  const nowMs = useClock(1000);
  const [home, setHome] = useState<HomeSummaryDto | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(() => {
    api
      .home()
      .then((h) => {
        setHome(h);
        setError(false);
      })
      .catch(() => setError(true));
  }, [api]);

  usePoll(load, POLL_MS, [load]);

  if (error && !home) return <ErrorState onRetry={load} />;
  if (!home) {
    return (
      <div className="lobby-tile-grid">
        <Skeleton height={168} />
        <Skeleton height={168} />
        <Skeleton height={168} />
      </div>
    );
  }

  // "Players in the open round" / "time left" — the hourly series' currently-open-for-bets round
  // (the fastest cycle, so the tile always has something fresh to say); real data from `home()`,
  // the same aggregate every other screen already polls.
  const btcBetting = home.hourly.betting;
  const playersNow = btcBetting.phase === "BETTING" ? btcBetting.participantCount : 0;
  const closesAtMs = btcBetting.phase === "BETTING" ? btcBetting.window.startMs : null;
  const lastDelta = lastRoundCrowdDelta(home.crowd.history);

  return (
    <div className="lobby-root">
      <div className="lobby-head">
        <h1 className="lobby-title">Pick a market</h1>
        <p className="lobby-sub">Call the next hour of BTC. More pairs coming.</p>
      </div>
      <div className="lobby-tile-grid">
        {PAIRS.map((pair) =>
          pair.status === "live" ? (
            <Link key={pair.id} to={`/play/${pair.id}`} className="lobby-tile lobby-tile--live">
              <span className="lobby-tile__glyph" aria-hidden="true">
                ₿
              </span>
              <span className="lobby-tile__symbol">{pair.symbol}</span>
              <span className="lobby-tile__meta">
                <span className="lobby-tile__meta-row">
                  <span className="lobby-tile__live-dot" aria-hidden="true" /> {playersNow} playing this round
                </span>
                {closesAtMs !== null && (
                  <span className="lobby-tile__meta-row mono">
                    <Countdown nowMs={nowMs} targetMs={closesAtMs} prefix="Closes in " />
                  </span>
                )}
                {lastDelta !== null && (
                  <span className="lobby-tile__meta-row">Last round: crowd {formatUsd(lastDelta, { sign: true })}</span>
                )}
              </span>
              <span className="lobby-tile__cta">PLAY →</span>
            </Link>
          ) : (
            <div key={pair.id} className="lobby-tile lobby-tile--soon" aria-disabled="true">
              <span className="lobby-tile__lock">
                <LockIcon />
              </span>
              <span className="lobby-tile__symbol">{pair.symbol}</span>
              <span className="lobby-tile__meta">
                <span className="lobby-tile__meta-row">{`${pair.name} — coming soon`}</span>
              </span>
            </div>
          ),
        )}
      </div>
    </div>
  );
}
