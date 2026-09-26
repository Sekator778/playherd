import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useApi, useSession } from "../api/ApiContext";
import type { LedgerEntryDto, LedgerEntryType, PlayerAccountDto } from "../api/contract";
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

/**
 * Withdraw/deposit to a real on-chain CAT (Р-6) is turned off for now (independent critique,
 * 2026-09-26, "Wallet deposit flow" — a blocker): the old "Deposit from wallet" form credited any
 * self-reported amount instantly with zero on-chain verification, so any player could mint
 * unlimited PLAY (reproduced live: 5,000,000 PLAY minted in ten seconds). Until real on-chain
 * verification exists there's nothing honest left to put here except that — never a form that
 * quietly does nothing, and never a button that looks live but isn't (`game-designer.md`'s
 * "никаких dark patterns"). Past deposits/withdrawals, including ones made while this was live,
 * still show in the Activity log below exactly as they happened — nothing here hides history.
 */
function UnavailableNotice({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="card">
      <h3>{title}</h3>
      <p className="muted">{children}</p>
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
        <UnavailableNotice title="Withdraw to wallet">
          Turned off for now — we don't yet verify a withdrawal on chain, so it stays fully in-game until that
          exists. Nothing you've already won or lost is affected.
        </UnavailableNotice>
        <UnavailableNotice title="Deposit from wallet">
          Turned off for now — the old "amount you sent" form credited any number with no on-chain check at all, so
          we removed it rather than leave something unsafe live. It'll come back once deposits are actually verified
          on chain.
        </UnavailableNotice>
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
