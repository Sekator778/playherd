import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, type Api, type PlayerAccountDto, type RoundBetting } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { NOW, bettingRoundOf, catalogue, marketsOf } from "./testFixtures";

const account = (balance: string): PlayerAccountDto => ({ playerId: TEST_PLAYER.playerId, balance, currentSeason: "2026-10", inPlay: "0.000" });
const engineNotReady = () => new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering — nothing was changed, please try again in a moment");

function renderSlip(methods: Partial<Api>, round: RoundBetting = bettingRoundOf("BTC", "BTC")) {
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(catalogue())),
    previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
    ...methods,
  });
  const onChanged = vi.fn();
  render(
    <MemoryRouter>
      <BetSlip round={round} nowMs={NOW} onChanged={onChanged} />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return { api, onChanged };
}

describe("BetSlip — an unknown balance is not a balance of zero", () => {
  it("while the balance loads, no 'not enough PLAY' is shown and the bet is not blocked; once known, the check is real", async () => {
    let answer: (acc: PlayerAccountDto) => void = () => {};
    renderSlip({ myAccount: () => new Promise<PlayerAccountDto>((resolve) => (answer = resolve)) });
    const stake = await screen.findByLabelText("Stake (PLAY)");
    const user = userEvent.setup();
    await user.clear(stake);
    await user.type(stake, "500"); // more than the balance that arrives below
    expect(screen.queryByText(/Not enough PLAY/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Available:/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeEnabled();

    await act(async () => answer(account("100.000")));
    expect(await screen.findByText(/Not enough PLAY — you have 100 PLAY, this bet needs 500 PLAY/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeDisabled();
  });

  it("a failed load (503 while the server restarts) says so instead of claiming a zero balance, and 'Try now' loads it", async () => {
    const myAccount = vi.fn<Api["myAccount"]>().mockRejectedValueOnce(engineNotReady()).mockResolvedValue(account("5000.000"));
    renderSlip({ myAccount });
    expect(await screen.findByText("Couldn't load your balance yet — trying again.")).toBeInTheDocument();
    expect(screen.queryByText(/Not enough PLAY|you have 0 PLAY/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeEnabled();

    await userEvent.setup().click(screen.getByRole("button", { name: "Try now" }));
    expect(await screen.findByText(/Available:/)).toHaveTextContent("Available: 5,000 PLAY");
    expect(screen.queryByText("Couldn't load your balance yet — trying again.")).not.toBeInTheDocument();
    expect(myAccount).toHaveBeenCalledTimes(2);
  });

  describe("a failed load is retried by itself", () => {
    beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
    afterEach(() => vi.useRealTimers());

    it("every 3 seconds, until it works — no reload of the page, no wait for the next round", async () => {
      const myAccount = vi.fn<Api["myAccount"]>().mockRejectedValueOnce(engineNotReady()).mockRejectedValueOnce(engineNotReady()).mockResolvedValue(account("5000.000"));
      renderSlip({ myAccount });
      await screen.findByText("Couldn't load your balance yet — trying again.");
      expect(myAccount).toHaveBeenCalledTimes(1);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      expect(myAccount).toHaveBeenCalledTimes(2);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      await waitFor(() => expect(screen.getByText(/Available:/)).toHaveTextContent("Available: 5,000 PLAY"));
      expect(myAccount).toHaveBeenCalledTimes(3);
    });
  });

  it("the balance is read again after the player's own bet changes (it was debited), so an edit checks the right number", async () => {
    const myAccount = vi.fn(async () => account("4500.000"));
    const bet = { playerId: TEST_PLAYER.playerId, roundId: "BTC-1h-x", direction: "UP" as const, leverage: 10, stake: "500.000" };
    const { rerender } = (() => {
      const api = fakeApi({
        markets: vi.fn(async () => marketsOf(catalogue())),
        previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
        myAccount,
      });
      const Wrapper = apiWrapper(api, TEST_PLAYER);
      const view = render(
        <MemoryRouter>
          <BetSlip round={bettingRoundOf("BTC", "BTC", { myBet: null })} nowMs={NOW} onChanged={() => {}} />
        </MemoryRouter>,
        { wrapper: Wrapper },
      );
      return { rerender: view.rerender };
    })();
    await waitFor(() => expect(myAccount).toHaveBeenCalledTimes(1));
    rerender(
      <MemoryRouter>
        <BetSlip round={bettingRoundOf("BTC", "BTC", { myBet: bet })} nowMs={NOW} onChanged={() => {}} />
      </MemoryRouter>,
    );
    await waitFor(() => expect(myAccount).toHaveBeenCalledTimes(2));
  });
});

describe("BetSlip — a button that needs the server says plainly when the server is restarting or busy", () => {
  async function placeBetWith(error: Error) {
    const user = userEvent.setup();
    const { api } = renderSlip({ myAccount: vi.fn(async () => account("5000.000")), placeBet: vi.fn(async () => Promise.reject(error)) });
    await screen.findByText(/Available:/);
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    return api;
  }

  it("503 ENGINE_NOT_READY: 'the server is restarting — try again in a moment', not the raw text", async () => {
    await placeBetWith(engineNotReady());
    expect(await screen.findByText("The server is restarting — try again in a moment.")).toBeInTheDocument();
    expect(screen.queryByText(/game engine is recovering/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeEnabled(); // and the player may simply press it again
  });

  it("429 RATE_LIMITED: its own plain message, not the server's developer wording", async () => {
    await placeBetWith(new ApiRequestError("RATE_LIMITED", "too many requests — please slow down and try again shortly"));
    expect(await screen.findByText("Too many requests just now — wait a few seconds and try again.")).toBeInTheDocument();
    expect(screen.queryByText(/please slow down/)).not.toBeInTheDocument();
  });

  it("any other server code keeps the server's own sentence", async () => {
    await placeBetWith(new ApiRequestError("ROUND_NOT_OPEN_FOR_BETTING", "Betting just closed for this round."));
    expect(await screen.findByText("Betting just closed for this round.")).toBeInTheDocument();
  });

  it("a transport failure shows its own text (the network, not 'something went wrong')", async () => {
    await placeBetWith(new Error("Network error — check your connection and try again."));
    expect(await screen.findByText("Network error — check your connection and try again.")).toBeInTheDocument();
  });

  it("cancelling a bet: the same words, and the bet stays", async () => {
    const user = userEvent.setup();
    const bet = { playerId: TEST_PLAYER.playerId, roundId: "BTC-1h-x", direction: "UP" as const, leverage: 10, stake: "500.000" };
    const cancelBet = vi.fn(async () => Promise.reject(engineNotReady()));
    const { onChanged } = renderSlip({ myAccount: vi.fn(async () => account("4500.000")), cancelBet }, bettingRoundOf("BTC", "BTC", { myBet: bet }));
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    await user.click(await screen.findByRole("button", { name: "CANCEL BET" }));
    expect(await screen.findByText("The server is restarting — try again in a moment.")).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "CANCEL BET" })).toBeEnabled();
  });
});
