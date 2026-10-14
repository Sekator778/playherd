/**
 * In-app feedback (Р-81, `docs/product/ux_spec.md` §2 "Feedback strip and form"): everything about it that is not
 * drawing — what is sent, how a failure is worded, and what this browser remembers. One message in one box: no
 * email, no name; the server adds the country (from the request's IP address, which it does not keep) and the player
 * id when someone is signed in, the browser adds what only it knows (time zone, language, which screen, which build).
 *
 * What the browser remembers, in `localStorage`, every access guarded (private windows and blocked site data throw or
 * come back empty — then the strip simply shows, and a close lasts for the page, not for good):
 * - `herd.feedback.dismissed` — the person closed the strip (×);
 * - `herd.feedback.sent` — the person sent a message.
 * Either one takes the strip away for good on this browser. The entry points that stay (the footer, the profile
 * menu) never depend on them.
 *
 * One module-level store, as `favorites.ts`: the strip, the form, the footer and the profile menu see the same state
 * at once, and the `storage` event keeps a second tab in step (a close in one tab closes it in all).
 */
import { useSyncExternalStore } from "react";
import { Capacitor } from "@capacitor/core";
import { ApiRequestError, FEEDBACK_MAX_LENGTH, type FeedbackClient, type FeedbackRequest } from "./contract";
import { tEn, type TFn } from "../i18n";

// ---------------------------------------------------------------------------
// Words (one place, so the form and its tests say the same thing)
// ---------------------------------------------------------------------------

/**
 * How long a send may take before the form gives up and says "couldn't send": a phone on a poor connection can leave a request
 * hanging for minutes (`fetch` has no timeout of its own), and a form stuck on "Sending…" cannot be tried again. A message that
 * does arrive late is stored all the same; the person who tries again may be stored twice — the lesser harm.
 */
export const FEEDBACK_SEND_TIMEOUT_MS = 20_000;

/** HTTP 429 (`RATE_LIMITED`): the sender writes too often. Not "a few seconds" — a limit on messages can be longer than that. */
export const FEEDBACK_RATE_LIMITED_TEXT = tEn("feedback.rateLimited");
/** Every other failure (network, server, a refusal): the message is still in the box, and says so. */
export const FEEDBACK_FAILED_TEXT = tEn("feedback.failed");

/** How a failed `submitFeedback` is told to the sender: a rate limit in its own words, anything else as "couldn't send, try again". */
export function feedbackErrorText(err: unknown, t: TFn = tEn): string {
  if (err instanceof ApiRequestError && err.code === "RATE_LIMITED") return t("feedback.rateLimited");
  return t("feedback.failed");
}

// ---------------------------------------------------------------------------
// The message
// ---------------------------------------------------------------------------

/** From this many characters the form shows "n / 2,000" — below it a counter is only noise. */
export const FEEDBACK_COUNTER_FROM = 1800;

/** Whether the form shows its character counter at `length` characters typed (the box's own count, not the trimmed one). */
export function showFeedbackCounter(length: number): boolean {
  return length >= FEEDBACK_COUNTER_FROM;
}

/** Whether `text` may be sent: something besides whitespace, and not more than the limit. */
export function canSendFeedback(text: string): boolean {
  const length = text.trim().length;
  return length > 0 && length <= FEEDBACK_MAX_LENGTH;
}

// ---------------------------------------------------------------------------
// What the browser says about itself
// ---------------------------------------------------------------------------

/** The longest value of each context field the web sends (`FeedbackRequest` documents them; the server may be stricter). */
const MAX_PAGE_LENGTH = 200;
const MAX_TIME_ZONE_LENGTH = 64;
const MAX_LANGUAGE_LENGTH = 35;

/** The path of an address — never its query string or hash (a link may carry a token, a search, a player's own filter). */
export function pathOnly(address: string | null | undefined): string | null {
  if (!address) return null;
  const cut = address.search(/[?#]/);
  const path = cut === -1 ? address : address.slice(0, cut);
  return path === "" ? null : path;
}

/** The browser's IANA time zone (`Europe/Berlin`), or `null` where it cannot say. */
export function detectTimeZone(): string | null {
  try {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return typeof zone === "string" && zone !== "" ? zone : null;
  } catch {
    return null;
  }
}

/** The browser's preferred language (`en-GB`), or `null` where it has none. */
export function detectLanguage(): string | null {
  try {
    const language = navigator.language;
    return typeof language === "string" && language !== "" ? language : null;
  } catch {
    return null;
  }
}

/**
 * Which build is running: `"app"` inside the Capacitor Android shell (the same check the hardware Back button uses,
 * `fx/useAndroidBackButton.ts`), `"pwa"` when the page was opened from the home screen (display-mode standalone, or
 * iOS Safari's own `navigator.standalone`), else `"web"` — a browser tab.
 */
export function detectFeedbackClient(): FeedbackClient {
  try {
    if (Capacitor.isNativePlatform()) return "app";
  } catch {
    // no bridge to ask: not the shell
  }
  try {
    if (window.matchMedia?.("(display-mode: standalone)").matches) return "pwa";
    if ((navigator as Navigator & { standalone?: boolean }).standalone === true) return "pwa";
  } catch {
    // an old WebView without matchMedia: a tab
  }
  return "web";
}

/** What the browser says about itself in a feedback message. */
export interface FeedbackEnvironment {
  timeZone: string | null;
  language: string | null;
  client: FeedbackClient | null;
}

export function readFeedbackEnvironment(): FeedbackEnvironment {
  return { timeZone: detectTimeZone(), language: detectLanguage(), client: detectFeedbackClient() };
}

/**
 * The argument of `submitFeedback`: the message trimmed, and the context — `page` is where the form was OPENED
 * (`location.pathname`; any query string or hash is cut off here too), the rest comes from the browser. Anything the
 * browser cannot state is `null`, never a made-up value.
 */
export function buildFeedbackRequest(
  text: string,
  page: string | null,
  environment: FeedbackEnvironment = readFeedbackEnvironment(),
): FeedbackRequest {
  const cap = (value: string | null, max: number) => (value === null ? null : value.slice(0, max));
  return {
    message: text.trim(),
    timeZone: cap(environment.timeZone, MAX_TIME_ZONE_LENGTH),
    language: cap(environment.language, MAX_LANGUAGE_LENGTH),
    page: cap(pathOnly(page), MAX_PAGE_LENGTH),
    client: environment.client,
  };
}

// ---------------------------------------------------------------------------
// What this browser remembers
// ---------------------------------------------------------------------------

export const FEEDBACK_DISMISSED_KEY = "herd.feedback.dismissed";
export const FEEDBACK_SENT_KEY = "herd.feedback.sent";

/** What the strip's visibility depends on — nothing else (not whether someone is signed in, not which screen). */
export interface FeedbackStripState {
  dismissed: boolean;
  sent: boolean;
}

/** The strip is shown to everyone until the person sends a message or closes it. */
export function shouldShowFeedbackStrip(state: FeedbackStripState): boolean {
  return !state.dismissed && !state.sent;
}

/** What happened in this page when the storage could not keep it: still true for the life of the page. */
const memory: FeedbackStripState = { dismissed: false, sent: false };

function readFlag(key: string): boolean {
  try {
    const value = window.localStorage.getItem(key);
    return value === "1" || value === "true";
  } catch {
    return false;
  }
}

function writeFlag(key: string): void {
  try {
    window.localStorage.setItem(key, "1");
  } catch {
    // storage unavailable: the in-memory flag still holds for this page
  }
}

/** Closed by the person (×). Read from the storage each time — nothing here can go stale behind the user's back. */
export function isFeedbackDismissed(): boolean {
  return memory.dismissed || readFlag(FEEDBACK_DISMISSED_KEY);
}

/** A message was sent from this browser. */
export function isFeedbackSent(): boolean {
  return memory.sent || readFlag(FEEDBACK_SENT_KEY);
}

let formOpen = false;
const listeners = new Set<() => void>();
let storageHandler: ((e: StorageEvent) => void) | undefined;

function emit(): void {
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1 && typeof window !== "undefined") {
    storageHandler = (e) => {
      if (e.key === FEEDBACK_DISMISSED_KEY || e.key === FEEDBACK_SENT_KEY || e.key === null) emit();
    };
    window.addEventListener("storage", storageHandler);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && storageHandler) {
      window.removeEventListener("storage", storageHandler);
      storageHandler = undefined;
    }
  };
}

/** The strip's × — for good on this browser (and, when the storage is blocked, for this page). */
export function dismissFeedbackStrip(): void {
  memory.dismissed = true;
  writeFlag(FEEDBACK_DISMISSED_KEY);
  emit();
}

/** A message was stored by the server: the strip has done its job and goes away. */
export function markFeedbackSent(): void {
  memory.sent = true;
  writeFlag(FEEDBACK_SENT_KEY);
  emit();
}

/** Opens the form — from the strip, the footer or the profile menu. */
export function openFeedbackForm(): void {
  if (formOpen) return;
  formOpen = true;
  emit();
}

export function closeFeedbackForm(): void {
  if (!formOpen) return;
  formOpen = false;
  emit();
}

/** Test hook: forget this page's memory and close the form (a test that cleared `localStorage` expects a fresh browser). */
export function resetFeedbackForTests(): void {
  memory.dismissed = false;
  memory.sent = false;
  formOpen = false;
  emit();
}

/** `""` while the strip is wanted; a value as soon as it is not — a primitive, so a render only follows a real change. */
function stripSnapshot(): string {
  return `${isFeedbackDismissed() ? "d" : ""}${isFeedbackSent() ? "s" : ""}`;
}

/** Whether the strip is to be shown (`shouldShowFeedbackStrip` over this browser's memory), for every component that cares. */
export function useFeedbackStripVisible(): boolean {
  const snapshot = useSyncExternalStore(subscribe, stripSnapshot, () => "");
  return shouldShowFeedbackStrip({ dismissed: snapshot.includes("d"), sent: snapshot.includes("s") });
}

/** Whether the feedback form is open. */
export function useFeedbackFormOpen(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => formOpen,
    () => false,
  );
}
