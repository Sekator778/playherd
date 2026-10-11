import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { FEEDBACK_MAX_LENGTH } from "../api/contract";
import {
  FEEDBACK_SEND_TIMEOUT_MS,
  buildFeedbackRequest,
  canSendFeedback,
  closeFeedbackForm,
  feedbackErrorText,
  markFeedbackSent,
  showFeedbackCounter,
  useFeedbackFormOpen,
} from "../api/feedback";
import { useBackHandler } from "../fx/backHandlers";
import { CheckIcon } from "./icons";
import "./Feedback.css";

type Phase = "editing" | "sending" | "sent";

const THANKS = "Thanks — we read every message.";
const DB_IP_URL = "https://db-ip.com";
const NUMBER = new Intl.NumberFormat("en-US");

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The feedback form (Р-81): one box — "What did you like? What didn't work for you?" — and nothing else to fill in. A
 * bottom sheet on a phone, a centred card elsewhere, on the app's own modal pattern (`ConnectWalletModal.tsx`: the dialog
 * role, a focus trap, Escape, focus handed back on close, the page behind it locked) — and Android's Back button closes
 * it (`fx/backHandlers.ts`).
 *
 * It says plainly what is kept beside the message: the country (worked out from the IP address, which is not saved with the message), the
 * time zone, the language, the page the form was opened on and — when signed in — the player id; the database that
 * works the country out asks for its credit (CC BY 4.0), so the credit is on the form. Opened from the strip, the footer
 * or the profile menu (`api/feedback.ts`'s store); never opens by itself.
 *
 * The person's words are never lost to a slip: a failed send leaves them in the box with the reason; closing the sheet
 * keeps them until the page is closed; they are cleared only when the server has stored them. A send that was still
 * running when the sheet was closed finishes all the same, and its outcome (thanks, or the reason it failed) is what the
 * sheet shows the next time it opens.
 */
export function FeedbackModal() {
  const isOpen = useFeedbackFormOpen();
  const api = useApi();
  const { pathname } = useLocation();
  const dialogRef = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLTextAreaElement>(null);
  const sendRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);
  const inFlight = useRef(false);

  const [text, setText] = useState("");
  const [phase, setPhase] = useState<Phase>("editing");
  const [error, setError] = useState<string | null>(null);
  // The screen the form was opened on (`FeedbackRequest.page`): read when it opens — during render, as the sign-in sheet
  // does for its own reset, so it cannot be a frame late.
  const [wasOpen, setWasOpen] = useState(false);
  const [openedOn, setOpenedOn] = useState<string | null>(null);
  if (isOpen !== wasOpen) {
    setWasOpen(isOpen);
    if (isOpen) setOpenedOn(pathname);
  }

  const close = () => {
    // Leaving the thank-you is the person having read it: the next opening is a fresh form.
    if (phase === "sent") setPhase("editing");
    // A refusal that was on screen has been read; one that comes in later (the sheet was closed mid-send) waits for the next opening.
    if (phase !== "sending") setError(null);
    closeFeedbackForm();
  };
  useBackHandler(isOpen, close);
  // Escape and Tab are read on the page, not on the dialog: a Send that has gone disabled (sending) takes the focus with it, and a
  // key pressed with the focus nowhere still has to close the sheet — or bring the focus back into it.
  const closeLatest = useRef(close);
  useEffect(() => {
    closeLatest.current = close;
  });
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        closeLatest.current();
        return;
      }
      if (e.key !== "Tab") return;
      // The dialog's focus trap (WAI-ARIA APG): Tab and Shift+Tab cycle among its own controls.
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
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [isOpen]);

  // DOM-only effects, as the sign-in sheet's: focus in on open and back out on close, the page behind it does not scroll.
  useEffect(() => {
    if (!isOpen) return;
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    // A computer: straight into the box. A touch screen: onto the sheet, so the keyboard does not come up over what the
    // sheet says before the person has read it — the first tap on the box brings it.
    const touch = typeof window.matchMedia === "function" && window.matchMedia("(pointer: coarse)").matches;
    (touch ? dialogRef.current : (textRef.current ?? dialogRef.current))?.focus();
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = originalOverflow;
      previouslyFocused.current?.focus?.();
    };
  }, [isOpen]);

  // The thank-you replaces the form that held the focus: it goes to its one button, so a keyboard or a screen reader is not dropped.
  useEffect(() => {
    if (isOpen && phase === "sent") closeRef.current?.focus();
  }, [isOpen, phase]);

  // A refusal comes back to a Send button that had gone disabled while sending (and so lost the focus): hand it back.
  useEffect(() => {
    if (isOpen && error) sendRef.current?.focus();
  }, [isOpen, error]);

  if (!isOpen) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (inFlight.current || !canSendFeedback(text)) return;
    inFlight.current = true;
    setPhase("sending");
    setError(null);
    let giveUp: number | undefined;
    try {
      // A request that never answers must not leave the form on "Sending…" for good (`FEEDBACK_SEND_TIMEOUT_MS`).
      const timeout = new Promise<never>((_, reject) => {
        giveUp = window.setTimeout(() => reject(new Error("The server did not answer in time.")), FEEDBACK_SEND_TIMEOUT_MS);
      });
      await Promise.race([api.submitFeedback(buildFeedbackRequest(text, openedOn)), timeout]);
      // Stored: the strip has done its job, and the words are the server's now.
      markFeedbackSent();
      setText("");
      setPhase("sent");
    } catch (err) {
      setError(feedbackErrorText(err));
      setPhase("editing");
    } finally {
      window.clearTimeout(giveUp);
      inFlight.current = false;
    }
  };

  const sending = phase === "sending";
  const counterShown = showFeedbackCounter(text.length);
  const atLimit = text.length >= FEEDBACK_MAX_LENGTH;
  const describedBy = ["feedback-note", counterShown ? "feedback-counter" : "", error ? "feedback-error" : ""].filter(Boolean).join(" ");

  return (
    <div
      className="modal-overlay feedback-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div
        ref={dialogRef}
        className="modal card feedback-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="feedback-title"
        tabIndex={-1}
      >
        {/* What a screen reader should hear as it happens; always in the page, so that the change is what gets announced. */}
        <p className="visually-hidden" role="status">
          {sending ? "Sending…" : phase === "sent" ? THANKS : ""}
        </p>

        {phase === "sent" ? (
          <div className="feedback-sent">
            <span className="feedback-sent__badge" aria-hidden="true">
              <CheckIcon />
            </span>
            <h2 id="feedback-title">{THANKS}</h2>
            <div className="modal-actions">
              <button ref={closeRef} type="button" className="btn btn--primary btn--block btn--lg" onClick={close}>
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={(e) => void submit(e)} noValidate aria-busy={sending}>
            <h2 id="feedback-title">Feedback</h2>
            <div className="field feedback-field">
              <label htmlFor="feedback-message">What did you like? What didn't work for you?</label>
              <textarea
                id="feedback-message"
                ref={textRef}
                rows={5}
                maxLength={FEEDBACK_MAX_LENGTH}
                value={text}
                readOnly={sending}
                onChange={(e) => {
                  setText(e.target.value);
                  setError(null);
                }}
                placeholder="Anything helps — even one sentence."
                autoCapitalize="sentences"
                aria-describedby={describedBy}
                aria-invalid={error !== null}
              />
              {counterShown && (
                <p id="feedback-counter" className={`field-hint feedback-counter${atLimit ? " feedback-counter--limit" : ""}`}>
                  {NUMBER.format(text.length)} / {NUMBER.format(FEEDBACK_MAX_LENGTH)}
                  {atLimit ? " — that's the limit" : ""}
                </p>
              )}
            </div>
            <p id="feedback-note" className="feedback-note">
              No email or name needed. We keep your message with your country (worked out from your IP address — the address itself
              isn't saved with it), time zone, language, the page you're on and, if you're signed in, your player ID.
            </p>
            <p className="feedback-credit">
              <a href={DB_IP_URL} target="_blank" rel="noopener noreferrer">
                IP Geolocation by DB-IP
              </a>
            </p>
            {error && (
              <p id="feedback-error" className="field-error" role="alert">
                {error}
              </p>
            )}
            <div className="feedback-actions">
              <button type="button" className="btn btn--secondary" onClick={close}>
                Cancel
              </button>
              <button
                ref={sendRef}
                type="submit"
                className={`btn btn--primary feedback-send${sending ? " feedback-send--sending" : ""}`}
                disabled={!canSendFeedback(text) || sending}
              >
                {sending ? "Sending…" : "Send"}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
