import { useT } from "../i18n";
import { countResults, type RoundResult } from "../api/lastResults";
import "./LastResults.css";

const MARK: Record<RoundResult, string> = { won: "W", lost: "L", flat: "=" };

/**
 * The result of the player's last settled rounds (up to 5), oldest on the left and the newest on the right.
 * Each mark is a letter and a shape as well as a colour (W round / L square / = dashed), so a loss is as
 * visible as a win and the signal is never colour alone. Nothing when there is no settled round.
 */
export function LastResults({ results }: { results: readonly RoundResult[] }) {
  const t = useT();
  if (results.length === 0) return null;
  const c = countResults(results);
  const label = t("lastResults.aria", {
    count: results.length,
    won: c.won,
    lost: c.lost,
    flat: c.flat,
  });
  return (
    <div className="last-results" role="group" aria-label={label}>
      <span className="last-results__title" aria-hidden="true">
        {t("lastResults.title")}
      </span>
      <ol className="last-results__row" aria-hidden="true">
        {results.map((r, i) => (
          <li key={i} className={`last-results__mark last-results__mark--${r}`} title={t(`lastResults.${r}`)}>
            {MARK[r]}
          </li>
        ))}
      </ol>
      <span className="last-results__hint faint" aria-hidden="true">
        {t("lastResults.hint")}
      </span>
    </div>
  );
}
