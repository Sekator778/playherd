import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useApi } from "../api/ApiContext";
import type { MarketDto, RoundPhase, ServerInfoDto } from "../api/contract";
import { formatCountdown } from "../format";
import { backupRoundNote, backupRoundOf, originPhrase, sourceName, type RoundSubject } from "./marketModel";
import { useT, type TKey } from "../i18n";

export function DemoBanner() {
  const t = useT();
  return (
    <div className="demo-banner" role="note">
      {t("common.demoBanner")}
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
  const t = useT();
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
      {t("common.replay", { from: fmt(sourceFromIso), to: fmt(sourceToIso), speedup })}
      {info.bots > 0 ? ` · ${t("common.botsPlaying", { count: info.bots })}` : ""}
    </div>
  );
}

/** Task item 4: "bot badge wherever isBot". Small enough to sit inline next to a nickname. */
export function BotBadge({ isBot }: { isBot?: boolean }) {
  const t = useT();
  if (!isBot) return null;
  return (
    <span className="bot-badge" title={t("common.botBadgeTitle")}>
      {t("common.botBadge")}
    </span>
  );
}

/** Task item 4: "resultsHash + link to results.json on settled rounds" — also used for a
 *  cancelled round, which carries the same optional field (`RoundCancelled.resultsHash`).
 *  `resultsHash` is optional even on a real backend (absent until the oracle's next batched
 *  singleton spend confirms — see `ServerInfoDto.oracle.pendingRecords`), so this renders
 *  nothing at all until it exists rather than a placeholder/loading state. */
export function ResultsHashLine({ roundId, resultsHash }: { roundId: string; resultsHash?: string }) {
  const t = useT();
  if (!resultsHash) return null;
  const short = `${resultsHash.slice(0, 10)}…${resultsHash.slice(-6)}`;
  return (
    <p className="disclaimer-line mono">
      {t("common.resultsHash", { hash: short })}{" "}
      <a href={`/api/rounds/${encodeURIComponent(roundId)}/results.json`} target="_blank" rel="noopener noreferrer">
        {t("common.verifyResults")}
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
  const t = useT();
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
      {t("common.botsNote", { count: bots })}
    </p>
  );
}

/** Task item 4: "oracle status line (last confirmed seq/height on testnet11)". */
export function OracleStatusLine() {
  const api = useApi();
  const t = useT();
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
      {t("common.oracle", {
        network: oracle.network,
        seq: oracle.lastConfirmedSeq ?? "—",
        height: oracle.lastConfirmedHeight ?? "—",
      })}
      {oracle.pendingRecords > 0 ? ` · ${t("common.oraclePending", { count: oracle.pendingRecords })}` : ""}
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
  const t = useT();
  const origin = originPhrase(source, t);
  const backupLabel = sourceName(backup, t);
  return (
    <p className="disclaimer-line">
      {origin ? t("common.disclosureOrigin", { origin }) : t("common.disclosureNoOrigin")}
      {backupLabel && (
        <>
          {" "}
          <span className="disclaimer-line__backup">
            {t("common.disclosureBackup", { backup: backupLabel, main: sourceName(source, t) ?? t("source.mainExchange") })}
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
  const t = useT();
  const backup = backupRoundOf({ source }, market, t);
  if (!backup) return null;
  return (
    <p className="venue-note" role="note">
      {backupRoundNote(backup, phase, subject, t)}
    </p>
  );
}

/** §2 ("Про отсутствие защиты от мультиаккаунтов"). */
export function MultiAccountDisclosureLine() {
  const t = useT();
  return <p className="disclaimer-line">{t("common.multiAccount")}</p>;
}

/**
 * Shared "copy to clipboard" button (task item 1's deposit address, task item 2's WalletConnect
 * "Copy connection link") — falls back to a temporary offscreen `<textarea>` +
 * `document.execCommand("copy")` when the async Clipboard API is unavailable/denied (non-HTTPS
 * dev, some in-app browsers), rather than silently doing nothing.
 */
export function CopyButton({
  value,
  label,
  copiedLabel,
  className = "btn btn--secondary",
}: {
  value: string;
  label?: string;
  copiedLabel?: string;
  className?: string;
}) {
  const t = useT();
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
      {copied ? (copiedLabel ?? t("common.copied")) : (label ?? t("common.copy"))}
    </button>
  );
}

export function ErrorState({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  const t = useT();
  return (
    <div className="state-card" role="alert">
      <div className="state-card__icon" aria-hidden="true">
        ⚠
      </div>
      <p className="state-card__title">{message ?? t("common.somethingWrong")}</p>
      {onRetry && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          {t("common.tryAgain")}
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

const PHASE_LABEL: Record<RoundPhase, TKey> = {
  BETTING: "phase.betting",
  RUNNING: "phase.running",
  SETTLED: "phase.settled",
  CANCELLED: "phase.cancelled",
};
const PHASE_CLASS: Record<RoundPhase, string> = {
  BETTING: "badge--betting",
  RUNNING: "badge--live",
  SETTLED: "badge--settled",
  CANCELLED: "badge--cancelled",
};

export function RoundStatusBadge({ phase }: { phase: RoundPhase }) {
  const t = useT();
  return <span className={`badge ${PHASE_CLASS[phase]}`}>{t(PHASE_LABEL[phase])}</span>;
}

export function Countdown({
  nowMs,
  targetMs,
  template,
  calm = false,
}: {
  nowMs: number;
  targetMs: number;
  /** Dictionary key of a sentence with a `{time}` slot ("Closes in {time}"); omitted → the bare time. */
  template?: TKey;
  /** No last-10-seconds escalation: for a list where eighteen cards tick down in unison, a
   *  screen-wide pulse would be noise, not information (the room's own countdown still escalates). */
  calm?: boolean;
}) {
  const t = useT();
  const remaining = Math.max(0, targetMs - nowMs);
  // Escalation in the last 10s/3s (docs/product/design/live-round.md §4) — a real, existing
  // timer reading more urgently as it actually runs out, not a fabricated pressure timer
  // (game-designer.md's "Честность" redline covers invented urgency, not an honest one).
  const tier = calm ? "" : remaining <= 3000 ? "countdown--critical" : remaining <= 10000 ? "countdown--warn" : "";
  return (
    <span className={`countdown mono ${calm ? "countdown--calm" : ""} ${tier}`}>
      {template ? t(template, { time: formatCountdown(remaining) }) : formatCountdown(remaining)}
    </span>
  );
}
