import type { EpochMs } from "../../api/contract";
import { timeZoneNote } from "../../format";
import { useT } from "../../i18n";
import type { BackupPrice } from "../marketModel";
import type { ChartLine } from "../priceChartModel";

/** The legend under the chart: what each kind of line means, which exchange the last point came from, whether the
 *  price is stale, and the zone the clock labels are in. Knows nothing of how the series is drawn. */
export function ChartLegend({
  lines,
  showStart,
  backup,
  stale,
  error,
  endMs,
}: {
  lines: readonly ChartLine[];
  /** The round-start marker is in view, so it needs its entry. */
  showStart: boolean;
  backup: BackupPrice | null;
  stale: boolean;
  error: boolean;
  /** The newest time on the chart: the zone note is for that moment (summer / winter time). */
  endMs: EpochMs;
}) {
  const t = useT();
  const open = lines.find((l) => l.id === "open");
  return (
    <figcaption className="pchart__legend faint">
      {open && (
        <span>
          <i className={`pchart__swatch pchart__swatch--${open.tone}`} aria-hidden="true" />{" "}
          {open.tone === "entry" ? t("pchart.openEntry") : t("pchart.openAll")}
        </span>
      )}
      {showStart && (
        <span>
          <i className="pchart__swatch pchart__swatch--start" aria-hidden="true" /> {t("pchart.start")}
        </span>
      )}
      {lines.some((l) => l.id === "own-liq") && (
        <span>
          <i className="pchart__swatch pchart__swatch--liq" aria-hidden="true" /> {t("pchart.ownLiq")}
        </span>
      )}
      {lines.some((l) => l.id === "crowd-liq") && (
        <span>
          <i className="pchart__swatch pchart__swatch--liq pchart__swatch--faint" aria-hidden="true" /> {t("pchart.herdLiq")}
        </span>
      )}
      {backup && (
        <span className="pchart__venue">{t("pchart.venue", { name: backup.name, primary: backup.primaryName ?? t("source.mainExchange") })}</span>
      )}
      {stale && <span className="pchart__stale">{t("pchart.stale")}</span>}
      {error && !stale && <span className="pchart__stale">{t("pchart.reconnecting")}</span>}
      {/* The axis labels carry no zone of their own: it is said here, so a player does not read them off their own clock (first tester pass, D9). */}
      <span className="pchart__zone">{t("pchart.zone", { zone: timeZoneNote(endMs, t) })}</span>
    </figcaption>
  );
}
