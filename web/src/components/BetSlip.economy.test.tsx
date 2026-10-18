import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, type Api, type LiquidationPreviewDto, type LiquidationPreviewRequest, type PlayerAccountDto } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { NOW, bettingRoundOf, market, marketsOf, seriesDto } from "./testFixtures";

/** Economy stage C (Р-92): the slip shows what the bet costs, keeps the leverage within the stake's tier, and guards x100+ behind a question. */

const CAP = 1200;
const account = (balance: string): PlayerAccountDto => ({ playerId: TEST_PLAYER.playerId, balance, currentSeason: "2026-10", inPlay: "0.000" });

/** The tiers of the server (A3): cap for ≤250 PLAY, cap/2 ≤500, cap/5 ≤1200, cap/10 above. */
function tierMax(stake: number): number {
  const divisor = stake <= 250 ? 1 : stake <= 500 ? 2 : stake <= 1200 ? 5 : 10;
  return Math.min(CAP, Math.max(2, Math.floor(CAP / divisor)));
}

/** What the server's preview says: the fees of A4 (r_m 1 bp, k = 1 in the short round, 20 % guard, 0.1 PLAY network), the total, the tier maximum; a move to liquidation of 1/leverage... times 0.9. */
function previewFor(req: LiquidationPreviewRequest): LiquidationPreviewDto {
  const stake = Number(req.stake);
  const max = tierMax(stake);
  if (req.leverage > max) throw new ApiRequestError("INVALID_LEVERAGE", `leverage must be in [2, ${max}] for a stake of ${stake} PLAY`);
  const herd = Math.max(0.001, Math.ceil(Math.round(stake * Math.min(0.2, 0.0001 * req.leverage) * 1e6) / 1e3) / 1e3);
  return {
    entryPriceUsed: "100.00",
    liquidationPrice: (100 * (1 - 0.9 / req.leverage)).toFixed(2),
    percentToLiquidation: 0.9 / req.leverage,
    feeHerd: herd.toFixed(3),
    feeNetwork: "0.100",
    totalDebit: (stake + herd + 0.1).toFixed(3),
    maxLeverageForStake: max,
  };
}

function renderSlip(methods: Partial<Api> = {}) {
  const previewLiquidation = vi.fn(async (req: LiquidationPreviewRequest) => previewFor(req));
  const btc = market("BTC", { series: [seriesDto({ maxLeverage: CAP, herdFeeBp: "1.0" }), seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 500 })] });
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf([btc])),
    myAccount: vi.fn(async () => account("5000.000")),
    previewLiquidation,
    ...methods,
  });
  render(
    <MemoryRouter>
      <BetSlip round={bettingRoundOf("BTC", "BTC")} nowMs={NOW} onChanged={() => {}} />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return { api, previewLiquidation };
}

const slider = () => screen.getByRole("slider") as HTMLInputElement;
const capText = () => document.querySelector(".leverage-dial__cap") as HTMLElement;

describe("BetSlip — what the bet costs (C1)", () => {
  it("asks the preview for the round's length, and shows the Herd fee and the network fee in PLAY and as a share of the stake, and the total debit", async () => {
    const { previewLiquidation } = renderSlip();
    const fees = await screen.findByLabelText("Cost of this bet");
    // stake 100, x10: Herd fee 100 × 10 × 1 bp = 0.1 PLAY (0.10%), network fee 0.1 PLAY (0.10%)
    expect(within(fees).getByText("Herd fee").nextElementSibling).toHaveTextContent("0.100 PLAY (0.10%)");
    expect(within(fees).getByText("Network fee").nextElementSibling).toHaveTextContent("0.100 PLAY (0.10%)");
    expect(within(fees).getByText("Total debit").nextElementSibling).toHaveTextContent("100.200 PLAY");
    expect(previewLiquidation).toHaveBeenCalledWith(expect.objectContaining({ marketId: "BTC", interval: "HOURLY", leverage: 10, stake: "100.000" }));
    expect(within(fees).getByText(/Cancel before the round starts and both fees come back/)).toBeInTheDocument();
  });

  it("names the highest leverage this stake allows, and a bigger stake lowers it (the stake tiers)", async () => {
    renderSlip();
    const fees = await screen.findByLabelText("Cost of this bet");
    expect(fees).toHaveTextContent("Highest leverage for this stake: x1200");
    const stake = screen.getByLabelText("Stake (PLAY)");
    await userEvent.setup().clear(stake);
    await userEvent.setup().type(stake, "600");
    await waitFor(() => expect(screen.getByLabelText("Cost of this bet")).toHaveTextContent("Highest leverage for this stake: x240"));
  });

  it("a stake that does not leave room for its fees is 'not enough PLAY' by the TOTAL, not by the stake alone", async () => {
    renderSlip({ myAccount: vi.fn(async () => account("100.150")) });
    await screen.findByText(/Available:/);
    // the bare stake (100) fits in 100.15, the total debit (100.2) does not
    expect(await screen.findByText(/Not enough PLAY — you have 100.15 PLAY, this bet needs 100.20 PLAY/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeDisabled();
  });

  it("MAX leaves room for the fees of the bet it makes", async () => {
    renderSlip({ myAccount: vi.fn(async () => account("1000.000")) });
    await screen.findByLabelText("Cost of this bet");
    await userEvent.setup().click(screen.getByRole("button", { name: "MAX — your whole balance" }));
    const stake = Number((screen.getByLabelText("Stake (PLAY)") as HTMLInputElement).value);
    expect(stake).toBeGreaterThan(998);
    expect(stake).toBeLessThan(1000);
    await waitFor(() => expect(screen.queryByText(/Not enough PLAY/)).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "PLACE BET" })).toBeEnabled();
  });

  it("a server that names no fees (an older one) shows no cost block and charges nothing the slip invents", async () => {
    renderSlip({ previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })) });
    await screen.findByText("Liquidation preview");
    expect(screen.queryByLabelText("Cost of this bet")).not.toBeInTheDocument();
  });
});

describe("BetSlip — leverage up to x100, and 'High leverage' behind a question (C2)", () => {
  it("opens with the dial capped at x100 on a x1200 market, a switch offers more", async () => {
    renderSlip();
    await screen.findByLabelText("Cost of this bet");
    expect(slider()).toHaveAttribute("max", "100");
    expect(capText()).toHaveTextContent("x2 – max x100");
    const toggle = screen.getByRole("switch", { name: "High leverage" });
    expect(toggle).toHaveAttribute("aria-checked", "false");
  });

  it("turning it on asks first — with the move that liquidates the bet, from the preview at the highest leverage — and unlocks nothing yet", async () => {
    const user = userEvent.setup();
    renderSlip();
    await screen.findByLabelText("Cost of this bet");
    await user.click(screen.getByRole("switch", { name: "High leverage" }));

    const question = await screen.findByRole("group", { name: "Unlock high leverage?" });
    // x1200: 0.9 / 1200 = 0.075% against the bet loses the stake
    await waitFor(() =>
      expect(question).toHaveTextContent("At x1200, a move of 0.075% against you liquidates this bet — you lose most of your stake."),
    );
    expect(slider()).toHaveAttribute("max", "100"); // still locked
    expect(screen.getByRole("switch", { name: "High leverage" })).toHaveAttribute("aria-checked", "false");
  });

  it("'Stay at x100' closes the question and leaves the dial where it was", async () => {
    const user = userEvent.setup();
    renderSlip();
    await screen.findByLabelText("Cost of this bet");
    await user.click(screen.getByRole("switch", { name: "High leverage" }));
    await user.click(await screen.findByRole("button", { name: "Stay at x100" }));
    expect(screen.queryByRole("group", { name: "Unlock high leverage?" })).not.toBeInTheDocument();
    expect(slider()).toHaveAttribute("max", "100");
    expect(screen.getByRole("switch", { name: "High leverage" })).toHaveAttribute("aria-checked", "false");
  });

  it("'Unlock up to x1200' lifts the ceiling: the dial then runs to the stake's maximum on a logarithmic track, and the bet is placed at that leverage", async () => {
    const user = userEvent.setup();
    const placeBet = vi.fn(async () => ({ playerId: TEST_PLAYER.playerId, roundId: "r", direction: "UP" as const, leverage: 1200, stake: "100.000" }));
    renderSlip({ placeBet });
    await screen.findByLabelText("Cost of this bet");
    await user.click(screen.getByRole("switch", { name: "High leverage" }));
    const unlock = await screen.findByRole("button", { name: "Unlock up to x1200" });
    await waitFor(() => expect(unlock).toBeEnabled());
    await user.click(unlock);

    expect(screen.getByRole("switch", { name: "High leverage" })).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByRole("group", { name: "Unlock high leverage?" })).not.toBeInTheDocument();
    expect(slider()).toHaveAttribute("max", "1000"); // 1000 positions of the logarithmic track
    expect(capText()).toHaveTextContent("x2 – max x1200");

    fireEvent.change(slider(), { target: { value: "1000" } }); // the end of the track is the cap itself
    await waitFor(() => expect(screen.getByText("x1200", { selector: ".leverage-dial__value" })).toBeInTheDocument());
    // no 1% cap any more (Р-102): min(20%, 1 bp × 1200 = 12%) of 100 PLAY
    await waitFor(() => expect(screen.getByLabelText("Cost of this bet")).toHaveTextContent("12.000 PLAY (12.00%)"));
    await user.click(screen.getByRole("button", { name: "PLACE BET" }));
    await waitFor(() => expect(placeBet).toHaveBeenCalledWith(expect.objectContaining({ leverage: 1200, stake: "100.000" })));
  });

  it("a bigger stake pulls the dial down to its tier, high leverage or not", async () => {
    const user = userEvent.setup();
    renderSlip();
    await screen.findByLabelText("Cost of this bet");
    await user.click(screen.getByRole("switch", { name: "High leverage" }));
    const unlock = await screen.findByRole("button", { name: "Unlock up to x1200" });
    await waitFor(() => expect(unlock).toBeEnabled());
    await user.click(unlock);
    fireEvent.change(slider(), { target: { value: "1000" } });
    await waitFor(() => expect(screen.getByText("x1200", { selector: ".leverage-dial__value" })).toBeInTheDocument());

    const stake = screen.getByLabelText("Stake (PLAY)");
    await user.clear(stake);
    await user.type(stake, "2000"); // above 1 200 PLAY: cap / 10
    await waitFor(() => expect(screen.getByText("x120", { selector: ".leverage-dial__value" })).toBeInTheDocument());
    expect(capText()).toHaveTextContent("x2 – max x120");
  });

  it("a market capped at x100 or lower has no switch at all", async () => {
    const btc = market("BTC", { series: [seriesDto({ maxLeverage: 40 }), seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 40 })] });
    renderSlip({ markets: vi.fn(async () => marketsOf([btc])) });
    await screen.findByText("Liquidation preview");
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(slider()).toHaveAttribute("max", "40");
  });
});
