import { Fragment } from "react";
import { Link } from "react-router-dom";
import { useMarkets } from "../api/useMarkets";
import { ErrorState, Skeleton } from "../components/Common";
import { formatDuration } from "../format";
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";
import { FEE_TABLE_LEVERAGES, factorOf, feeRows, feeRules, formatFeeShare, type FeeCell } from "./feesModel";
import "./FeesPage.css";

/**
 * "All fees" (D-102, `docs/research/fee_display_venues_2026-10-18.md` §4): what a bet costs, for every market, round length and boost, in one public
 * table — no sign-in. The numbers are computed from what `markets()` carries per series (`herdFeeBp`, `feeFactor`, `maxLeverage`) with the server's
 * formula (`feesModel.ts`); a fee changed on the server changes this page with no release.
 */
export function FeesPage() {
  const t = useT();
  const { markets, economy, loading, error, refresh } = useMarkets();
  const rules = feeRules(economy);
  const rows = feeRows(markets, rules);
  const short = rows.find((r) => r.interval === "HOURLY");
  const long = rows.find((r) => r.interval === "DAILY");
  const k = (value: number | null) => (value === null ? "—" : String(value));
  const share = (cell: FeeCell | null) => (cell ? formatFeeShare(cell.percent) : null);

  return (
    <div className="stack fees-page" style={{ gap: 20 }}>
      <h1>{t("fees.title")}</h1>
      {rules && <p>{t("fees.intro", { network: rules.networkFee })}</p>}

      {loading && error && <ErrorState message={t("fees.loadFail")} onRetry={refresh} />}
      {loading && !error && <Skeleton height={220} />}
      {!loading && rows.length === 0 && <p className="muted">{t("fees.none")}</p>}

      {rules && rows.length > 0 && (
        <>
          <p className="fees-page__formula">
            <Rich text={t("fees.formula", { guard: rules.guardPercent })} />
          </p>
          <p>
            {t("fees.factors", {
              short: short ? formatDuration(short.durationSec, t) : "—",
              long: long ? formatDuration(long.durationSec, t) : "—",
              kShort: k(factorOf(rows, "HOURLY", false)),
              kLong: k(factorOf(rows, "DAILY", false)),
              kOdds: k(factorOf(rows, "DAILY", true)),
            })}
          </p>
          <p>
            {t("fees.tiers", {
              a: rules.bounds[0],
              b: rules.bounds[1],
              c: rules.bounds[2],
              db: rules.divisors[1],
              dc: rules.divisors[2],
              dd: rules.divisors[3],
            })}
          </p>

          <div className="fees-page__scroll" role="region" aria-label={t("fees.caption", { a: rules.bounds[0] })} tabIndex={0}>
            <table className="data-table fees-page__table">
              <caption className="fees-page__caption">{t("fees.caption", { a: rules.bounds[0] })}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("fees.colMarket")}</th>
                  {FEE_TABLE_LEVERAGES.map((l) => (
                    <th key={l} scope="col" className="fees-page__num">
                      x{l}
                    </th>
                  ))}
                  <th scope="col" className="fees-page__num">
                    {t("fees.colMax")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.marketId}:${row.interval}`}>
                    <th scope="row" className="fees-page__market">
                      <strong>{row.symbol}</strong>
                      {row.odds && (
                        <>
                          {" "}
                          <span className="mk-tag mk-tag--odds">{t("parts.oddsTag")}</span>
                        </>
                      )}
                      <span className="fees-page__round"> · {formatDuration(row.durationSec, t)}</span>
                    </th>
                    {row.cells.map((cell, i) => (
                      <td key={FEE_TABLE_LEVERAGES[i]} className="fees-page__num mono">
                        {cell ? share(cell) : <span className="faint" aria-label={t("fees.notOffered")}>—</span>}
                      </td>
                    ))}
                    <td className="fees-page__num mono">
                      <Fragment>
                        {share(row.max)} <span className="faint">(x{row.max.leverage})</span>
                      </Fragment>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="faint">{t("betSlip.feeWhere")}</p>
          <p className="faint">{t("betSlip.feeRefund")}</p>
        </>
      )}

      <p>
        <Link to="/how-it-works">{t("fees.howItWorks")}</Link>
      </p>
    </div>
  );
}
