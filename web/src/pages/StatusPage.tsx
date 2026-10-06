/**
 * Status (`/status`) — public, no sign-in. The one page where a player can see what Polymarket's app does not
 * show: where every price comes from and how fresh it is, what the server has anchored on the Chia chain, and
 * every round that was cancelled and why. Plain words and numbers only — nothing here is there to sell the
 * product (`docs/product/decisions.md` Р-43: "публичная страница статуса — последняя публикация оракула, казна
 * PLAY, журнал отмен"; Р-71: the extras that earn trust).
 *
 * Four sections, each with its own loading / empty / error state so one failing source never blanks the page:
 * - mode and price source (`serverInfo()`: `mode`, `priceSource`, `replay`, `bots`, `roundDurationsSec`, `engineReady`);
 * - the markets, one row each: source (and the backup exchange of a crypto market, Р-76), last price — marked when it is
 *   the backup's — how old it is, stale / no price / closed (`markets()`); above them one row per source (Coinbase,
 *   Kraken, Hyperliquid, Polymarket): the markets it is the main source of and the backup for, how its prices are doing.
 *   The public API has no list of price feeds (`/api/admin/status` has one, behind the operator's key), so the rows are
 *   what the markets themselves say — a stream's connected / silent / REST state is not shown;
 * - the oracle (`serverInfo().oracle`): network, launcher id, last confirmed record, records waiting, the
 *   lowest record this database knows, the operator's balance — and how to check it yourself;
 * - the cancellations log (`cancellations()`, paged): which market, when, why, how many bets were refunded. A refresh
 *   that finds more new rows than one page keeps paging until it meets what is shown (`api/paging.ts`) — no silent gap.
 *
 * A mock build has no `serverInfo()` at all (`contract.ts`): that is said as it is — demo data, nothing anchored —
 * and the markets and the cancellations (which the mock does serve) still show.
 */
import { useCallback, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import type { CancellationDto } from "../api/contract";
import { loadOlder, refreshNewest, type PagedList } from "../api/paging";
import { useMarkets } from "../api/useMarkets";
import { usePoll } from "../api/usePoll";
import { useServerInfo } from "../api/useServerInfo";
import { CopyButton, ErrorState, Skeleton } from "../components/Common";
import { MarketIcon } from "../components/MarketIcon";
import { backupPriceOf } from "../components/marketModel";
import { useServerNow } from "../components/useServerNow";
import { formatDateTimeUtc, formatPrice } from "../format";
import {
  ageText,
  betsCount,
  CANCELLATION_OPS,
  CANCELLATIONS_PAGE,
  describePriceSource,
  formatOperatorBalance,
  freshnessOf,
  marketsList,
  roundLengthsOf,
  sourceName,
  summarizeMarkets,
  summarizeSources,
  verifierCommand,
  type SourceSummary,
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
  // A row whose numbers cannot be read is left out, never printed as "NaN SEC".
  const lengths = info ? roundLengthsOf(info) : null;
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
            {lengths && (
              <Fact label="Round lengths">
                Every market has two series of rounds: {lengths.short} and {lengths.long}.
              </Fact>
            )}
            <Fact label="Game engine">
              {info.engineReady === false ? (
                <>
                  <strong>Not ready.</strong> The server is rebuilding its state after a failure: rounds, bets and balances are refused for a
                  moment and answer again by themselves — the app keeps what it shows and asks again.
                </>
              ) : (
                <>
                  <strong>Ready.</strong> Rounds, bets and balances are being served.
                </>
              )}
            </Fact>
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

/** One source of the prices: the exchange (or Polymarket) with the markets it is the main source of and the ones it stands
 *  behind as a backup, and how its own prices are doing — a word beside every colour, like the market rows below. */
function SourceRow({ source }: { source: SourceSummary }) {
  return (
    <li className="status-source">
      <div className="status-source__id">
        <strong>{source.name}</strong>
        {source.main.length > 0 && <span className="faint">Main source of {marketsList(source.main)}</span>}
        {source.backupFor.length > 0 && <span className="faint">Backup for {marketsList(source.backupFor)}</span>}
        {source.shownFor.length > 0 && (
          <span className="status-source__shown">Its price is shown now for {marketsList(source.shownFor)}: the main exchange has no fresh price</span>
        )}
      </div>
      <div className="status-source__state">
        {source.fresh > 0 && <span className="status-chip status-chip--fresh">{source.fresh} live</span>}
        {source.stale > 0 && <span className="status-chip status-chip--stale">{source.stale} stale</span>}
        {source.noPrice > 0 && <span className="status-chip status-chip--none">{source.noPrice} no price yet</span>}
      </div>
    </li>
  );
}

function MarketsSection() {
  const { markets, loading, error, refresh } = useMarkets();
  const nowMs = useServerNow(1000);
  const summary = summarizeMarkets(markets, nowMs);
  const sources = summarizeSources(markets, nowMs);
  const hasBackups = markets.some((m) => m.status === "ACTIVE" && !!m.backupSource);
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
          {sources.length > 0 && (
            <ul className="status-sources" aria-label="Sources behind the prices">
              {sources.map((source) => (
                <SourceRow key={source.source} source={source} />
              ))}
            </ul>
          )}
          <ul className="status-markets">
            {markets.map((market) => {
              const fresh = freshnessOf(market, nowMs);
              const backupName = sourceName(market.backupSource);
              const backupPrice = fresh.kind === "closed" ? null : backupPriceOf(market);
              return (
                <li key={market.id} className={`status-market status-market--${fresh.kind}`} title={market.title}>
                  <MarketIcon market={market} size={28} />
                  <div className="status-market__id">
                    <strong>{market.symbol}</strong>
                    <span className="faint">
                      {sourceName(market.source)}
                      {backupName ? ` · Backup: ${backupName}` : ""}
                      {market.oracleIndex !== undefined ? ` · asset #${market.oracleIndex}` : ""}
                    </span>
                    {backupPrice && <span className="status-market__venue">Showing {backupPrice.name}'s price</span>}
                    {/* The server's own words for why a market left ("Moved to a new price source: Coinbase"), as it sent them. */}
                    {fresh.kind === "closed" && market.closeReason && <span className="status-market__reason">{market.closeReason}</span>}
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
            A price is stale when its source has sent nothing newer within its limit. A round that ends with no reliable price holds up the
            rounds after it — betting on that market, for that round length, pauses meanwhile — for five minutes at most (fifteen for
            Polymarket's odds, two when the market's other exchange is still sending prices), counted again after the server restarts; then
            the round is cancelled and every bet refunded (see the log below). A round nobody bet on, if its price can't be read, is skipped.
          </p>
          {hasBackups && (
            <p className="faint status-fine">
              A crypto market has a backup exchange. A round runs entirely on one exchange, chosen when it starts — the main one if it has a live
              price then, otherwise the backup — so its open, its close and every liquidation come from that exchange alone. While the main
              exchange has no fresh price a market shows the backup's price and says so.
            </p>
          )}
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

type LogState = PagedList<CancellationDto>;

/** One row's market, when the server names it (`CancellationDto.asset`): "GER" — an older server sends none. */
function CancellationRow({ c }: { c: CancellationDto }) {
  return (
    <li className="status-cancel">
      <p className="status-cancel__head">
        <span className="status-cancel__when">
          {c.asset && <strong className="status-cancel__market">{c.asset}</strong>}
          <span className="mono">{formatDateTimeUtc(c.cancelledAtMs)}</span>
        </span>
        <span className="status-chip status-chip--none">{betsCount(c.refundedBetCount)} refunded</span>
      </p>
      <p className="status-cancel__reason">{c.reason}</p>
      <p className="faint status-cancel__round">
        <Link to={`/round/${encodeURIComponent(c.roundId)}`} className="mono">
          {c.roundId}
        </Link>
      </p>
    </li>
  );
}

function CancellationsSection() {
  const api = useApi();
  const [log, setLog] = useState<LogState | null>(null);
  const [failed, setFailed] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // What is shown, for a refresh that may page for a while: and one thing at a time reads or changes it.
  const shown = useRef<LogState | null>(null);
  const busy = useRef(false);
  const show = (next: LogState) => {
    shown.current = next;
    setLog(next);
  };
  const fetchPage = useCallback((params: Parameters<typeof api.cancellations>[0]) => api.cancellations(params), [api]);

  // First page now, and again every half minute: a new cancellation joins the top, nothing loaded earlier is lost —
  // and a burst of more new rows than one page is walked until it meets what is shown, so there is no silent hole.
  const load = useCallback(() => {
    if (busy.current) return;
    busy.current = true;
    api
      .cancellations({ limit: CANCELLATIONS_PAGE })
      .then((page) => refreshNewest(fetchPage, shown.current, page, CANCELLATIONS_PAGE, CANCELLATION_OPS))
      .then((next) => {
        setFailed(false);
        shown.current = next;
        setLog(next);
      })
      .catch(() => setFailed(true))
      .finally(() => {
        busy.current = false;
      });
  }, [api, fetchPage]);
  usePoll(load, 30_000, [load]);

  const loadMore = async () => {
    if (!log || busy.current) return;
    busy.current = true;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const next = await loadOlder(fetchPage, log, CANCELLATIONS_PAGE, CANCELLATION_OPS);
      if (next) show(next);
      else setMoreFailed(true);
    } catch {
      setMoreFailed(true);
    } finally {
      busy.current = false;
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
              <CancellationRow key={c.roundId} c={c} />
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
