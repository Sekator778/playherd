import { useEffect, useRef, useState, type RefObject } from "react";
import { useLocation } from "react-router-dom";
import { dismissFeedbackStrip, openFeedbackForm, useFeedbackFormOpen, useFeedbackStripVisible } from "../api/feedback";
import { useT } from "../i18n";
import { CloseIcon, FeedbackIcon } from "./icons";
import "./Feedback.css";

/** What the strip says. One short line; the word on the right names where a tap goes. */

/** What the strip never sits on: the room's arena (the big UP and DOWN), the bet sheet, the pinned "My bet" card (D-98) and the bet slip — the stake, the leverage, PLACE BET
 *  (and, once there is a bet, its edit and cancel). The lobby's cards are not on the list: their small UP and DOWN lead to the
 *  room, the place a bet is made, and a strip that stepped aside for every card would not stay on the lobby for a moment. */
const KEEP_CLEAR_OF = ".room-arena, .bet-slip, .my-bet-dock, .bet-sheet";
/** How near a control may come before the strip steps aside, so that the strip is gone before a thumb could reach it, not after. */
const KEEP_CLEAR_PAD_PX = 8;

const NON_TEXT_INPUT_TYPES = new Set(["button", "submit", "reset", "checkbox", "radio", "range", "file", "image", "color", "hidden"]);

/** A control the person types into (not a button, a switch or a slider). */
function isTextEntry(target: EventTarget | null): boolean {
  if (target instanceof HTMLTextAreaElement) return true;
  if (target instanceof HTMLInputElement) return !NON_TEXT_INPUT_TYPES.has(target.type);
  return target instanceof HTMLElement && target.isContentEditable === true;
}

/**
 * On a touch screen: whether a text field has the focus — the keyboard is up, and a bar fixed to the bottom edge of what is
 * left of the screen (the stake field, the lobby's search) would sit on the bet slip's own controls. On a computer there is
 * no keyboard in the way, so nothing steps aside there.
 */
function useTextEntryFocused(): boolean {
  const [typing, setTyping] = useState(false);
  useEffect(() => {
    const touch = typeof window.matchMedia === "function" ? window.matchMedia("(pointer: coarse)") : null;
    const onIn = (e: FocusEvent) => setTyping(touch?.matches === true && isTextEntry(e.target));
    const onOut = (e: FocusEvent) => setTyping(touch?.matches === true && isTextEntry(e.relatedTarget));
    document.addEventListener("focusin", onIn);
    document.addEventListener("focusout", onOut);
    return () => {
      document.removeEventListener("focusin", onIn);
      document.removeEventListener("focusout", onOut);
    };
  }, []);
  return typing;
}

/**
 * Whether the game's own controls — the arena's UP and DOWN, the bet slip — are under the strip right now: as the page
 * scrolls, as it is resized, as its content grows, as the screen changes. The strip then steps aside until they have moved on.
 * A bottom bar over a scrolling page passes over whatever is beneath it, and the one thing it must not do is sit on UP, DOWN
 * or PLACE BET. Nothing flickers: the answer changes once as an edge of the arena or the slip crosses the strip, not on every
 * frame of the scroll. Not while the strip itself has the keyboard focus (a key that scrolls the page must not take the focus
 * away from it).
 */
function useControlsUnderStrip(strip: RefObject<HTMLElement | null>, enabled: boolean): boolean {
  const { pathname } = useLocation();
  const [under, setUnder] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let frame = 0;
    const measure = () => {
      frame = 0;
      const el = strip.current;
      if (!el) return;
      if (el.contains(document.activeElement)) {
        setUnder(false);
        return;
      }
      const box = el.getBoundingClientRect();
      setUnder(
        Array.from(document.querySelectorAll(KEEP_CLEAR_OF)).some((other) => {
          const r = other.getBoundingClientRect();
          return r.bottom > box.top - KEEP_CLEAR_PAD_PX && r.top < box.bottom + KEEP_CLEAR_PAD_PX;
        }),
      );
    };
    const schedule = () => {
      if (frame === 0) frame = window.requestAnimationFrame(measure);
    };
    schedule();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    const sizes = typeof ResizeObserver === "function" ? new ResizeObserver(schedule) : null;
    sizes?.observe(document.body);
    return () => {
      if (frame !== 0) window.cancelAnimationFrame(frame);
      window.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      sizes?.disconnect();
    };
    // `pathname`: a new screen is a new page to look at, whether or not anything has scrolled yet.
  }, [strip, enabled, pathname]);
  return under;
}

/**
 * The invitation to leave feedback (D-81, Alex: "может нам сделать какой-то баннер, оставить отзыв"): a closable strip at
 * the bottom of the screen, above the tab bar, shown to everyone — signed in or not — until the person sends a message or
 * closes it. Then it is gone for good on this browser (`api/feedback.ts` remembers it); the entry point stays in the footer
 * and the profile menu.
 *
 * It asks once and keeps still: no delay-then-pop, no animation after its first appearance, no second life after it is
 * closed. It sits clear of the game — above the tab bar and the home-indicator inset, with room reserved under the page
 * content (`.page--feedback-strip`, `Feedback.css`) so every control can be scrolled clear of it, and out of the way (faded,
 * untouchable, gone for a screen reader) whenever it would be on the arena's UP and DOWN or on the bet slip, while the form is
 * open, and, on a touch screen, while a text field has the keyboard. It never moves or resizes the page: stepping aside changes only the strip.
 */
export function FeedbackStrip() {
  const t = useT();
  const visible = useFeedbackStripVisible();
  const formOpen = useFeedbackFormOpen();
  const typing = useTextEntryFocused();
  const ref = useRef<HTMLElement>(null);
  const onControls = useControlsUnderStrip(ref, visible);
  if (!visible) return null;
  const away = formOpen || typing || onControls;
  return (
    <section ref={ref} className={`feedback-strip${away ? " feedback-strip--away" : ""}`} aria-label={t("layout.feedback")}>
      <button type="button" className="feedback-strip__open" onClick={openFeedbackForm}>
        <FeedbackIcon className="feedback-strip__icon" />
        <span className="feedback-strip__text">{t("feedback.stripText")}</span>
        <span className="feedback-strip__action">
          {t("layout.feedback")} <span className="feedback-strip__arrow" aria-hidden="true">→</span>
        </span>
      </button>
      <button
        type="button"
        className="feedback-strip__close"
        aria-label={t("feedback.closeAria")}
        title={t("feedback.closeTitle")}
        onClick={dismissFeedbackStrip}
      >
        <CloseIcon />
      </button>
    </section>
  );
}
