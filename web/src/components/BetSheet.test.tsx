/**
 * The bet sheet (Р-98): a tap on UP or DOWN opens it; stake chips, three risk levels clamped to what the market and the stake allow, the
 * plain-words example, ONE button. Signed out, the first step is inside the sheet and the choice survives the sign-in.
 */
import { StrictMode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { TEST_PLAYER, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api, Direction, LiquidationPreviewRequest } from "../api/contract";
import { BetSheetHost } from "./BetSheet";
import { clearBetDraft } from "./betDraft";
import { closeBetSheet, openBetSheet } from "./betSheetState";
import { ConnectWalletModal } from "./ConnectWalletModal";
import { setProMode } from "./proMode";
import { bettingRoundOf, market, marketsOf, runningRoundOf, seriesDto } from "./testFixtures";

vi.mock("../wallet/testWallet", () => ({
  getTestWallet: () => ({ address: "txch1testwallet" }),
  signTestWalletMessage: () => ({ address: "txch1testwallet", publicKey: "pk", signature: "sig" }),
}));

const HOUR = 3_600_000;

interface Options {
  cap?: number;
  /** The highest boost a stake of 500 or more allows (the stake tiers); the preview says it, as the server does. */
  tierAt500?: number;
  signedIn?: boolean;
  kind?: "PERP" | "ODDS";
  placeBet?: Api["placeBet"];
}

function setup({ cap = 40, tierAt500, signedIn = true, kind = "PERP", placeBet }: Options = {}) {
  const start = Date.now() + HOUR;
  const round = bettingRoundOf("BTC", "BTC", { roundId: `BTC-1h-${start}`, window: { interval: "HOURLY", startMs: start, endMs: start + HOUR } });
  let player: typeof TEST_PLAYER | null = signedIn ? TEST_PLAYER : null;
  const place = placeBet ?? vi.fn<Api["placeBet"]>(async (req) => ({ playerId: TEST_PLAYER.playerId, ...req }));
  const api = fakeApi({
    currentPlayer: async () => player,
    serverInfo: async () => serverInfoDto(),
    authChallenge: async () => ({ nonce: "n", message: "sign me", expiresAtMs: 1 }),
    authVerify: async () => {
      player = { ...TEST_PLAYER, nickname: "" };
      return { player, isNewPlayer: true };
    },
    setNickname: async (nickname: string) => ({ ...TEST_PLAYER, nickname }),
    markets: async () => marketsOf([market("BTC", { kind, maxLeverage: cap, series: [seriesDto({ maxLeverage: cap })] })], Date.now()),
    rounds: async () => ({ interval: "HOURLY" as const, betting: round, running: runningRoundOf("BTC", "BTC") }),
    previewLiquidation: async (req: LiquidationPreviewRequest) => {
      const stake = Number(req.stake);
      const max = tierAt500 !== undefined && stake >= 500 ? tierAt500 : cap;
      return {
        entryPriceUsed: "100.00",
        liquidationPrice: req.direction === "DOWN" ? (100 * (1 + 0.9 / req.leverage)).toFixed(2) : (100 * (1 - 0.9 / req.leverage)).toFixed(2),
        percentToLiquidation: 0.9 / req.leverage,
        feeHerd: "0.250",
        feeNetwork: "0.100",
        totalDebit: (stake + 0.35).toFixed(3),
        maxLeverageForStake: max,
      };
    },
    myAccount: async () => ({ playerId: TEST_PLAYER.playerId, balance: "10000.000", currentSeason: "2026-10", inPlay: "0.000" }),
    placeBet: place,
  });
  // StrictMode, as the dev server runs it: effects run twice, and the sign-in the sheet asks for must survive that.
  render(
    <StrictMode>
      <MemoryRouter initialEntries={["/"]}>
        <ApiProvider api={api}>
          <BetSheetHost />
          <ConnectWalletModal />
        </ApiProvider>
      </MemoryRouter>
    </StrictMode>,
  );
  const open = (direction: Direction = "DOWN") => act(() => openBetSheet({ marketId: "BTC", interval: "HOURLY", direction }));
  return { round, place, open, user: userEvent.setup() };
}

const sheet = () => screen.findByRole("dialog", { name: "Place a bet" });
const pressed = (el: HTMLElement) => el.getAttribute("aria-pressed") === "true";

beforeEach(() => {
  window.localStorage.clear();
  setProMode(false);
});
afterEach(() => {
  act(() => closeBetSheet());
  clearBetDraft();
});

describe("the sheet, signed in", () => {
  it("opens on 100 and Normal x10, says the bet in plain words with real numbers, and ONE button places exactly that", async () => {
    const { place, open, user, round } = setup();
    open("DOWN");
    const dialog = await sheet();
    expect(dialog).toHaveTextContent("DOWN on BTC");
    expect(pressed(within(dialog).getByRole("button", { name: "100" }))).toBe(true);
    expect(pressed(within(dialog).getByRole("button", { name: /Normal/ }))).toBe(true);

    // x10 on 100 PLAY: a 1% move is 10 PLAY; the wipeout is 9% away (the stub's price); DOWN wins when the price falls.
    expect(await within(dialog).findByText(/BTC −1% → you win \+10/)).toBeInTheDocument();
    expect(within(dialog).getByText(/BTC \+1% → you lose -10/)).toBeInTheDocument();
    expect(within(dialog).getByText(/BTC \+9% \(\$109\.00\) → you lose the whole 100/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Fees 0\.35 PLAY/ })).toBeInTheDocument();

    const go = within(dialog).getByRole("button", { name: "BET 100 ON DOWN" });
    await user.click(go);
    expect(place).toHaveBeenCalledTimes(1);
    expect(place).toHaveBeenCalledWith({ roundId: round.roundId, direction: "DOWN", leverage: 10, stake: "100.000" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Place a bet" })).not.toBeInTheDocument());
  });

  it("an UP bet reads the other way round: the price rising wins, the wipeout is below", async () => {
    const { open } = setup();
    open("UP");
    const dialog = await sheet();
    expect(await within(dialog).findByText(/BTC \+1% → you win \+10/)).toBeInTheDocument();
    expect(within(dialog).getByText(/BTC −1% → you lose -10/)).toBeInTheDocument();
    expect(within(dialog).getByText(/BTC −9% \(\$91\.00\) → you lose the whole 100/)).toBeInTheDocument();
  });

  it("the chosen stake and level are what is placed: 500 and Bold x25", async () => {
    const { place, open, user, round } = setup();
    open("UP");
    const dialog = await sheet();
    await user.click(within(dialog).getByRole("button", { name: "500" }));
    await user.click(within(dialog).getByRole("button", { name: /Bold/ }));
    const go = await within(dialog).findByRole("button", { name: "BET 500 ON UP" });
    // the example follows the choice: x25 on 500 PLAY, 1% = 125 (the wipeout is 3.6% away, so 1% still fits inside it)
    expect(await within(dialog).findByText(/you win \+125/)).toBeInTheDocument();
    await user.click(go);
    expect(place).toHaveBeenCalledTimes(1);
    expect(place).toHaveBeenCalledWith({ roundId: round.roundId, direction: "UP", leverage: 25, stake: "500.000" });
  });

  it("'other' takes any amount of at least the minimum; below it there is no bet", async () => {
    const { place, open, user } = setup();
    open("UP");
    const dialog = await sheet();
    await user.click(within(dialog).getByRole("button", { name: "other" }));
    const input = within(dialog).getByLabelText("Your amount (PLAY)");
    await user.clear(input);
    await user.type(input, "50");
    expect(within(dialog).getByRole("button", { name: "BET 50 ON UP" })).toBeDisabled();
    expect(within(dialog).getByText("Minimum stake is 100 PLAY.")).toBeInTheDocument();
    await user.clear(input);
    await user.type(input, "250");
    await user.click(await within(dialog).findByRole("button", { name: "BET 250 ON UP" }));
    expect(place).toHaveBeenCalledWith(expect.objectContaining({ stake: "250.000" }));
  });

  it("a stake over the balance cannot be bet: said in words, the button is off", async () => {
    const { open, user } = setup();
    open("UP");
    const dialog = await sheet();
    await user.click(within(dialog).getByRole("button", { name: "other" }));
    const input = within(dialog).getByLabelText("Your amount (PLAY)");
    await user.clear(input);
    await user.type(input, "20000");
    expect(await within(dialog).findByText(/more than you have/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "BET 20,000 ON UP" })).toBeDisabled();
  });

  it("the server's refusal is shown in the sheet and the sheet stays", async () => {
    const placeBet = vi.fn<Api["placeBet"]>().mockRejectedValue(new Error("nope"));
    const { open, user } = setup({ placeBet });
    open("UP");
    const dialog = await sheet();
    await user.click(await within(dialog).findByRole("button", { name: "BET 100 ON UP" }));
    expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Place a bet" })).toBeInTheDocument();
  });

  it("Escape and the × close it without a bet", async () => {
    const { place, open, user } = setup();
    open("UP");
    await sheet();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Place a bet" })).not.toBeInTheDocument());
    open("UP");
    const dialog = await sheet();
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog", { name: "Place a bet" })).not.toBeInTheDocument();
    expect(place).not.toHaveBeenCalled();
  });
});

describe("risk levels are clamped to what the market and the stake allow", () => {
  it("an odds market capped at x10: Safer x3 and Normal x10, no Bold — and it opens on Safer, as the slip has since D2", async () => {
    const { open } = setup({ cap: 10, kind: "ODDS" });
    open("UP");
    const dialog = await sheet();
    await within(dialog).findByRole("button", { name: /Safer/ });
    expect(within(dialog).getByRole("button", { name: /Safer.*x3/ })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Normal.*x10/ })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: /Bold/ })).not.toBeInTheDocument();
    expect(pressed(within(dialog).getByRole("button", { name: /Safer/ }))).toBe(true);
    expect(within(dialog).getByText("The highest boost for this market and bet is x10.")).toBeInTheDocument();
  });

  it("a market capped at x5 shows the clamped value on the level, never x10", async () => {
    const { open } = setup({ cap: 5 });
    open("UP");
    const dialog = await sheet();
    expect(await within(dialog).findByRole("button", { name: /Normal.*x5/ })).toBeInTheDocument();
    expect(within(dialog).queryByText("x10")).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: /Bold/ })).not.toBeInTheDocument();
  });

  it("a big stake lowers the levels to its tier (the server's maxLeverageForStake): Normal x8, Bold gone", async () => {
    const { open, user, place } = setup({ tierAt500: 8 });
    open("UP");
    const dialog = await sheet();
    expect(await within(dialog).findByRole("button", { name: /Bold.*x25/ })).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "500" }));
    await waitFor(() => expect(within(dialog).queryByRole("button", { name: /Bold/ })).not.toBeInTheDocument());
    expect(within(dialog).getByRole("button", { name: /Normal.*x8/ })).toBeInTheDocument();
    expect(within(dialog).getByText("The highest boost for this market and bet is x8.")).toBeInTheDocument();
    await user.click(await within(dialog).findByRole("button", { name: "BET 500 ON UP" }));
    expect(place).toHaveBeenCalledWith(expect.objectContaining({ leverage: 8, stake: "500.000" }));
  });
});

describe("signed out: the first step is in the sheet, and the choice survives the sign-in", () => {
  it("the bet button is not offered: 'Continue with test wallet' and Sage are", async () => {
    const { open, place } = setup({ signedIn: false });
    open("DOWN");
    const dialog = await sheet();
    expect(within(dialog).getByRole("button", { name: "Continue with test wallet" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Sage/ })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: /^BET / })).not.toBeInTheDocument();
    expect(place).not.toHaveBeenCalled();
  });

  it("500 on Bold, then the test wallet, a nickname, and PLACE THIS BET places exactly 500 at x25 — no second tap on the test wallet", async () => {
    const { open, user, place, round } = setup({ signedIn: false });
    open("DOWN");
    const sheetDialog = await sheet();
    await user.click(within(sheetDialog).getByRole("button", { name: "500" }));
    await user.click(within(sheetDialog).getByRole("button", { name: /Bold/ }));
    await within(sheetDialog).findByText(/you win \+125/);
    await user.click(within(sheetDialog).getByRole("button", { name: "Continue with test wallet" }));

    // The sign-in dialog opens on its way already: the nickname, not "Connect your wallet" again.
    const connect = await screen.findByRole("dialog", { name: /Pick a nickname/ }, { timeout: 4000 });
    await user.type(await within(connect).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
    await user.click(within(connect).getByRole("button", { name: "CONTINUE" }));
    await within(connect).findByRole("heading", { name: "You've got 10,000 PLAY" });
    expect(within(connect).getByText(/DOWN · x25 · 500 PLAY/)).toBeInTheDocument();

    await user.click(within(connect).getByRole("button", { name: "PLACE THIS BET" }));
    await waitFor(() => expect(place).toHaveBeenCalledTimes(1));
    expect(place).toHaveBeenCalledWith({ roundId: round.roundId, direction: "DOWN", leverage: 25, stake: "500.000" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Place a bet" })).not.toBeInTheDocument());
  });

  it("'Change the bet' in the welcome dialog leaves the sheet there, signed in, with the choice and its BET button", async () => {
    const { open, user, place } = setup({ signedIn: false });
    open("UP");
    const sheetDialog = await sheet();
    await user.click(within(sheetDialog).getByRole("button", { name: "1,000" }));
    await user.click(within(sheetDialog).getByRole("button", { name: "Continue with test wallet" }));
    const connect = await screen.findByRole("dialog", { name: /Pick a nickname/ }, { timeout: 4000 });
    await user.type(await within(connect).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
    await user.click(within(connect).getByRole("button", { name: "CONTINUE" }));
    await within(connect).findByRole("heading", { name: "You've got 10,000 PLAY" });
    await user.click(within(connect).getByRole("button", { name: "CHANGE THE BET" }));
    expect(place).not.toHaveBeenCalled();
    const again = await sheet();
    expect(await within(again).findByRole("button", { name: "BET 1,000 ON UP" })).toBeEnabled();
  });
});
