import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeAll, describe, expect, it } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { simNowMs } from "../api/clock";
import type { RoundBetting } from "../api/contract";
import { mockApi } from "../api/mockApi";
import { BetSlip } from "./BetSlip";

/** Signs the shared `mockApi` singleton in once for this file, mirroring the real
 *  challenge/verify flow (`ux_spec.md` §10) — cheaper than driving the connect-wallet modal
 *  through the UI for every test that just needs a signed-in player with a starter pack. */
let bettingRound: RoundBetting;

beforeAll(async () => {
  const challenge = await mockApi.authChallenge({});
  const address = "txch1betsliptestaddress00000000000000000000000000000000q";
  const session = await mockApi.authVerify({ nonce: challenge.nonce, address, publicKey: "pk", signature: "sig" });
  expect(session.isNewPlayer).toBe(true);
  await mockApi.setNickname("slipTester");

  const pair = await mockApi.rounds("HOURLY");
  if (pair.betting.phase !== "BETTING") throw new Error("expected the next hourly round to be open for bets");
  bettingRound = pair.betting;
});

function renderBetSlip() {
  return render(
    <MemoryRouter>
      <ApiProvider>
        <BetSlip round={bettingRound} nowMs={simNowMs()} onChanged={() => {}} />
      </ApiProvider>
    </MemoryRouter>,
  );
}

describe("BetSlip client-side validation (ux_spec.md §11)", () => {
  it("disables PLACE BET and explains a stake below the 100 PLAY minimum", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = await screen.findByLabelText("Stake (PLAY)");

    await user.clear(stakeInput);
    await user.type(stakeInput, "50");

    expect(await screen.findByText("Minimum stake is 100 PLAY.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeDisabled();
  });

  it("disables PLACE BET and explains a stake above the player's balance", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = await screen.findByLabelText("Stake (PLAY)");

    await user.clear(stakeInput);
    await user.type(stakeInput, "999999");

    expect(await screen.findByText(/Not enough PLAY/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeDisabled();
  });

  it("enables PLACE BET for a valid stake and shows a live liquidation preview", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = await screen.findByLabelText("Stake (PLAY)");

    await user.clear(stakeInput);
    await user.type(stakeInput, "500");

    expect(await screen.findByText(/Liquidation price: \$/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeEnabled();
  });

  it("the +100 stake chip adds to whatever is already staked", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = (await screen.findByLabelText("Stake (PLAY)")) as HTMLInputElement;

    await user.clear(stakeInput);
    await user.type(stakeInput, "50");
    await user.click(screen.getByRole("button", { name: "+100" }));

    expect(stakeInput.value).toBe("150");
  });

  it("the MAX stake chip fills in the player's full effective balance", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = (await screen.findByLabelText("Stake (PLAY)")) as HTMLInputElement;

    await user.click(screen.getByRole("button", { name: "MAX" }));

    expect(Number(stakeInput.value)).toBeGreaterThan(0);
  });
});
