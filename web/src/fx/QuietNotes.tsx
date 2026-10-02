/**
 * The quiet summary of a settlement the player has already been told about (`ResultEvent.quiet`): a liquidation
 * shown mid-round (`useLiquidationWatcher.ts`) is not flashed, stamped and shaken a second time when its round
 * ends — one line says the round ended and what it meant, and goes away on its own. It is still signed and still
 * names the loss (a loss is as visible as a win), only not loud: no overlay, no flash, no particles, no sound,
 * no vibration; it sits above the tab bar and blocks nothing; the next bet is one tap away.
 *
 * The same quiet line also tells a liquidated bet's other possible end: its round was CANCELLED, so the stake is
 * refunded (`ResultEvent.refunded`, Р-23) — the moment said the stake was lost, and a refund must not arrive unsaid.
 */
import { useEffect } from "react";
import { Link } from "react-router-dom";
import type { ResultEvent } from "./resultEvent";
import { useMarkets } from "../api/useMarkets";
import { formatDuration, formatPlayAmount } from "../format";
import { CloseIcon, CrackIcon, RefundIcon } from "../components/icons";

/** How long a note stays: long enough to read one line, short enough not to pile up. */
export const QUIET_NOTE_MS = 7000;
/** More than this at once are folded into one count — a catch-up after a long absence must not fill the screen. */
const MAX_VISIBLE = 3;

function QuietNote({ event, onDismiss }: { event: ResultEvent; onDismiss: (id: string) => void }) {
  const { byId } = useMarkets();
  useEffect(() => {
    const timer = window.setTimeout(() => onDismiss(event.id), QUIET_NOTE_MS);
    return () => window.clearTimeout(timer);
  }, [event.id, onDismiss]);

  const length =
    event.durationSec !== undefined && Number.isFinite(event.durationSec) && event.durationSec > 0
      ? formatDuration(event.durationSec)
      : (() => {
          const series = byId.get(event.marketId)?.series.find((s) => s.interval === event.interval);
          return series ? formatDuration(series.durationSec) : null;
        })();

  const refunded = event.refunded === true;
  return (
    <li className={`quiet-note ${refunded ? "quiet-note--refunded" : ""}`}>
      {refunded ? <RefundIcon className="quiet-note__icon" /> : <CrackIcon className="quiet-note__icon" />}
      <div className="quiet-note__body">
        <p className="quiet-note__title">
          <strong>{event.symbol}</strong>
          {length ? ` · ${length}` : ""} {refunded ? "round cancelled" : "round ended"}
        </p>
        {refunded ? (
          <p className="quiet-note__text">
            {event.stakePlay > 0 ? `Your stake of ${formatPlayAmount(event.stakePlay)} PLAY is refunded` : "Your stake is refunded"} — a cancelled round returns every bet in
            it, liquidated ones too.
          </p>
        ) : (
          <p className="quiet-note__text">
            Your liquidated bet: <span className="outcome-lost">{formatPlayAmount(event.netPlay, { sign: true })} PLAY</span>. Your balance doesn't
            change now — the stake was lost when you were liquidated.
          </p>
        )}
      </div>
      <Link to={`/round/${event.roundId}`} className="quiet-note__link" onClick={() => onDismiss(event.id)}>
        View round
      </Link>
      <button type="button" className="quiet-note__close" aria-label="Dismiss" onClick={() => onDismiss(event.id)}>
        <CloseIcon />
      </button>
    </li>
  );
}

export function QuietNotes({ notes, onDismiss }: { notes: readonly ResultEvent[]; onDismiss: (id: string) => void }) {
  if (notes.length === 0) return null;
  const visible = notes.slice(-MAX_VISIBLE);
  const folded = notes.length - visible.length;
  return (
    <ul className="quiet-notes" role="status" aria-live="polite" aria-label="Round summaries">
      {folded > 0 && <li className="quiet-notes__more">+{folded} earlier round summaries</li>}
      {visible.map((event) => (
        <QuietNote key={event.id} event={event} onDismiss={onDismiss} />
      ))}
    </ul>
  );
}
