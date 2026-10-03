import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useApi } from "../api/ApiContext";
import type { MarketDto, RoundPhase, ServerInfoDto } from "../api/contract";
import { formatCountdown } from "../format";
import { backupRoundNote, backupRoundOf, originPhrase, sourceName, type RoundSubject } from "./marketModel";

export function DemoBanner() {
  return (
    <div className="demo-banner" role="note">
      DEMO — PLAY is toy money on a test network. Nothing here is real funds.
    </div>
  );
}

/**
 * Task item 4 ("Show serverInfo: a clear mode banner in replay mode… bot badge wherever isBot,
 * oracle status line…"). `MockApi` has no `serverInfo()` (`contract.ts`'s own doc comment on the
 * method) so this renders nothing at all in the mock build — there is no server to report on —
 * rather than a "server unreachable" error state.
 */
export function ServerInfoBanner() {
  const api = useApi();
  const [info, setInfo] = useState<ServerInfoDto | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .serverInfo?.()
      .then((i) => {
        if (!cancelled) setInfo(i);
      })
      .catch(() => {
        if (!cancelled) setInfo(null);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  if (!info || info.mode !== "replay" || !info.replay) return null;
  const { sourceFromIso, sourceToIso, speedup } = info.replay;
  const fmt = (iso: string) => iso.slice(0, 10);
  return (
    <div className="replay-banner" role="note">
      Replay: BTC prices from {fmt(sourceFromIso)}–{fmt(sourceToIso)}, {speedup}× speed
      {info.bots > 0 ? ` · ${info.bots} bots playing` : ""}
    </div>
  );
}

/** Task item 4: "bot badge wherever isBot". Small enough to sit inline next to a nickname. */
export function BotBadge({ isBot }: { isBot?: boolean }) {
  if (!isBot) return null;
  return (
    <span className="bot-badge" title="Played by a test-mode bot (Р-46)">
      BOT
    </span>
  );
}

/** Task item 4: "resultsHash + link to results.json on settled rounds" — also used for a
 *  cancelled round, which carries the same optional field (`RoundCancelled.resultsHash`).
 *  `resultsHash` is optional even on a real backend (absent until the oracle's next batched
 *  singleton spend confirms — see `ServerInfoDto.oracle.pendingRecords`), so this renders
 *  nothing at all until it exists rather than a placeholder/loading state. */
export function ResultsHashLine({ roundId, resultsHash }: { roundId: string; resultsHash?: string }) {
  if (!resultsHash) return null;
  const short = `${resultsHash.slice(0, 10)}…${resultsHash.slice(-6)}`;
  return (
    <p className="disclaimer-line mono">
      Results hash: {short} —{" "}
      <a href={`/api/rounds/${encodeURIComponent(roundId)}/results.json`} target="_blank" rel="noopener noreferrer">
        verify results.json
      </a>
    </p>
  );
}

/** Honest labelling of the crowd (Р-46: "bots are labelled"): the player counts and the UP/DOWN split on the
 *  lobby cards cannot tell a bot from a person, so where a server reports test bots (`ServerInfoDto.bots`) the
 *  page says so. Renders nothing for a server that reports none and for the mock (no `serverInfo()` — there
 *  is no server to ask, like the other lines of this family). */
export function BotsNote() {
  const api = useApi();
  const [bots, setBots] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .serverInfo?.()
      .then((i) => {
        if (!cancelled) setBots(i.bots);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [api]);

  if (bots <= 0) return null;
  return (
    <p className="faint" style={{ margin: 0 }}>
      Player counts and the crowd split include {bots} labelled test bots (BOT in the live bets feed).
    </p>
  );
}

/** Task item 4: "oracle status line (last confirmed seq/height on testnet11)". */
export function OracleStatusLine() {
  const api = useApi();
  const [info, setInfo] = useState<ServerInfoDto | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .serverInfo?.()
      .then((i) => {
        if (!cancelled) setInfo(i);
      })
      .catch(() => {
        if (!cancelled) setInfo(null);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  if (!info) return null;
  const { oracle } = info;
  return (
    <p className="disclaimer-line mono">
      Oracle ({oracle.network}): last confirmed seq {oracle.lastConfirmedSeq ?? "—"}, height{" "}
      {oracle.lastConfirmedHeight ?? "—"}
      {oracle.pendingRecords > 0 ? ` · ${oracle.pendingRecords} pending` : ""}
    </p>
  );
}

/** Р-33/§2 ("Про управляемый оракул"): shown wherever the outcome depends on the oracle. The first half names where the
 *  number really comes from — "Price from Coinbase", "Price from Kraken", "Price from Hyperliquid", "Odds from
 *  Polymarket" (`originPhrase`); a Polymarket odds market is not "a price from Hyperliquid" (Р-69); the second half is the
 *  same for every source: it is OUR key that publishes it. A caller that does not know the source passes none and the
 *  line says nothing about it — it never assumes one (a round of a market that has left `markets()` once claimed
 *  "Price from Hyperliquid" for an odds round). A market that has a backup exchange (Р-76) says so after it: which one,
 *  and that a round runs on it only when the main exchange has no price at the round's start. */
export function OracleDisclosureLine({ source, backup }: { source?: MarketDto["source"]; backup?: MarketDto["backupSource"] }) {
  const origin = originPhrase(source);
  const backupLabel = sourceName(backup);
  return (
    <p className="disclaimer-line">
      {origin ? `${origin}, published by our team's key` : "Published by our team's key"} — not a decentralized oracle.
      {backupLabel && (
        <>
          {" "}
          <span className="disclaimer-line__backup">
            Backup: {backupLabel}, used for a round only if {sourceName(source) ?? "the main exchange"} has no price when it starts.
          </span>
        </>
      )}
    </p>
  );
}

/**
 * A round that runs (or ran) on its market's backup exchange says so (Р-76: a round runs entirely on one exchange,
 * chosen when it starts), where the page prints its prices — a quiet boxed note, nothing is wrong. Nothing for a round on
 * the main exchange, nor when the market (which says which exchange is the main one) is not on the list.
 */
export function RoundVenueNote({
  source,
  market,
  phase,
  subject = "this",
}: {
  /** `RoundBase.source`: the exchange the round runs on. */
  source: MarketDto["source"] | undefined;
  market: Pick<MarketDto, "source" | "backupSource"> | undefined;
  phase: "RUNNING" | "SETTLED";
  /** `this` on the round's own page; `live` in a room, which also shows the round open for bets (its exchange is not
   *  chosen yet — it is the one that starts next). */
  subject?: RoundSubject;
}) {
  const backup = backupRoundOf({ source }, market);
  if (!backup) return null;
  return (
    <p className="venue-note" role="note">
      {backupRoundNote(backup, phase, subject)}
    </p>
  );
}

/** §2 ("Про отсутствие защиты от мультиаккаунтов"). */
export function MultiAccountDisclosureLine() {
  return <p className="disclaimer-line">Ratings are for fun — we don't detect multiple accounts.</p>;
}

/**
 * Shared "copy to clipboard" button (task item 1's deposit address, task item 2's WalletConnect
 * "Copy connection link") — falls back to a temporary offscreen `<textarea>` +
 * `document.execCommand("copy")` when the async Clipboard API is unavailable/denied (non-HTTPS
 * dev, some in-app browsers), rather than silently doing nothing.
 */
export function CopyButton({
  value,
  label = "Copy",
  copiedLabel = "Copied!",
  className = "btn btn--secondary",
}: {
  value: string;
  label?: string;
  copiedLabel?: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);
  const timeoutRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timeoutRef.current), []);

  const onClick = async () => {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      const el = document.createElement("textarea");
      el.value = value;
      el.style.position = "fixed";
      el.style.opacity = "0";
      document.body.appendChild(el);
      el.select();
      try {
        document.execCommand("copy");
      } catch {
        // Nothing more to try — the button just won't flash "Copied!" below.
      }
      document.body.removeChild(el);
    }
    setCopied(true);
    window.clearTimeout(timeoutRef.current);
    timeoutRef.current = window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button type="button" className={className} onClick={() => void onClick()}>
      {copied ? copiedLabel : label}
    </button>
  );
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
  calm = false,
}: {
  nowMs: number;
  targetMs: number;
  prefix?: string;
  /** No last-10-seconds escalation: for a list where eighteen cards tick down in unison, a
   *  screen-wide pulse would be noise, not information (the room's own countdown still escalates). */
  calm?: boolean;
}) {
  const remaining = Math.max(0, targetMs - nowMs);
  // Escalation in the last 10s/3s (docs/product/design/live-round.md §4) — a real, existing
  // timer reading more urgently as it actually runs out, not a fabricated pressure timer
  // (game-designer.md's "Честность" redline covers invented urgency, not an honest one).
  const tier = calm ? "" : remaining <= 3000 ? "countdown--critical" : remaining <= 10000 ? "countdown--warn" : "";
  return (
    <span className={`countdown mono ${calm ? "countdown--calm" : ""} ${tier}`}>
      {prefix}
      {formatCountdown(remaining)}
    </span>
  );
}
