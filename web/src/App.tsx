import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { LobbyPage } from "./pages/LobbyPage";
import { PairRoomPage } from "./pages/PairRoomPage";
import { RoundPage } from "./pages/RoundPage";
import { StatsPage } from "./pages/StatsPage";
import { ProfilePage } from "./pages/ProfilePage";
import { MyProfilePage } from "./pages/MyProfilePage";
import { WalletPage } from "./pages/WalletPage";
import { HowItWorksPage } from "./pages/HowItWorksPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { LabPage } from "./pages/LabPage";
import { ConceptALobby, ConceptARoom, ConceptAStats } from "./pages/lab/concepts/ConceptA";
import { ConceptBLobby, ConceptBRoom, ConceptBStats } from "./pages/lab/concepts/ConceptB";
import { ConceptCLobby, ConceptCRoom, ConceptCStats } from "./pages/lab/concepts/ConceptC";

/**
 * Routes — task IA: "Lobby / Pair room / bottom tabs (Lobby, Stats, Profile)", concept A chosen
 * per Р-61 (`docs/product/design/concepts.md`). Predates this task's own note in `ux_spec.md` §1
 * ("предложение — ни один источник не фиксирует URL-схему"): still true, this is this session's
 * proposal for the new IA's URLs, not a fixed decision.
 *
 * Old routes that duplicated the new IA redirect to their new home (never removed outright — an
 * old bookmark/link still lands somewhere sensible): `/crowd` → `/stats`, `/ratings` →
 * `/stats/ratings` (`/profile/:playerId` for someone else's public profile is unaffected —
 * leaderboards/position chips still link straight to it). `/round/:roundId` and `/lab` (+ its
 * `/lab/concepts/*` prototypes) are kept exactly as they were — explicit task requirement.
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
        <Route path="/play/:asset" element={<PairRoomPage />} />
        <Route path="/round/:roundId" element={<RoundPage />} />
        <Route path="/stats" element={<StatsPage />} />
        <Route path="/stats/:tab" element={<StatsPage />} />
        <Route path="/crowd" element={<Navigate to="/stats" replace />} />
        <Route path="/ratings" element={<Navigate to="/stats/ratings" replace />} />
        <Route path="/profile/me" element={<MyProfilePage />} />
        <Route path="/profile/:playerId" element={<ProfilePage />} />
        <Route path="/wallet" element={<WalletPage />} />
        <Route path="/how-it-works" element={<HowItWorksPage />} />
        {/* Hidden design lab (implementation-plan.md §5) — intentionally not in any nav; reachable
         *  only by knowing the URL. */}
        <Route path="/lab" element={<LabPage />} />
        {/* Р-59 follow-up: three clickable concept prototypes for the lobby/room/stats IA
         *  (`docs/product/design/concepts.md`) — mock data only (`pages/lab/concepts/mockData.ts`),
         *  same "hidden by omission from nav" rule as `/lab` above. Concept A is now the chosen,
         *  real IA above; all three prototypes stay reachable for reference/comparison. */}
        <Route path="/lab/concepts/a" element={<ConceptALobby />} />
        <Route path="/lab/concepts/a/room/:pairId" element={<ConceptARoom />} />
        <Route path="/lab/concepts/a/stats" element={<ConceptAStats />} />
        <Route path="/lab/concepts/b" element={<ConceptBLobby />} />
        <Route path="/lab/concepts/b/room/:pairId" element={<ConceptBRoom />} />
        <Route path="/lab/concepts/b/stats" element={<ConceptBStats />} />
        <Route path="/lab/concepts/c" element={<ConceptCLobby />} />
        <Route path="/lab/concepts/c/room/:pairId" element={<ConceptCRoom />} />
        <Route path="/lab/concepts/c/stats" element={<ConceptCStats />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
