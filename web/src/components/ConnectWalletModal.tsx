import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useApi, useConnectModalState, useSession } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import { STARTER_PACK_PLAY } from "../api/contract";
import { checkNickname, hasPlaceholderNickname, NICKNAME_HINT, NICKNAME_MAX } from "../api/nickname";
import { prefetchServerInfo, useServerInfo } from "../api/useServerInfo";
import { formatPlayAmount, shortenAddress } from "../format";
import { connectAndSignWithSage, getWalletConnectProjectId, rememberSageAddress } from "../wallet/walletConnect";
import { QrCode } from "../wallet/QrCode";
import { CopyButton, MultiAccountDisclosureLine, Skeleton } from "./Common";
import { SageSignInTips } from "./SageHints";

type Step = "connect" | "sage-qr" | "waiting" | "nickname" | "starter-pack" | "error";

/** How long "Opening a connection to Sage…" is allowed to spin before the sheet says Sage could not be reached (first tester
 *  pass, D5: the relay's socket opened and closed within 3 s and the sheet spun for ever, with no word and no way on). */
export const SAGE_CONNECT_WAIT_MS = 15_000;

/**
 * The first-time sign-in flow (`ux_spec.md` §10), one modal shared by every "CONNECT WALLET"
 * entry point in the app (header, Home/Round/Profile/Wallet "not signed in" cards). Copy is
 * quoted verbatim from the spec. Two connect routes as of Р-55: the always-available browser test
 * wallet (Р-45, `wallet/testWallet.ts`) and real Sage over WalletConnect (`wallet/walletConnect.ts`)
 * — the latter's button is disabled with "Coming soon" until a Reown `projectId` is configured
 * (build-time `VITE_WALLETCONNECT_PROJECT_ID` or `ServerInfoDto.walletConnectProjectId` at
 * runtime), which as of this task is not yet the case (Alex's registration is in progress).
 */
export function ConnectWalletModal() {
  const { isOpen, close } = useConnectModalState();
  const session = useSession();
  const api = useApi();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const dialogRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);
  // Bumped on every open/close transition so a stale connect-attempt resolution (from an attempt
  // the user abandoned by closing the modal) can recognize it's stale and bail instead of
  // clobbering whatever step a later attempt has reached.
  const attemptIdRef = useRef(0);

  const [step, setStep] = useState<Step>("connect");
  const [nickname, setNickname] = useState("");
  const [address, setAddress] = useState("");
  const [pairingUri, setPairingUri] = useState("");
  const [errorMessage, setErrorMessage] = useState("");
  const [signInVia, setSignInVia] = useState<"test wallet" | "Sage">("test wallet");
  // Sage's pairing link has not arrived within SAGE_CONNECT_WAIT_MS: it could not be reached, and the sheet says so.
  const [sageStalled, setSageStalled] = useState(false);
  // Counts the Sage attempts of this sheet, so that "Try Sage again" starts a fresh 15 s wait (the step and the link do not change).
  const [sageAttempt, setSageAttempt] = useState(0);
  const sageProjectIdRef = useRef("");
  const { status: serverInfoStatus, info: serverInfo } = useServerInfo();
  const testWalletOffered = serverInfoStatus === "unsupported" ? true : serverInfo?.testWallet !== false;
  // The nickname step: the server's own refusal, shown beside the field — and whether a save is on its way.
  const [nicknameError, setNicknameError] = useState<string | null>(null);
  const [savingNickname, setSavingNickname] = useState(false);
  // This sign-in made the player (`AuthSessionDto.isNewPlayer`): they pick a nickname and are told about the first pack. A returning
  // player is not told about it again — their balance is in the header, whatever it has become — except when THIS sign-in credited
  // a pack (`starterPackGranted`: the first sign-in of a new month, when a pack comes on top of the balance they have).
  const [startedAsNewPlayer, setStartedAsNewPlayer] = useState(false);
  // This sign-in credited a starter pack, so the dialog that says so is owed — after the nickname, when one is asked for.
  const [packGranted, setPackGranted] = useState(false);

  // What the "connect" step offers (the test wallet, Sage) depends on `serverInfo()`: `ConnectStep` below reads it from
  // the shared store — unknown until the server answers, never assumed. The answer is asked for as soon as the app
  // loads (one request, no poll), so by the time the sheet opens it is usually there already.
  useEffect(() => {
    prefetchServerInfo(api);
  }, [api]);

  // Reset the flow to its first step whenever the modal transitions from closed to open —
  // during render (React's "adjusting state when a prop changes" pattern), not in an effect,
  // so it can't run a frame late.
  const [wasOpen, setWasOpen] = useState(false);
  if (isOpen !== wasOpen) {
    setWasOpen(isOpen);
    if (isOpen) {
      setStep("connect");
      setNickname("");
      setPairingUri("");
      setErrorMessage("");
      setNicknameError(null);
      setSavingNickname(false);
      setStartedAsNewPlayer(false);
      setPackGranted(false);
      setSageStalled(false);
    }
  }

  // DOM-only side effects (focus management, scroll lock, attempt invalidation) — no state to
  // synchronize, so unlike the reset above these legitimately belong in an effect.
  useEffect(() => {
    // Every open/close transition invalidates whatever connect attempt was in flight.
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

  // "Opening a connection to Sage…" is a wait for the pairing link; past SAGE_CONNECT_WAIT_MS with none, Sage is not
  // reachable (and a link that turns up later still shows its QR: the sheet does not stay wrong).
  useEffect(() => {
    if (step !== "sage-qr" || pairingUri) return;
    const timer = window.setTimeout(() => setSageStalled(true), SAGE_CONNECT_WAIT_MS);
    return () => window.clearTimeout(timer);
  }, [step, pairingUri, sageAttempt]);

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

  /** Shared tail of both connect routes below, once a `(address, publicKey, signature)` triple
   *  exists — identical to what `startConnect`/`startSageConnect` used to each do inline. */
  const finishSignIn = async (
    attemptId: number,
    signed: { address: string; publicKey: string; signature: string },
    nonce: string,
  ) => {
    const session_ = await api.authVerify({ nonce, ...signed });
    if (attemptIdRef.current !== attemptId) return;
    setAddress(session_.player.walletAddress);
    await session.refresh();
    if (attemptIdRef.current !== attemptId) return;
    // A pack was credited by this sign-in: a new player's, or a returning player's new month's (an older server and the mock send
    // no `starterPackGranted`, and `isNewPlayer` then stands for it).
    const granted = session_.isNewPlayer || session_.starterPackGranted === true;
    setStartedAsNewPlayer(session_.isNewPlayer);
    setPackGranted(granted);
    // A new player picks a nickname and is then told about the starter pack. A returning one is signed in: the sheet goes away
    // and leaves them where they were — unless a pack was credited (told once, below), or they left the first sign-in at the
    // nickname step, which the server remembers as no nickname at all (the wallet address stands in): it is asked for once more.
    if (session_.isNewPlayer || hasPlaceholderNickname(session_.player)) setStep("nickname");
    else if (granted) setStep("starter-pack");
    else close();
  };

  const startConnect = async () => {
    // A new attempt invalidates the one before it: a Sage connection abandoned for the test wallet must not wake up
    // later, finish its sign-in and take the sheet over.
    const attemptId = ++attemptIdRef.current;
    setSignInVia("test wallet");
    setStep("waiting");
    try {
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
      await finishSignIn(attemptId, signed, challenge.nonce);
    } catch (err) {
      if (attemptIdRef.current !== attemptId) return;
      setErrorMessage(mutationErrorText(err, "Something went wrong signing in."));
      setStep("error");
    }
  };

  /** Real Sage sign-in (Р-55). `wallet/walletConnect.ts` owns the WalletConnect
   *  pairing/session/signing; this only wires its callbacks to `Api.authChallenge`/`authVerify`,
   *  the same two calls `startConnect` makes for the test wallet — `nonce` is threaded through a
   *  closure variable because `connectAndSignWithSage`'s `getMessage` callback only returns the
   *  message text (`chia_signMessageByAddress`'s own param), not the nonce `authVerify` also
   *  needs. */
  const startSageConnect = async (wcProjectId: string) => {
    const attemptId = ++attemptIdRef.current;
    sageProjectIdRef.current = wcProjectId;
    setSignInVia("Sage");
    setSageStalled(false);
    setSageAttempt((n) => n + 1);
    setPairingUri("");
    setStep("sage-qr");
    let nonce = "";
    try {
      const signed = await connectAndSignWithSage(
        wcProjectId,
        async (addr) => {
          const challenge = await api.authChallenge({ address: addr });
          nonce = challenge.nonce;
          return challenge.message;
        },
        (uri) => {
          if (attemptIdRef.current === attemptId) setPairingUri(uri);
        },
      );
      if (attemptIdRef.current !== attemptId) return;
      setStep("waiting");
      rememberSageAddress(signed.address);
      await finishSignIn(attemptId, signed, nonce);
    } catch (err) {
      if (attemptIdRef.current !== attemptId) return;
      setErrorMessage(mutationErrorText(err, "Something went wrong connecting to Sage."));
      setStep("error");
    }
  };

  // Checked here first, with the reason in words (`api/nickname.ts` mirrors the server's rule); if the server refuses
  // anyway — 400 `INVALID_NICKNAME`, or 503 while it restarts — its sentence stays beside the field and the step stays.
  const nicknameCheck = checkNickname(nickname);
  const submitNickname = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!nicknameCheck.ok || savingNickname) return;
    const attemptId = attemptIdRef.current;
    setSavingNickname(true);
    setNicknameError(null);
    try {
      await api.setNickname(nicknameCheck.value);
      await session.refresh();
      if (attemptIdRef.current !== attemptId) return; // the sheet was closed meanwhile
      if (packGranted) setStep("starter-pack");
      else close();
    } catch (err) {
      if (attemptIdRef.current !== attemptId) return;
      setNicknameError(mutationErrorText(err, "Couldn't save the nickname — try again."));
    } finally {
      setSavingNickname(false);
    }
  };

  // "Not now" on the second asking: the player leaves the nickname for later, but not the news — a pack that was credited is still told.
  const skipNickname = () => {
    if (packGranted) setStep("starter-pack");
    else close();
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
          <ConnectStep onTestWallet={() => void startConnect()} onSage={(projectId) => void startSageConnect(projectId)} onCancel={handleClose} />
        )}

        {step === "sage-qr" && (
          <div className="text-center">
            <h2 id="connect-modal-title">Connect with Sage</h2>
            {pairingUri ? (
              <>
                <div className="qr-frame">
                  <QrCode value={pairingUri} size={220} label="WalletConnect pairing QR code — scan with Sage" />
                </div>
                <p className="muted">
                  Open Sage on your phone or desktop, scan this code, then approve the connection and the sign-in
                  message it shows you.
                </p>
                <CopyButton value={pairingUri} label="Copy connection link" className="btn btn--secondary btn--block" />
                <SageSignInTips />
              </>
            ) : sageStalled ? (
              <div className="stack" style={{ gap: 12, padding: "12px 0" }}>
                <div role="alert">
                  <p className="field-error">Couldn't reach Sage.</p>
                  <p className="muted">
                    The connection it needs did not open within {SAGE_CONNECT_WAIT_MS / 1000} seconds — it may be your network, or this site's
                    address may not be allowed to use WalletConnect yet.
                  </p>
                </div>
                {testWalletOffered && (
                  <button type="button" className="btn btn--primary btn--block btn--lg" onClick={() => void startConnect()}>
                    Continue with test wallet
                  </button>
                )}
                <button
                  type="button"
                  className={`btn ${testWalletOffered ? "btn--secondary" : "btn--primary btn--lg"} btn--block`}
                  onClick={() => void startSageConnect(sageProjectIdRef.current)}
                >
                  Try Sage again
                </button>
              </div>
            ) : (
              <div style={{ padding: "24px 0" }}>
                <div className="spinner" aria-hidden="true" />
                <p style={{ marginTop: 16 }}>Opening a connection to Sage…</p>
              </div>
            )}
            <button type="button" className="modal-close-text" onClick={handleClose}>
              Cancel
            </button>
          </div>
        )}

        {step === "waiting" && (
          <div className="text-center" style={{ padding: "24px 0" }}>
            <div className="spinner" aria-hidden="true" />
            <p style={{ marginTop: 16 }}>
              {signInVia === "Sage" ? "Waiting for the sign-in message to be approved in Sage…" : "Signing in with your test wallet…"}
            </p>
          </div>
        )}

        {step === "error" && (
          <div className="text-center">
            <h2 id="connect-modal-title">Couldn't connect</h2>
            <p className="field-error">{errorMessage}</p>
            <div className="modal-actions stack" style={{ gap: 10 }}>
              {signInVia === "Sage" && testWalletOffered && (
                <button type="button" className="btn btn--primary btn--block btn--lg" onClick={() => void startConnect()}>
                  Continue with test wallet
                </button>
              )}
              <button
                type="button"
                className={`btn ${signInVia === "Sage" && testWalletOffered ? "btn--secondary" : "btn--primary btn--lg"} btn--block`}
                onClick={() => setStep("connect")}
              >
                Try again
              </button>
              <button type="button" className="btn btn--secondary btn--block" onClick={handleClose}>
                Cancel
              </button>
            </div>
          </div>
        )}

        {step === "nickname" && (
          <form onSubmit={(e) => void submitNickname(e)} noValidate>
            <h2 id="connect-modal-title">Pick a nickname</h2>
            <p className="muted">Shown next to your wallet address — {shortenAddress(address)}.</p>
            <div className="field">
              <label htmlFor="nickname-input">Nickname</label>
              <input
                id="nickname-input"
                type="text"
                autoFocus
                maxLength={NICKNAME_MAX}
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                value={nickname}
                onChange={(e) => {
                  setNickname(e.target.value);
                  setNicknameError(null);
                }}
                placeholder="e.g. moonWalker"
                aria-describedby="nickname-hint nickname-error"
                aria-invalid={nicknameError !== null || (!nicknameCheck.ok && nicknameCheck.reason !== null)}
              />
              <p id="nickname-hint" className="field-hint">
                {NICKNAME_HINT}
              </p>
              {/* What is wrong with what is typed — or the server's own refusal; one line, in words. */}
              <p id="nickname-error" className="field-error" role="alert" hidden={!(nicknameError ?? (nicknameCheck.ok ? null : nicknameCheck.reason))}>
                {nicknameError ?? (nicknameCheck.ok ? null : nicknameCheck.reason)}
              </p>
            </div>
            <MultiAccountDisclosureLine />
            <div className="modal-actions stack" style={{ gap: 10 }}>
              <button type="submit" className="btn btn--primary btn--block btn--lg" disabled={!nicknameCheck.ok || savingNickname}>
                {savingNickname ? "SAVING…" : "CONTINUE"}
              </button>
              {/* A returning player who left their first sign-in at this step is asked once more — and may leave it again. A new
                  player is picking the first one. */}
              {!startedAsNewPlayer && (
                <button type="button" className="btn btn--secondary btn--block" disabled={savingNickname} onClick={skipNickname}>
                  Not now
                </button>
              )}
            </div>
          </form>
        )}

        {step === "starter-pack" && (
          <div className="text-center">
            {startedAsNewPlayer ? (
              <>
                <h2 id="connect-modal-title">You've got {formatPlayAmount(STARTER_PACK_PLAY)} PLAY</h2>
                <p className="muted">
                  PLAY — test tokens for this demo, not real money. Place your first bet or explore the crowd account.
                </p>
              </>
            ) : (
              <>
                {/* A returning player's pack comes on top of the balance they have: "You've got 10,000" would be wrong. */}
                <h2 id="connect-modal-title">New season: +{formatPlayAmount(STARTER_PACK_PLAY)} PLAY</h2>
                <p className="muted">
                  A new month started, so a new starter pack was added to your balance. PLAY — test tokens for this demo, not real money.
                </p>
              </>
            )}
            <div className="modal-actions stack" style={{ gap: 10 }}>
              <button
                type="button"
                className="btn btn--primary btn--block btn--lg"
                onClick={() => {
                  handleClose();
                  // Signed in from a room (or the lobby) the player is already where the bet is: the button must
                  // not throw the room away. From any other screen the lobby is where a first bet starts.
                  if (!isBettingScreen(pathname)) navigate("/");
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

/** The screens a first bet is placed from: the lobby (its cards lead to a room) and a room itself. */
function isBettingScreen(pathname: string): boolean {
  return pathname === "/" || pathname.startsWith("/play/");
}

/**
 * The "connect" step: which ways in this server offers. Both depend on its `serverInfo()` — whether the browser test
 * wallet is on (`testWallet`, Р-45: `false` → no test-wallet button at all, not a disabled one) and whether Sage has a
 * Reown `projectId` (`walletConnectProjectId` or the build's own) — so until the server has answered NOTHING is offered
 * (a placeholder), and a server that cannot be reached says so with a retry instead of guessing; a later answer applies
 * by itself (the shared store, `api/useServerInfo.ts`). A build with no `serverInfo()` at all (the mock) offers the
 * test wallet and Sage as the build's own project id allows.
 */
function ConnectStep({ onTestWallet, onSage, onCancel }: { onTestWallet: () => void; onSage: (projectId: string) => void; onCancel: () => void }) {
  const { status, info, refresh } = useServerInfo();
  const known = status === "ready" || status === "unsupported";
  const testWalletOffered = status === "unsupported" ? true : info?.testWallet !== false;
  const wcProjectId = known ? getWalletConnectProjectId(info) : null;

  return (
    <>
      <h2 id="connect-modal-title">Connect your wallet</h2>
      {status === "loading" && (
        <div className="stack" style={{ gap: 12 }} role="status" aria-label="Asking the server how you can sign in">
          <Skeleton height={20} width="70%" />
          <Skeleton height={52} />
          <Skeleton height={52} />
        </div>
      )}
      {status === "error" && (
        <div className="stack" style={{ gap: 12 }}>
          <p className="field-error" role="alert">
            Can't reach the server right now, so we can't tell how you can sign in. Check your connection and try again.
          </p>
          <button type="button" className="btn btn--primary btn--block btn--lg" onClick={refresh}>
            Try again
          </button>
        </div>
      )}
      {known && (
        <>
          {testWalletOffered ? (
            <>
              {/* The test wallet leads on this stand (HANDOFF §6 п.10): it is the one path that works for
               *  everybody — the Sage button first was a dead end for a visitor without Sage. Both stay. */}
              <p className="test-wallet-tag">TEST WALLET</p>
              <p className="muted">
                Fastest way in: this stand signs you in with a real Chia key generated right in your browser — not Sage. It doesn't move any
                funds, and each browser/profile is a separate player.
              </p>
              <div className="modal-actions" style={{ marginTop: 0 }}>
                <button type="button" className="btn btn--primary btn--block btn--lg" onClick={onTestWallet}>
                  Continue with test wallet
                </button>
              </div>
              <div className="modal-or" role="separator">
                <span>or</span>
              </div>
            </>
          ) : (
            <p className="muted">
              This server signs you in with your own Chia wallet (Sage) — a real signature on testnet11; it doesn't move any funds.
            </p>
          )}
          <button
            type="button"
            className={`btn ${testWalletOffered ? "btn--secondary" : "btn--primary"} btn--block btn--lg`}
            onClick={() => wcProjectId && onSage(wcProjectId)}
            disabled={!wcProjectId}
            title={wcProjectId ? undefined : "Coming soon — needs WalletConnect project id"}
          >
            Connect with Sage
          </button>
          {!wcProjectId ? (
            <p className="field-hint">
              {testWalletOffered ? "Coming soon — needs WalletConnect project id." : "Sign-in isn't available on this server yet — Sage needs a WalletConnect project id."}
            </p>
          ) : (
            <p className="field-hint">Real wallet, real signature — Sage needs to be on testnet11.</p>
          )}
        </>
      )}
      <button type="button" className="modal-close-text" onClick={onCancel}>
        Cancel
      </button>
    </>
  );
}
