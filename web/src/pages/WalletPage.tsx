import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { checkPlayAmount, PLAY_DECIMALS } from "../api/amountInput";
import { isEngineNotReady, mutationErrorText } from "../api/apiErrors";
import { useEngineEpoch } from "../api/engineState";
import { loadOlder, refreshNewest, type PageOps, type PagedList } from "../api/paging";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { usePoll } from "../api/usePoll";
import { MIN_WITHDRAWAL_PLAY, type LedgerEntryDto, type LedgerEntryType, type PlayerAccountDto, type WithdrawalRequestDto } from "../api/contract";
import { CopyButton, EmptyState, ErrorState, ServerInfoBanner, Skeleton } from "../components/Common";
import { formatDateTime, formatPlayAmount, rollingPlayFormat, shortenAddress } from "../format";
import { settlementNets } from "./walletModel";
import { RollingDigits } from "../fx/RollingDigits";
import { useFxSettings } from "../fx/fxSettings";
import { primeAudioContext } from "../fx/sound";
import { QrCode } from "../wallet/QrCode";
import { useT, type TFn, type TKey } from "../i18n";
import { SageDepositHints } from "../components/SageHints";
import {
  ArrowDownTrayIcon,
  ArrowUpTrayIcon,
  ChipIcon,
  CoinIcon,
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

// The fee types of economy stage A have no label yet (stage C adds them in six languages): `ledgerLabel` makes their name readable.
const LEDGER_LABEL: Partial<Record<LedgerEntryType, TKey>> = {
  STARTER_PACK: "wallet.ledgerStarterPack",
  STAKE_DEBIT: "wallet.ledgerStakeDebit",
  STAKE_REFUND: "wallet.ledgerStakeRefund",
  SETTLEMENT: "wallet.ledgerSettlement",
  DEPOSIT: "wallet.ledgerDeposit",
  WITHDRAWAL: "wallet.ledgerWithdrawal",
  WITHDRAWAL_REFUND: "wallet.ledgerWithdrawalRefund",
  // A signed credit or debit the operator booked to reverse a wrong deposit or withdrawal (`contract.ts`).
  OPERATOR_CORRECTION: "wallet.ledgerOperatorCorrection",
};

/** The row's label: the table's, else — a type this build has not heard of yet — the server's word made readable
 *  ("SOME_NEW_TYPE" → "Some new type"), never a row with an amount and no name. */
function ledgerLabel(type: string, t: TFn): string {
  const known: Record<string, TKey | undefined> = LEDGER_LABEL;
  if (known[type]) return t(known[type]);
  const words = type.toLowerCase().replace(/_/g, " ").trim();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : t("wallet.ledgerFallback");
}

/** How the wallet history pages (`api/paging.ts`): by `sequence`, newest first (`atMs`, then `sequence` — rows of
 *  rounds that end at one boundary share a millisecond). */
const LEDGER_PAGE = 20;
const LEDGER_OPS: PageOps<LedgerEntryDto> = {
  keyOf: (e) => e.sequence,
  atMsOf: (e) => e.atMs,
  compare: (a, b) => b.atMs - a.atMs || b.sequence - a.sequence,
};

/** direction.md §3's icon pattern, applied per ledger-entry-type (§4 names Wallet's ledger as one
 *  of the "long list" screens it targets) — was plain text-only rows, indistinguishable from a
 *  bank statement. `SETTLEMENT` is a PAYOUT (a credit): it shows the round's win/loss/even icon only when the
 *  round's NET is known (`net`, from `settlementNets`) — never the sign of the payout, which dressed a loss that paid back
 *  99.83 of 100 as a trophy; with no net to go on (the round's stake is on an older page) it is the neutral PLAY coin. */
function ledgerIcon(entry: LedgerEntryDto, net: number | undefined): ReactNode {
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
    case "OPERATOR_CORRECTION":
      return <EqualsDiscIcon />; // an adjustment, not a win or a loss — the signed amount says which way
    case "SETTLEMENT": {
      if (net === undefined) return <CoinIcon />;
      return net > 0 ? <TrophyIcon /> : net < 0 ? <MinusDiscIcon /> : <EqualsDiscIcon />;
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
  const t = useT();
  const numeric = Number(balance);
  const prevRef = useRef(numeric);
  const [flashClass, setFlashClass] = useState("");

  useEffect(() => {
    if (prevRef.current === numeric) return;
    setFlashClass(numeric > prevRef.current ? "value-flash-up" : "value-flash-down");
    prevRef.current = numeric;
    const timer = window.setTimeout(() => setFlashClass(""), 450);
    return () => window.clearTimeout(timer);
  }, [numeric]);

  return (
    <div className={`card ${flashClass}`}>
      <div className="stat-tile__label">{t("wallet.balance")}</div>
      <div className="stat-tile__value" style={{ fontSize: 32 }}>
        <RollingDigits value={numeric} durationMs={500} format={rollingPlayFormat(numeric, { unit: true })} />
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
  const t = useT();
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
      .catch((err) => setErrorMessage(mutationErrorText(err, t("wallet.depositLoadError"), t)));
  }, [api, t]);

  // Loads once — and again when the server comes back from a restart (`api/engineState.ts`).
  const engineEpoch = useEngineEpoch();
  useEffect(() => {
    load();
  }, [load, engineEpoch]);

  return (
    <div className="card">
      <h3>{t("wallet.depositTitle")}</h3>
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
            <QrCode value={address} size={200} label={t("wallet.depositQrLabel")} />
          </div>
          <p className="mono deposit-address" aria-label={t("wallet.depositAddressAria")}>
            {address}
          </p>
          <CopyButton value={address} label={t("wallet.copyAddress")} className="btn btn--secondary btn--block" />
          <p className="muted">
            {t("wallet.depositHelp")}
          </p>
          <p className="disclaimer-line">
            <span className="live-dot" aria-hidden="true" /> {t("wallet.watching")}
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

const WITHDRAWAL_DISPLAY_LABEL: Record<WithdrawalDisplayState, TKey> = {
  QUEUED: "wallet.stateQueued",
  IN_FLIGHT: "wallet.stateInFlight",
  CONFIRMED: "wallet.stateConfirmed",
  FAILED: "wallet.stateFailed",
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
  const t = useT();
  const display = displayWithdrawalState(w);
  return (
    <li className="list-row" style={{ flexDirection: "column", alignItems: "stretch", gap: 6 }}>
      <span className="list-row__main" style={{ justifyContent: "space-between", width: "100%" }}>
        <span className="mono">{formatPlayAmount(w.amount)} PLAY</span>
        <span className={`badge ${WITHDRAWAL_DISPLAY_CLASS[display]}`}>{t(WITHDRAWAL_DISPLAY_LABEL[display])}</span>
      </span>
      {display === "CONFIRMED" && (
        <span className="faint mono">
          {w.height !== null
            ? t("wallet.coinAtHeight", { coin: w.coinId ? shortenAddress(w.coinId) : "—", height: w.height.toLocaleString("en-US") })
            : t("wallet.coin", { coin: w.coinId ? shortenAddress(w.coinId) : "—" })}
        </span>
      )}
      {display === "FAILED" && <span className="faint">{w.failureReason ?? t("wallet.failedRefunded")}</span>}
      {(display === "QUEUED" || display === "IN_FLIGHT") && (
        <span className="faint">
          {display === "QUEUED" ? t("wallet.queuedHelp") : t("wallet.inFlightHelp")}
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
  const t = useT();
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
      setErrorMessage(mutationErrorText(err, t("wallet.withdrawError"), t));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card">
      <h3>{t("wallet.withdrawTitle")}</h3>
      <form onSubmit={(e) => void submit(e)} className="stack" style={{ gap: 12 }}>
        <div className="field">
          <label htmlFor="withdraw-amount">{t("wallet.amountLabel")}</label>
          <input
            id="withdraw-amount"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="0"
          />
          <p className="field-hint">{t("wallet.balanceHint", { amount: formatPlayAmount(balance) })}</p>
          {amount.trim() !== "" && !check.isPositive && <p className="field-error">{t("wallet.amountPositive")}</p>}
          {check.isPositive && check.tooManyDecimals && (
            <p className="field-error">{t("wallet.decimals", { count: PLAY_DECIMALS })}</p>
          )}
          {check.isPositive && !check.tooManyDecimals && belowMin && (
            <p className="field-error">{t("wallet.minWithdrawal", { amount: formatPlayAmount(MIN_WITHDRAWAL_PLAY) })}</p>
          )}
          {check.overBalance && (
            <p className="field-error">
              {t("wallet.notEnough", { have: formatPlayAmount(balance), need: formatPlayAmount(check.amount) })}
            </p>
          )}
        </div>
        <div className="field">
          <span className="field-hint">{t("wallet.destination")}</span>
          <p className="mono" style={{ margin: 0 }}>
            {shortenAddress(address)}
          </p>
        </div>
        {errorMessage && <p className="field-error">{errorMessage}</p>}
        <button type="submit" className="btn btn--primary btn--block" disabled={!valid || submitting}>
          {submitting ? t("wallet.requesting") : t("wallet.withdraw")}
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
  const t = useT();
  return (
    <div className="card">
      <h3>{t("wallet.settings")}</h3>
      <ul className="list-card">
        <li className="list-row">
          <span className="list-row__main">
            <span className="list-row__icon" aria-hidden="true">
              {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
            </span>
            {t("wallet.sound")}
          </span>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => {
              if (!fx.sfxEnabled) primeAudioContext();
              fx.setSfxEnabled(!fx.sfxEnabled);
            }}
          >
            {fx.sfxEnabled ? t("common.on") : t("common.off")}
          </button>
        </li>
        <li className="list-row">
          <span className="list-row__main">
            <span className="list-row__icon" aria-hidden="true">
              {fx.hapticsEnabled ? <VibrationOnIcon /> : <VibrationOffIcon />}
            </span>
            {t("wallet.vibration")}
          </span>
          <button type="button" className="btn btn--secondary" onClick={() => fx.setHapticsEnabled(!fx.hapticsEnabled)}>
            {fx.hapticsEnabled ? t("common.on") : t("common.off")}
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
  const t = useT();
  const { player } = useSession();
  const [account, setAccount] = useState<PlayerAccountDto | null>(null);
  const [ledger, setLedger] = useState<PagedList<LedgerEntryDto> | null>(null);
  const [error, setError] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // What is on screen, for a refresh that may page for a while — and one read or paging step at a time.
  const shown = useRef<PagedList<LedgerEntryDto> | null>(null);
  const busy = useRef(false);
  const fetchLedger = useCallback((params: Parameters<typeof api.myLedger>[0]) => api.myLedger(params), [api]);
  // The result of each settled round whose rows are all loaded — what the settlement rows say, instead of their payout's sign.
  const nets = useMemo(() => settlementNets(ledger?.items ?? []), [ledger]);

  const load = useCallback(() => {
    if (busy.current) return;
    busy.current = true;
    Promise.all([api.myAccount(), api.myLedger()])
      // The poll brings the NEWEST page: it is folded into what is already loaded (new rows on top, the pages of
      // "Load more" kept, a burst of more than a page walked until it meets the loaded rows), never swapped in.
      .then(async ([acc, page]) => {
        const next = await refreshNewest(fetchLedger, shown.current, page, LEDGER_PAGE, LEDGER_OPS);
        shown.current = next;
        setAccount(acc);
        setLedger(next);
        setError(false);
      })
      .catch((err) => {
        // A server restarting (503) is not a failure of this page: what is shown stays, the poll asks again.
        if (!isEngineNotReady(err)) setError(true);
      })
      .finally(() => {
        busy.current = false;
      });
  }, [api, fetchLedger]);

  // Polls while this tab is visible (`usePoll`'s own contract) so an incoming deposit's `DEPOSIT`
  // ledger entry — credited automatically by the server once chainsvc reports it confirmed,
  // there's no push/subscription mechanism for it — shows up without a manual refresh (Р-53:
  // "live status of incoming deposits").
  usePoll(load, 5000, []);

  const loadMore = async () => {
    if (!ledger || ledger.items.length === 0 || busy.current) return;
    busy.current = true;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const next = await loadOlder(fetchLedger, ledger, LEDGER_PAGE, LEDGER_OPS);
      if (next) {
        shown.current = next;
        setLedger(next);
      } else {
        setMoreFailed(true);
      }
    } catch {
      setMoreFailed(true);
    } finally {
      busy.current = false;
      setLoadingMore(false);
    }
  };

  // Nothing to show yet and the read failed: an error with a retry. With the numbers on screen a failed poll never
  // replaces them (a restart of the server shows the banner and nothing else) — a quiet line says they may be old.
  if (error && (!account || !ledger)) return <ErrorState onRetry={load} />;
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
        {error && (
          <p className="status-note" role="status">
            {t("wallet.refreshFailed")}
          </p>
        )}
        <h3>{t("wallet.activity")}</h3>
        {ledger.items.length === 0 ? (
          <EmptyState>{t("wallet.noActivity")}</EmptyState>
        ) : (
          <>
            <ul className="list-card">
              {ledger.items.map((e) => {
                // A settlement is a payout; whether the ROUND was won or lost is its net (see `walletModel.ts`).
                const net = e.type === "SETTLEMENT" && e.roundId !== null ? nets.get(e.roundId) : undefined;
                const iconColor =
                  e.type === "SETTLEMENT" ? (net === undefined ? "var(--text-muted)" : net > 0 ? "var(--up)" : net < 0 ? "var(--down)" : "var(--text-muted)") : Number(e.delta) >= 0 ? "var(--up)" : "var(--down)";
                return (
                  <li key={e.sequence} className="list-row">
                    <span className="list-row__main">
                      <span className="list-row__icon" aria-hidden="true" style={{ color: iconColor }}>
                        {ledgerIcon(e, net)}
                      </span>
                      <span>
                        {ledgerLabel(e.type, t)}
                        <br />
                        <span className="faint">{formatDateTime(e.atMs)}</span>
                        {net !== undefined && (
                          <span className={`ledger-result ${outcomeClassName(outcomeFromNet(net))}`}>
                            {t(net > 0 ? "wallet.won" : net < 0 ? "wallet.lost" : "wallet.even", { amount: formatPlayAmount(net, { sign: true }) })}
                          </span>
                        )}
                      </span>
                    </span>
                    <span className="mono list-row__amount" style={{ color: Number(e.delta) >= 0 ? "var(--up)" : "var(--down)" }}>
                      {formatPlayAmount(e.delta, { sign: true })} PLAY
                    </span>
                  </li>
                );
              })}
            </ul>
            {ledger.hasMore && (
              <button type="button" className="btn btn--secondary" onClick={() => void loadMore()} disabled={loadingMore}>
                {loadingMore ? t("wallet.loading") : t("common.loadMore")}
              </button>
            )}
            {moreFailed && (
              <p className="status-note" role="alert">
                {t("wallet.moreFailed")}
              </p>
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
  const t = useT();

  return (
    <div>
      <h1>{t("wallet.title")}</h1>
      {/* Р-63: the sticky top DEMO banner is gone from every screen — this is where that fact
       *  still lives, as plain text, on the screen where balances/deposits/withdrawals actually
       *  happen (task: "keep factual labels where they matter... in the wallet/How it works
       *  page"). */}
      <p className="disclaimer-line">{t("wallet.disclaimer")}</p>
      {/* Р-63: same fact `ServerInfoBanner` used to show as a sticky top strip on every screen —
       *  renders nothing at all against `MockApi` (no `serverInfo()`, `Common.tsx`'s own doc
       *  comment) or a live (non-replay) `RealApi` build; only ever appears in test/replay mode. */}
      <ServerInfoBanner />
      {!player ? (
        <div className="state-card">
          <p className="state-card__title">{t("wallet.connectToSee")}</p>
          <button type="button" className="btn btn--primary" onClick={openConnectModal}>
            {t("common.connectWallet")}
          </button>
        </div>
      ) : (
        <WalletContent />
      )}
    </div>
  );
}
