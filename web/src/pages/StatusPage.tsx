/**
 * Status (`/status`) — public, no sign-in. The one page where a player can see what Polymarket's app does not
 * show: where every price comes from and how fresh it is, what the server has anchored on the Chia chain, and
 * every round that was cancelled and why. Plain words and numbers only — nothing here is there to sell the
 * product (`docs/product/decisions.md` Р-43: "публичная страница статуса — последняя публикация оракула, казна
 * PLAY, журнал отмен"; Р-71: the extras that earn trust).
 *
 * Four sections, each with its own loading / empty / error state so one failing source never blanks the page:
 * - mode and price source (`serverInfo()`: `mode`, `priceSource`, `replay`, `bots`, `roundDurationsSec`);
 * - the markets, one row each: source, last price, how old it is, stale / no price / closed (`markets()`);
 * - the oracle (`serverInfo().oracle`): network, launcher id, last confirmed record, records waiting, the
 *   lowest record this database knows, the operator's balance — and how to check it yourself;
 * - the cancellations log (`cancellations()`, paged): when, why, how many bets were refunded.
 *
 * A mock build has no `serverInfo()` at all (`contract.ts`): that is said as it is — demo data, nothing anchored —
 * and the markets and the cancellations (which the mock does serve) still show.
 */
import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import type { CancellationDto } from "../api/contract";
import { useMarkets } from "../api/useMarkets";
import { usePoll } from "../api/usePoll";
import { useServerInfo } from "../api/useServerInfo";
import { CopyButton, ErrorState, Skeleton } from "../components/Common";
import { MarketIcon } from "../components/MarketIcon";
import { useServerNow } from "../components/useServerNow";
import { formatDateTimeUtc, formatDuration, formatPrice } from "../format";
import {
  ageText,
  betsCount,
  CANCELLATIONS_PAGE,
  describePriceSource,
  formatOperatorBalance,
  freshnessOf,
  mergeCancellations,
  nextCancellationsParams,
  refreshCancellations,
  sourceName,
  summarizeMarkets,
  verifierCommand,
} from "./statusModel";
import "./StatusPage.css";

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="status-fact">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Mode and price source
// ---------------------------------------------------------------------------

function ServerSection() {
  const { status, info, refreshFailed, refresh } = useServerInfo();
  return (
    <section className="card status-card" aria-labelledby="status-server-title">
      <h2 id="status-server-title" className="section-title">
        Mode and price source
      </h2>
      {status === "unsupported" && (
        <p>
          This copy of the app runs on built-in demo data, not on a server: prices are made up on your device and nothing is anchored on a
          chain. A real deployment answers here with its mode, where its prices come from and the state of its oracle.
        </p>
      )}
      {status === "loading" && <Skeleton height={96} />}
      {status === "error" && <ErrorState message="Couldn't reach the server." onRetry={refresh} />}
      {status === "ready" && info && (
        <>
          <dl className="status-facts">
            <Fact label="Mode">{info.mode === "live" ? "Live" : "Replay (a test mode)"}</Fact>
            <Fact label="Prices">
              <strong>{describePriceSource(info).title}.</strong> {describePriceSource(info).detail}
            </Fact>
            {info.roundDurationsSec && (
              <Fact label="Round lengths">
                Every market has two series of rounds: {formatDuration(info.roundDurationsSec.HOURLY)} and {formatDuration(info.roundDurationsSec.DAILY)}.
              </Fact>
            )}
            {info.bots > 0 && (
              <Fact label="Players">
                {info.bots} labelled test bots play next to real players — they are marked BOT wherever a player is shown, and their bets are
                inside the crowd split and the player counts.
              </Fact>
            )}
          </dl>
          {refreshFailed && (
            <p className="status-note" role="status">
              Couldn't refresh — this is the last answer the server gave.
            </p>
          )}
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Markets
// ---------------------------------------------------------------------------

function MarketsSection() {
  const { markets, loading, error, refresh } = useMarkets();
  const nowMs = useServerNow(1000);
  const summary = summarizeMarkets(markets, nowMs);
  return (
    <section className="card status-card" aria-labelledby="status-markets-title">
      <h2 id="status-markets-title" className="section-title">
        Markets and their prices
      </h2>
      {loading && error && <ErrorState message="Couldn't load the markets." onRetry={refresh} />}
      {loading && !error && <Skeleton height={140} />}
      {!loading && markets.length === 0 && <p className="muted">No market is listed right now.</p>}
      {!loading && markets.length > 0 && (
        <>
          <p className="status-summary" aria-live="polite">
            <strong>
              {summary.total} {summary.total === 1 ? "market" : "markets"}
            </strong>
            {summary.fresh > 0 && ` · ${summary.fresh} live`}
            {summary.stale > 0 && ` · ${summary.stale} stale`}
            {summary.noPrice > 0 && ` · ${summary.noPrice} waiting for a first price`}
            {summary.closed > 0 && ` · ${summary.closed} closed`}
          </p>
          {error && (
            <p className="status-note" role="status">
              Can't reach the server — these are the last prices we got; the ages below keep counting.
            </p>
          )}
          <ul className="status-markets">
            {markets.map((market) => {
              const fresh = freshnessOf(market, nowMs);
              return (
                <li key={market.id} className={`status-market status-market--${fresh.kind}`} title={market.title}>
                  <MarketIcon market={market} size={28} />
                  <div className="status-market__id">
                    <strong>{market.symbol}</strong>
                    <span className="faint">
                      {sourceName(market.source)}
                      {market.oracleIndex !== undefined ? ` · asset #${market.oracleIndex}` : ""}
                    </span>
                  </div>
                  <div className="status-market__price mono">{market.price ? formatPrice(market.price.price, market) : "—"}</div>
                  <div className="status-market__age">
                    <span className={`status-chip status-chip--${fresh.kind}`}>{fresh.word}</span>
                    {fresh.ageMs !== null && <span className="faint mono">{ageText(fresh.ageMs)}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
          <p className="faint status-fine">
            A price is stale when its source has sent nothing newer within its limit. Betting stays open meanwhile; if a reliable price can't be
            read within an hour of a round closing, the round is cancelled and every bet refunded (see the log below).
          </p>
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Oracle
// ---------------------------------------------------------------------------

function OracleSection() {
  const { status, info, refresh } = useServerInfo();
  const oracle = info?.oracle;
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  return (
    <section className="card status-card" aria-labelledby="status-oracle-title">
      <h2 id="status-oracle-title" className="section-title">
        Anchored on Chia
      </h2>
      {status === "unsupported" && <p className="muted">Nothing is anchored in a demo build — there is no oracle to show.</p>}
      {status === "loading" && <Skeleton height={140} />}
      {status === "error" && <ErrorState message="Couldn't reach the server." onRetry={refresh} />}
      {status === "ready" && oracle && (
        <>
          <p className="muted status-lead">
            After every round the server publishes one record to an oracle on the Chia chain: the closing price and a hash of the round's full
            results. It is one key — ours — not a decentralized oracle; the chain makes the record permanent and public, so it can't be changed
            afterwards.
          </p>
          <dl className="status-facts">
            <Fact label="Network">{oracle.network} — a test network; its coins and PLAY have no real value.</Fact>
            <Fact label="Launcher id">
              <span className="status-id">
                <code className="mono status-id__value" aria-label="Oracle launcher id">
                  {oracle.launcherId}
                </code>
                <CopyButton value={oracle.launcherId} label="Copy" className="btn btn--secondary status-id__copy" />
              </span>
              <span className="faint status-hint">The oracle's identity on the chain; the verifier below starts from it.</span>
            </Fact>
            <Fact label="Last confirmed record">
              {oracle.lastConfirmedSeq !== null ? (
                <>
                  <span className="mono">#{oracle.lastConfirmedSeq}</span>
                  {oracle.lastConfirmedHeight !== null && (
                    <>
                      {" "}
                      at block <span className="mono">{oracle.lastConfirmedHeight.toLocaleString("en-US")}</span>
                    </>
                  )}
                </>
              ) : (
                "none yet"
              )}
            </Fact>
            <Fact label="Waiting to be anchored">
              <span className="mono">{oracle.pendingRecords}</span> {oracle.pendingRecords === 1 ? "record" : "records"}
              {oracle.pendingRecords > 0 && <span className="faint status-hint">Records go out in batches — a round's record joins the next one.</span>}
            </Fact>
            {oracle.firstSeq !== undefined && oracle.firstSeq !== null && (
              <Fact label="Oldest record here">
                <span className="mono">#{oracle.firstSeq}</span>
                <span className="faint status-hint">
                  The lowest record number this server's database knows; earlier ones were published before this database existed and can only
                  be checked on the chain.
                </span>
              </Fact>
            )}
            <Fact label="Operator balance">
              <span className="mono" title={oracle.operatorBalanceMojos ? `${oracle.operatorBalanceMojos} mojos` : undefined}>
                {formatOperatorBalance(oracle.operatorBalanceMojos)}
              </span>
              <span className="faint status-hint">What the publishing key holds to pay network fees.</span>
            </Fact>
          </dl>
          <p className="status-links">
            <a href="/api/markets/registry.json" target="_blank" rel="noopener noreferrer">
              Market registry (registry.json)
            </a>
            <span className="faint">which asset number in a record is which market</span>
          </p>

          <div className="status-verify" id="verify">
            <h3>Check it yourself</h3>
            <ol>
              <li>
                Open any settled round and follow “verify results.json”: the full list of its bets and payouts, at{" "}
                <code className="mono">/api/rounds/&lt;round id&gt;/results.json</code>.
              </li>
              <li>The chain record of that round holds the SHA-256 of that file, the closing price and the asset number above.</li>
              <li>
                Run the public verifier. It reads only the public Chia API and needs none of our keys; it is a script in the project
                repository (<code className="mono">tools/verify-oracle</code>, with its own README).
              </li>
            </ol>
            <div className="status-command">
              <code className="mono" aria-label="Verifier command">
                {verifierCommand(oracle.launcherId, origin)}
              </code>
              <CopyButton value={verifierCommand(oracle.launcherId, origin)} label="Copy command" className="btn btn--secondary" />
            </div>
            <p className="faint status-fine">A healthy run ends with “CONFLICTS: 0”, “Gaps in the seq sequence: 0” and “Mismatches: 0”.</p>
          </div>
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Cancellations log
// ---------------------------------------------------------------------------

interface LogState {
  items: CancellationDto[];
  hasMore: boolean;
}

function CancellationsSection() {
  const api = useApi();
  const [log, setLog] = useState<LogState | null>(null);
  const [failed, setFailed] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);

  // First page now, and again every half minute: a new cancellation joins the top, nothing loaded earlier is lost.
  const load = useCallback(() => {
    api
      .cancellations({ limit: CANCELLATIONS_PAGE })
      .then((page) => {
        setFailed(false);
        setLog((current) =>
          current ? { items: refreshCancellations(current.items, page), hasMore: current.hasMore } : { items: page.items, hasMore: page.hasMore },
        );
      })
      .catch(() => setFailed(true));
  }, [api]);
  usePoll(load, 30_000, [load]);

  const loadMore = async () => {
    if (!log) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      // The cursor is a timestamp and several rounds can be cancelled at one instant, so a page can be all repeats:
      // ask again for more of them rather than stopping short of rounds that are really there.
      let limit = CANCELLATIONS_PAGE;
      for (let attempt = 0; attempt < 4; attempt++) {
        const page = await api.cancellations(nextCancellationsParams(log.items, limit));
        const merged = mergeCancellations(log.items, page.items);
        if (merged.length > log.items.length || !page.hasMore) {
          setLog({ items: merged, hasMore: page.hasMore });
          return;
        }
        limit *= 2;
      }
      setMoreFailed(true);
    } catch {
      setMoreFailed(true);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <section className="card status-card" aria-labelledby="status-cancel-title">
      <h2 id="status-cancel-title" className="section-title">
        Cancelled rounds
      </h2>
      <p className="muted status-lead">
        A round is cancelled when no reliable price can be read, when its market closes, or when an operator steps in. Every bet in it is refunded
        — liquidated ones too — and the reason is public, here and on the round's page.
      </p>
      {!log && failed && <ErrorState message="Couldn't load the log." onRetry={load} />}
      {!log && !failed && <Skeleton height={96} />}
      {log && failed && (
        <p className="status-note" role="status">
          Couldn't refresh the log — this is what we had.
        </p>
      )}
      {log && log.items.length === 0 && <p className="muted">No round has been cancelled so far.</p>}
      {log && log.items.length > 0 && (
        <>
          <ul className="status-cancels">
            {log.items.map((c) => (
              <li key={c.roundId} className="status-cancel">
                <p className="status-cancel__head">
                  <span className="mono">{formatDateTimeUtc(c.cancelledAtMs)}</span>
                  <span className="status-chip status-chip--none">{betsCount(c.refundedBetCount)} refunded</span>
                </p>
                <p className="status-cancel__reason">{c.reason}</p>
                <p className="faint status-cancel__round">
                  <Link to={`/round/${encodeURIComponent(c.roundId)}`} className="mono">
                    {c.roundId}
                  </Link>
                </p>
              </li>
            ))}
          </ul>
          {log.hasMore && (
            <button type="button" className="btn btn--secondary" onClick={() => void loadMore()} disabled={loadingMore}>
              {loadingMore ? "Loading…" : "Show older"}
            </button>
          )}
          {moreFailed && (
            <p className="status-note" role="alert">
              Couldn't load older cancellations. Try again.
            </p>
          )}
        </>
      )}
    </section>
  );
}

export function StatusPage() {
  return (
    <div className="status-page stack" style={{ gap: 16 }}>
      <header>
        <h1>Status</h1>
        <p className="muted status-lead">
          Where the prices come from, how fresh they are, what is written on the Chia chain, and every round that was cancelled. Public — no sign-in
          needed.
        </p>
      </header>
      <ServerSection />
      <MarketsSection />
      <OracleSection />
      <CancellationsSection />
      <p>
        <Link to="/how-it-works">How it works →</Link>
      </p>
    </div>
  );
}
