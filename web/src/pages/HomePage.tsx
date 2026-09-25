import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import type { HomeSummaryDto, Interval } from "../api/contract";
import { RATING_ROUNDS_THRESHOLD } from "../api/contract";
import { BetSlip } from "../components/BetSlip";
import { Countdown, ErrorState, OracleDisclosureLine, RoundStatusBadge, Skeleton } from "../components/Common";
import { LazyCrowdChart } from "../components/LazyCrowdChart";
import { IntervalTabs } from "../components/RoundParts";
import { formatPlayAmount, formatPercent } from "../format";

function RichRow({ entry }: { entry: HomeSummaryDto["topRich"][number] }) {
  return (
    <li className="list-row">
      <Link to={`/profile/${entry.player.playerId}`}>
        #{entry.rank} {entry.player.displayName}
      </Link>
      <span className="mono">{formatPlayAmount(entry.growth, { decimals: 0, sign: true })} PLAY</span>
    </li>
  );
}

function ProphetRow({ entry }: { entry: HomeSummaryDto["topProphets"][number] }) {
  return (
    <li className="list-row">
      <Link to={`/profile/${entry.player.playerId}`}>
        #{entry.rank} {entry.player.displayName}
      </Link>
      <span className="mono">{formatPercent(entry.accuracy)}</span>
    </li>
  );
}

export function HomePage() {
  const api = useApi();
  const nowMs = useClock(1000);
  const [interval, setInterval_] = useState<Interval>("HOURLY");
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

  useEffect(() => {
    load();
  }, [load, nowMs]);

  if (error && !home) {
    return <ErrorState onRetry={load} />;
  }

  if (!home) {
    return (
      <div className="home-layout">
        <Skeleton height={220} />
        <Skeleton height={280} />
        <Skeleton height={220} />
      </div>
    );
  }

  const pair = interval === "HOURLY" ? home.hourly : home.daily;
  const running = pair.running;
  const betting = pair.betting;

  return (
    <div className="home-layout">
      <section className="home-section home-section--round">
        <IntervalTabs value={interval} onChange={setInterval_} />
        <div className="two-col" style={{ marginTop: 16 }}>
          <div className="card">
            <h2>Current round</h2>
            <RoundStatusBadge phase={running.phase} />
            {running.phase === "RUNNING" && (
              <p style={{ marginTop: 12 }}>
                <Countdown nowMs={nowMs} targetMs={running.window.endMs} prefix="Resolves in " />
              </p>
            )}
            {running.phase === "SETTLED" && <p style={{ marginTop: 12 }}>Settled.</p>}
            {(running.phase === "RUNNING" || running.phase === "SETTLED") && <OracleDisclosureLine />}
            <p>
              <Link to={`/round/${running.roundId}`}>View full round →</Link>
            </p>
          </div>
          {betting.phase === "BETTING" ? (
            <>
              <div className="desktop-only">
                <BetSlip round={betting} nowMs={nowMs} onChanged={load} />
              </div>
              <div className="card mobile-only">
                <p>{betting.participantCount} players have voted</p>
                <Link className="btn btn--secondary btn--block" to={`/round/${betting.roundId}`}>
                  Open on desktop to bet
                </Link>
              </div>
            </>
          ) : (
            <div className="card">
              <h3>This round was cancelled.</h3>
              <p className="muted">{betting.cancellation.reason}</p>
            </div>
          )}
        </div>
      </section>

      <section className="home-section home-section--chart">
        <h2>Crowd account vs BTC</h2>
        <LazyCrowdChart history={home.crowd.history} variant="compact" />
        <OracleDisclosureLine />
        <p>
          <Link to="/crowd">Full crowd account →</Link>
        </p>
      </section>

      <section className="home-section home-section--ratings">
        <div className="grid-2">
          <div>
            <h3>Richest</h3>
            {home.topRich.length === 0 ? (
              <p className="muted">No one has qualified yet — play {RATING_ROUNDS_THRESHOLD} rounds this season to appear here.</p>
            ) : (
              <ul className="list-card">
                {home.topRich.map((e) => (
                  <RichRow key={e.player.playerId} entry={e} />
                ))}
              </ul>
            )}
            <p>
              <Link to="/ratings">Full ratings →</Link>
            </p>
          </div>
          <div>
            <h3>Prophets</h3>
            {home.topProphets.length === 0 ? (
              <p className="muted">No one has qualified yet — play {RATING_ROUNDS_THRESHOLD} rounds this season to appear here.</p>
            ) : (
              <ul className="list-card">
                {home.topProphets.map((e) => (
                  <ProphetRow key={e.player.playerId} entry={e} />
                ))}
              </ul>
            )}
            <p>
              <Link to="/ratings">Full ratings →</Link>
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
