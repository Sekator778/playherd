/**
 * Bottom tab "Profile" (`/profile/me`) — player review fix item 2. The tab used to open the
 * Wallet screen directly (`Layout.tsx`'s `TAB_LINKS` pointed "Profile" at `/wallet`) — tapping
 * "Profile" got you a balance and a deposit form, never the player's own nickname/rounds/
 * accuracy/history that the label promised. This page is what the tab now opens: the player's own
 * profile overview (identical content to `ProfilePage mine`, reused as-is, not duplicated) with
 * Wallet moved inside it as a plain in-page sub-tab (`the decision log` D-59/D-61's
 * "не финансовый терминал" — the wallet becomes one section of the player's profile, not the
 * whole tab). `/wallet` itself is untouched and still works as a standalone deep link (header
 * balance pill, profile-menu "Wallet" link, old bookmarks) — this page embeds the exact same
 * `WalletContent` rather than forking it.
 */
import { useState } from "react";
import { useSession } from "../api/ApiContext";
import { ServerInfoBanner } from "../components/Common";
import { ProfilePage } from "./ProfilePage";
import { WalletContent } from "./WalletPage";
import { useT } from "../i18n";

type ProfileSegment = "overview" | "wallet";

export function MyProfilePage() {
  const { player, openConnectModal } = useSession();
  const t = useT();
  const [segment, setSegment] = useState<ProfileSegment>("overview");

  if (!player) {
    // Same copy/pattern as `ProfilePage`'s own `mine && !player` branch (D-48 — signing in works
    // from any device, no desktop-only fallback here either).
    return (
      <div className="state-card">
        <p className="state-card__title">{t("profile.connectToSee")}</p>
        <button type="button" className="btn btn--primary" onClick={openConnectModal}>
          {t("common.connectWallet")}
        </button>
      </div>
    );
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="tabs" role="tablist" aria-label={t("profile.tabsAria")}>
        <button type="button" role="tab" aria-selected={segment === "overview"} onClick={() => setSegment("overview")}>
          {t("profile.overview")}
        </button>
        <button type="button" role="tab" aria-selected={segment === "wallet"} onClick={() => setSegment("wallet")}>
          {t("profile.wallet")}
        </button>
      </div>

      {segment === "overview" ? (
        <ProfilePage mine />
      ) : (
        <div className="stack" style={{ gap: 16 }}>
          {/* D-63: same fact as `WalletPage.tsx`'s standalone copy — plain text, not a sticky
           *  banner, kept here too since this is the same "wallet" section, just reached through
           *  a different tab. */}
          <p className="disclaimer-line">{t("wallet.disclaimer")}</p>
          <ServerInfoBanner />
          <WalletContent />
        </div>
      )}
    </div>
  );
}
