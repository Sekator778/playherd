import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { CrowdAccountDto, RatingsDto, WeeklyRatingsDto } from "../api/contract";
import { RatingsPage } from "./RatingsPage";

const season: RatingsDto = { season: "2026-10", roundsThreshold: 10, rich: [], prophets: [] };
const vera = { playerId: "p-vera", nickname: "Vera", walletAddress: "txch1vera", displayName: "Vera (txch1v…vera)" };
const week: WeeklyRatingsDto = {
  weekStartMs: Date.UTC(2026, 9, 19),
  roundsThreshold: 10,
  rich: [{ rank: 1, player: vera, growth: "42.500", roundsPlayed: 11 }],
  prophets: [{ rank: 1, player: vera, accuracy: 0.7, eligibleRounds: 10, roundsPlayed: 11 }],
};

function renderRatings(weeklyRatings: () => Promise<WeeklyRatingsDto>, botWeeklyRatings = vi.fn(async () => ({ ...week, rich: [], prophets: [] }))) {
  const api = fakeApi({
    ratings: vi.fn(async () => season),
    weeklyRatings,
    botWeeklyRatings,
    botRatings: vi.fn(async () => season),
    crowdAccount: vi.fn(async () => ({ seasonReturns: [] }) as unknown as CrowdAccountDto),
    mySeasonRecord: vi.fn(async () => {
      throw new Error("not needed");
    }),
    myProfile: vi.fn(async () => {
      throw new Error("not needed");
    }),
  });
  render(
    <MemoryRouter>
      <RatingsPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return { botWeeklyRatings };
}

describe("Ratings - the 'This week' tab", () => {
  it("is next to the season's tabs, asks for the week only when opened, and lists both rankings", async () => {
    const weeklyRatings = vi.fn(async () => week);
    renderRatings(weeklyRatings);
    await screen.findByText(/No one has qualified yet/);
    expect(weeklyRatings).not.toHaveBeenCalled();

    await userEvent.setup().click(screen.getByRole("tab", { name: "This week" }));
    expect(await screen.findByText(/Monday 00:00 UTC until now/)).toBeInTheDocument();
    expect(weeklyRatings).toHaveBeenCalledTimes(1);
    expect(await screen.findAllByText("Vera (txch1v…vera)")).toHaveLength(2);
    expect(screen.getByText(/^\+42\.5\d* PLAY$/)).toBeInTheDocument();
    expect(screen.getByText(/the same minimum of 10 rounds|same minimum of 10 rounds/i)).toBeInTheDocument();
    // the season picker belongs to the season's tabs only
    expect(screen.queryByLabelText("Season")).not.toBeInTheDocument();
  });

  it("an empty week says how to appear, not an error", async () => {
    renderRatings(async () => ({ ...week, rich: [], prophets: [] }));
    await userEvent.setup().click(await screen.findByRole("tab", { name: "This week" }));
    expect(await screen.findByText(/play 10 rounds this week to appear here/)).toBeInTheDocument();
  });

  it("a server without the call shows the empty state", async () => {
    renderRatings(async () => {
      throw new Error("no such call");
    });
    await userEvent.setup().click(await screen.findByRole("tab", { name: "This week" }));
    expect(await screen.findByText(/to appear here/)).toBeInTheDocument();
  });

  it("the bots' tab can switch to the week and asks for the bots' week then", async () => {
    const { botWeeklyRatings } = renderRatings(async () => week);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("tab", { name: "Bots" }));
    expect(botWeeklyRatings).not.toHaveBeenCalled();
    await user.click(screen.getAllByRole("tab", { name: "This week" })[1]);
    expect(await screen.findByText(/Bots written by other developers, this week/)).toBeInTheDocument();
    expect(botWeeklyRatings).toHaveBeenCalledTimes(1);
  });
});
