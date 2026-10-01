import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { checkPlayAmount, PLAY_DECIMALS } from "../api/amountInput";
import { usePoll } from "../api/usePoll";
import { MIN_WITHDRAWAL_PLAY, type LedgerEntryDto, type LedgerEntryType, type PlayerAccountDto, type WithdrawalRequestDto } from "../api/contract";
import { CopyButton, EmptyState, ErrorState, ServerInfoBanner, Skeleton } from "../components/Common";
import { formatDateTimeUtc, formatPlayAmount, shortenAddress } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
import { useFxSettings } from "../fx/fxSettings";
import { primeAudioContext } from "../fx/sound";
import { QrCode } from "../wallet/QrCode";
import { SageDepositHints } from "../components/SageHints";
import {
  ArrowDownTrayIcon,
  ArrowUpTrayIcon,
  ChipIcon,
  EqualsDiscIcon,
  GiftIcon,
  MinusDiscIcon,
  RefundIcon,
  SpeakerMuteIcon,
  SpeakerOnIcon,
  TrophyIcon,
  VibrationOffIcon,
  VibrationOnIcon,
} from "../components/icons";

const LEDGER_LABEL: Record<LedgerEntryType, string> = {
  STARTER_PACK: "Starter pack",
  STAKE_DEBIT: "Bet placed",
  STAKE_REFUND: "Bet refunded",
  SETTLEMENT: "Round settled",
  DEPOSIT: "Deposit confirmed",
  WITHDRAWAL: "Withdrawal requested",
  WITHDRAWAL_REFUND: "Withdrawal refunded",
};

/** direction.md §3's icon pattern, applied per ledger-entry-type (§4 names Wallet's ledger as one
 *  of the "long list" screens it targets) — was plain text-only rows, indistinguishable from a
 *  bank statement. `SETTLEMENT` has no fixed glyph of its own: it reuses the same win/loss/even
 *  icons as the result-moment card, keyed off the entry's own signed `delta`. */
function ledgerIcon(entry: LedgerEntryDto): ReactNode {
  switch (entry.type) {
    case "STARTER_PACK":
      return <GiftIcon />;
    case "STAKE_DEBIT":
      return <ChipIcon />;
    case "STAKE_REFUND":
    case "WITHDRAWAL_REFUND":
      return <RefundIcon />;
    case "DEPOSIT":
      return <ArrowDownTrayIcon />;
    case "WITHDRAWAL":
      return <ArrowUpTrayIcon />;
    case "SETTLEMENT": {
      const delta = Number(entry.delta);
      return delta > 0 ? <TrophyIcon /> : delta < 0 ? <MinusDiscIcon /> : <EqualsDiscIcon />;
    }
    default:
      return null;
  }
}

/** Balance — the Wallet page's hero number (direction.md §0/§4): rolls on every change via the
 *  same `RollingDigits` the result-moment card and header balance pill use, and flashes its
 *  border win/loss-colored for one cycle when it moves (`value-flash-up`/`-down`, fx.css) — the
 *  same treatment `live-round.md` §5 gives the Crowd page's stat tiles. Doubles as the "live"
 *  feedback for an incoming deposit (Р-53, `ux_spec.md` §8): once `usePoll` below picks up the
 *  resulting `DEPOSIT` ledger entry, the balance rolls and flashes on its own, no separate
 *  "deposit arrived" mechanism needed. */
function BalanceCard({ balance }: { balance: string }) {
  const numeric = Number(balance);
  const prevRef = useRef(numeric);
  const [flashClass, setFlashClass] = useState("");

  useEffect(() => {
    if (prevRef.current === numeric) return;
    setFlashClass(numeric > prevRef.current ? "value-flash-up" : "value-flash-down");
    prevRef.current = numeric;
    const t = window.setTimeout(() => setFlashClass(""), 450);
    return () => window.clearTimeout(t);
  }, [numeric]);

  return (
    <div className={`card ${flashClass}`}>
      <div className="stat-tile__label">Balance</div>
      <div className="stat-tile__value" style={{ fontSize: 32 }}>
        <RollingDigits value={numeric} durationMs={500} format={(n) => `${formatPlayAmount(n)} PLAY`} />
      </div>
    </div>
  );
}

/**
 * Real on-chain deposit (Р-53): the player's own dedicated address (task's FIXED INTERFACE,
 * chainsvc `POST /v1/play/deposit-address`, proxied by the server as `myDepositAddress`) — they
 * send PLAY to it themselves from Sage; there is nothing to submit here. Replaces the old
 * self-reported "amount you sent" form entirely (`contract.ts`'s `DepositAddressDto` doc —
 * independent critique, 2026-09-26: that form credited any typed-in amount with zero on-chain
 * check, "5,000,000 fake PLAY minted in ten seconds"). "Live status" is the page's own balance/
 * ledger polling (`WalletContent`'s `usePoll`) surfacing the resulting `DEPOSIT` entry once
 * chainsvc reports it confirmed — there's no separate "pending deposit" state to show (chainsvc
 * only ever reports already-confirmed coins, `DepositAddressDto`'s doc comment).
 */
function DepositCard() {
  const api = useApi();
  const [address, setAddress] = useState<string | null>(null);
  // The real failure text (`ApiException.depositVerificationUnavailable`/`chainServiceUnavailable`
  // on the server, e.g. "deposits are not available yet") is more honest than a generic retry
  // prompt when the real cause is "this stand doesn't have deposits turned on yet" — surfaced
  // as-is rather than replaced, same reasoning as `WithdrawCard`'s `errorMessage`.
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .myDepositAddress()
      .then((r) => {
        setAddress(r.address);
        setErrorMessage(null);
      })
      .catch((err) => setErrorMessage(err instanceof Error ? err.message : "Couldn't load your deposit address."));
  }, [api]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="card">
      <h3>Deposit from wallet</h3>
      {errorMessage ? (
        <ErrorState message={errorMessage} onRetry={load} />
      ) : !address ? (
        <div className="stack" style={{ gap: 12, alignItems: "center" }}>
          <Skeleton height={200} width={200} />
          <Skeleton height={16} width="80%" />
        </div>
      ) : (
        <div className="stack" style={{ gap: 12 }}>
          <div className="qr-frame">
            <QrCode value={address} size={200} label="Deposit address QR code — scan with Sage" />
          </div>
          <p className="mono deposit-address" aria-label="Deposit address">
            {address}
          </p>
          <CopyButton value={address} label="Copy address" className="btn btn--secondary btn--block" />
          <p className="muted">
            Send PLAY from Sage to this address — credited after confirmation on testnet11 (~1–5 min).
          </p>
          <p className="disclaimer-line">
            <span className="live-dot" aria-hidden="true" /> Watching for deposits — this page checks automatically.
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * Reduces `WithdrawalRequestDto`'s two real fields (`state` — REQUESTED until a terminal
 * outcome — and `chainState`, chainsvc's own granular word) down to exactly the four states the
 * task asks the UI to show: queued/in_flight/confirmed/failed. `state` wins once it's terminal;
 * otherwise anything short of `"in_flight"` (null, `"submitted"`, `"queued"`) all read the same
 * to a player — "queued, not on chain yet" — so there's no separate UI state for "accepted by our
 * server but not yet handed to chainsvc" vs. "chainsvc has it queued".
 */
type WithdrawalDisplayState = "QUEUED" | "IN_FLIGHT" | "CONFIRMED" | "FAILED";
function displayWithdrawalState(w: WithdrawalRequestDto): WithdrawalDisplayState {
  if (w.state === "CONFIRMED" || w.state === "FAILED") return w.state;
  return w.chainState === "in_flight" ? "IN_FLIGHT" : "QUEUED";
}

const WITHDRAWAL_DISPLAY_LABEL: Record<WithdrawalDisplayState, string> = {
  QUEUED: "QUEUED",
  IN_FLIGHT: "IN FLIGHT",
  CONFIRMED: "CONFIRMED",
  FAILED: "FAILED",
};
const WITHDRAWAL_DISPLAY_CLASS: Record<WithdrawalDisplayState, string> = {
  QUEUED: "badge--betting",
  IN_FLIGHT: "badge--live",
  CONFIRMED: "badge--settled",
  FAILED: "badge--cancelled",
};

/** No public testnet11 block explorer is reliably up (`week1_testnet_results.md`: spacescan.io
 *  504/403, coinset.org has no per-network human-readable page) — shown as plain reference text,
 *  never a link (same reasoning as `ResultsHashLine`'s "resultsHash" wouldn't link to a dead
 *  page either). */
function WithdrawalStatusRow({ w }: { w: WithdrawalRequestDto }) {
  const display = displayWithdrawalState(w);
  return (
    <li className="list-row" style={{ flexDirection: "column", alignItems: "stretch", gap: 6 }}>
      <span className="list-row__main" style={{ justifyContent: "space-between", width: "100%" }}>
        <span className="mono">{formatPlayAmount(w.amount)} PLAY</span>
        <span className={`badge ${WITHDRAWAL_DISPLAY_CLASS[display]}`}>{WITHDRAWAL_DISPLAY_LABEL[display]}</span>
      </span>
      {display === "CONFIRMED" && (
        <span className="faint mono">
          Coin {w.coinId ? shortenAddress(w.coinId) : "—"}
          {w.height !== null ? ` at height ${w.height.toLocaleString("en-US")}` : ""}
        </span>
      )}
      {display === "FAILED" && <span className="faint">{w.failureReason ?? "Failed — refunded to your balance."}</span>}
      {(display === "QUEUED" || display === "IN_FLIGHT") && (
        <span className="faint">
          {display === "QUEUED" ? "Waiting for the next treasury batch." : "Submitted to testnet11 — waiting for confirmation."}
        </span>
      )}
    </li>
  );
}

/**
 * Real on-chain withdrawal (Р-53): destination is always the player's own registered address
 * (Р-57) — shown, never editable, so there's no risk of a typo sending PLAY somewhere unrecoverable.
 * Polls `Api.withdrawal(requestId)` (task's FIXED INTERFACE state machine, mirrored 1:1 by
 * `WithdrawalState`) until it reaches `CONFIRMED`/`FAILED`, same polling shape as the rest of this
 * app (`usePoll`), just started per-request rather than on a fixed screen-level interval.
 */
function WithdrawCard({ balance, address, onBalanceChange }: { balance: number; address: string; onBalanceChange: () => void }) {
  const api = useApi();
  const [amount, setAmount] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [recent, setRecent] = useState<WithdrawalRequestDto[]>([]);
  const pollTimeouts = useRef<number[]>([]);

  useEffect(
    () => () => {
      pollTimeouts.current.forEach((id) => window.clearTimeout(id));
    },
    [],
  );

  const check = checkPlayAmount(amount, balance);
  const belowMin = check.isPositive && check.amount < Number(MIN_WITHDRAWAL_PLAY);
  const valid = check.valid && !belowMin;

  const pollUntilSettled = (requestId: string) => {
    const tick = () => {
      api
        .withdrawal(requestId)
        .then((updated) => {
          setRecent((prev) => prev.map((w) => (w.requestId === requestId ? updated : w)));
          if (updated.state === "CONFIRMED" || updated.state === "FAILED") {
            if (updated.state === "FAILED") onBalanceChange(); // refunded — balance changed again
            return;
          }
          pollTimeouts.current.push(window.setTimeout(tick, 1500));
        })
        .catch(() => {
          pollTimeouts.current.push(window.setTimeout(tick, 3000)); // transient error — keep trying
        });
    };
    pollTimeouts.current.push(window.setTimeout(tick, 1500));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || submitting) return;
    setSubmitting(true);
    setErrorMessage("");
    try {
      const record = await api.requestWithdrawal(check.amount.toFixed(PLAY_DECIMALS));
      setRecent((prev) => [record, ...prev]);
      setAmount("");
      onBalanceChange();
      pollUntilSettled(record.requestId);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : "Something went wrong requesting the withdrawal.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card">
      <h3>Withdraw to wallet</h3>
      <form onSubmit={(e) => void submit(e)} className="stack" style={{ gap: 12 }}>
        <div className="field">
          <label htmlFor="withdraw-amount">Amount (PLAY)</label>
          <input
            id="withdraw-amount"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="0"
          />
          <p className="field-hint">Balance: {formatPlayAmount(balance)} PLAY</p>
          {amount.trim() !== "" && !check.isPositive && <p className="field-error">Enter an amount greater than 0.</p>}
          {check.isPositive && check.tooManyDecimals && (
            <p className="field-error">At most {PLAY_DECIMALS} decimal places.</p>
          )}
          {check.isPositive && !check.tooManyDecimals && belowMin && (
            <p className="field-error">Minimum withdrawal is {formatPlayAmount(MIN_WITHDRAWAL_PLAY)} PLAY.</p>
          )}
          {check.overBalance && (
            <p className="field-error">
              Not enough PLAY — you have {formatPlayAmount(balance)} PLAY, this withdrawal needs {formatPlayAmount(check.amount)} PLAY.
            </p>
          )}
        </div>
        <div className="field">
          <span className="field-hint">Destination — your registered address:</span>
          <p className="mono" style={{ margin: 0 }}>
            {shortenAddress(address)}
          </p>
        </div>
        {errorMessage && <p className="field-error">{errorMessage}</p>}
        <button type="submit" className="btn btn--primary btn--block" disabled={!valid || submitting}>
          {submitting ? "Requesting…" : "Withdraw"}
        </button>
      </form>
      {recent.length > 0 && (
        <ul className="list-card" style={{ marginTop: 16 }}>
          {recent.map((w) => (
            <WithdrawalStatusRow key={w.requestId} w={w} />
          ))}
        </ul>
      )}
    </div>
  );
}

/** Task IA: "Profile (wallet, deposit/withdraw, settings incl. sound/vibration)" — the same
 *  toggles the header's `FxMenu` popover already exposes (`Layout.tsx`, `fx/fxSettings.ts`), also
 *  surfaced here as plain, always-visible rows — the Profile tab's own home for them, not a
 *  second independent setting. */
function SettingsCard() {
  const fx = useFxSettings();
  return (
    <div className="card">
      <h3>Settings</h3>
      <ul className="list-card">
        <li className="list-row">
          <span className="list-row__main">
            <span className="list-row__icon" aria-hidden="true">
              {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
            </span>
            Sound
          </span>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => {
              if (!fx.sfxEnabled) primeAudioContext();
              fx.setSfxEnabled(!fx.sfxEnabled);
            }}
          >
            {fx.sfxEnabled ? "On" : "Off"}
          </button>
        </li>
        <li className="list-row">
          <span className="list-row__main">
            <span className="list-row__icon" aria-hidden="true">
              {fx.hapticsEnabled ? <VibrationOnIcon /> : <VibrationOffIcon />}
            </span>
            Vibration
          </span>
          <button type="button" className="btn btn--secondary" onClick={() => fx.setHapticsEnabled(!fx.hapticsEnabled)}>
            {fx.hapticsEnabled ? "On" : "Off"}
          </button>
        </li>
      </ul>
    </div>
  );
}

/** Exported (player review item 2) so `MyProfilePage.tsx` can embed the exact same balance/
 *  deposit/withdraw/settings content as a sub-tab of the Profile tab, instead of duplicating it —
 *  self-contained (reads `useApi`/`useSession` itself), so it needs no props from either host.
 *  `/wallet` itself is untouched and still works as its own standalone route (header balance
 *  pill, profile-menu "Wallet" link, old bookmarks) — this just gives it a second caller. */
export function WalletContent() {
  const api = useApi();
  const { player } = useSession();
  const [account, setAccount] = useState<PlayerAccountDto | null>(null);
  const [ledger, setLedger] = useState<LedgerEntryDto[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState(false);

  const load = useCallback(() => {
    Promise.all([api.myAccount(), api.myLedger()])
      .then(([acc, page]) => {
        setAccount(acc);
        setLedger(page.items);
        setHasMore(page.hasMore);
        setError(false);
      })
      .catch(() => setError(true));
  }, [api]);

  // Polls while this tab is visible (`usePoll`'s own contract) so an incoming deposit's `DEPOSIT`
  // ledger entry — credited automatically by the server once chainsvc reports it confirmed,
  // there's no push/subscription mechanism for it — shows up without a manual refresh (Р-53:
  // "live status of incoming deposits").
  usePoll(load, 5000, []);

  const loadMore = async () => {
    if (!ledger || ledger.length === 0) return;
    const page = await api.myLedger({ before: ledger[ledger.length - 1].atMs });
    setLedger([...ledger, ...page.items]);
    setHasMore(page.hasMore);
  };

  if (error) return <ErrorState onRetry={load} />;
  if (!account || !ledger) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={60} width={200} />
        <Skeleton height={240} />
      </div>
    );
  }

  return (
    <div className="two-col">
      <div className="stack" style={{ gap: 16 }}>
        <BalanceCard balance={account.balance} />
        <h3>Activity</h3>
        {ledger.length === 0 ? (
          <EmptyState>No activity yet — your starter pack will appear here once you sign in.</EmptyState>
        ) : (
          <>
            <ul className="list-card">
              {ledger.map((e) => (
                <li key={e.sequence} className="list-row">
                  <span className="list-row__main">
                    <span className="list-row__icon" aria-hidden="true" style={{ color: Number(e.delta) >= 0 ? "var(--up)" : "var(--down)" }}>
                      {ledgerIcon(e)}
                    </span>
                    <span>
                      {LEDGER_LABEL[e.type]}
                      <br />
                      <span className="faint">{formatDateTimeUtc(e.atMs)}</span>
                    </span>
                  </span>
                  <span className="mono" style={{ color: Number(e.delta) >= 0 ? "var(--up)" : "var(--down)" }}>
                    {formatPlayAmount(e.delta, { sign: true })} PLAY
                  </span>
                </li>
              ))}
            </ul>
            {hasMore && (
              <button type="button" className="btn btn--secondary" onClick={() => void loadMore()}>
                Load more
              </button>
            )}
          </>
        )}
      </div>
      <div className="stack" style={{ gap: 16 }}>
        {player && <WithdrawCard balance={Number(account.balance)} address={player.walletAddress} onBalanceChange={load} />}
        <DepositCard />
        <SageDepositHints />
        <SettingsCard />
      </div>
    </div>
  );
}

export function WalletPage() {
  const { player, openConnectModal } = useSession();

  return (
    <div>
      <h1>Wallet</h1>
      {/* Р-63: the sticky top DEMO banner is gone from every screen — this is where that fact
       *  still lives, as plain text, on the screen where balances/deposits/withdrawals actually
       *  happen (task: "keep factual labels where they matter... in the wallet/How it works
       *  page"). */}
      <p className="disclaimer-line">PLAY — test tokens on Herd's test network. Nothing here is real money.</p>
      {/* Р-63: same fact `ServerInfoBanner` used to show as a sticky top strip on every screen —
       *  renders nothing at all against `MockApi` (no `serverInfo()`, `Common.tsx`'s own doc
       *  comment) or a live (non-replay) `RealApi` build; only ever appears in test/replay mode. */}
      <ServerInfoBanner />
      {!player ? (
        <div className="state-card">
          <p className="state-card__title">Connect your wallet to see your balance</p>
          <button type="button" className="btn btn--primary" onClick={openConnectModal}>
            CONNECT WALLET
          </button>
        </div>
      ) : (
        <WalletContent />
      )}
    </div>
  );
}
