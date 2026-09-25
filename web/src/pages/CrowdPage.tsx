import { useCallback, useEffect, useState } from "react";
import { useApi } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import type { CrowdAccountDto } from "../api/contract";
import { CROWD_STARTING_CAPITAL_USD } from "../api/contract";
import { ErrorState, OracleDisclosureLine, OracleStatusLine, Skeleton } from "../components/Common";
import { LazyCrowdChart } from "../components/LazyCrowdChart";
import { LiveStatTile } from "../components/RoundParts";
import { formatPercent, formatUpdatedAtUtc, formatUsd } from "../format";

export function CrowdPage() {
  const api = useApi();
  const tick = useClock(5000);
  const [crowd, setCrowd] = useState<CrowdAccountDto | null>(null);
  const [error, setError] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);

  const load = useCallback(() => {
    api
      .crowdAccount()
      .then((c) => {
        setCrowd(c);
        setError(false);
        setUpdatedAt(Date.now());
      })
      .catch(() => setError(true));
  }, [api]);

  useEffect(() => {
    load();
  }, [load, tick]);

  if (error && !crowd) return <ErrorState onRetry={load} />;
  if (!crowd) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <div className="grid-3">
          <Skeleton height={90} />
          <Skeleton height={90} />
          <Skeleton height={90} />
        </div>
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
      <h1>Crowd account vs the market</h1>
      <div className="grid-3">
        <LiveStatTile label="Capital" value={Number(crowd.capital)} format={(n) => formatUsd(n)} />
        <LiveStatTile label="Return since day one" value={returnSinceDayOne} format={(n) => formatPercent(n, { sign: true })} />
        <LiveStatTile
          label="This season"
          value={thisSeason ? thisSeason.returnPct : null}
          format={(n) => formatPercent(n, { sign: true })}
        />
      </div>
      <LazyCrowdChart history={crowd.history} variant="full" />
      {updatedAt !== null && <p className="faint">{formatUpdatedAtUtc(updatedAt)}</p>}
      <OracleDisclosureLine />
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
