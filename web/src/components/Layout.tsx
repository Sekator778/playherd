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
    <NavLink to="/wallet" className="balance-pill">
      <CoinIcon className="balance-pill__coin" />
      {/* Rolls on ANY change, not only while a result moment is on screen — the direct answer to
       *  Alex's "чтобы сумма выигрыша или проигрыша... сразу это проявлялось"
       *  (implementation-plan.md §2). */}
      <span className="balance-pill__amount">
        <RollingDigits value={Number(balance)} format={(n) => `${formatPlayAmount(n)} PLAY`} durationMs={500} />
      </span>
      {inPlay !== null && inPlay > 0 && (
        <span className="balance-pill__in-play">+{formatPlayAmount(inPlay, { decimals: 0 })} in play</span>
      )}
    </NavLink>
  );
}

/** Sound/haptics toggle pair (direction.md §6) — lives in the header, next to the profile menu. */
function FxToggles() {
  const fx = useFxSettings();
  return (
    <div className="lab-row" style={{ gap: 6 }}>
      <button
        type="button"
        className="fx-toggle"
        aria-pressed={fx.sfxEnabled}
        aria-label={fx.sfxEnabled ? "Mute sound" : "Unmute sound"}
        title={fx.sfxEnabled ? "Sound on" : "Sound off"}
        onClick={() => {
          if (!fx.sfxEnabled) primeAudioContext();
          fx.setSfxEnabled(!fx.sfxEnabled);
        }}
      >
        {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
      </button>
      <button
        type="button"
        className="fx-toggle"
        aria-pressed={fx.hapticsEnabled}
        aria-label={fx.hapticsEnabled ? "Turn off vibration" : "Turn on vibration"}
        title={fx.hapticsEnabled ? "Vibration on" : "Vibration off"}
        onClick={() => fx.setHapticsEnabled(!fx.hapticsEnabled)}
      >
        {fx.hapticsEnabled ? <VibrationOnIcon /> : <VibrationOffIcon />}
      </button>
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
          <FxToggles />
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
