/**
 * Stats → Crowd (`/stats`) — Р-5/Р-14/Р-17/Р-25/Р-26: the crowd account against the market, now one account PER
 * MARKET (Т-6) and the whole herd at a glance (Р-71):
 *
 * - an overview of every market's crowd account (`crowdOverview()`): total herd capital and return, the
 *   best and the worst market — the worst as visible as the best — and a ranked list that opens a market;
 * - a market selector (default: `?m=`, else the market you came from, else the open BTC market — `spot_BTC` since BTC's price
 *   moved to its own exchange, never the legacy `BTC` perpetual that was closed for it) driving `crowdAccount(id)`,
 *   with its capital, returns and the chart crowd vs hold vs anti-crowd on a time axis that follows how much
 *   history there is (`components/crowdModel.ts`).
 *
 * A row of the overview carries the market's own `title` and `status` (`CrowdOverviewDto`), so a closed market
 * keeps its row — marked closed — and its account stays reachable after it has left `markets()`: nothing here
 * joins the overview against the market list for a name.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { isEngineNotReady } from "../api/apiErrors";
import { useClock } from "../api/useClock";
import { useMarket, useMarkets } from "../api/useMarkets";
import type { CrowdAccountDto, CrowdOverviewDto, MarketDto } from "../api/contract";
import { CROWD_STARTING_CAPITAL_USD } from "../api/contract";
import { ErrorState, OracleDisclosureLine, OracleStatusLine, Skeleton } from "../components/Common";
import { LazyCrowdChart } from "../components/LazyCrowdChart";
import { MarketIcon } from "../components/MarketIcon";
import { LiveStatTile } from "../components/RoundParts";
import { bestAndWorst, marketGroups, rankedByReturn, type OverviewRow } from "../components/crowdModel";
import { defaultMarket, subtitleOf } from "../components/marketModel";
import { lastMarket, rememberMarket } from "../components/lastMarket";
import { trend, TREND_GLYPH } from "../components/marketFormat";
import { formatPercent, formatUpdatedAtUtc, formatUsd } from "../format";
import "./CrowdPage.css";

/** The herd's return as a signed, arrowed figure — never colour alone. */
function Return({ value }: { value: number }) {
  const t = trend(value);
  return (
    <span className={`crowd-return crowd-return--${t} mono`}>
      <span aria-hidden="true">{TREND_GLYPH[t]}</span> {formatPercent(value, { sign: true })}
    </span>
  );
}

/** "closed" beside a market's ticker: a word, not just a dimmed row. */
function ClosedTag({ row }: { row: OverviewRow }) {
  return row.status === "CLOSED" ? <span className="crowd-closed-tag">closed</span> : null;
}

/** The row's name for a spoken label: the ticker, and "closed" — the row's `title` rides along as the button's
 *  own `title` (its description), so a long question is not read out in front of every figure. */
function rowName(row: OverviewRow): string {
  return `${row.symbol}${row.status === "CLOSED" ? ", closed" : ""}`;
}

/** One market's mini-card in the overview (best / worst): taps through to that market's account below. */
function Extreme({ label, row, onPick }: { label: string; row: OverviewRow; onPick: (marketId: string) => void }) {
  return (
    <button
      type="button"
      className="crowd-extreme"
      title={row.title}
      onClick={() => onPick(row.marketId)}
      aria-label={`${label}: ${rowName(row)}, ${formatPercent(row.returnPct, { sign: true })}. Show its account.`}
    >
      <span className="crowd-extreme__label">{label}</span>
      <span className="crowd-extreme__symbol">
        {row.symbol} <ClosedTag row={row} />
      </span>
      <Return value={row.returnPct} />
    </button>
  );
}

function Overview({ overview, onPick }: { overview: CrowdOverviewDto; onPick: (marketId: string) => void }) {
  const ranked = rankedByReturn(overview.markets);
  const extremes = bestAndWorst(overview.markets);
  const maxAbs = Math.max(0.0001, ...ranked.map((r) => Math.abs(r.returnPct)));
  return (
    <section className="card crowd-overview" aria-labelledby="crowd-overview-title">
      <h2 id="crowd-overview-title" className="section-title">
        The whole herd — {overview.markets.length} markets
      </h2>
      <div className="crowd-overview__totals">
        <div>
          <div className="stat-tile__label">Herd capital</div>
          <div className="crowd-overview__big mono">{formatUsd(overview.totalCapital)}</div>
          <div className="faint">of {formatUsd(overview.totalStartingCapital, { decimals: 0 })} paper money at the start</div>
        </div>
        <div>
          <div className="stat-tile__label">Return</div>
          <div className="crowd-overview__big">
            <Return value={overview.returnPct} />
          </div>
        </div>
      </div>
      {extremes ? (
        <div className="crowd-overview__extremes">
          <Extreme label="Best market" row={extremes.best} onPick={onPick} />
          {extremes.worst && <Extreme label="Worst market" row={extremes.worst} onPick={onPick} />}
        </div>
      ) : (
        <p className="muted">The herd hasn't traded yet — its first position opens when a round settles.</p>
      )}
      {ranked.length > 2 && (
        <details className="crowd-overview__all">
          <summary>Every market, best to worst</summary>
          <ul className="crowd-rank">
            {ranked.map((r) => (
              <li key={r.marketId}>
                <button
                  type="button"
                  className="crowd-rank__row"
                  title={r.title}
                  onClick={() => onPick(r.marketId)}
                  aria-label={`${rowName(r)}: ${formatPercent(r.returnPct, { sign: true })}, won ${r.roundsWon} of ${r.roundsTraded} rounds. Show its account.`}
                >
                  <span className="crowd-rank__symbol">
                    {r.symbol}
                    <ClosedTag row={r} />
                  </span>
                  <span className="crowd-rank__bar" aria-hidden="true">
                    <span
                      className={`crowd-rank__fill crowd-rank__fill--${trend(r.returnPct)}`}
                      style={{ width: `${Math.max(2, (Math.abs(r.returnPct) / maxAbs) * 100)}%` }}
                    />
                  </span>
                  <Return value={r.returnPct} />
                  <span className="crowd-rank__won faint mono">
                    {r.roundsWon}/{r.roundsTraded}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </details>
      )}
      <p className="faint">The herd trades paper money — $100,000 per market — on the majority side of every round.</p>
    </section>
  );
}

/** What the account below needs to know of its market: for a market still on the list that is the `MarketDto`;
 *  for one that has left it, the few facts the overview row carries (`source` unknown: no oracle line then). */
type AccountMarket = Pick<MarketDto, "id" | "symbol" | "iconUrl" | "status"> & Partial<Pick<MarketDto, "source" | "backupSource" | "title">>;

function CrowdAccount({ market }: { market: AccountMarket }) {
  const api = useApi();
  const tick = useClock(5000);
  const [crowd, setCrowd] = useState<CrowdAccountDto | null>(null);
  const [error, setError] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const marketId = market.id;

  const load = useCallback(() => {
    api
      .crowdAccount(marketId)
      .then((c) => {
        setCrowd(c);
        setError(false);
        setUpdatedAt(Date.now());
      })
      // A restarting server (503) is not a failure of this account: the skeleton (or the numbers shown) stay and the
      // next tick (5 s) asks again; the banner says why.
      .catch((err) => {
        if (!isEngineNotReady(err)) setError(true);
      });
  }, [api, marketId]);

  useEffect(() => {
    load();
  }, [load, tick]);

  if (error && !crowd) return <ErrorState onRetry={load} />;
  if (!crowd) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={90} />
        <Skeleton height={360} />
      </div>
    );
  }
  if (crowd.history.length === 0) {
    return (
      <div className="state-card">
        <p>The crowd account opens its first position when the first round settles.</p>
      </div>
    );
  }

  const returnSinceDayOne = Number(crowd.capital) / Number(CROWD_STARTING_CAPITAL_USD) - 1;
  const thisSeason = crowd.seasonReturns[crowd.seasonReturns.length - 1];

  return (
    <div className="stack" style={{ gap: 20 }}>
      <div className="crowd-tiles">
        <LiveStatTile label="Capital" value={Number(crowd.capital)} format={(n) => formatUsd(n)} />
        <LiveStatTile label="Return since day one" value={returnSinceDayOne} format={(n) => formatPercent(n, { sign: true })} />
        <LiveStatTile
          label="This season"
          value={thisSeason ? thisSeason.returnPct : null}
          format={(n) => formatPercent(n, { sign: true })}
        />
      </div>
      <LazyCrowdChart history={crowd.history} variant="full" symbol={market.symbol} />
      {updatedAt !== null && <p className="faint">{formatUpdatedAtUtc(updatedAt)}</p>}
      {market.source && <OracleDisclosureLine source={market.source} backup={market.backupSource} />}
      <OracleStatusLine />
      <h2>Season returns</h2>
      <table className="data-table data-table--responsive">
        <thead>
          <tr>
            <th>Season</th>
            <th>Return</th>
          </tr>
        </thead>
        <tbody>
          {[...crowd.seasonReturns].reverse().map((s) => (
            <tr key={s.season}>
              <td data-label="Season">{s.season}</td>
              <td data-label="Return" className="mono">
                {formatPercent(s.returnPct, { sign: true })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function CrowdPage() {
  const api = useApi();
  const tick = useClock(5000);
  const [params, setParams] = useSearchParams();
  const { markets, categories, loading: marketsLoading, error: marketsError, refresh } = useMarkets();
  // What the link or the room the player came from asks for; null when nothing does. Not "BTC" then: that is the legacy
  // perpetual which the catalog closed when BTC's price moved to its own exchange — the default is chosen from the list.
  const wanted = params.get("m") ?? lastMarket();
  const requested = useMarket(wanted).market;

  const [overview, setOverview] = useState<CrowdOverviewDto | null>(null);
  useEffect(() => {
    let cancelled = false;
    api
      .crowdOverview()
      .then((o) => {
        if (!cancelled) setOverview(o);
      })
      .catch(() => {
        // The overview is an extra: without it the market's own account still shows.
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const groups = useMemo(() => marketGroups(markets, categories), [markets, categories]);

  // The market asked for (the link, or the room it came from): from the list; else — a closed market that has
  // left it — from its row of the overview (which keeps it); else the default (`defaultMarket`: the open BTC market,
  // else the first open crypto market), and with the list holding neither, the first market it holds.
  const wantedRow = wanted ? overview?.markets.find((r) => r.marketId.toLowerCase() === wanted.toLowerCase()) : undefined;
  const leftTheList: AccountMarket | undefined =
    !requested && wantedRow ? { id: wantedRow.marketId, symbol: wantedRow.symbol, title: wantedRow.title, status: wantedRow.status, iconUrl: null } : undefined;
  const market: AccountMarket | undefined = requested ?? leftTheList ?? defaultMarket(markets) ?? markets[0];

  const pick = useCallback(
    (marketId: string) => {
      rememberMarket(marketId);
      setParams({ m: marketId }, { replace: true });
    },
    [setParams],
  );

  if (marketsLoading && marketsError) return <ErrorState onRetry={refresh} />;
  if (marketsLoading || !market) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={120} />
        <Skeleton height={48} />
        <Skeleton height={360} />
      </div>
    );
  }

  return (
    <div className="stack" style={{ gap: 20 }}>
      <h1>Crowd account vs the market</h1>

      {overview && <Overview overview={overview} onPick={pick} />}

      <div className="crowd-selector">
        <MarketIcon market={market} size={36} />
        <div className="crowd-selector__field">
          <label htmlFor="crowd-market" className="stat-tile__label">
            Market
          </label>
          <select id="crowd-market" className="crowd-selector__select" value={market.id} onChange={(e) => pick(e.target.value)}>
            {groups.map((g) => (
              <optgroup key={g.id} label={g.title}>
                {g.markets.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.symbol} · {m.kind === "ODDS" ? (subtitleOf(m) ?? m.title) : m.title}
                    {m.status === "CLOSED" ? " (closed)" : ""}
                  </option>
                ))}
              </optgroup>
            ))}
            {leftTheList && (
              <optgroup label="Left the list">
                <option value={leftTheList.id}>
                  {leftTheList.symbol} · {leftTheList.title} (closed)
                </option>
              </optgroup>
            )}
          </select>
        </div>
        {market.status === "ACTIVE" && (
          <Link to={`/play/${encodeURIComponent(market.id)}`} className="btn btn--secondary crowd-selector__play">
            Play {market.symbol}
          </Link>
        )}
      </div>

      <CrowdAccount key={market.id} market={market} />
    </div>
  );
}
