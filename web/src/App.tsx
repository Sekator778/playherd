import { Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { HomePage } from "./pages/HomePage";
import { RoundPage } from "./pages/RoundPage";
import { CrowdPage } from "./pages/CrowdPage";
import { RatingsPage } from "./pages/RatingsPage";
import { ProfilePage } from "./pages/ProfilePage";
import { WalletPage } from "./pages/WalletPage";
import { HowItWorksPage } from "./pages/HowItWorksPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { LabPage } from "./pages/LabPage";

/** Routes per `docs/product/ux_spec.md` §1 (предложение — no source fixes a URL scheme). */
export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/round/:roundId" element={<RoundPage />} />
        <Route path="/crowd" element={<CrowdPage />} />
        <Route path="/ratings" element={<RatingsPage />} />
        <Route path="/profile/me" element={<ProfilePage mine />} />
        <Route path="/profile/:playerId" element={<ProfilePage />} />
        <Route path="/wallet" element={<WalletPage />} />
        <Route path="/how-it-works" element={<HowItWorksPage />} />
        {/* Hidden design lab (implementation-plan.md §5) — intentionally not in NAV_LINKS
         *  (Layout.tsx); reachable only by knowing the URL, per that doc's "скрытость через
         *  отсутствие в навигации достаточна". */}
        <Route path="/lab" element={<LabPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
