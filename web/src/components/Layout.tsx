import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import { totalInPlayStake } from "../api/inPlay";
import { formatPlayAmount } from "../format";
import { ConnectWalletModal } from "./ConnectWalletModal";
import { DemoBanner, ServerInfoBanner } from "./Common";
import { CoinIcon, SpeakerOnIcon, SpeakerMuteIcon, VibrationOnIcon, VibrationOffIcon } from "./icons";
import { useSettlementWatcher } from "../fx/useSettlementWatcher";
import { ResultMomentHost } from "../fx/ResultMomentHost";
import { RollingDigits } from "../fx/RollingDigits";
import { useFxSettings } from "../fx/fxSettings";
import { primeAudioContext } from "../fx/sound";

const NAV_LINKS = [
  { to: "/", label: "Home", end: true },
  { to: "/crowd", label: "Crowd" },
  { to: "/ratings", label: "Ratings" },
  { to: "/how-it-works", label: "How it works" },
];

function BalancePill() {
  const api = useApi();
  const { player } = useSession();
  const [balance, setBalance] = useState<string | null>(null);
  // Sum of the player's stakes still tied up in an open or running round (Alex's report,
  // 2026-09-25): the free balance alone made a player who'd just won, then staked the payout on
  // the next round, look like they'd lost — nothing said the missing PLAY was reserved, not
  // gone. Built from `rounds()` (already-returned data, `api/inPlay.ts`), no contract change.
  const [inPlay, setInPlay] = useState<number | null>(null);
  const tick = useClock(4000);

  useEffect(() => {
    if (!player) return;
    let cancelled = false;
    Promise.all([api.myAccount(), api.rounds("HOURLY"), api.rounds("DAILY")]).then(
      ([acc, hourly, daily]) => {
        if (cancelled) return;
        setBalance(acc.balance);
        setInPlay(totalInPlayStake(player.playerId, hourly, daily));
      },
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player, tick]);

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
      {inPlay !== null && inPlay > 0 && (
        <span className="balance-pill__in-play">+{formatPlayAmount(inPlay)} in play</span>
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

function Header() {
  const { player, openConnectModal } = useSession();
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <>
      <header className="app-header">
        <NavLink to="/" className="app-header__logo">
          Herd
        </NavLink>
        <nav className="app-header__nav" aria-label="Main">
          {NAV_LINKS.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end}>
              {link.label}
            </NavLink>
          ))}
        </nav>
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
        <button
          type="button"
          className="hamburger"
          aria-expanded={mobileOpen}
          aria-label="Open navigation menu"
          onClick={() => setMobileOpen((v) => !v)}
        >
          ☰
        </button>
      </header>
      {mobileOpen && (
        <nav className="mobile-nav" aria-label="Main mobile">
          {NAV_LINKS.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end} onClick={() => setMobileOpen(false)}>
              {link.label}
            </NavLink>
          ))}
        </nav>
      )}
    </>
  );
}

export function Layout() {
  // Global settlement-event watcher (implementation-plan.md §3) — runs regardless of which page
  // is mounted below, so a result moment fires "видно с любой страницы" (game-designer.md).
  useSettlementWatcher();

  return (
    <div className="page">
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <DemoBanner />
      <Header />
      <main id="main-content" className="main-content">
        <div className="container">
          <ServerInfoBanner />
          <Outlet />
        </div>
      </main>
      <footer className="app-footer">
        <div className="container">Herd — crowd vs market. A toy-money demo on Chia testnet11.</div>
      </footer>
      <ConnectWalletModal />
      {/* Portal-ish host (result-moment.md §1) — outside <Outlet/>, on top of everything. */}
      <ResultMomentHost />
    </div>
  );
}
