import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useApi, useConnectModalState, useSession } from "../api/ApiContext";
import { STARTER_PACK_PLAY } from "../api/contract";
import { formatPlayAmount, shortenAddress } from "../format";
import { MultiAccountDisclosureLine } from "./Common";

type Step = "connect" | "waiting" | "nickname" | "starter-pack";

/**
 * The first-time sign-in flow (`ux_spec.md` §10), one modal shared by every "CONNECT WALLET"
 * entry point in the app (header, Home/Round/Profile/Wallet "not signed in" cards). Copy is
 * quoted verbatim from the spec.
 */
export function ConnectWalletModal() {
  const { isOpen, close } = useConnectModalState();
  const session = useSession();
  const api = useApi();
  const navigate = useNavigate();
  const dialogRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);
  // Bumped on every open/close transition so a stale `startConnect()` resolution (from an
  // attempt the user abandoned by closing the modal) can recognize it's stale and bail instead
  // of clobbering whatever step a later attempt has reached.
  const attemptIdRef = useRef(0);

  const [step, setStep] = useState<Step>("connect");
  const [nickname, setNickname] = useState("");
  const [address, setAddress] = useState("");

  // Reset the flow to its first step whenever the modal transitions from closed to open —
  // during render (React's "adjusting state when a prop changes" pattern), not in an effect,
  // so it can't run a frame late.
  const [wasOpen, setWasOpen] = useState(false);
  if (isOpen !== wasOpen) {
    setWasOpen(isOpen);
    if (isOpen) {
      setStep("connect");
      setNickname("");
    }
  }

  // DOM-only side effects (focus management, scroll lock, attempt invalidation) — no state to
  // synchronize, so unlike the reset above these legitimately belong in an effect.
  useEffect(() => {
    // Every open/close transition invalidates whatever `startConnect()` attempt was in flight.
    attemptIdRef.current += 1;
    if (!isOpen) return;
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = originalOverflow;
      previouslyFocused.current?.focus?.();
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const handleClose = () => {
    close();
  };

  const FOCUSABLE_SELECTOR =
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      handleClose();
      return;
    }
    if (e.key !== "Tab") return;
    // Manual focus trap (WAI-ARIA APG dialog pattern) — cycle Tab/Shift+Tab among the dialog's
    // own focusable elements so keyboard focus can't escape into the page behind the overlay.
    const dialog = dialogRef.current;
    if (!dialog) return;
    const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (e.shiftKey) {
      if (active === first || !dialog.contains(active)) {
        e.preventDefault();
        last.focus();
      }
    } else if (active === last || !dialog.contains(active)) {
      e.preventDefault();
      first.focus();
    }
  };

  const startConnect = async () => {
    const attemptId = attemptIdRef.current;
    setStep("waiting");
    // Browser test wallet (Р-45): a real Chia key generated in this browser, persisted in
    // localStorage — every browser profile/incognito window/phone is a different player. Signs
    // the exact scheme a real Sage-backed login uses (docs/research/week1_wallet_auth.md §2).
    // Dynamically imported — its WASM crypto module (~2 MB gzipped, the whole
    // chia-wallet-sdk-wasm surface, not just the signing slice this app uses) has no reason to
    // sit in the initial bundle every visitor downloads, especially on the mobile connections
    // Р-48 targets; it only loads once someone actually opens this modal.
    const { getTestWallet, signTestWalletMessage } = await import("../wallet/testWallet");
    const wallet = getTestWallet();
    const challenge = await api.authChallenge({ address: wallet.address });
    const signed = signTestWalletMessage(challenge.message);
    // Signing itself is near-instant (WASM, no network round trip) — this short delay just
    // keeps the "waiting" state actually visible instead of flashing past it.
    await new Promise((r) => setTimeout(r, 400));
    if (attemptIdRef.current !== attemptId) return; // modal closed/reopened mid-attempt
    const session_ = await api.authVerify({
      nonce: challenge.nonce,
      address: signed.address,
      publicKey: signed.publicKey,
      signature: signed.signature,
    });
    if (attemptIdRef.current !== attemptId) return;
    setAddress(session_.player.walletAddress);
    await session.refresh();
    if (attemptIdRef.current !== attemptId) return;
    setStep(session_.isNewPlayer ? "nickname" : "starter-pack");
  };

  const submitNickname = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!nickname.trim()) return;
    await api.setNickname(nickname.trim());
    await session.refresh();
    setStep("starter-pack");
  };

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) handleClose();
      }}
    >
      <div
        ref={dialogRef}
        className="modal card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="connect-modal-title"
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        {step === "connect" && (
          <>
            <h2 id="connect-modal-title">Connect your wallet</h2>
            <p className="test-wallet-tag">TEST WALLET</p>
            <p className="muted">
              This local stand signs you in with a real Chia key generated right in your browser — not Sage. It
              doesn't move any funds, and each browser/profile is a separate player.
            </p>
            <div className="modal-actions">
              <button type="button" className="btn btn--primary btn--block btn--lg" onClick={() => void startConnect()}>
                Continue with test wallet
              </button>
            </div>
            <button type="button" className="modal-close-text" onClick={handleClose}>
              Cancel
            </button>
          </>
        )}

        {step === "waiting" && (
          <div className="text-center" style={{ padding: "24px 0" }}>
            <div className="spinner" aria-hidden="true" />
            <p style={{ marginTop: 16 }}>Signing in with your test wallet…</p>
          </div>
        )}

        {step === "nickname" && (
          <form onSubmit={(e) => void submitNickname(e)}>
            <h2 id="connect-modal-title">Pick a nickname</h2>
            <p className="muted">Shown next to your wallet address — {shortenAddress(address)}.</p>
            <div className="field">
              <label htmlFor="nickname-input">Nickname</label>
              <input
                id="nickname-input"
                type="text"
                autoFocus
                maxLength={24}
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="e.g. moonWalker"
              />
            </div>
            <MultiAccountDisclosureLine />
            <div className="modal-actions">
              <button type="submit" className="btn btn--primary btn--block btn--lg" disabled={!nickname.trim()}>
                CONTINUE
              </button>
            </div>
          </form>
        )}

        {step === "starter-pack" && (
          <div className="text-center">
            <h2 id="connect-modal-title">You've got {formatPlayAmount(STARTER_PACK_PLAY)} PLAY</h2>
            <p className="muted">Toy money for this demo — place your first bet or explore the crowd account.</p>
            <div className="modal-actions stack" style={{ gap: 10 }}>
              <button
                type="button"
                className="btn btn--primary btn--block btn--lg"
                onClick={() => {
                  handleClose();
                  navigate("/");
                }}
              >
                PLACE A BET
              </button>
              <button type="button" className="btn btn--secondary btn--block" onClick={handleClose}>
                EXPLORE
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
