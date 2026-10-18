/**
 * The host over a QUEUE of moments — the second card is its own card. Found in the first live run against the real server
 * (two bets settling at one boundary): the card that followed another reused its predecessor's component instance, so it
 * showed the first card's balance, played no sound/haptics/particles of its own, and — its dismiss timer is set only on
 * mount — never went away by itself: a full-screen overlay over the next bet until it was tapped.
 */
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setForcedReducedMotion } from "./reducedMotion";
import { ResultMomentHost } from "./ResultMomentHost";
import type { ResultEvent } from "./resultEvent";
import { publish } from "./resultEventBus";

function event(over: Partial<ResultEvent>): ResultEvent {
  return {
    id: "r1:p",
    kind: "LOSS",
    roundId: "r1",
    marketId: "BTC",
    symbol: "BTC",
    interval: "HOURLY",
    durationSec: 180,
    netPlay: -1.71,
    stakePlay: 200,
    leverage: 10,
    balanceBefore: 10_000,
    balanceAfter: 9_998.29,
    atMs: 0,
    tier: 1,
    ...over,
  };
}

const balanceLine = () => document.querySelector(".result-card__balance")?.textContent ?? null;
const title = () => document.querySelector(".result-card__title")?.textContent ?? null;

beforeEach(() => {
  vi.useFakeTimers();
  setForcedReducedMotion(true);
});
afterEach(() => {
  vi.useRealTimers();
  setForcedReducedMotion(null);
});

describe("ResultMomentHost — staked → returned, with the fees", () => {
  it("a moment that knows the fees adds them to the returned line; one that does not shows the line alone", () => {
    render(
      <MemoryRouter>
        <ResultMomentHost />
      </MemoryRouter>,
    );
    act(() => {
      publish(event({ id: "f:p", roundId: "f", kind: "WIN", netPlay: 9.2, stakePlay: 100, feesPlay: 1.07, balanceBefore: 1000, balanceAfter: 1009.2 }));
    });
    expect(document.querySelector(".result-card__returned")?.textContent).toBe("Stake 100 → returned 109.20 PLAY · fees 1.07");
    act(() => {
      document.querySelector<HTMLElement>(".result-overlay")?.click();
    });
    act(() => {
      publish(event({ id: "g:p", roundId: "g", kind: "LOSS", netPlay: -100, stakePlay: 100, balanceBefore: 1000, balanceAfter: 1000 }));
    });
    expect(document.querySelector(".result-card__returned")?.textContent).toBe("Stake 100 → returned 0 PLAY");
  });
});

describe("ResultMomentHost — a queue of moments", () => {
  function renderHost() {
    return render(
      <MemoryRouter>
        <ResultMomentHost />
      </MemoryRouter>,
    );
  }

  it("each moment shows its OWN numbers: the second card's balance is not the first one's", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", symbol: "LUIZ", kind: "EVEN", netPlay: 0, balanceBefore: 10_000, balanceAfter: 10_000 }));
      publish(event({ id: "b:p", roundId: "b", symbol: "BTC", netPlay: -1.71, balanceBefore: 10_000, balanceAfter: 9_998.29 }));
    });
    expect(balanceLine()).toContain("10,000 PLAY");
    expect(screen.getByText("No gain, no loss")).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    // the second card: the loss, and the balance AFTER it
    expect(title()).toContain("-1.71 PLAY");
    expect(balanceLine()).toContain("9,998.29 PLAY");
  });

  it("says what came back of the stake next to the signed result — and the headline stays the net result (Р-98)", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "LOSS", netPlay: -19.85, stakePlay: 100, balanceBefore: 10_000, balanceAfter: 9_980.15 }));
    });
    expect(title()).toContain("-19.85 PLAY");
    expect(screen.getByText("Stake 100 → returned 80.15 PLAY")).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    act(() => {
      publish(event({ id: "b:p", roundId: "b", kind: "LIQUIDATION", netPlay: -100, stakePlay: 100 }));
    });
    expect(title()).toContain("-100 PLAY");
    expect(screen.getByText("Stake 100 → returned 0 PLAY")).toBeInTheDocument();
  });

  it("the mid-round liquidation toast has no 'returned' line: the round is not over", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "c:p", roundId: "c", kind: "LIQUIDATION", midRound: true, netPlay: -100, stakePlay: 100 }));
    });
    expect(screen.queryByText(/returned/)).not.toBeInTheDocument();
  });

  it("every card leaves by itself — the second one too (it used to wait for a tap, over the player's next bet)", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500); // the first (EVEN) card's hold
    });
    expect(document.querySelector(".result-overlay")).not.toBeNull(); // the second is up
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(document.querySelector(".result-overlay")).toBeNull();
  });

  // BTC of the default (mock) catalogue is priced by Coinbase with Kraken behind it (Р-76).
  it("a round that settled on the market's backup exchange says so, under the figures", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "k:p", roundId: "k", marketId: "BTC", venue: "kraken" }));
    });
    await act(async () => {}); // the market list answers
    expect(document.querySelector(".result-card__venue")).toHaveTextContent("Settled on Kraken, the backup exchange — Coinbase had no price when the round started");
  });

  it("…and says nothing when it settled on the main exchange, or when the event names no exchange (a history row)", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "c:p", roundId: "c", marketId: "BTC", venue: "coinbase" }));
    });
    await act(async () => {});
    expect(document.querySelector(".result-card__venue")).toBeNull();
    await act(async () => {
      (document.querySelector(".result-overlay") as HTMLElement).click();
      publish(event({ id: "h:p", roundId: "h", marketId: "BTC" }));
    });
    await act(async () => {});
    expect(document.querySelector(".result-card__venue")).toBeNull();
  });

  it("a mid-round liquidation toast keeps its own words — the venue line is for a settlement", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "l:p", roundId: "l", kind: "LIQUIDATION", midRound: true, marketId: "BTC", venue: "kraken" }));
    });
    await act(async () => {});
    expect(document.querySelector(".result-card__venue")).toBeNull();
    expect(document.querySelector(".result-card__note")).toHaveTextContent("You lose most of your stake.");
  });

  it("a card dismissed by a tap is followed by the next, which is also its own card", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
    });
    await act(async () => {
      (document.querySelector(".result-overlay") as HTMLElement).click();
    });
    expect(title()).toContain("-1.71 PLAY");
    expect(balanceLine()).toContain("9,998.29 PLAY");
  });
});

function renderHost() {
  return render(
    <MemoryRouter>
      <ResultMomentHost />
    </MemoryRouter>,
  );
}

/** The card (not the dimmed area around it) of the moment on screen. */
const card = () => document.querySelector(".result-card") as HTMLElement;
const tapOn = async (element: Element) => {
  await act(async () => {
    (element as HTMLElement).click();
  });
};

describe("ResultMomentHost — a tap on the moment's card dismisses it (first tester pass, D3)", () => {
  it("a tap on the card itself — not only on the dimmed area around it — dismisses it, and the next moment follows", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
    });
    expect(screen.getByText("No gain, no loss")).toBeInTheDocument();
    await tapOn(card());
    expect(screen.queryByText("No gain, no loss")).not.toBeInTheDocument();
    expect(title()).toContain("-1.71 PLAY");
  });

  it("…so does a tap on its hint, which says exactly that", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
    });
    await tapOn(screen.getByText("Tap to dismiss"));
    expect(title()).toContain("-1.71 PLAY");
  });

  it("a tap on the toast of a liquidation, which blocks nothing around it, dismisses it too", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "l:p", roundId: "l", kind: "LIQUIDATION", midRound: true, netPlay: -200 }));
    });
    await tapOn(card());
    expect(document.querySelector(".result-overlay")).toBeNull();
  });

  it("'View round' dismisses once: the moment after it is shown, not skipped along with it", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b", netPlay: -1.71 }));
      publish(event({ id: "c:p", roundId: "c", netPlay: -2.5, balanceAfter: 9_995.79 }));
    });
    await tapOn(screen.getByRole("link", { name: "View round" }));
    expect(title()).toContain("-1.71 PLAY"); // b, not c
  });

  it("the sound switch is the one thing on the card that does not dismiss it", async () => {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
    });
    await tapOn(screen.getByRole("button", { name: /sound/i }));
    expect(screen.getByText("No gain, no loss")).toBeInTheDocument();
  });
});

describe("ResultMomentHost — the '+N more results' summary (first tester pass, D3)", () => {
  /** Four results at once: two are shown one by one, the rest collapse into the summary. */
  async function toSummary(extra: Array<Partial<ResultEvent>> = [{ id: "c:p", roundId: "c", netPlay: -2.5 }, { id: "d:p", roundId: "d", netPlay: -3.5 }]) {
    renderHost();
    act(() => {
      publish(event({ id: "a:p", roundId: "a", kind: "EVEN", netPlay: 0 }));
      publish(event({ id: "b:p", roundId: "b" }));
      extra.forEach((e) => publish(event(e)));
    });
    await tapOn(card());
    await tapOn(card());
  }

  it("leaves by itself, like a single result — it used to dim and blur the app until it was tapped", async () => {
    await toSummary();
    const header = screen.getByRole("button", { name: /\+2 more results/ });
    expect(header).toHaveTextContent("+2 more results — -6 PLAY net");
    expect(header.querySelector(".outcome-lost")).toHaveTextContent("-6 PLAY"); // the net is as loud as a win would be
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2900);
    });
    expect(document.querySelector(".result-overlay")).not.toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    expect(document.querySelector(".result-overlay")).toBeNull();
  });

  it("once the player has opened the list to read it, it stays longer — and still leaves", async () => {
    await toSummary();
    await tapOn(screen.getByRole("button", { name: /\+2 more results/ }));
    expect(screen.getByText("-2.50 PLAY")).toBeInTheDocument();
    expect(screen.getByText("-3.50 PLAY")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(7900);
    });
    expect(document.querySelector(".result-overlay")).not.toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    expect(document.querySelector(".result-overlay")).toBeNull();
  });

  it("Dismiss and the dimmed area still dismiss it at once", async () => {
    await toSummary();
    await tapOn(screen.getByRole("button", { name: "Dismiss" }));
    expect(document.querySelector(".result-overlay")).toBeNull();
  });

  it("a summary of ONE result names it — signed, with its market and leverage — instead of '+1 more results' behind a tap", async () => {
    await toSummary([{ id: "c:p", roundId: "c", symbol: "ETH", leverage: 2, netPlay: -0.12 }]);
    const summary = document.querySelector(".result-card--overflow") as HTMLElement;
    expect(summary).toHaveTextContent("1 more result");
    expect(summary).toHaveTextContent("-0.12 PLAY");
    expect(summary).toHaveTextContent("ETH · x2");
    expect(summary).not.toHaveTextContent(/\+1 more results/);
    expect(summary.querySelector(".result-card__overflow-header")?.tagName).not.toBe("BUTTON"); // nothing to open
  });

  it("…and a liquidated one says so", async () => {
    await toSummary([{ id: "c:p", roundId: "c", kind: "LIQUIDATION", symbol: "BOLT", leverage: 10, netPlay: -100 }]);
    expect(document.querySelector(".result-card--overflow")).toHaveTextContent("-100 PLAY liquidated · BOLT · x10");
  });

  describe("stays while it is being read — a pointer on it, the keyboard on its controls — and its clock starts again when that ends", () => {
    // user-event needs time that moves by itself under fake timers (Testing Library's own waits would otherwise never end), so the
    // checks below keep half a second to spare around each limit.
    beforeEach(() => {
      vi.useRealTimers();
      vi.useFakeTimers({ shouldAdvanceTime: true });
    });
    const user = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const summaryCard = () => document.querySelector(".result-card--overflow") as HTMLElement;
    const overlayIsUp = () => document.querySelector(".result-overlay") !== null;
    const wait = (ms: number) =>
      act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
      });

    it("a pointer on the card holds it for as long as it stays — past the 3 s it would have left after", async () => {
      await toSummary();
      const u = user();
      await u.hover(summaryCard());
      await wait(3_500);
      expect(overlayIsUp()).toBe(true);
      await wait(20_000);
      expect(overlayIsUp()).toBe(true);
    });

    it("…and when the pointer leaves, the clock starts over: a whole hold from then, not what was left of it", async () => {
      await toSummary();
      const u = user();
      await wait(2_000); // most of the 3 s has gone
      await u.hover(summaryCard());
      await wait(10_000);
      await u.unhover(summaryCard());
      await wait(2_000); // a whole 3 s is not over, though 12 s have passed since the card came
      expect(overlayIsUp()).toBe(true);
      await wait(1_500);
      expect(overlayIsUp()).toBe(false);
    });

    it("a pointer on the opened list holds it too, and the open list keeps its longer hold once the pointer is gone", async () => {
      await toSummary();
      const u = user();
      await u.click(screen.getByRole("button", { name: /\+2 more results/ })); // opens the list; the pointer is on the card
      await wait(30_000);
      expect(overlayIsUp()).toBe(true);
      await u.unhover(summaryCard());
      await wait(6_000);
      expect(overlayIsUp()).toBe(true);
      await wait(3_000);
      expect(overlayIsUp()).toBe(false);
    });

    it("the keyboard on one of its controls holds it, and Tab to the next control of the card does not let go", async () => {
      await toSummary();
      const u = user();
      await u.tab(); // the header of the summary
      expect(screen.getByRole("button", { name: /\+2 more results/ })).toHaveFocus();
      await wait(10_000);
      expect(overlayIsUp()).toBe(true);
      await u.tab(); // the Dismiss button: still inside the card
      expect(screen.getByRole("button", { name: "Dismiss" })).toHaveFocus();
      await wait(10_000);
      expect(overlayIsUp()).toBe(true);
    });

    it("…and when focus leaves the card the clock starts over", async () => {
      await toSummary();
      const u = user();
      await u.tab();
      await wait(10_000);
      expect(overlayIsUp()).toBe(true);
      act(() => (document.activeElement as HTMLElement).blur()); // focus goes to the page
      await wait(2_000);
      expect(overlayIsUp()).toBe(true);
      await wait(1_500);
      expect(overlayIsUp()).toBe(false);
    });

    it("a click or a tap that happens to focus a button is not the keyboard: once the pointer is gone, it leaves on time", async () => {
      await toSummary();
      const u = user();
      await u.click(screen.getByRole("button", { name: /\+2 more results/ }));
      expect(screen.getByRole("button", { name: /\+2 more results/ })).toHaveFocus(); // the click focused it …
      await u.unhover(summaryCard()); // … and the finger or the mouse left
      await wait(6_000);
      expect(overlayIsUp()).toBe(true);
      await wait(3_000);
      expect(overlayIsUp()).toBe(false); // the focus on the button did not hold it
    });

    it("Dismiss still dismisses at once while it is held", async () => {
      await toSummary();
      const u = user();
      await u.hover(summaryCard());
      await u.click(screen.getByRole("button", { name: "Dismiss" }));
      expect(overlayIsUp()).toBe(false);
    });
  });
});
