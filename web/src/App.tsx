import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { LobbyPage } from "./pages/LobbyPage";
import { PairRoomPage } from "./pages/PairRoomPage";
import { RoundPage } from "./pages/RoundPage";
import { StatsPage } from "./pages/StatsPage";
import { ProfilePage } from "./pages/ProfilePage";
import { MyProfilePage } from "./pages/MyProfilePage";
import { WalletPage } from "./pages/WalletPage";
import { FeesPage } from "./pages/FeesPage";
import { HowItWorksPage } from "./pages/HowItWorksPage";
import { StatusPage } from "./pages/StatusPage";
import { DevelopersPage } from "./pages/DevelopersPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { LabPage } from "./pages/LabPage";
import { LabStatesPage } from "./pages/LabStatesPage";
import { ConceptALobby, ConceptARoom, ConceptAStats } from "./pages/lab/concepts/ConceptA";

/**
 * Routes — task IA: "Lobby / Pair room / bottom tabs (Lobby, Stats, Profile)", concept A chosen
 * per Р-61 (`docs/product/design/concepts.md`), grown into Polymarket-style category panels → market
 * cards → a room with a leveraged bet (Р-69, `docs/product/design/markets-lobby.md`). Predates this
 * task's own note in `ux_spec.md` §1 ("предложение — ни один источник не фиксирует URL-схему"): still
 * true, this is this session's proposal for the new IA's URLs, not a fixed decision.
 *
 * The lobby keeps its panel, chips and search in the query string (`?c=sports&sort=hot&q=ger`); the room
 * is `/play/:marketId` (any market id, matched ignoring case so an old `/play/btc` still lands) and reads
 * `?side=up|down` and `?series=daily`; Stats reads `?m=<marketId>`.
 *
 * Old routes that duplicated the new IA redirect to their new home (never removed outright — an
 * old bookmark/link still lands somewhere sensible): `/crowd` → `/stats`, `/ratings` →
 * `/stats/ratings` (`/profile/:playerId` for someone else's public profile is unaffected —
 * leaderboards/position chips still link straight to it). `/round/:roundId` and `/lab` (+ its
 * `/lab/concepts/a` prototype) are kept exactly as they were.
 *
 * `/status` is the public status page (Р-43): the price sources and how fresh each market's price is, what the
 * server has anchored on Chia, the log of cancelled rounds — linked from the footer and from How it works.
 *
 * Player review fix (2026-09-26, item 2): `/profile/me` no longer redirects to the Stats tab's
 * History segment — it renders `MyProfilePage` directly, the screen the bottom tab bar's
 * "Profile" now opens (`Layout.tsx`'s `TAB_LINKS`, previously pointed at `/wallet`). Wallet moved
 * inside it as a sub-tab; `/wallet` itself is untouched (still a valid standalone deep link).
 */
export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<LobbyPage />} />
        <Route path="/play/:marketId" element={<PairRoomPage />} />
        <Route path="/round/:roundId" element={<RoundPage />} />
        <Route path="/stats" element={<StatsPage />} />
        <Route path="/stats/:tab" element={<StatsPage />} />
        <Route path="/crowd" element={<Navigate to="/stats" replace />} />
        <Route path="/ratings" element={<Navigate to="/stats/ratings" replace />} />
        <Route path="/profile/me" element={<MyProfilePage />} />
        <Route path="/profile/:playerId" element={<ProfilePage />} />
        <Route path="/wallet" element={<WalletPage />} />
        <Route path="/how-it-works" element={<HowItWorksPage />} />
        <Route path="/fees" element={<FeesPage />} />
        <Route path="/status" element={<StatusPage />} />
        {/* Р-106: the API for bots of outside developers - how to get a key, every call, a minimal bot. */}
        <Route path="/developers" element={<DevelopersPage />} />
        {/* Hidden design lab (implementation-plan.md §5) — intentionally not in any nav; reachable
         *  only by knowing the URL. */}
        <Route path="/lab" element={<LabPage />} />
        <Route path="/lab/states" element={<LabStatesPage />} />
        {/* Р-59 follow-up: the clickable concept prototype that became the product (concept A,
         *  `docs/product/design/concepts.md`) — mock data only (`pages/lab/concepts/mockData.ts`), same
         *  "hidden by omission from nav" rule as `/lab` above. The two rejected prototypes (B "Live
         *  show", C "Call it") were removed with the Р-69 screen rebuild; they live in git history. */}
        <Route path="/lab/concepts/a" element={<ConceptALobby />} />
        <Route path="/lab/concepts/a/room/:pairId" element={<ConceptARoom />} />
        <Route path="/lab/concepts/a/stats" element={<ConceptAStats />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
