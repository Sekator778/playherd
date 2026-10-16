/**
 * A bet set up in the signed-out slip survives the sign-in (first tester, F4): the signed-in slip starts from it, and the welcome
 * dialog's main button places exactly that bet instead of only closing itself — with the server's refusal shown in the dialog.
 */
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider, useSession } from "../api/ApiContext";
import { TEST_PLAYER, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import { ApiRequestError, type Api, type Direction, type LiquidationPreviewRequest } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { ConnectWalletModal } from "./ConnectWalletModal";
import { clearBetDraft } from "./betDraft";
import { bettingRoundOf, market, marketsOf, seriesDto } from "./testFixtures";

vi.mock("../wallet/testWallet", () => ({
  getTestWallet: () => ({ address: "txch1testwallet" }),
  signTestWalletMessage: () => ({ address: "txch1testwallet", publicKey: "pk", signature: "sig" }),
}));

const CAP = 1200;
const HOUR = 3_600_000;
const scrollIntoView = vi.fn();

/** The round the room shows: betting stays open for an hour of the real clock (the draft is judged by it). */
const round = () => {
  const start = Date.now() + HOUR;
  return bettingRoundOf("BTC", "BTC", { roundId: `BTC-1h-${start}`, window: { interval: "HOURLY", startMs: start, endMs: start + HOUR } });
};

function preview(req: LiquidationPreviewRequest) {
  const stake = Number(req.stake);
  return {
    entryPriceUsed: "100.00",
    liquidationPrice: (100 * (1 - 0.9 / req.leverage)).toFixed(2),
    percentToLiquidation: 0.9 / req.leverage,
    feeHerd: "0.250",
    feeNetwork: "0.100",
    totalDebit: (stake + 0.35).toFixed(3),
    maxLeverageForStake: CAP,
  };
}

function Room({ round, nowMs, onChanged }: { round: ReturnType<typeof bettingRoundOf>; nowMs: number; onChanged: () => void }) {
  const [direction, setDirection] = useState<Direction>("UP");
  const { player } = useSession();
  return (
    <>
      <button type="button" onClick={() => setDirection("DOWN")}>
        the arena: DOWN
      </button>
      <p data-testid="signed-in">{player ? "in" : "out"}</p>
      <BetSlip round={round} nowMs={nowMs} onChanged={onChanged} direction={direction} onDirectionChange={setDirection} hideDirectionPicker />
    </>
  );
}

function setup(placeBet: Api["placeBet"], path = "/play/BTC") {
  const theRound = round();
  let player: typeof TEST_PLAYER | null = null;
  const onChanged = vi.fn();
  const api = fakeApi({
    currentPlayer: async () => player,
    serverInfo: async () => serverInfoDto(),
    authChallenge: async () => ({ nonce: "n", message: "sign me", expiresAtMs: 1 }),
    authVerify: async () => {
      player = { ...TEST_PLAYER, nickname: "" };
      return { player, isNewPlayer: true };
    },
    setNickname: async (nickname: string) => ({ ...TEST_PLAYER, nickname }),
    markets: async () => marketsOf([market("BTC", { series: [seriesDto({ maxLeverage: CAP })] })]),
    previewLiquidation: async (req: LiquidationPreviewRequest) => preview(req),
    myAccount: async () => ({ playerId: TEST_PLAYER.playerId, balance: "10000.000", currentSeason: "2026-10", inPlay: "0.000" }),
    placeBet,
  });
  render(
    <MemoryRouter initialEntries={[path]}>
      <ApiProvider api={api}>
        <Room round={theRound} nowMs={Date.now()} onChanged={onChanged} />
        <ConnectWalletModal />
      </ApiProvider>
    </MemoryRouter>,
  );
  return { theRound, onChanged, user: userEvent.setup() };
}

/** In the signed-out slip: DOWN in the arena, x40, 250 PLAY — then CONNECT WALLET, the test wallet and a nickname, up to the welcome dialog. */
async function setUpBetAndSignIn(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText("Cost of this bet", { selector: "p" });
  await user.click(screen.getByRole("button", { name: "the arena: DOWN" }));
  fireEvent.change(screen.getByRole("slider"), { target: { value: "40" } });
  const stake = screen.getByLabelText("Preview stake (PLAY)");
  await user.clear(stake);
  await user.type(stake, "250");
  await waitFor(() => expect(screen.getByLabelText("Cost of this bet")).toHaveTextContent("250.350 PLAY"));
  await user.click(screen.getByRole("button", { name: "CONNECT WALLET" }));
  const dialog = await screen.findByRole("dialog");
  await user.click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
  await user.type(await within(dialog).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
  await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
  await within(dialog).findByRole("heading", { name: "You've got 10,000 PLAY" });
  return dialog;
}

beforeEach(() => {
  Element.prototype.scrollIntoView = scrollIntoView;
  scrollIntoView.mockClear();
});
afterEach(() => clearBetDraft());

describe("the bet set up before signing in (F4)", () => {
  it("the welcome dialog says the bet in one line, with the total debit, and PLACE THIS BET places exactly it", async () => {
    const placeBet = vi.fn<Api["placeBet"]>(async (req) => ({ playerId: TEST_PLAYER.playerId, ...req }));
    const { theRound, onChanged, user } = setup(placeBet);
    const dialog = await setUpBetAndSignIn(user);

    expect(within(dialog).getByText(/Your bet:.*DOWN · x40 · 250 PLAY/)).toBeInTheDocument();
    expect(within(dialog).getByText("250.350 PLAY in all, fees included")).toBeInTheDocument();
    expect(placeBet).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole("button", { name: "PLACE THIS BET" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(placeBet).toHaveBeenCalledTimes(1);
    expect(placeBet).toHaveBeenCalledWith({ roundId: theRound.roundId, direction: "DOWN", leverage: 40, stake: "250.000" });
    // the room is told to read the round, and the slip says the server has the bet
    expect(onChanged).toHaveBeenCalled();
    expect(await screen.findByText(/Saved, refreshing/)).toBeInTheDocument();
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
  });

  it("an error from the server stays in the dialog, the draft is kept, and the bet can be placed again", async () => {
    const placeBet = vi
      .fn<Api["placeBet"]>()
      .mockRejectedValueOnce(new ApiRequestError("ROUND_NOT_OPEN_FOR_BETTING", "betting for this round has closed"))
      .mockImplementation(async (req) => ({ playerId: TEST_PLAYER.playerId, ...req }));
    const { user } = setup(placeBet);
    const dialog = await setUpBetAndSignIn(user);

    await user.click(within(dialog).getByRole("button", { name: "PLACE THIS BET" }));
    expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(within(dialog).getByText(/DOWN · x40 · 250 PLAY/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "PLACE THIS BET" })).toBeEnabled();

    await user.click(within(dialog).getByRole("button", { name: "PLACE THIS BET" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(placeBet).toHaveBeenCalledTimes(2);
    expect(placeBet).toHaveBeenLastCalledWith(expect.objectContaining({ direction: "DOWN", leverage: 40, stake: "250.000" }));
  });

  it("CHANGE THE BET sends nothing: the signed-in slip starts from the draft (direction, leverage, stake), its submit button on screen and focused", async () => {
    const placeBet = vi.fn<Api["placeBet"]>(async (req) => ({ playerId: TEST_PLAYER.playerId, ...req }));
    const { user } = setup(placeBet);
    const dialog = await setUpBetAndSignIn(user);

    await user.click(within(dialog).getByRole("button", { name: "CHANGE THE BET" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(placeBet).not.toHaveBeenCalled();

    expect(await screen.findByLabelText("Stake (PLAY)")).toHaveValue("250");
    expect(screen.getByRole("slider")).toHaveValue("40");
    expect(screen.getByText("DOWN", { selector: ".bet-summary__side" })).toBeInTheDocument();
    const submit = screen.getByRole("button", { name: "PLACE BET" });
    await waitFor(() => expect(submit).toHaveFocus());
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it("the High leverage choice is carried too", async () => {
    const placeBet = vi.fn<Api["placeBet"]>(async (req) => ({ playerId: TEST_PLAYER.playerId, ...req }));
    const { user } = setup(placeBet);
    await screen.findByText("Cost of this bet", { selector: "p" });
    await user.click(screen.getByRole("switch", { name: "High leverage" }));
    const unlock = await screen.findByRole("button", { name: "Unlock up to x1200" });
    await waitFor(() => expect(unlock).toBeEnabled());
    await user.click(unlock);
    fireEvent.change(screen.getByRole("slider"), { target: { value: "1000" } });
    await waitFor(() => expect(screen.getByText("x1200", { selector: ".leverage-dial__value" })).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "CONNECT WALLET" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
    await user.type(await within(dialog).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    await within(dialog).findByRole("heading", { name: "You've got 10,000 PLAY" });
    expect(within(dialog).getByText(/UP · x1200 · 100 PLAY/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "CHANGE THE BET" }));
    expect(screen.getByRole("switch", { name: "High leverage" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText("x1200", { selector: ".leverage-dial__value" })).toBeInTheDocument();
  });

  it("without a draft (the player connected elsewhere) the main button no longer promises a bet: CHOOSE A BET, which only closes", async () => {
    const placeBet = vi.fn<Api["placeBet"]>();
    const { user } = setup(placeBet);
    await screen.findByText("Cost of this bet", { selector: "p" });
    await user.click(screen.getByRole("button", { name: "CONNECT WALLET" })); // the slip saves a draft…
    clearBetDraft(); // …which is gone by the time the dialog asks (the round closed, or the player signed in elsewhere)
    const dialog = await screen.findByRole("dialog");
    await user.click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
    await user.type(await within(dialog).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(await within(dialog).findByRole("button", { name: "CHOOSE A BET" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "PLACE THIS BET" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "CHOOSE A BET" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(placeBet).not.toHaveBeenCalled();
  });

  it("a draft of another room is not offered here", async () => {
    const placeBet = vi.fn<Api["placeBet"]>();
    const { user } = setup(placeBet, "/play/ETH");
    const dialog = await setUpBetAndSignIn(user);
    expect(within(dialog).getByRole("button", { name: "CHOOSE A BET" })).toBeInTheDocument();
    expect(within(dialog).queryByText(/Your bet:/)).not.toBeInTheDocument();
  });
});
