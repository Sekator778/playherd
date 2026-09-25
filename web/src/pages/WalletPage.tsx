import { useCallback, useEffect, useRef, useState } from "react";
import { useApi, useSession } from "../api/ApiContext";
import type {
  DepositRequestDto,
  LedgerEntryDto,
  LedgerEntryType,
  PlayerAccountDto,
  WithdrawalRequestDto,
} from "../api/contract";
import { EmptyState, ErrorState, Skeleton } from "../components/Common";
import { formatDateTimeUtc, formatPlayAmount } from "../format";

const LEDGER_LABEL: Record<LedgerEntryType, string> = {
  STARTER_PACK: "Starter pack",
  STAKE_DEBIT: "Bet placed",
  STAKE_REFUND: "Bet refunded",
  SETTLEMENT: "Round settled",
  DEPOSIT: "Deposit confirmed",
  WITHDRAWAL: "Withdrawal requested",
  WITHDRAWAL_REFUND: "Withdrawal refunded",
};

function WithdrawForm({ balance, onDone }: { balance: string; onDone: () => void }) {
  const api = useApi();
  const [amount, setAmount] = useState("");
  const [request, setRequest] = useState<WithdrawalRequestDto | null>(null);
  const pollRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearInterval(pollRef.current), []);

  const amountNum = Number(amount) || 0;
  const overBalance = amountNum > Number(balance);

  const submit = async () => {
    const r = await api.requestWithdrawal(amountNum.toFixed(3));
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
        <input id="withdraw-amount" type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} />
        {overBalance && <p className="field-error">You only have {formatPlayAmount(balance)} PLAY.</p>}
      </div>
      <button
        type="button"
        className="btn btn--primary"
        disabled={!amountNum || overBalance}
        onClick={() => void submit()}
      >
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

  const amountNum = Number(amount) || 0;

  const submit = async () => {
    const r = await api.requestDeposit(amountNum.toFixed(3));
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
        <input id="deposit-amount" type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="deposit-ref">Transaction reference (optional)</label>
        <input id="deposit-ref" type="text" value={reference} onChange={(e) => setReference(e.target.value)} />
      </div>
      <button type="button" className="btn btn--primary" disabled={!amountNum} onClick={() => void submit()}>
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
        <div className="card">
          <div className="stat-tile__label">Balance</div>
          <div className="stat-tile__value" style={{ fontSize: 32 }}>
            {formatPlayAmount(account.balance)} PLAY
          </div>
        </div>
        <h3>Activity</h3>
        {ledger.length === 0 ? (
          <EmptyState>No activity yet — your starter pack will appear here once you sign in.</EmptyState>
        ) : (
          <>
            <ul className="list-card">
              {ledger.map((e) => (
                <li key={e.sequence} className="list-row">
                  <span>
                    {LEDGER_LABEL[e.type]}
                    <br />
                    <span className="faint">{formatDateTimeUtc(e.atMs)}</span>
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
