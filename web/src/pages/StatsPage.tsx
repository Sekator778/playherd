/**
 * Stats tab (task IA: "Stats (crowd vs market duel card + chart reframed for players, ratings,
 * history)") — one URL-addressable tab strip over three screens that already existed and are
 * already tested: the public Crowd account (`CrowdPage`), the two season leaderboards
 * (`RatingsPage`), and the signed-in player's own round history (`ProfilePage mine`, previously
 * reachable at `/profile/me`). Nothing about those three pages changes here — this is composition,
 * not a rewrite.
 */
import { Link, useParams } from "react-router-dom";
import { CrowdPage } from "./CrowdPage";
import { RatingsPage } from "./RatingsPage";
import { ProfilePage } from "./ProfilePage";
import { useT } from "../i18n";

type StatsTab = "crowd" | "ratings" | "history";

function normalizeTab(raw: string | undefined): StatsTab {
  return raw === "ratings" ? "ratings" : raw === "history" ? "history" : "crowd";
}

export function StatsPage() {
  const { tab } = useParams<{ tab?: string }>();
  const t = useT();
  const active = normalizeTab(tab);

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="tabs" role="tablist" aria-label={t("stats.aria")}>
        <Link to="/stats" role="tab" aria-selected={active === "crowd"}>
          {t("stats.crowd")}
        </Link>
        <Link to="/stats/ratings" role="tab" aria-selected={active === "ratings"}>
          {t("stats.ratings")}
        </Link>
        <Link to="/stats/history" role="tab" aria-selected={active === "history"}>
          {t("stats.history")}
        </Link>
      </div>

      {active === "crowd" && <CrowdPage />}
      {active === "ratings" && <RatingsPage />}
      {active === "history" && <ProfilePage mine />}
    </div>
  );
}
