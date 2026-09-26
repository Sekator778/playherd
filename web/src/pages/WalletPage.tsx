import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { checkPlayAmount, PLAY_DECIMALS } from "../api/amountInput";
import type {
  DepositRequestDto,
  LedgerEntryDto,
  LedgerEntryType,
  PlayerAccountDto,
  WithdrawalRequestDto,
} from "../api/contract";
import { EmptyState, ErrorState, Skeleton } from "../components/Common";
import { formatDateTimeUtc, formatPlayAmount } from "../format";
import { RollingDigits } from "../fx/RollingDigits";
import { ArrowDownTrayIcon, ArrowUpTrayIcon, ChipIcon, EqualsDiscIcon, GiftIcon, MinusDiscIcon, RefundIcon, TrophyIcon } from "../components/icons";

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
 *  same treatment `live-round.md` §5 gives the Crowd page's stat tiles. */
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

function WithdrawForm({ balance, onDone }: { balance: string; onDone: () => void }) {
  const api = useApi();
  const [amount, setAmount] = useState("");
  const [request, setRequest] = useState<WithdrawalRequestDto | null>(null);
  const pollRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearInterval(pollRef.current), []);

  // Client-side validation (Alex, wave2 fix): positive, ≤ balance, ≤3 decimals — see
  // `amountInput.ts`'s header for the bug this closes (a negative amount used to pass).
  const check = checkPlayAmount(amount, Number(balance));

  const submit = async () => {
    const r = await api.requestWithdrawal(check.amount.toFixed(3));
    setRequest(r);
    onDone();
    pollRef.current = window.setInterval(async () => {
      const updated = await api.withdrawal(r.requestId);
      setRequest(updated);
      if (updated.state !== "REQUESTED") {
        window.clearInterval(pollRef.current);
        onDone();
      }
    }, 1000);
  };

  return (
    <div className="card">
      <h3>Withdraw to wallet</h3>
      <div className="field">
        <label htmlFor="withdraw-amount">Amount (PLAY)</label>
        <input id="withdraw-amount" type="number" min={0} step="0.001" value={amount} onChange={(e) => setAmount(e.target.value)} />
        {amount !== "" && !check.isPositive && <p className="field-error">Enter an amount greater than 0.</p>}
        {check.isPositive && check.tooManyDecimals && (
          <p className="field-error">At most {PLAY_DECIMALS} decimal places.</p>
        )}
        {check.isPositive && !check.tooManyDecimals && check.overBalance && (
          <p className="field-error">You only have {formatPlayAmount(balance)} PLAY.</p>
        )}
      </div>
      <button type="button" className="btn btn--primary" disabled={!check.valid} onClick={() => void submit()}>
        Withdraw
      </button>
      {request && (
        <p className="muted" style={{ marginTop: 10 }}>
          {request.state === "REQUESTED" &&
            `Withdrawal requested — ${formatPlayAmount(request.amount)} PLAY, pending confirmation.`}
          {request.state === "CONFIRMED" &&
            `Withdrawal confirmed — ${formatPlayAmount(request.amount)} PLAY sent to your wallet.`}
          {request.state === "FAILED" &&
            `Withdrawal failed — ${formatPlayAmount(request.amount)} PLAY returned to your balance.`}
        </p>
      )}
    </div>
  );
}

function DepositForm({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [request, setRequest] = useState<DepositRequestDto | null>(null);
  const pollRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearInterval(pollRef.current), []);

  // Client-side validation (Alex, wave2 fix): positive, ≤3 decimals — no balance ceiling for a
  // deposit (see `amountInput.ts`'s header for the bug this closes: a negative amount used to
  // pass this form too).
  const check = checkPlayAmount(amount);

  const submit = async () => {
    const r = await api.requestDeposit(check.amount.toFixed(3));
    setRequest(r);
    onDone();
    pollRef.current = window.setInterval(async () => {
      const updated = await api.deposit(r.requestId);
      setRequest(updated);
      if (updated.state !== "REQUESTED") {
        window.clearInterval(pollRef.current);
        onDone();
      }
    }, 1000);
  };

  return (
    <div className="card">
      <h3>Deposit from wallet</h3>
      <div className="field">
        <label htmlFor="deposit-amount">Amount you sent (PLAY)</label>
        <input id="deposit-amount" type="number" min={0} step="0.001" value={amount} onChange={(e) => setAmount(e.target.value)} />
        {amount !== "" && !check.isPositive && <p className="field-error">Enter an amount greater than 0.</p>}
        {check.isPositive && check.tooManyDecimals && (
          <p className="field-error">At most {PLAY_DECIMALS} decimal places.</p>
        )}
      </div>
      <div className="field">
        <label htmlFor="deposit-ref">Transaction reference (optional)</label>
        <input id="deposit-ref" type="text" value={reference} onChange={(e) => setReference(e.target.value)} />
      </div>
      <button type="button" className="btn btn--primary" disabled={!check.valid} onClick={() => void submit()}>
        I sent it
      </button>
      {request && (
        <p className="muted" style={{ marginTop: 10 }}>
          {request.state === "REQUESTED" &&
            `Deposit requested — we'll credit ${formatPlayAmount(request.amount)} PLAY once it's confirmed on testnet11.`}
          {request.state === "CONFIRMED" && `Deposit confirmed — ${formatPlayAmount(request.amount)} PLAY added to your balance.`}
          {request.state === "FAILED" && "Deposit failed — no PLAY was received."}
        </p>
      )}
    </div>
  );
}

function WalletContent() {
  const api = useApi();
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

  useEffect(() => {
    load();
  }, [load]);

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
                    {formatPlayAmount(e.delta, { decimals: 3, sign: true })} PLAY
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
        <WithdrawForm balance={account.balance} onDone={load} />
        <DepositForm onDone={load} />
      </div>
    </div>
  );
}

export function WalletPage() {
  const { player, openConnectModal } = useSession();

  return (
    <div>
      <h1>Wallet</h1>
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
