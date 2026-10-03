/**
 * The host over a QUEUE of moments — the second card is its own card. Found in the first live run against the real server
 * (two bets settling at one boundary): the card that followed another reused its predecessor's component instance, so it
 * showed the first card's balance, played no sound/haptics/particles of its own, and — its dismiss timer is set only on
 * mount — never went away by itself: a full-screen overlay over the next bet until it was tapped.
 */
import { act, render, screen } from "@testing-library/react";
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
    expect(document.querySelector(".result-card__note")).toHaveTextContent("Your whole stake is lost.");
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
