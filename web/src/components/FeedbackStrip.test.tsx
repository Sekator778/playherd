/**
 * The feedback strip (D-81): shown to everyone until a message is sent or the strip is closed; gone for good then; and
 * never in the way of the game — it steps aside rather than sit on UP, DOWN, the bet slip or a keyboard.
 */
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiInstanceContext, SessionContext, type SessionState } from "../api/ApiContext";
import { TEST_PLAYER, fakeApi } from "../api/apiTestUtils";
import {
  FEEDBACK_DISMISSED_KEY,
  FEEDBACK_SENT_KEY,
  closeFeedbackForm,
  isFeedbackDismissed,
  markFeedbackSent,
  openFeedbackForm,
  resetFeedbackForTests,
  useFeedbackFormOpen,
} from "../api/feedback";
import type { PlayerSummaryDto } from "../api/contract";
import { FeedbackStrip } from "./FeedbackStrip";
import betSlipSource from "./BetSlip.tsx?raw";
import pairRoomSource from "../pages/PairRoomPage.tsx?raw";

function FormState() {
  return <p data-testid="form-open">{String(useFeedbackFormOpen())}</p>;
}

function session(player: PlayerSummaryDto | null): SessionState {
  return { player, loading: false, refresh: async () => {}, signOut: async () => {}, openConnectModal: () => {} };
}

function renderStrip({ player = null as PlayerSummaryDto | null, path = "/" } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ApiInstanceContext.Provider value={fakeApi({})}>
        <SessionContext.Provider value={session(player)}>
          <FeedbackStrip />
          <FormState />
        </SessionContext.Provider>
      </ApiInstanceContext.Provider>
    </MemoryRouter>,
  );
}

// Found by its class, not its role: a stepped-aside strip is `visibility: hidden` — gone for a screen reader too, name included —
// and is still the strip. (Where a test is about what a person can reach, it asks by role and name instead.)
const strip = () => {
  const el = document.querySelector<HTMLElement>(".feedback-strip");
  if (!el) throw new Error("the feedback strip is not on the page");
  return el;
};
const isAway = () => strip().classList.contains("feedback-strip--away");

/** Every rect jsdom reports is empty (0,0,0,0) — for the geometry tests, an element is put where the test says. */
function place(el: Element, top: number, bottom: number): void {
  vi.spyOn(el, "getBoundingClientRect").mockReturnValue({ top, bottom, left: 0, right: 390, width: 390, height: bottom - top, x: 0, y: top, toJSON: () => ({}) });
}

/** A touch screen (the keyboard is a thing) or a computer (it is not). */
function pointer(kind: "coarse" | "fine"): void {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({ matches: query === `(pointer: ${kind})`, media: query, addEventListener() {}, removeEventListener() {} })),
  );
}

// The strip measures on the next animation frame, as a browser gives it: the test holds the frames and runs them when it says.
let frames: FrameRequestCallback[] = [];
function flushFrames(): void {
  act(() => {
    const pending = frames;
    frames = [];
    pending.forEach((callback) => callback(0));
  });
}
/** The page scrolled (or was resized): the event, and the frame the strip asks for in answer. */
function pageMoved(event = "scroll"): void {
  act(() => {
    window.dispatchEvent(new Event(event));
  });
  flushFrames();
}

beforeEach(() => {
  window.localStorage.clear();
  resetFeedbackForTests();
  frames = [];
  vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback: FrameRequestCallback) => frames.push(callback));
  vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.innerHTML = "";
});

describe("the strip — what it is", () => {
  it("one short line — 'Tell us what you liked and what you didn't' — and a button that says Feedback", () => {
    renderStrip();
    expect(strip()).toHaveTextContent("Tell us what you liked and what you didn't");
    expect(screen.getByRole("button", { name: /Feedback/ })).toBeInTheDocument();
  });

  it("is a labelled region, not an alert: it asks once and never interrupts a screen reader", () => {
    renderStrip();
    expect(strip().tagName).toBe("SECTION");
    expect(strip()).not.toHaveAttribute("role", "alert");
    expect(strip().querySelector("[aria-live]")).toBeNull();
  });

  it("is shown to a visitor who has not signed in", () => {
    renderStrip({ player: null });
    expect(strip()).toBeInTheDocument();
  });

  it("is shown to a signed-in player just the same", () => {
    renderStrip({ player: TEST_PLAYER });
    expect(strip()).toBeInTheDocument();
  });

  it("is shown on every screen — nothing in it depends on the route", () => {
    for (const path of ["/", "/play/BTC", "/round/BTC-1h-0", "/wallet", "/profile/me", "/status", "/how-it-works", "/stats"]) {
      const { unmount } = renderStrip({ path });
      expect(strip()).toBeInTheDocument();
      unmount();
    }
  });

  it("has a close button with a name a screen reader can say, and says where Feedback goes on", () => {
    renderStrip();
    const close = screen.getByRole("button", { name: "Close the feedback banner" });
    expect(close.getAttribute("title")).toMatch(/footer/);
  });
});

describe("opening it", () => {
  it("one tap on the strip opens the form — and takes nothing away: the strip is still the person's to close", async () => {
    const user = userEvent.setup();
    renderStrip();
    await user.click(screen.getByRole("button", { name: /Feedback/ }));
    expect(screen.getByTestId("form-open")).toHaveTextContent("true");
    expect(isFeedbackDismissed()).toBe(false);
    expect(window.localStorage.getItem(FEEDBACK_DISMISSED_KEY)).toBeNull();
  });

  it("the whole text is part of the one button: a thumb need not find a small word", () => {
    renderStrip();
    const open = screen.getByRole("button", { name: /Feedback/ });
    expect(open).toHaveTextContent("Tell us what you liked and what you didn't");
  });
});

describe("closing it — one tap, for good on this browser", () => {
  it("× removes the strip at once, and writes herd.feedback.dismissed", async () => {
    const user = userEvent.setup();
    renderStrip();
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
    expect(window.localStorage.getItem("herd.feedback.dismissed")).toBe("1");
  });

  it("closing does not open the form", async () => {
    const user = userEvent.setup();
    renderStrip();
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(screen.getByTestId("form-open")).toHaveTextContent("false");
  });

  it("stays closed after a reload (a fresh mount over the same storage) — no re-showing", async () => {
    const user = userEvent.setup();
    const first = renderStrip();
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    first.unmount();
    renderStrip();
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });

  it("stays closed whether the person signs in or out afterwards — the browser remembers, not the account", () => {
    window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
    const first = renderStrip({ player: null });
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
    first.unmount();
    renderStrip({ player: TEST_PLAYER });
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });

  it("a close in another tab closes it here too", () => {
    renderStrip();
    expect(strip()).toBeInTheDocument();
    act(() => {
      window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
      window.dispatchEvent(new StorageEvent("storage", { key: FEEDBACK_DISMISSED_KEY, newValue: "1" }));
    });
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });
});

describe("a message sent — the strip has done its job", () => {
  it("is not shown once a message was sent (herd.feedback.sent)", () => {
    window.localStorage.setItem(FEEDBACK_SENT_KEY, "1");
    renderStrip();
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });

  it("goes away the moment a message is stored", () => {
    renderStrip();
    expect(strip()).toBeInTheDocument();
    act(() => markFeedbackSent());
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });
});

describe("a browser that remembers nothing — the strip simply shows", () => {
  it("storage that throws on every read: shown", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    renderStrip();
    expect(strip()).toBeInTheDocument();
  });

  it("storage that throws on every write: × still closes it — for this page", async () => {
    const user = userEvent.setup();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "QuotaExceededError");
    });
    renderStrip();
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });
});

describe("it steps aside — never on the form, the keyboard or the game", () => {
  it("is in the way of nothing at rest", () => {
    renderStrip();
    expect(isAway()).toBe(false);
  });

  it("is away while the form is open, and back, in place, when it closes", () => {
    renderStrip();
    act(() => openFeedbackForm());
    expect(isAway()).toBe(true);
    act(() => closeFeedbackForm());
    expect(isAway()).toBe(false);
  });

  it("a touch screen: away while a text field has the keyboard (the stake, the search), back when it is put down", () => {
    pointer("coarse");
    renderStrip();
    const field = document.createElement("input");
    field.type = "text";
    document.body.appendChild(field);
    act(() => field.focus());
    expect(isAway()).toBe(true);
    act(() => field.blur());
    expect(isAway()).toBe(false);
  });

  it("a textarea and a number field count as typing; a button, a checkbox and a slider do not", () => {
    pointer("coarse");
    renderStrip();
    const make = (tag: string, type?: string) => {
      const el = document.createElement(tag);
      if (type) (el as HTMLInputElement).type = type;
      document.body.appendChild(el);
      return el;
    };
    for (const [el, typing] of [
      [make("textarea"), true],
      [make("input", "number"), true],
      [make("input", "search"), true],
      [make("input", "checkbox"), false],
      [make("input", "range"), false],
      [make("input", "button"), false],
      [make("button"), false],
    ] as Array<[HTMLElement, boolean]>) {
      act(() => el.focus());
      expect(isAway(), `${el.tagName} ${(el as HTMLInputElement).type ?? ""}`).toBe(typing);
      act(() => el.blur());
    }
  });

  it("moving from one text field straight to another does not bring the strip back for a moment", () => {
    pointer("coarse");
    renderStrip();
    const a = document.createElement("input");
    const b = document.createElement("input");
    document.body.append(a, b);
    act(() => a.focus());
    act(() => b.focus());
    expect(isAway()).toBe(true);
  });

  it("a computer has no keyboard in the way: a field with the focus does not move it", () => {
    pointer("fine");
    renderStrip();
    const field = document.createElement("input");
    document.body.appendChild(field);
    act(() => field.focus());
    expect(isAway()).toBe(false);
  });

  it("a browser without matchMedia never steps aside for typing — and does not break", () => {
    vi.stubGlobal("matchMedia", undefined);
    renderStrip();
    const field = document.createElement("input");
    document.body.appendChild(field);
    expect(() => act(() => field.focus())).not.toThrow();
    expect(isAway()).toBe(false);
  });
});

describe("it steps aside for the room's controls — the arena's UP and DOWN, the bet slip with PLACE BET", () => {
  function setScene(controlTop: number, controlBottom: number, selector = "bet-slip"): HTMLElement {
    const control = document.createElement("div");
    control.className = selector;
    document.body.appendChild(control);
    place(control, controlTop, controlBottom);
    return control;
  }

  it("away while the bet slip is under it (the strip is at 684–740), back when the slip has moved on", () => {
    renderStrip();
    place(strip(), 684, 740);
    const slip = setScene(300, 1000);
    pageMoved();
    expect(isAway()).toBe(true);

    place(slip, -400, 500); // scrolled on: its bottom edge is above the strip
    pageMoved();
    expect(isAway()).toBe(false);

    place(slip, 800, 1500); // or not reached yet: its top edge is below the strip
    pageMoved();
    expect(isAway()).toBe(false);
  });

  it("the arena (UP and DOWN) is kept clear of too", () => {
    renderStrip();
    place(strip(), 684, 740);
    setScene(620, 750, "room-arena");
    pageMoved();
    expect(isAway()).toBe(true);
  });

  it("steps aside a little before the edge arrives, not after (a thumb is faster than a frame)", () => {
    renderStrip();
    place(strip(), 684, 740);
    setScene(745, 1500); // 5 px under the strip: not touching it yet, but nearly
    pageMoved();
    expect(isAway()).toBe(true);
    // ... and far enough off, it is in place
    place(document.querySelector(".bet-slip")!, 800, 1500);
    pageMoved();
    expect(isAway()).toBe(false);
  });

  it("the classes it keeps clear of are still the room's arena and the slip's own — a rename would quietly turn the yield off", () => {
    expect(pairRoomSource).toMatch(/className=\{`room-arena[ `]/);
    expect(betSlipSource).toContain('className="card bet-slip"');
  });

  it("the lobby's cards are not on the list — a strip that stepped aside for every card would never be seen", () => {
    renderStrip();
    place(strip(), 684, 740);
    setScene(600, 900, "market-card");
    pageMoved();
    expect(isAway()).toBe(false);
  });

  it("looks again when the window is resized", () => {
    renderStrip();
    place(strip(), 684, 740);
    setScene(300, 1000);
    pageMoved("resize");
    expect(isAway()).toBe(true);
  });

  it("while the strip itself has the focus it stays (a key that scrolls the page must not take the focus away)", async () => {
    const user = userEvent.setup();
    renderStrip();
    place(strip(), 684, 740);
    setScene(300, 1000);
    await user.tab(); // onto the strip's button
    expect(strip().contains(document.activeElement)).toBe(true);
    pageMoved();
    expect(isAway()).toBe(false);
  });

  it("a stepped-aside strip is gone for the eye, the thumb and the keyboard (the class carries visibility and pointer-events)", () => {
    renderStrip();
    place(strip(), 684, 740);
    setScene(300, 1000);
    pageMoved();
    expect(isAway()).toBe(true);
    // Real CSS lives in Feedback.css (jsdom reads it: `css: true`): visibility hidden removes it from the accessibility tree and the tab order.
    expect(getComputedStyle(strip()).visibility).toBe("hidden");
    expect(getComputedStyle(strip()).pointerEvents).toBe("none");
  });
});
