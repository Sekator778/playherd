import type { CSSProperties, ReactNode } from "react";
import type { RoundPhase } from "../api/contract";
import { formatCountdown } from "../format";

export function DemoBanner() {
  return (
    <div className="demo-banner" role="note">
      DEMO — PLAY is toy money on a test network. Nothing here is real funds.
    </div>
  );
}

/** Р-33/§2 ("Про управляемый оракул"): shown wherever the outcome depends on the oracle. */
export function OracleDisclosureLine() {
  return (
    <p className="disclaimer-line">
      Price from Hyperliquid, published by our team's key — not a decentralized oracle.
    </p>
  );
}

/** §2 ("Про отсутствие защиты от мультиаккаунтов"). */
export function MultiAccountDisclosureLine() {
  return <p className="disclaimer-line">Ratings are for fun — we don't detect multiple accounts.</p>;
}

export function ErrorState({ message = "Something went wrong.", onRetry }: { message?: string; onRetry?: () => void }) {
  return (
    <div className="state-card" role="alert">
      <div className="state-card__icon" aria-hidden="true">
        ⚠
      </div>
      <p className="state-card__title">{message}</p>
      {onRetry && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="state-card">
      <p className="muted">{children}</p>
    </div>
  );
}

export function Skeleton({
  width = "100%",
  height = 16,
  style,
}: {
  width?: string | number;
  height?: string | number;
  style?: CSSProperties;
}) {
  return <div className="skeleton" style={{ width, height, ...style }} aria-hidden="true" />;
}

const PHASE_LABEL: Record<RoundPhase, string> = {
  BETTING: "OPEN FOR BETS",
  RUNNING: "LIVE",
  SETTLED: "SETTLED",
  CANCELLED: "CANCELLED",
};
const PHASE_CLASS: Record<RoundPhase, string> = {
  BETTING: "badge--betting",
  RUNNING: "badge--live",
  SETTLED: "badge--settled",
  CANCELLED: "badge--cancelled",
};

export function RoundStatusBadge({ phase }: { phase: RoundPhase }) {
  return <span className={`badge ${PHASE_CLASS[phase]}`}>{PHASE_LABEL[phase]}</span>;
}

export function Countdown({
  nowMs,
  targetMs,
  prefix,
}: {
  nowMs: number;
  targetMs: number;
  prefix?: string;
}) {
  const remaining = Math.max(0, targetMs - nowMs);
  return (
    <span className="countdown mono">
      {prefix}
      {formatCountdown(remaining)}
    </span>
  );
}
