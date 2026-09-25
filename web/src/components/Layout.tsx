import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { useClock } from "../api/useClock";
import { formatPlayAmount } from "../format";
import { ConnectWalletModal } from "./ConnectWalletModal";
import { DemoBanner } from "./Common";

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
  const tick = useClock(4000);

  useEffect(() => {
    if (!player) return;
    let cancelled = false;
    api.myAccount().then((acc) => {
      if (!cancelled) setBalance(acc.balance);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player, tick]);

  if (!player || balance === null) return null;
  return (
    <NavLink to="/wallet" className="balance-pill">
      {formatPlayAmount(balance)} PLAY
    </NavLink>
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
  return (
    <div className="page">
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <DemoBanner />
      <Header />
      <main id="main-content" className="main-content">
        <div className="container">
          <Outlet />
        </div>
      </main>
      <footer className="app-footer">
        <div className="container">Herd — crowd vs market. A toy-money demo on Chia testnet11.</div>
      </footer>
      <ConnectWalletModal />
    </div>
  );
}
