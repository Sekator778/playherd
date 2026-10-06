/**
 * The bet slip after the player's own bet is placed, updated or cancelled (first tester pass, D4): UPDATE BET used to leave the
 * form open with the same button and not a word of it, and CANCEL BET could not be reached from there without a reload.
 */
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, BetDto, PlayerAccountDto } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { NOW, bettingRoundOf, catalogue, marketsOf } from "./testFixtures";

const account = (balance: string): PlayerAccountDto => ({ playerId: TEST_PLAYER.playerId, balance, currentSeason: "2026-10", inPlay: "0.000" });

/** A server with one bet slot for the player: `placeBet` fills it, `cancelBet` empties it, and `onChanged` re-reads it into the round. */
function renderSlipWithServer(initial: BetDto | null) {
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
  function Room() {
    const [round, setRound] = useState(() => bettingRoundOf("BTC", "BTC", { myBet: server.bet }));
    return (
      <BetSlip
        round={round}
        nowMs={NOW}
        // the room's own reload: the round as the server now has it
        onChanged={async () => setRound(bettingRoundOf("BTC", "BTC", { myBet: server.bet }))}
      />
    );
  }
  render(
    <MemoryRouter>
      <Room />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return { server, placeBet, cancelBet };
}

const myBet = (stake: string, over: Partial<BetDto> = {}): BetDto => ({
  playerId: TEST_PLAYER.playerId,
  roundId: bettingRoundOf("BTC", "BTC").roundId,
  direction: "UP",
  leverage: 10,
  stake,
  ...over,
});

async function stakeIs(value: string) {
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const field = await screen.findByLabelText("Stake (PLAY)");
  await user.clear(field);
  await user.type(field, value);
  return user;
}

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => vi.useRealTimers());

describe("BetSlip — UPDATE BET (first tester pass, D4)", () => {
  it("leaves edit mode: the form gives way to the 'Your bet' card with the NEW numbers, and says 'Bet updated.'", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));

    expect(placeBet).toHaveBeenCalledWith(expect.objectContaining({ stake: "650.000", leverage: 10 }));
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent("Your bet: ▲ UP · x10 · 650 PLAY");
    expect(screen.queryByRole("button", { name: "UPDATE BET" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Bet updated.");
    // and the card has what an edit needs next: edit again, or cancel
    expect(screen.getByRole("button", { name: "EDIT BET" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "CANCEL BET" })).toBeEnabled();
  });

  it("the line says it for a few seconds and then leaves the card alone", async () => {
    renderSlipWithServer(myBet("500.000"));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Bet updated.");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByText(/Your bet:/)).toBeInTheDocument();
  });

  it("an update the server refuses stays in the form, with the reason — nothing says 'updated'", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    placeBet.mockRejectedValueOnce(new Error("Network error — check your connection and try again."));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(await screen.findByRole("button", { name: "EDIT BET" }));
    await stakeIs("650");
    await user.click(screen.getByRole("button", { name: "UPDATE BET" }));
    expect(await screen.findByText("Network error — check your connection and try again.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "UPDATE BET" })).toBeEnabled();
    expect(screen.queryByText("Bet updated.")).not.toBeInTheDocument();
  });
});

describe("BetSlip — edit mode has a way out that sends nothing", () => {
  it("BACK TO YOUR BET returns to the card with the bet as it was, and the next EDIT starts from the bet, not from what was typed", async () => {
    const { placeBet } = renderSlipWithServer(myBet("500.000"));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
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
    expect(screen.getByRole("status")).toHaveTextContent("Bet placed.");
  });

  it("CANCEL BET asks, then the form is back with 'Bet cancelled — 500 PLAY is back in your balance.'", async () => {
    const { cancelBet } = renderSlipWithServer(myBet("500.000"));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" })); // the confirmation's own button
    expect(cancelBet).toHaveBeenCalledTimes(1);
    const form = (await screen.findByRole("button", { name: "PLACE BET" })).closest("form") as HTMLElement;
    expect(within(form).getByRole("status")).toHaveTextContent("Bet cancelled — 500 PLAY is back in your balance.");
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument();
  });
});
