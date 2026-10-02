import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import { inPlayExcludingLiquidated, loadInPlay } from "../api/inPlay";
import { useMyPositions } from "../api/useMyPositions";
import { formatPlayAmount } from "../format";
import { ConnectWalletModal } from "./ConnectWalletModal";
import { PositionsStrip } from "./PositionsStrip";
import { ScrollManager } from "./ScrollManager";
import { CoinIcon, SpeakerOnIcon, SpeakerMuteIcon, VibrationOnIcon, VibrationOffIcon, LobbyTabIcon, StatsTabIcon, ProfileTabIcon } from "./icons";
import { useSettlementWatcher } from "../fx/useSettlementWatcher";
import { useLiquidationWatcher } from "../fx/useLiquidationWatcher";
import { useAwayReplay } from "../fx/useAwayReplay";
import { useAndroidBackButton } from "../fx/useAndroidBackButton";
import { ResultMomentHost } from "../fx/ResultMomentHost";
import { RollingDigits } from "../fx/RollingDigits";
import { useFxSettings } from "../fx/fxSettings";
import { primeAudioContext } from "../fx/sound";

/**
 * Bottom tab bar (task IA: "Lobby / Stats / Profile") — replaces the old top nav row
 * (Home/Crowd/Ratings/How it works) as the app's PRIMARY navigation, on both mobile and desktop
 * (one nav paradigm, not two). "How it works" moved to the footer (`Layout` below) — it's
 * reference material, not a tab a player taps every session.
 *
 * Player review fix (2026-09-26, item 2): "Profile" used to point at `/wallet` — tapping it opened
 * a balance/deposit screen, never a profile (nickname, rounds, accuracy, history). It now opens
 * `/profile/me` (`MyProfilePage.tsx`), which shows that profile with Wallet moved inside it as a
 * sub-tab; `/wallet` is unchanged as a direct deep link (header balance pill, profile menu).
 */
const TAB_LINKS = [
  { to: "/", label: "Lobby", end: true, Icon: LobbyTabIcon },
  { to: "/stats", label: "Stats", end: false, Icon: StatsTabIcon },
  { to: "/profile/me", label: "Profile", end: false, Icon: ProfileTabIcon },
];

function BalancePill() {
  const api = useApi();
  const { player } = useSession();
  const [balance, setBalance] = useState<string | null>(null);
  // Sum of the player's stakes still tied up in an open or running round (Alex's report,
  // 2026-09-25): the free balance alone made a player who'd just won, then staked the payout on
  // the next round, look like they'd lost — nothing said the missing PLAY was reserved, not
  // gone. `myAccount().inPlay` covers every market; `api/inPlay.ts` only falls back to summing
  // `rounds()` (BTC) for a server that does not send it.
  const [inPlay, setInPlay] = useState<number | null>(null);
  const tick = useClock(4000);
  // A position a liquidation has already taken is lost, whatever the server still counts as "in play" until its
  // round settles — the pill must not go on suggesting it can come back (`api/inPlay.ts`). The account is also
  // fetched again whenever the set of positions changes (a bet placed or cancelled, a round settled), so the
  // pill and the list never disagree for a poll's length.
  const { positions } = useMyPositions();
  const positionsKey = positions.map((p) => `${p.roundId}:${p.liquidated ? 1 : 0}`).join("|");
  const shownInPlay = inPlay === null ? null : inPlayExcludingLiquidated(inPlay, positions);

  useEffect(() => {
    if (!player) return;
    let cancelled = false;
    api
      .myAccount()
      .then(async (acc) => {
        const inPlayStake = await loadInPlay(api, player.playerId, acc);
        if (cancelled) return;
        setBalance(acc.balance);
        setInPlay(inPlayStake);
      })
      .catch(() => {}); // a failed tick keeps the pill as it was; the next tick tries again
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player, tick, positionsKey]);

  if (!player || balance === null) return null;
  return (
    <NavLink to="/wallet" className="balance-pill" aria-label={`Balance: ${formatPlayAmount(balance)} PLAY`}>
      <CoinIcon className="balance-pill__coin" />
      {/* Rolls on ANY change, not only while a result moment is on screen — the direct answer to
       *  Alex's "чтобы сумма выигрыша или проигрыша... сразу это проявлялось"
       *  (implementation-plan.md §2). Icon-only unit here (polish item 2, 2026-09-26): a bare
       *  number + "PLAY" wrapped the word onto its own line on a 1080px phone once the header ran
       *  tight on width — the gold coin icon already says "this is PLAY", so the word is dropped
       *  from this compact chip specifically (kept everywhere roomier: Wallet, the result-moment
       *  card, …) and moved to `aria-label` above so it's still announced, not just implied by a
       *  decorative icon. */}
      <span className="balance-pill__amount">
        <RollingDigits value={Number(balance)} format={(n) => formatPlayAmount(n)} durationMs={500} />
      </span>
      {shownInPlay !== null && shownInPlay > 0 && (
        <span className="balance-pill__in-play">+{formatPlayAmount(shownInPlay)} in play</span>
      )}
    </NavLink>
  );
}

/** Sound/haptics settings (direction.md §6) — lives in the header, next to the profile menu.
 *  One compact button (polish item 3, 2026-09-26: two separate 34px toggle buttons stacked here
 *  wrapped onto two lines right beside the logo on a 1080px phone once the header ran tight on
 *  width — `.lab-row`'s `flex-wrap: wrap`, borrowed for its gap styling, was never meant to run in
 *  a squeezed header). A single icon-only trigger opens a small popover menu with both toggles as
 *  discoverable rows, same open/outside-click pattern as `ProfileMenu` below — not player-gated,
 *  since sound/haptics apply before sign-in too. */
function FxMenu() {
  const fx = useFxSettings();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  return (
    <div className="fx-menu" ref={ref}>
      <button
        type="button"
        className="fx-toggle"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Sound and vibration settings"
        title="Sound and vibration"
        onClick={() => setOpen((v) => !v)}
      >
        {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
      </button>
      {open && (
        <ul className="fx-menu__list" role="menu">
          <li role="none">
            <button
              type="button"
              role="menuitemcheckbox"
              aria-checked={fx.sfxEnabled}
              onClick={() => {
                if (!fx.sfxEnabled) primeAudioContext();
                fx.setSfxEnabled(!fx.sfxEnabled);
              }}
            >
              {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
              <span>Sound {fx.sfxEnabled ? "on" : "off"}</span>
            </button>
          </li>
          <li role="none">
            <button
              type="button"
              role="menuitemcheckbox"
              aria-checked={fx.hapticsEnabled}
              onClick={() => fx.setHapticsEnabled(!fx.hapticsEnabled)}
            >
              {fx.hapticsEnabled ? <VibrationOnIcon /> : <VibrationOffIcon />}
              <span>Vibration {fx.hapticsEnabled ? "on" : "off"}</span>
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}

function ProfileMenu() {
  const { player, signOut } = useSession();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  if (!player) return null;

  return (
    <div className="profile-menu" ref={ref}>
      <button
        type="button"
        className="profile-menu__button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {player.nickname || "Player"} ▾
      </button>
      {open && (
        <ul className="profile-menu__list" role="menu">
          <li role="none">
            <NavLink to="/profile/me" role="menuitem" onClick={() => setOpen(false)}>
              My profile
            </NavLink>
          </li>
          <li role="none">
            <NavLink to="/wallet" role="menuitem" onClick={() => setOpen(false)}>
              Wallet
            </NavLink>
          </li>
          <li role="none">
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                void signOut();
              }}
            >
              Sign out
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}

/**
 * Header — now just logo, sound/vibration, balance and sign-in/profile: the primary nav row
 * (Home/Crowd/Ratings/How it works) moved to {@link BottomTabBar} (task IA), and the honest
 * DEMO/replay banners that used to sit above this header are gone from every screen this header
 * wraps (Р-63 — "removed"; the same facts now live as plain text on Wallet/How it works and in
 * the connect-wallet onboarding, not a sticky top plaque).
 */
function Header() {
  const { player, openConnectModal } = useSession();

  return (
    <header className="app-header">
      <NavLink to="/" className="app-header__logo">
        Herd
      </NavLink>
      <div className="app-header__right">
        <FxMenu />
        <BalancePill />
        {player ? (
          <ProfileMenu />
        ) : (
          <button type="button" className="btn btn--primary" onClick={openConnectModal}>
            CONNECT WALLET
          </button>
        )}
      </div>
    </header>
  );
}

/** Task IA: "bottom tab bar: Lobby / Stats / Profile" — the app's one primary nav, desktop and
 *  mobile alike (concept A, `docs/product/design/concepts.md`: "плавающая нижняя навигация-
 *  таблетка с иконками"). */
function BottomTabBar() {
  return (
    <nav className="tab-bar" aria-label="Main">
      {TAB_LINKS.map(({ to, label, end, Icon }) => (
        <NavLink key={to} to={to} end={end} className="tab-bar__item">
          <Icon className="tab-bar__icon" />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}

export function Layout() {
  // Global settlement-event watcher (implementation-plan.md §3) — runs regardless of which page
  // is mounted below, so a result moment fires "видно с любой страницы" (game-designer.md).
  useSettlementWatcher();
  // The mid-round LIQUIDATION moment (Р-50): `myPositions()` says a running position is liquidated long before
  // its round settles, so this fires then — once, remembered across reloads and tabs.
  useLiquidationWatcher();
  // "While you were away" one-shot catch-up (task item) — same reasoning: lives at the layout
  // level, not on any one page, so it runs no matter which screen the app opens on.
  useAwayReplay();
  // Android hardware Back (player review fix item 4) — native-only no-op on web, see the hook's
  // own doc comment.
  useAndroidBackButton();

  return (
    <div className="page">
      <ScrollManager />
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <Header />
      <main id="main-content" className="main-content">
        <div className="container">
          {/* "Your bets" across every market (Р-71) — in the page flow, never fixed to the screen. */}
          <PositionsStrip />
          <Outlet />
        </div>
      </main>
      <footer className="app-footer">
        <div className="container">
          Herd — crowd vs market. A toy-money demo on Chia testnet11. <NavLink to="/how-it-works">How it works</NavLink>
          {" · "}
          <NavLink to="/status">Status</NavLink>
        </div>
      </footer>
      <BottomTabBar />
      <ConnectWalletModal />
      {/* Portal-ish host (result-moment.md §1) — outside <Outlet/>, on top of everything. */}
      <ResultMomentHost />
    </div>
  );
}
