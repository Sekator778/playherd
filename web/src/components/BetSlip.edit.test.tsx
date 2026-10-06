/**
 * The bet slip after the player's own bet is placed, updated or cancelled (first tester pass, D4): UPDATE BET used to leave the
 * form open with the same button and not a word of it, and CANCEL BET could not be reached from there without a reload. And what
 * the slip says waits for the page: "Bet updated." only once the room has read the round again and the card shows the new bet.
 */
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect, useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, BetDto, PlayerAccountDto } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { NOW, bettingRoundOf, catalogue, marketsOf } from "./testFixtures";

const account = (balance: string): PlayerAccountDto => ({ playerId: TEST_PLAYER.playerId, balance, currentSeason: "2026-10", inPlay: "0.000" });

interface RenderOptions {
  /**
   * What the room's reload does when the slip asks for it (`onChanged`). The default reads the round as the server has it, at
   * once. A test passes its own to make the reload late (a promise it settles later), failing (it settles without reading, as
   * the room's `load()` does when the request fails — it swallows the error), rejected, or stale (it reads the OLD bet).
   * `poll()` is the room's own poll, which reads the round again every two seconds: the test calls it when it is that time.
   */
  reload?: (room: { poll: () => void; pollWith: (bet: BetDto | null) => void }) => void | Promise<void>;
}

/** A server with one bet slot for the player: `placeBet` fills it, `cancelBet` empties it; a room reads that slot into the round. */
function renderSlipWithServer(initial: BetDto | null, options: RenderOptions = {}) {
  const server = { bet: initial };
  const placeBet = vi.fn<Api["placeBet"]>(async (req) => {
    server.bet = { playerId: TEST_PLAYER.playerId, roundId: req.roundId, direction: req.direction, leverage: req.leverage, stake: req.stake };
    return server.bet;
  });
  const cancelBet = vi.fn<Api["cancelBet"]>(async () => {
    server.bet = null;
  });
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(catalogue())),
    previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
    myAccount: vi.fn(async () => account("5000.000")),
    placeBet,
    cancelBet,
  });
  // The room's way of setting the round it shows; registered by the room below.
  let show: (round: ReturnType<typeof bettingRoundOf>) => void = () => {};
  const poll = () => act(() => show(bettingRoundOf("BTC", "BTC", { myBet: server.bet })));
  const pollWith = (bet: BetDto | null) => act(() => show(bettingRoundOf("BTC", "BTC", { myBet: bet })));
  const moveOn = () => act(() => show(bettingRoundOf("BTC", "BTC", { roundId: "BTC-1h-next", myBet: null })));
  function Room() {
    const [round, setRound] = useState(() => bettingRoundOf("BTC", "BTC", { myBet: server.bet }));
    useEffect(() => {
      show = setRound;
    }, []);
    return (
      <>
        <button type="button">somewhere else on the page</button>
        <BetSlip round={round} nowMs={NOW} onChanged={() => (options.reload ? options.reload({ poll, pollWith }) : poll())} />
      </>
    );
  }
  render(
    <MemoryRouter>
      <Room />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return { server, placeBet, cancelBet, poll, pollWith, moveOn };
}

const myBet = (stake: string, over: Partial<BetDto> = {}): BetDto => ({
  playerId: TEST_PLAYER.playerId,
  roundId: bettingRoundOf("BTC", "BTC").roundId,
  direction: "UP",
  leverage: 10,
  stake,
  ...over,
});

const newUser = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

async function stakeIs(value: string) {
  const user = newUser();
  const field = await screen.findByLabelText("Stake (PLAY)");
  await user.clear(field);
  await user.type(field, value);
  return user;
}

/** The bet slip's one status line (an element that is always there; only its text changes). */
const status = () => screen.getByRole("status");

/** A promise the test settles later, for a reload that is late. */
function deferred() {
  let resolve: () => void = () => {};
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => vi.useRealTimers());

describe("BetSlip — UPDATE BET (first tester pass, D4)", () => {
  it("leaves edit mode: the form gives way to the 'Your bet' card with the NEW numbers, and says 'Bet updated.'", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));

    expect(placeBet).toHaveBeenCalledWith(expect.objectContaining({ stake: "650.000", leverage: 10 }));
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("Your bet: ▲ UP · x10 · 650 PLAY");
    expect(screen.queryByRole("button", { name: "UPDATE BET" })).not.toBeInTheDocument();
    expect(status()).toHaveTextContent("Bet updated.");
    // and the card has what an edit needs next: edit again, or cancel
    expect(screen.getByRole("button", { name: "EDIT BET" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "CANCEL BET" })).toBeEnabled();
  });

  it("the line says it for a few seconds and then leaves the card alone — the element stays, its text goes", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });
    expect(status()).toBeEmptyDOMElement();
    expect(screen.getByText(/Your bet:/)).toBeInTheDocument();
  });

  it("an update the server refuses stays in the form, with the reason — nothing says 'updated'", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    placeBet.mockRejectedValueOnce(new Error("Network error — check your connection and try again."));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByText("Network error — check your connection and try again.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeEnabled();
    expect(screen.queryByText("Bet updated.")).not.toBeInTheDocument();
    expect(status()).toBeEmptyDOMElement();
  });
});

describe("BetSlip — what it says waits for the page to show the new bet (first tester pass, D4)", () => {
  async function toUpdate(options: RenderOptions) {
    const view = renderSlipWithServer(myBet("500.000"), options);
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    return { ...view, user };
  }

  it("a reload that is late: 'Saved, refreshing…' and the form until it lands — never 'Bet updated.' over the old card", async () => {
    const late = deferred();
    const { user, poll, placeBet } = await toUpdate({ reload: () => late.promise });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));

    expect(placeBet).toHaveBeenCalledTimes(1);
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText("Bet updated.")).not.toBeInTheDocument();
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument(); // the card still holds the old bet: it is not shown
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeDisabled(); // the bet is placed: not again

    poll(); // the room has read the round
    late.resolve();
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("Your bet: ▲ UP · x10 · 650 PLAY");
    expect(status()).toHaveTextContent("Bet updated.");
    expect(status()).not.toHaveTextContent("refreshing");
  });

  it("a reload that fails (the room's load() swallows the error and answers nothing): 'Saved, refreshing…', the form is kept, and the poll finishes it", async () => {
    const { user, poll, placeBet } = await toUpdate({ reload: async () => {} });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText("Bet updated.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeInTheDocument(); // the form, with what the player typed
    expect(screen.getByLabelText("Stake (PLAY)")).toHaveValue("650");
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument();

    poll(); // two seconds later the room's own poll reads the round
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("650 PLAY");
    expect(status()).toHaveTextContent("Bet updated.");
    expect(placeBet).toHaveBeenCalledTimes(1);
  });

  it("a reload that rejects is the same, and nothing is left unhandled", async () => {
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);
    try {
      const { user, poll } = await toUpdate({ reload: () => Promise.reject(new Error("offline")) });
      await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000);
      });
      expect(status()).toHaveTextContent("Saved, refreshing…");
      expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeInTheDocument();
      poll();
      expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      process.off("unhandledRejection", unhandled);
    }
  });

  it("a reload that throws on the spot is the same", async () => {
    const { user, poll } = await toUpdate({
      reload: () => {
        throw new Error("offline");
      },
    });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText(/Something went wrong/)).not.toBeInTheDocument(); // the bet WAS saved
    poll();
    expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
  });

  it("a reload that answers with the OLD bet (a read that was in flight before the change) is not the new state either", async () => {
    const { user, pollWith } = await toUpdate({ reload: ({ pollWith: show }) => show(myBet("500.000")) });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText("Bet updated.")).not.toBeInTheDocument();
    pollWith(myBet("650.000"));
    expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
    expect(screen.getByText(/Your bet:/)).toHaveTextContent("650 PLAY");
  });

  it("an update that changes nothing is already what the page shows: said at once, whatever the reload does", async () => {
    const user = newUser();
    renderSlipWithServer(myBet("500.000"), { reload: async () => {} });
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
    expect(screen.getByText(/Your bet:/)).toHaveTextContent("500 PLAY");
  });

  it("a FIRST bet leaves the form once the page shows it — with the reload failing, it says so and keeps the form", async () => {
    const { poll } = renderSlipWithServer(null, { reload: async () => {} });
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText("Bet placed.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeDisabled();
    poll();
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("300 PLAY");
    expect(status()).toHaveTextContent("Bet placed.");
    expect(screen.queryByRole("button", { name: "PLACE BET" })).not.toBeInTheDocument();
  });

  it("a cancel: the prompt is gone at once, the card holds nothing to press until the page shows the bet gone — then the form and the line", async () => {
    const { poll, cancelBet } = renderSlipWithServer(myBet("500.000"), { reload: async () => {} });
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    expect(screen.getByText("Cancel your bet and get 500 PLAY back?")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" })); // the confirmation's own button
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(cancelBet).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/Cancel your bet and get/)).not.toBeInTheDocument(); // the prompt has done its work
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.queryByText(/Bet cancelled/)).not.toBeInTheDocument();
    expect(screen.getByText(/Your bet:/)).toBeInTheDocument(); // the page has not caught up
    expect(screen.getByRole("button", { name: "EDIT BET" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "CANCEL BET" })).toBeDisabled();

    poll();
    expect(await screen.findByRole("button", { name: "PLACE BET" })).toBeInTheDocument();
    expect(status()).toHaveTextContent("Bet cancelled — 500 PLAY is back in your balance.");
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument();
  });

  it("a page that never shows the change (a second tab placed another bet; a server that stores it differently) does not hold the slip for the rest of the round", async () => {
    const { user, pollWith } = await toUpdate({ reload: async () => {} });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(status()).toHaveTextContent("Saved, refreshing…");
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeDisabled();
    pollWith(myBet("777.000")); // not what was sent, and never will be
    await act(async () => {
      await vi.advanceTimersByTimeAsync(11_000);
    });
    expect(status()).toBeEmptyDOMElement(); // it stopped waiting: nothing is said that is not so
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeEnabled();
  });

  it("a round that moves on while the change is waiting leaves it behind: the next round's slip starts clean", async () => {
    const { user, moveOn } = await toUpdate({ reload: async () => {} });
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(status()).toHaveTextContent("Saved, refreshing…");
    moveOn();
    expect(await screen.findByRole("button", { name: "PLACE BET" })).toBeEnabled();
    expect(status()).toBeEmptyDOMElement();
  });
});

describe("BetSlip — edit mode has a way out that sends nothing", () => {
  it("BACK TO YOUR BET returns to the card with the bet as it was, and the next EDIT starts from the bet, not from what was typed", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("999");
    await user.click(screen.getByRole("button", { name: "BACK TO YOUR BET" }));
    expect(placeBet).not.toHaveBeenCalled();
    expect(screen.getByText(/Your bet:/)).toHaveTextContent("Your bet: ▲ UP · x10 · 500 PLAY");
    await user.click(screen.getByRole("button", { name: "EDIT BET" }));
    expect(screen.getByLabelText("Stake (PLAY)")).toHaveValue("500");
  });

  it("a first bet has nothing to go back to: no such button on PLACE BET", async () => {
    renderSlipWithServer(null);
    await screen.findByRole("button", { name: "PLACE BET" });
    expect(screen.queryByRole("button", { name: "BACK TO YOUR BET" })).not.toBeInTheDocument();
  });
});

describe("BetSlip — placing and cancelling say what happened too", () => {
  it("PLACE BET brings the card and 'Bet placed.'", async () => {
    renderSlipWithServer(null);
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("300 PLAY");
    expect(status()).toHaveTextContent("Bet placed.");
  });

  it("CANCEL BET asks, then the form is back with 'Bet cancelled — 500 PLAY is back in your balance.'", async () => {
    const { cancelBet } = renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" })); // the confirmation's own button
    expect(cancelBet).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("button", { name: "PLACE BET" })).toBeInTheDocument();
    expect(status()).toHaveTextContent("Bet cancelled — 500 PLAY is back in your balance.");
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument();
  });
});

describe("BetSlip — the status line is one live region that is always there (first tester pass, D4)", () => {
  it("it is in the slip before anything happens, empty — and it is the SAME element through the card, the form, the prompt and every notice", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    const line = status();
    expect(line).toBeEmptyDOMElement();
    expect(line).toHaveAttribute("role", "status");
    expect(line).toHaveAttribute("aria-atomic", "true");

    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    expect(status()).toBe(line); // card -> form
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByText("Bet updated.")).toBeInTheDocument();
    expect(status()).toBe(line); // form -> card, with its text
    await user.click(screen.getByRole("button", { name: "CANCEL BET" }));
    expect(status()).toBe(line); // the prompt
    await user.click(screen.getByRole("button", { name: "CANCEL BET" }));
    expect(await screen.findByRole("button", { name: "PLACE BET" })).toBeInTheDocument();
    expect(status()).toBe(line); // card -> form again
    expect(status()).toHaveTextContent("Bet cancelled");
  });

  it("only its text changes: 'Saved, refreshing…' and then the result are said in the one element", async () => {
    const late = deferred();
    const { poll } = renderSlipWithServer(null, { reload: () => late.promise });
    const line = status();
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    expect(status()).toBe(line);
    expect(line).toHaveTextContent("Saved, refreshing…");
    poll();
    late.resolve();
    expect(await screen.findByText("Bet placed.")).toBe(within(line).getByText("Bet placed."));
    expect(status()).toBe(line);
  });

  it("an empty line is out of sight — it takes no room on the card", async () => {
    renderSlipWithServer(myBet("500.000"));
    await screen.findByText(/Your bet:/);
    expect(status()).toHaveClass("visually-hidden");
  });

  it("with something to say it is a line on the card, with a glyph — never colour alone", async () => {
    renderSlipWithServer(null);
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    await screen.findByText("Bet placed.");
    expect(status()).toHaveClass("bet-notice");
    expect(status().querySelector("[aria-hidden='true']")).toHaveTextContent("✓");
  });
});

describe("BetSlip — focus follows the view that replaces the one the player pressed in (first tester pass, D4: after UPDATE BET and EDIT BET it fell to <body>)", () => {
  it("UPDATE BET: focus goes to the status line at once and stays there when the card appears", async () => {
    const late = deferred();
    const { user, poll } = await (async () => {
      const view = renderSlipWithServer(myBet("500.000"), { reload: () => late.promise });
      const user = newUser();
      await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
      await stakeIs("650");
      return { ...view, user };
    })();
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(status()).toHaveFocus(); // while the form is still there
    poll();
    late.resolve();
    await screen.findByText(/Your bet:/);
    expect(document.activeElement).not.toBe(document.body);
    expect(status()).toHaveFocus();
    expect(status()).toHaveTextContent("Bet updated.");
  });

  it("PLACE BET: the same", async () => {
    renderSlipWithServer(null);
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    await screen.findByText(/Your bet:/);
    expect(status()).toHaveFocus();
  });

  it("EDIT BET: focus goes to the form, named 'Edit your bet' — and Tab goes on to its first control", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    const form = screen.getByRole("form", { name: "Edit your bet" });
    expect(form).toHaveFocus();
    expect(document.activeElement).not.toBe(document.body);
    await user.tab();
    expect(form).toContainElement(document.activeElement as HTMLElement);
    expect(document.activeElement).not.toBe(form);
  });

  it("BACK TO YOUR BET: focus goes to the 'Your bet' line of the card", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await user.click(screen.getByRole("button", { name: "BACK TO YOUR BET" }));
    expect(screen.getByText(/Your bet:/)).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("button", { name: "EDIT BET" })).toHaveFocus();
  });

  it("CANCEL BET asks: focus goes to the question — not to a button that cancels; KEEP BET puts it back on the card", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    const question = screen.getByText("Cancel your bet and get 500 PLAY back?");
    expect(question).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "KEEP BET" }));
    expect(screen.getByText(/Your bet:/)).toHaveFocus();
    expect(screen.queryByText(/Cancel your bet and get/)).not.toBeInTheDocument();
  });

  it("a confirmed cancel: focus goes to the status line, and is still there when the form is back", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = newUser();
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    await screen.findByRole("button", { name: "PLACE BET" });
    expect(status()).toHaveFocus();
    expect(status()).toHaveTextContent("Bet cancelled");
  });

  it("a player who has gone to something else meanwhile is not pulled back when the reload lands", async () => {
    const late = deferred();
    const { poll } = renderSlipWithServer(null, { reload: () => late.promise });
    const user = await stakeIs("300");
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    expect(status()).toHaveFocus();
    const elsewhere = screen.getByRole("button", { name: "somewhere else on the page" });
    await user.click(elsewhere);
    expect(elsewhere).toHaveFocus();
    poll();
    late.resolve();
    await screen.findByText(/Your bet:/);
    expect(status()).toHaveTextContent("Bet placed.");
    expect(elsewhere).toHaveFocus();
  });
});
