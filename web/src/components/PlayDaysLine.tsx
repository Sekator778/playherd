import { useT } from "../i18n";
import "./PlayDaysLine.css";

/**
 * "Played 3 days in a row · best 5" (`PublicProfileDto.playDays`, UTC days with a settled round). Only told: no bonus, no countdown, no
 * "don't lose it", no notification. Zero is a calm state with a plain line (D-71), not an empty space and not a shamed "0".
 */
export function PlayDaysLine({ current, best }: { current: number; best: number }) {
  const t = useT();
  return (
    <p className="play-days" aria-label={t("playDays.aria", { current, best })}>
      <span className="play-days__main">
        {current > 0 ? t("playDays.main", { count: current }) : t("playDays.none")}
      </span>
      {best > 0 && <span className="play-days__best"> · {t("playDays.best", { best })}</span>}
    </p>
  );
}
