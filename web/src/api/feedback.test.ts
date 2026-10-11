/**
 * Feedback (Р-81): the rules that are not drawing — when the strip is shown and what the browser remembers (including a
 * browser that remembers nothing), what is sent and how a failure is worded.
 */
import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError, FEEDBACK_MAX_LENGTH } from "./contract";
import {
  FEEDBACK_COUNTER_FROM,
  FEEDBACK_DISMISSED_KEY,
  FEEDBACK_FAILED_TEXT,
  FEEDBACK_RATE_LIMITED_TEXT,
  FEEDBACK_SENT_KEY,
  buildFeedbackRequest,
  canSendFeedback,
  closeFeedbackForm,
  detectFeedbackClient,
  detectLanguage,
  detectTimeZone,
  dismissFeedbackStrip,
  feedbackErrorText,
  isFeedbackDismissed,
  isFeedbackSent,
  markFeedbackSent,
  openFeedbackForm,
  pathOnly,
  resetFeedbackForTests,
  shouldShowFeedbackStrip,
  showFeedbackCounter,
  useFeedbackFormOpen,
  useFeedbackStripVisible,
} from "./feedback";

const platform = vi.hoisted(() => ({ native: false }));

vi.mock("@capacitor/core", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@capacitor/core")>();
  return { ...actual, Capacitor: { ...actual.Capacitor, isNativePlatform: () => platform.native } };
});

beforeEach(() => {
  window.localStorage.clear();
  resetFeedbackForTests();
  platform.native = false;
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("when the strip is shown — to everyone, until a message is sent or the strip is closed", () => {
  it("shown while the person has done neither", () => {
    expect(shouldShowFeedbackStrip({ dismissed: false, sent: false })).toBe(true);
  });

  it("gone once it was closed", () => {
    expect(shouldShowFeedbackStrip({ dismissed: true, sent: false })).toBe(false);
  });

  it("gone once a message was sent", () => {
    expect(shouldShowFeedbackStrip({ dismissed: false, sent: true })).toBe(false);
  });

  it("gone when both happened", () => {
    expect(shouldShowFeedbackStrip({ dismissed: true, sent: true })).toBe(false);
  });

  it("a fresh browser shows it", () => {
    expect(isFeedbackDismissed()).toBe(false);
    expect(isFeedbackSent()).toBe(false);
    const { result } = renderHook(() => useFeedbackStripVisible());
    expect(result.current).toBe(true);
  });
});

describe("what this browser remembers — herd.feedback.dismissed and herd.feedback.sent", () => {
  it("closing writes herd.feedback.dismissed and hides the strip at once", () => {
    const { result } = renderHook(() => useFeedbackStripVisible());
    act(() => dismissFeedbackStrip());
    expect(window.localStorage.getItem("herd.feedback.dismissed")).toBe("1");
    expect(window.localStorage.getItem(FEEDBACK_SENT_KEY)).toBeNull();
    expect(result.current).toBe(false);
  });

  it("a stored message writes herd.feedback.sent and hides the strip at once", () => {
    const { result } = renderHook(() => useFeedbackStripVisible());
    act(() => markFeedbackSent());
    expect(window.localStorage.getItem("herd.feedback.sent")).toBe("1");
    expect(window.localStorage.getItem(FEEDBACK_DISMISSED_KEY)).toBeNull();
    expect(result.current).toBe(false);
  });

  it("the keys are the ones the brief names", () => {
    expect(FEEDBACK_DISMISSED_KEY).toBe("herd.feedback.dismissed");
    expect(FEEDBACK_SENT_KEY).toBe("herd.feedback.sent");
  });

  it("remembers across a reload: what the storage holds is read, not what the page happened to do", () => {
    window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
    expect(isFeedbackDismissed()).toBe(true);
    expect(renderHook(() => useFeedbackStripVisible()).result.current).toBe(false);
  });

  it("a message sent earlier keeps the strip away too", () => {
    window.localStorage.setItem(FEEDBACK_SENT_KEY, "1");
    expect(isFeedbackSent()).toBe(true);
    expect(renderHook(() => useFeedbackStripVisible()).result.current).toBe(false);
  });

  it("anything else in the storage is not a yes (a stray value never takes the strip away)", () => {
    window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "0");
    window.localStorage.setItem(FEEDBACK_SENT_KEY, "maybe");
    expect(isFeedbackDismissed()).toBe(false);
    expect(isFeedbackSent()).toBe(false);
  });

  it("a close made in another tab (a storage event) takes the strip away here too", () => {
    const { result } = renderHook(() => useFeedbackStripVisible());
    expect(result.current).toBe(true);
    act(() => {
      window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
      window.dispatchEvent(new StorageEvent("storage", { key: FEEDBACK_DISMISSED_KEY, newValue: "1" }));
    });
    expect(result.current).toBe(false);
  });

  it("a storage event about some other key changes nothing", () => {
    const { result } = renderHook(() => useFeedbackStripVisible());
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: "herd.favorites", newValue: "[]" }));
    });
    expect(result.current).toBe(true);
  });
});

describe("a browser that remembers nothing (private mode, blocked site data): the strip simply shows", () => {
  function blockStorage(): void {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "QuotaExceededError");
    });
  }

  it("reading throws: not dismissed, not sent — shown", () => {
    blockStorage();
    expect(isFeedbackDismissed()).toBe(false);
    expect(isFeedbackSent()).toBe(false);
    expect(renderHook(() => useFeedbackStripVisible()).result.current).toBe(true);
  });

  it("the whole storage object throws on access — shown, and closing does not throw", () => {
    vi.spyOn(window, "localStorage", "get").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    const { result } = renderHook(() => useFeedbackStripVisible());
    expect(result.current).toBe(true);
    expect(() => act(() => dismissFeedbackStrip())).not.toThrow();
    expect(result.current).toBe(false); // for this page, at least
  });

  it("writing throws: a close or a send still holds for the life of the page, and nothing throws", () => {
    blockStorage();
    const { result } = renderHook(() => useFeedbackStripVisible());
    expect(() => act(() => dismissFeedbackStrip())).not.toThrow();
    expect(result.current).toBe(false);
    expect(isFeedbackDismissed()).toBe(true);
    expect(() => act(() => markFeedbackSent())).not.toThrow();
    expect(isFeedbackSent()).toBe(true);
  });
});

describe("the form's open state — shared by the strip, the footer and the profile menu", () => {
  it("opens and closes for every component that reads it", () => {
    const a = renderHook(() => useFeedbackFormOpen());
    const b = renderHook(() => useFeedbackFormOpen());
    expect(a.result.current).toBe(false);
    act(() => openFeedbackForm());
    expect(a.result.current).toBe(true);
    expect(b.result.current).toBe(true);
    act(() => closeFeedbackForm());
    expect(a.result.current).toBe(false);
    expect(b.result.current).toBe(false);
  });

  it("opening it is not a way to take the strip away: only a close or a stored message does that", () => {
    const strip = renderHook(() => useFeedbackStripVisible());
    act(() => openFeedbackForm());
    expect(strip.result.current).toBe(true);
  });
});

describe("the message", () => {
  it("is sendable when it has something besides whitespace", () => {
    expect(canSendFeedback("")).toBe(false);
    expect(canSendFeedback("   \n\t  ")).toBe(false);
    expect(canSendFeedback("a")).toBe(true);
    expect(canSendFeedback("  Great game  ")).toBe(true);
  });

  it("is sendable up to the limit and not over it, counted after trimming", () => {
    expect(canSendFeedback("x".repeat(FEEDBACK_MAX_LENGTH))).toBe(true);
    expect(canSendFeedback("x".repeat(FEEDBACK_MAX_LENGTH + 1))).toBe(false);
    expect(canSendFeedback(`  ${"x".repeat(FEEDBACK_MAX_LENGTH)}  `)).toBe(true);
  });

  it("the limit is 2000", () => {
    expect(FEEDBACK_MAX_LENGTH).toBe(2000);
  });

  it("the counter shows only near the limit", () => {
    expect(FEEDBACK_COUNTER_FROM).toBeLessThan(FEEDBACK_MAX_LENGTH);
    expect(showFeedbackCounter(0)).toBe(false);
    expect(showFeedbackCounter(FEEDBACK_COUNTER_FROM - 1)).toBe(false);
    expect(showFeedbackCounter(FEEDBACK_COUNTER_FROM)).toBe(true);
    expect(showFeedbackCounter(FEEDBACK_MAX_LENGTH)).toBe(true);
  });
});

describe("the client — web, the installed web app, or the Android shell", () => {
  function standalone(matches: boolean): void {
    vi.stubGlobal(
      "matchMedia",
      vi.fn((query: string) => ({ matches: matches && query === "(display-mode: standalone)", media: query })),
    );
  }

  it("a browser tab is web", () => {
    standalone(false);
    expect(detectFeedbackClient()).toBe("web");
  });

  it("the Capacitor Android shell is app", () => {
    platform.native = true;
    standalone(false);
    expect(detectFeedbackClient()).toBe("app");
  });

  it("opened from the home screen (display-mode standalone) is pwa", () => {
    standalone(true);
    expect(detectFeedbackClient()).toBe("pwa");
  });

  it("iOS Safari's own standalone flag is pwa too", () => {
    standalone(false);
    // jsdom has no `navigator.standalone` (only iOS Safari does): put one there for this test, and take it away again.
    Object.defineProperty(window.navigator, "standalone", { value: true, configurable: true });
    try {
      expect(detectFeedbackClient()).toBe("pwa");
    } finally {
      delete (window.navigator as { standalone?: boolean }).standalone;
    }
  });

  it("the shell wins over standalone: it is the app, whatever the display mode says", () => {
    platform.native = true;
    standalone(true);
    expect(detectFeedbackClient()).toBe("app");
  });

  it("a browser without matchMedia is a tab, not an error", () => {
    vi.stubGlobal("matchMedia", undefined);
    expect(detectFeedbackClient()).toBe("web");
  });

  it("a matchMedia that throws is a tab, not an error", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => {
        throw new Error("no");
      }),
    );
    expect(detectFeedbackClient()).toBe("web");
  });
});

describe("what the browser says about itself", () => {
  it("the time zone is Intl's IANA name", () => {
    vi.spyOn(Intl, "DateTimeFormat").mockImplementation(
      () => ({ resolvedOptions: () => ({ timeZone: "Europe/Berlin" }) }) as unknown as Intl.DateTimeFormat,
    );
    expect(detectTimeZone()).toBe("Europe/Berlin");
  });

  it("no time zone (undefined, empty, or Intl throwing) is null — never a made-up one", () => {
    const spy = vi.spyOn(Intl, "DateTimeFormat");
    spy.mockImplementation(() => ({ resolvedOptions: () => ({ timeZone: undefined }) }) as unknown as Intl.DateTimeFormat);
    expect(detectTimeZone()).toBeNull();
    spy.mockImplementation(() => ({ resolvedOptions: () => ({ timeZone: "" }) }) as unknown as Intl.DateTimeFormat);
    expect(detectTimeZone()).toBeNull();
    spy.mockImplementation(() => {
      throw new RangeError("no");
    });
    expect(detectTimeZone()).toBeNull();
  });

  it("the language is navigator.language, or null when there is none", () => {
    const spy = vi.spyOn(window.navigator, "language", "get");
    spy.mockReturnValue("de-DE");
    expect(detectLanguage()).toBe("de-DE");
    spy.mockReturnValue("");
    expect(detectLanguage()).toBeNull();
  });
});

describe("the path of the page — never its query string or hash", () => {
  it("cuts a query string", () => {
    expect(pathOnly("/play/BTC?side=up&series=daily")).toBe("/play/BTC");
  });

  it("cuts a hash, and a hash after a query", () => {
    expect(pathOnly("/status#oracle")).toBe("/status");
    expect(pathOnly("/?c=sports#top")).toBe("/");
  });

  it("leaves a plain path as it is", () => {
    expect(pathOnly("/round/ETH-1h-1735689600000")).toBe("/round/ETH-1h-1735689600000");
    expect(pathOnly("/")).toBe("/");
  });

  it("nothing (or only a query) is null", () => {
    expect(pathOnly(null)).toBeNull();
    expect(pathOnly(undefined)).toBeNull();
    expect(pathOnly("")).toBeNull();
    expect(pathOnly("?q=1")).toBeNull();
  });
});

describe("the request — what submitFeedback is given", () => {
  const env = { timeZone: "Europe/Berlin", language: "de-DE", client: "web" as const };

  it("sends exactly message, timeZone, language, page and client", () => {
    expect(buildFeedbackRequest("Nice game", "/play/BTC", env)).toEqual({
      message: "Nice game",
      timeZone: "Europe/Berlin",
      language: "de-DE",
      page: "/play/BTC",
      client: "web",
    });
  });

  it("sends the message trimmed", () => {
    expect(buildFeedbackRequest("  \n Nice game \t\n ", "/", env).message).toBe("Nice game");
  });

  it("keeps what is inside the message as typed (line breaks, spacing)", () => {
    expect(buildFeedbackRequest("Liked: the chart.\n\nDidn't: the  slip.", "/", env).message).toBe("Liked: the chart.\n\nDidn't: the  slip.");
  });

  it("sends the page without its query string", () => {
    expect(buildFeedbackRequest("x", "/play/BTC?side=up", env).page).toBe("/play/BTC");
  });

  it("sends what the browser cannot say as null — time zone, language, page", () => {
    expect(buildFeedbackRequest("x", null, { timeZone: null, language: null, client: "pwa" })).toEqual({
      message: "x",
      timeZone: null,
      language: null,
      page: null,
      client: "pwa",
    });
  });

  it("the client is the one the browser reports: app, pwa or web", () => {
    for (const client of ["app", "pwa", "web"] as const) {
      expect(buildFeedbackRequest("x", "/", { ...env, client }).client).toBe(client);
    }
  });

  it("keeps the context fields inside the sizes documented in the contract", () => {
    const r = buildFeedbackRequest("x", `/${"p".repeat(500)}`, { timeZone: "z".repeat(200), language: "l".repeat(100), client: "web" });
    expect(r.page!.length).toBe(200);
    expect(r.timeZone!.length).toBe(64);
    expect(r.language!.length).toBe(35);
  });

  it("reads the real browser when no environment is given: a tab, the zone and the language of the machine", () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false })));
    vi.spyOn(window.navigator, "language", "get").mockReturnValue("en-GB");
    const r = buildFeedbackRequest("hello", "/wallet");
    expect(r.client).toBe("web");
    expect(r.language).toBe("en-GB");
    expect(typeof r.timeZone === "string" || r.timeZone === null).toBe(true);
    expect(r.page).toBe("/wallet");
    expect(Object.keys(r).sort()).toEqual(["client", "language", "message", "page", "timeZone"]);
  });
});

describe("how a failure is told", () => {
  it("a rate limit (429 RATE_LIMITED) is 'too many messages, try later'", () => {
    const text = feedbackErrorText(new ApiRequestError("RATE_LIMITED", "Too many requests"));
    expect(text).toBe(FEEDBACK_RATE_LIMITED_TEXT);
    expect(text).toMatch(/too many messages/i);
    expect(text).toMatch(/later/i);
  });

  it("anything else is 'couldn't send, try again' — and says the text is still there", () => {
    const text = feedbackErrorText(new ApiRequestError("OTHER", "boom"));
    expect(text).toBe(FEEDBACK_FAILED_TEXT);
    expect(text).toMatch(/couldn't send/i);
    expect(text).toMatch(/try again/i);
    expect(text).toMatch(/still here/i);
  });

  it("every other API code, a network failure and a thing that is not even an error say the same", () => {
    for (const code of ["NOT_FOUND", "ENGINE_NOT_READY", "NOT_SIGNED_IN", "INVALID_NICKNAME", "MARKET_CLOSED"] as const) {
      expect(feedbackErrorText(new ApiRequestError(code, "x"))).toBe(FEEDBACK_FAILED_TEXT);
    }
    expect(feedbackErrorText(new Error("Network error — check your connection and try again."))).toBe(FEEDBACK_FAILED_TEXT);
    expect(feedbackErrorText("nope")).toBe(FEEDBACK_FAILED_TEXT);
    expect(feedbackErrorText(undefined)).toBe(FEEDBACK_FAILED_TEXT);
  });

  it("never shows the server's own sentence (a developer's text), whatever it says", () => {
    expect(feedbackErrorText(new ApiRequestError("RATE_LIMITED", "429 bucket exhausted for key 10.0.0.1:submitFeedback"))).not.toMatch(/bucket|10\.0/);
    expect(feedbackErrorText(new ApiRequestError("OTHER", "NullPointerException at X"))).not.toMatch(/NullPointer/);
  });
});
