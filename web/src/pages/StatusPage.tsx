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
import { formatDateTime, formatPrice } from "../format";
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";
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
  const t = useT();
  const { status, info, refreshFailed, refresh } = useServerInfo();
  // A row whose numbers cannot be read is left out, never printed as "NaN SEC".
  const lengths = info ? roundLengthsOf(info, t) : null;
  return (
    <section className="card status-card" aria-labelledby="status-server-title">
      <h2 id="status-server-title" className="section-title">
        {t("status.serverTitle")}
      </h2>
      {status === "unsupported" && (
        <p>
          {t("status.unsupported")}
        </p>
      )}
      {status === "loading" && <Skeleton height={96} />}
      {status === "error" && <ErrorState message={t("status.reachFail")} onRetry={refresh} />}
      {status === "ready" && info && (
        <>
          <dl className="status-facts">
            <Fact label={t("status.factMode")}>{info.mode === "live" ? t("status.modeLive") : t("status.modeReplay")}</Fact>
            <Fact label={t("status.factPrices")}>
              <strong>{describePriceSource(info, t).title}.</strong> {describePriceSource(info, t).detail}
            </Fact>
            {lengths && (
              <Fact label={t("status.factLengths")}>
                {t("status.lengths", { short: lengths.short, long: lengths.long })}
              </Fact>
            )}
            <Fact label={t("status.factEngine")}>
              {info.engineReady === false ? (
                <Rich text={t("status.engineNotReady")} />
              ) : (
                <Rich text={t("status.engineReady")} />
              )}
            </Fact>
            {info.bots > 0 && (
              <Fact label={t("status.factPlayers")}>
                {t("status.botsFact", { count: info.bots })}
              </Fact>
            )}
          </dl>
          {refreshFailed && (
            <p className="status-note" role="status">
              {t("status.refreshLast")}
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
  const t = useT();
  return (
    <li className="status-source">
      <div className="status-source__id">
        <strong>{source.name}</strong>
        {source.main.length > 0 && <span className="faint">{t("status.mainOf", { list: marketsList(source.main, 6, t) })}</span>}
        {source.backupFor.length > 0 && <span className="faint">{t("status.backupFor", { list: marketsList(source.backupFor, 6, t) })}</span>}
        {source.shownFor.length > 0 && (
          <span className="status-source__shown">{t("status.shownFor", { list: marketsList(source.shownFor, 6, t) })}</span>
        )}
      </div>
      <div className="status-source__state">
        {source.fresh > 0 && <span className="status-chip status-chip--fresh">{t("status.chipLive", { count: source.fresh })}</span>}
        {source.stale > 0 && <span className="status-chip status-chip--stale">{t("status.chipStale", { count: source.stale })}</span>}
        {source.noPrice > 0 && <span className="status-chip status-chip--none">{t("status.chipNoPrice", { count: source.noPrice })}</span>}
      </div>
    </li>
  );
}

function MarketsSection() {
  const t = useT();
  const { markets, loading, error, refresh } = useMarkets();
  const nowMs = useServerNow(1000);
  const summary = summarizeMarkets(markets, nowMs);
  const sources = summarizeSources(markets, nowMs, t);
  const hasBackups = markets.some((m) => m.status === "ACTIVE" && !!m.backupSource);
  return (
    <section className="card status-card" aria-labelledby="status-markets-title">
      <h2 id="status-markets-title" className="section-title">
        {t("status.marketsTitle")}
      </h2>
      {loading && error && <ErrorState message={t("status.marketsLoadFail")} onRetry={refresh} />}
      {loading && !error && <Skeleton height={140} />}
      {!loading && markets.length === 0 && <p className="muted">{t("status.marketsNone")}</p>}
      {!loading && markets.length > 0 && (
        <>
          <p className="status-summary" aria-live="polite">
            <strong>{t("lobby.count", { count: summary.total })}</strong>
            {summary.fresh > 0 && t("status.sumLive", { count: summary.fresh })}
            {summary.stale > 0 && t("status.sumStale", { count: summary.stale })}
            {summary.noPrice > 0 && t("status.sumNoPrice", { count: summary.noPrice })}
            {summary.closed > 0 && t("lobby.closedCount", { count: summary.closed })}
          </p>
          {error && (
            <p className="status-note" role="status">
              {t("status.marketsOffline")}
            </p>
          )}
          {sources.length > 0 && (
            <ul className="status-sources" aria-label={t("status.sourcesAria")}>
              {sources.map((source) => (
                <SourceRow key={source.source} source={source} />
              ))}
            </ul>
          )}
          <ul className="status-markets">
            {markets.map((market) => {
              const fresh = freshnessOf(market, nowMs, t);
              const backupName = sourceName(market.backupSource, t);
              const backupPrice = fresh.kind === "closed" ? null : backupPriceOf(market, t);
              return (
                <li key={market.id} className={`status-market status-market--${fresh.kind}`} title={market.title}>
                  <MarketIcon market={market} size={28} />
                  <div className="status-market__id">
                    <strong>{market.symbol}</strong>
                    <span className="faint">
                      {sourceName(market.source, t)}
                      {backupName ? t("status.backupLabel", { name: backupName }) : ""}
                      {market.oracleIndex !== undefined ? t("status.assetNo", { index: market.oracleIndex }) : ""}
                    </span>
                    {backupPrice && <span className="status-market__venue">{t("status.showingBackup", { name: backupPrice.name })}</span>}
                    {/* The server's own words for why a market left ("Moved to a new price source: Coinbase"), as it sent them. */}
                    {fresh.kind === "closed" && market.closeReason && <span className="status-market__reason">{market.closeReason}</span>}
                  </div>
                  <div className="status-market__price mono">{market.price ? formatPrice(market.price.price, market) : "—"}</div>
                  <div className="status-market__age">
                    <span className={`status-chip status-chip--${fresh.kind}`}>{fresh.word}</span>
                    {fresh.ageMs !== null && <span className="faint mono">{ageText(fresh.ageMs, t)}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
          <p className="faint status-fine">
            {t("status.fine1")}
          </p>
          {hasBackups && (
            <p className="faint status-fine">
              {t("status.fine2")}
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
  const t = useT();
  const { status, info, refresh } = useServerInfo();
  const oracle = info?.oracle;
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  return (
    <section className="card status-card" aria-labelledby="status-oracle-title">
      <h2 id="status-oracle-title" className="section-title">
        {t("status.oracleTitle")}
      </h2>
      {status === "unsupported" && <p className="muted">{t("status.oracleDemo")}</p>}
      {status === "loading" && <Skeleton height={140} />}
      {status === "error" && <ErrorState message={t("status.reachFail")} onRetry={refresh} />}
      {status === "ready" && oracle && (
        <>
          <p className="muted status-lead">
            {t("status.oracleLead")}
          </p>
          <dl className="status-facts">
            <Fact label={t("status.factNetwork")}>{t("status.networkBody", { network: oracle.network })}</Fact>
            <Fact label={t("status.factLauncher")}>
              <span className="status-id">
                <code className="mono status-id__value" aria-label={t("status.launcherAria")}>
                  {oracle.launcherId}
                </code>
                <CopyButton value={oracle.launcherId} className="btn btn--secondary status-id__copy" />
              </span>
              <span className="faint status-hint">{t("status.launcherHint")}</span>
            </Fact>
            <Fact label={t("status.factLast")}>
              {oracle.lastConfirmedSeq !== null ? (
                <span className="mono">
                  {oracle.lastConfirmedHeight !== null
                    ? t("status.lastAt", { seq: oracle.lastConfirmedSeq, height: oracle.lastConfirmedHeight.toLocaleString("en-US") })
                    : t("status.lastSeq", { seq: oracle.lastConfirmedSeq })}
                </span>
              ) : (
                t("status.noneYet")
              )}
            </Fact>
            <Fact label={t("status.factPending")}>
              <span className="mono">{t("status.records", { count: oracle.pendingRecords })}</span>
              {oracle.pendingRecords > 0 && <span className="faint status-hint">{t("status.pendingHint")}</span>}
            </Fact>
            {oracle.firstSeq !== undefined && oracle.firstSeq !== null && (
              <Fact label={t("status.factOldest")}>
                <span className="mono">#{oracle.firstSeq}</span>
                <span className="faint status-hint">
                  {t("status.oldestHint")}
                </span>
              </Fact>
            )}
            <Fact label={t("status.factOperator")}>
              <span className="mono" title={oracle.operatorBalanceMojos ? t("status.mojos", { mojos: oracle.operatorBalanceMojos }) : undefined}>
                {formatOperatorBalance(oracle.operatorBalanceMojos)}
              </span>
              <span className="faint status-hint">{t("status.operatorHint")}</span>
            </Fact>
          </dl>
          <p className="status-links">
            <a href="/api/markets/registry.json" target="_blank" rel="noopener noreferrer">
              {t("status.registry")}
            </a>
            <span className="faint">{t("status.registryNote")}</span>
          </p>

          <div className="status-verify" id="verify">
            <h3>{t("status.verifyTitle")}</h3>
            <ol>
              <li>
                {t("status.verify1")}{" "}
                <code className="mono">/api/rounds/&lt;round id&gt;/results.json</code>.
              </li>
              <li>{t("status.verify2")}</li>
              <li>
                {t("status.verify3", { path: "tools/verify-oracle" })}
              </li>
            </ol>
            <div className="status-command">
              <code className="mono" aria-label={t("status.commandAria")}>
                {verifierCommand(oracle.launcherId, origin)}
              </code>
              <CopyButton value={verifierCommand(oracle.launcherId, origin)} label={t("status.copyCommand")} className="btn btn--secondary" />
            </div>
            <p className="faint status-fine">{t("status.healthy")}</p>
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
  const t = useT();
  return (
    <li className="status-cancel">
      <p className="status-cancel__head">
        <span className="status-cancel__when">
          {c.asset && <strong className="status-cancel__market">{c.asset}</strong>}
          <span className="mono">{formatDateTime(c.cancelledAtMs)}</span>
        </span>
        <span className="status-chip status-chip--none">{t("status.refundedChip", { bets: betsCount(c.refundedBetCount, t) })}</span>
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
  const t = useT();
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
        {t("status.cancelTitle")}
      </h2>
      <p className="muted status-lead">
        {t("status.cancelLead")}
      </p>
      {!log && failed && <ErrorState message={t("status.logLoadFail")} onRetry={load} />}
      {!log && !failed && <Skeleton height={96} />}
      {log && failed && (
        <p className="status-note" role="status">
          {t("status.logRefreshFail")}
        </p>
      )}
      {log && log.items.length === 0 && <p className="muted">{t("status.logNone")}</p>}
      {log && log.items.length > 0 && (
        <>
          <ul className="status-cancels">
            {log.items.map((c) => (
              <CancellationRow key={c.roundId} c={c} />
            ))}
          </ul>
          {log.hasMore && (
            <button type="button" className="btn btn--secondary" onClick={() => void loadMore()} disabled={loadingMore}>
              {loadingMore ? t("wallet.loading") : t("status.showOlder")}
            </button>
          )}
          {moreFailed && (
            <p className="status-note" role="alert">
              {t("status.olderFail")}
            </p>
          )}
        </>
      )}
    </section>
  );
}

export function StatusPage() {
  const t = useT();
  return (
    <div className="status-page stack" style={{ gap: 16 }}>
      <header>
        <h1>{t("layout.status")}</h1>
        <p className="muted status-lead">
          {t("status.pageLead")}
        </p>
      </header>
      <ServerSection />
      <MarketsSection />
      <OracleSection />
      <CancellationsSection />
      <p>
        <Link to="/how-it-works">{t("status.howLink")}</Link>
      </p>
    </div>
  );
}
