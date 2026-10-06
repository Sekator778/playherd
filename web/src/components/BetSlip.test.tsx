import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeAll, describe, expect, it, vi } from "vitest";
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

  it("the 25 / 50 / 75 % chips stake that share of the balance (whole PLAY, rounded down), MAX all of it", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = (await screen.findByLabelText("Stake (PLAY)")) as HTMLInputElement;
    const balance = Number((await mockApi.myAccount()).balance);
    await screen.findByText(/Available:/);

    await user.click(screen.getByRole("button", { name: "25% of your balance" }));
    expect(stakeInput.value).toBe(String(Math.floor(balance * 0.25)));
    await user.click(screen.getByRole("button", { name: "50% of your balance" }));
    expect(stakeInput.value).toBe(String(Math.floor(balance * 0.5)));
    await user.click(screen.getByRole("button", { name: "75% of your balance" }));
    expect(stakeInput.value).toBe(String(Math.floor(balance * 0.75)));
    await user.click(screen.getByRole("button", { name: "MAX — your whole balance" }));
    expect(Number(stakeInput.value)).toBe(balance);
    // The chip that matches the field is shown pressed.
    expect(screen.getByRole("button", { name: "MAX — your whole balance" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "75% of your balance" })).toHaveAttribute("aria-pressed", "false");
  });

  it("the − and + buttons move the stake by the minimum stake, never below 0 and never above the balance", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = (await screen.findByLabelText("Stake (PLAY)")) as HTMLInputElement;
    await screen.findByText(/Available:/);
    const balance = Number((await mockApi.myAccount()).balance);

    await user.clear(stakeInput);
    await user.type(stakeInput, "250");
    await user.click(screen.getByRole("button", { name: "Increase stake by 100 PLAY" }));
    expect(stakeInput.value).toBe("350");
    await user.click(screen.getByRole("button", { name: "Decrease stake by 100 PLAY" }));
    await user.click(screen.getByRole("button", { name: "Decrease stake by 100 PLAY" }));
    await user.click(screen.getByRole("button", { name: "Decrease stake by 100 PLAY" }));
    await user.click(screen.getByRole("button", { name: "Decrease stake by 100 PLAY" }));
    expect(stakeInput.value).toBe("0");

    await user.clear(stakeInput);
    await user.type(stakeInput, String(Math.floor(balance) - 10));
    await user.click(screen.getByRole("button", { name: "Increase stake by 100 PLAY" }));
    expect(Number(stakeInput.value)).toBe(balance);
  });

  it("shows how much can be staked next to the amount", async () => {
    renderBetSlip();
    const balance = Number((await mockApi.myAccount()).balance);
    expect(await screen.findByText(/Available:/)).toHaveTextContent(`Available: ${balance.toLocaleString("en-US")} PLAY`);
  });

  it("summarises the bet in one line before it is placed", async () => {
    renderBetSlip();
    const user = userEvent.setup();
    const stakeInput = await screen.findByLabelText("Stake (PLAY)");
    await user.clear(stakeInput);
    await user.type(stakeInput, "500");
    const summary = await screen.findByText(/on BTC/);
    expect(summary).toHaveTextContent(/500 PLAY.*UP.*x10 on BTC · 1 HOUR round/);
  });
});

describe("BetSlip is market-aware (Р-69)", () => {
  async function renderFor(marketId: string) {
    const pair = await mockApi.rounds("HOURLY", marketId);
    if (pair.betting.phase !== "BETTING") throw new Error("expected an open betting round");
    render(
      <MemoryRouter>
        <ApiProvider>
          <BetSlip round={pair.betting} nowMs={simNowMs()} onChanged={() => {}} />
        </ApiProvider>
      </MemoryRouter>,
    );
    return pair.betting;
  }

  it.each([
    ["BTC", "40"],
    ["SOL", "20"],
    ["DOGE", "10"],
    ["PM900101", "10"],
  ])("%s: the leverage dial goes up to the market's own maximum (x%s)", async (marketId, max) => {
    await renderFor(marketId);
    const dial = await screen.findByLabelText("Leverage");
    await waitFor(() => expect(dial).toHaveAttribute("max", max)); // once the market list has arrived
    expect(dial).toHaveAttribute("min", "2");
  });

  it("previews the liquidation price of the round's own market (a 6-digit probability for an ODDS market)", async () => {
    await renderFor("PM900101");
    expect(await screen.findByText(/Liquidation price: \d+\.\d%/)).toBeInTheDocument();
  });

  it("places the bet on the round's own market, so it shows up in myPositions()", async () => {
    const round = await renderFor("SOL");
    const user = userEvent.setup();
    const stakeInput = await screen.findByLabelText("Stake (PLAY)");
    await user.clear(stakeInput);
    await user.type(stakeInput, "200");
    await user.click(await screen.findByRole("button", { name: "PLACE BET" }));
    await waitFor(async () => {
      const positions = await mockApi.myPositions();
      expect(positions.find((p) => p.roundId === round.roundId)).toMatchObject({ marketId: "SOL", symbol: "SOL", stake: "200.000" });
    });
  });
});

describe("«With the herd» / «Against the herd» (Р-71)", () => {
  /** The real open round of the mock, with the live split set by hand. */
  function round(over: Partial<RoundBetting>): RoundBetting {
    return { ...bettingRound, ...over };
  }

  /** Renders the slip under the arena's control. The session loads after the first paint (the preview
   *  slip is shown for a moment, then the real form replaces it), so tests wait for the form's own field
   *  — or the preview's, when signed out — before grabbing buttons that would otherwise be detached. */
  function renderControlled(r: RoundBetting, direction: "UP" | "DOWN" = "UP") {
    const onDirectionChange = vi.fn();
    render(
      <MemoryRouter>
        <ApiProvider>
          <BetSlip round={r} nowMs={simNowMs()} onChanged={() => {}} direction={direction} onDirectionChange={onDirectionChange} hideDirectionPicker />
        </ApiProvider>
      </MemoryRouter>,
    );
    return onDirectionChange;
  }

  it("With the herd picks the side with more PLAY on it, Against the herd the other — and each says which side it is", async () => {
    const user = userEvent.setup();
    const onChange = renderControlled(round({ upStake: "900.000", downStake: "100.000", majorityDirection: "UP" }), "DOWN");
    await screen.findByLabelText("Stake (PLAY)");
    const withHerd = screen.getByRole("button", { name: /With the herd/ });
    const against = screen.getByRole("button", { name: /Against the herd/ });
    expect(withHerd).toHaveTextContent("UP");
    expect(against).toHaveTextContent("DOWN");

    await user.click(withHerd);
    expect(onChange).toHaveBeenLastCalledWith("UP");
    await user.click(against);
    expect(onChange).toHaveBeenLastCalledWith("DOWN");
  });

  it("works the other way round when the herd is on DOWN", async () => {
    const user = userEvent.setup();
    const onChange = renderControlled(round({ upStake: "100.000", downStake: "900.000", majorityDirection: "DOWN" }));
    await screen.findByLabelText("Stake (PLAY)");
    await user.click(screen.getByRole("button", { name: /With the herd/ }));
    expect(onChange).toHaveBeenLastCalledWith("DOWN");
    await user.click(screen.getByRole("button", { name: /Against the herd/ }));
    expect(onChange).toHaveBeenLastCalledWith("UP");
  });

  it("reads the stakes when the server sends no majorityDirection", async () => {
    const user = userEvent.setup();
    const { majorityDirection: _omit, ...withoutMajority } = round({ upStake: "100.000", downStake: "300.000" });
    void _omit;
    const onChange = renderControlled(withoutMajority as RoundBetting);
    await screen.findByLabelText("Stake (PLAY)");
    await user.click(screen.getByRole("button", { name: /With the herd/ }));
    expect(onChange).toHaveBeenLastCalledWith("DOWN");
  });

  it("the button that matches the current direction is shown pressed", async () => {
    renderControlled(round({ upStake: "900.000", downStake: "100.000", majorityDirection: "UP" }), "UP");
    await screen.findByLabelText("Stake (PLAY)");
    expect(screen.getByRole("button", { name: /With the herd/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /Against the herd/ })).toHaveAttribute("aria-pressed", "false");
  });

  it("with no majority yet — an empty round or an exact tie — both are off and a line says why", async () => {
    const user = userEvent.setup();
    const onChange = renderControlled(round({ upStake: "0.000", downStake: "0.000", majorityDirection: null }));
    await screen.findByLabelText("Stake (PLAY)");
    const withHerd = screen.getByRole("button", { name: /With the herd/ });
    const against = screen.getByRole("button", { name: /Against the herd/ });
    expect(withHerd).toBeDisabled();
    expect(against).toBeDisabled();
    expect(screen.getByText(/No herd yet/)).toBeInTheDocument();
    await user.click(withHerd);
    expect(onChange).not.toHaveBeenCalled();

    // An exact tie is no herd either.
    expect(round({ upStake: "500.000", downStake: "500.000", majorityDirection: null }).majorityDirection).toBeNull();
  });

  it("is there for a signed-out visitor too (the preview slip), where the arena is the direction picker", async () => {
    // The shared mock player is signed in for this file; sign out to see the preview slip.
    await mockApi.signOut();
    try {
      const onChange = renderControlled(round({ upStake: "900.000", downStake: "100.000", majorityDirection: "UP" }), "DOWN");
      expect(await screen.findByText("PREVIEW — not signed in")).toBeInTheDocument();
      await userEvent.setup().click(screen.getByRole("button", { name: /With the herd/ }));
      expect(onChange).toHaveBeenLastCalledWith("UP");
    } finally {
      const challenge = await mockApi.authChallenge({});
      await mockApi.authVerify({ nonce: challenge.nonce, address: "txch1betsliptestaddress00000000000000000000000000000000q", publicKey: "pk", signature: "sig" });
      await mockApi.setNickname("slipTester");
    }
  });
});

describe("the leverage dial's heat is relative to the market's cap", () => {
  it("an odds market (x10) reaches Flame at its own maximum", async () => {
    const pair = await mockApi.rounds("HOURLY", "PM900101");
    if (pair.betting.phase !== "BETTING") throw new Error("expected an open betting round");
    render(
      <MemoryRouter>
        <ApiProvider>
          <BetSlip round={pair.betting} nowMs={simNowMs()} onChanged={() => {}} />
        </ApiProvider>
      </MemoryRouter>,
    );
    await screen.findByLabelText("Stake (PLAY)"); // the signed-in form has replaced the preview slip
    const dial = screen.getByLabelText("Leverage");
    await waitFor(() => expect(dial).toHaveAttribute("max", "10")); // once the market list has arrived
    fireEvent.change(dial, { target: { value: "10" } });
    expect(await screen.findByText("Flame")).toBeInTheDocument();
    expect(screen.getByText(/max x10/)).toBeInTheDocument();
    fireEvent.change(dial, { target: { value: "2" } });
    expect(await screen.findByText("Calm")).toBeInTheDocument();
  });
});

describe("«What's this?» beside the liquidation preview opens in the app, in place (first tester pass, D12)", () => {
  function renderSlip() {
    return mockApi.rounds("HOURLY").then((pair) => {
      if (pair.betting.phase !== "BETTING") throw new Error("expected an open betting round");
      return render(
        <MemoryRouter>
          <ApiProvider>
            <BetSlip round={pair.betting} nowMs={simNowMs()} onChanged={() => {}} />
          </ApiProvider>
        </MemoryRouter>,
      );
    });
  }

  it("is a button that opens the explanation right there — not a link to another page in a new tab", async () => {
    await renderSlip();
    const user = userEvent.setup();
    const toggle = await screen.findByRole("button", { name: "What's this?" });
    expect(screen.queryByRole("link", { name: "What's this?" })).not.toBeInTheDocument();
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText(/not only at its close/)).not.toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/If the price reaches the liquidation price at any moment of the round — not only at its close/)).toBeInTheDocument();
    expect(screen.getByText("The higher the leverage, the closer that price sits to the entry price.")).toBeInTheDocument();
    // the half-made bet is still there
    expect(screen.getByLabelText("Stake (PLAY)")).toBeInTheDocument();

    await user.click(toggle);
    expect(screen.queryByText(/not only at its close/)).not.toBeInTheDocument();
  });

  it("stays open while the preview card blinks out and back (an empty stake field has no preview)", async () => {
    await renderSlip();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "What's this?" }));
    const stake = await screen.findByLabelText("Stake (PLAY)");
    await user.clear(stake);
    await waitFor(() => expect(screen.queryByRole("button", { name: "What's this?" })).not.toBeInTheDocument());
    await user.type(stake, "300");
    expect(await screen.findByRole("button", { name: "What's this?" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/not only at its close/)).toBeInTheDocument();
  });

  it("the signed-out preview slip has it too", async () => {
    await mockApi.signOut();
    try {
      await renderSlip();
      expect(await screen.findByText("PREVIEW — not signed in")).toBeInTheDocument();
      await userEvent.setup().click(await screen.findByRole("button", { name: "What's this?" }));
      expect(screen.getByText(/not only at its close/)).toBeInTheDocument();
    } finally {
      const challenge = await mockApi.authChallenge({});
      await mockApi.authVerify({ nonce: challenge.nonce, address: "txch1betsliptestaddress00000000000000000000000000000000q", publicKey: "pk", signature: "sig" });
      await mockApi.setNickname("slipTester");
    }
  });
});

describe("a fresh slip does not open on the market's cap (first tester pass, D2)", () => {
  async function renderDial(marketId: string) {
    const pair = await mockApi.rounds("HOURLY", marketId);
    if (pair.betting.phase !== "BETTING") throw new Error("expected an open betting round");
    render(
      <MemoryRouter>
        <ApiProvider>
          <BetSlip round={pair.betting} nowMs={simNowMs()} onChanged={() => {}} />
        </ApiProvider>
      </MemoryRouter>,
    );
    return pair.betting;
  }

  it.each([
    ["BTC", "40", "10", "Warm"],
    ["SOL", "20", "5", "Warm"],
    ["DOGE", "10", "3", "Warm"],
    ["PM900101", "10", "3", "Warm"], // an odds market: it used to open on x10, its cap — Flame
  ])("%s (cap x%s): the signed-in slip opens at x%s, in the %s zone", async (marketId, cap, expected, zone) => {
    await renderDial(marketId);
    await screen.findByLabelText("Stake (PLAY)");
    const dial = screen.getByLabelText("Leverage") as HTMLInputElement;
    await waitFor(() => expect(dial).toHaveAttribute("max", cap)); // once the market list has arrived
    expect(dial.value).toBe(expected);
    expect(document.querySelector(".leverage-dial__value")).toHaveTextContent(`x${expected}`);
    expect(document.querySelector(".leverage-dial__zone")).toHaveTextContent(zone);
  });

  it("a dial the player moved stays where they put it — also when it goes up to the cap", async () => {
    await renderDial("PM900101");
    await screen.findByLabelText("Stake (PLAY)");
    const dial = screen.getByLabelText("Leverage") as HTMLInputElement;
    await waitFor(() => expect(dial).toHaveAttribute("max", "10"));
    fireEvent.change(dial, { target: { value: "10" } });
    expect(await screen.findByText("Flame")).toBeInTheDocument();
    expect(dial.value).toBe("10");
  });

  it("the signed-out preview slip opens on the same default", async () => {
    await mockApi.signOut();
    try {
      await renderDial("PM900101");
      expect(await screen.findByText("PREVIEW — not signed in")).toBeInTheDocument();
      const dial = screen.getByLabelText("Leverage") as HTMLInputElement;
      await waitFor(() => expect(dial).toHaveAttribute("max", "10"));
      expect(dial.value).toBe("3");
    } finally {
      const challenge = await mockApi.authChallenge({});
      await mockApi.authVerify({ nonce: challenge.nonce, address: "txch1betsliptestaddress00000000000000000000000000000000q", publicKey: "pk", signature: "sig" });
      await mockApi.setNickname("slipTester");
    }
  });
});
