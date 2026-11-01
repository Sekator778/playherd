import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { CrowdAccountDto, RatingsDto } from "../api/contract";
import { RatingsPage } from "./RatingsPage";

const people: RatingsDto = { season: "2026-10", roundsThreshold: 10, rich: [], prophets: [] };
const botPlayer = { playerId: "api-1", nickname: "Alpha", walletAddress: "txapi1abc", displayName: "Alpha (txapi1…abc)", isBot: true };
const bots: RatingsDto = {
  season: "2026-10",
  roundsThreshold: 10,
  rich: [{ rank: 1, player: botPlayer, growth: "120.500", roundsPlayed: 12 }],
  prophets: [{ rank: 1, player: botPlayer, accuracy: 0.75, eligibleRounds: 12, roundsPlayed: 12 }],
};

function renderRatings(botRatings: (season?: string) => Promise<RatingsDto>) {
  const api = fakeApi({
    ratings: vi.fn(async () => people),
    botRatings,
    crowdAccount: vi.fn(async () => ({ seasonReturns: [] }) as unknown as CrowdAccountDto),
    mySeasonRecord: vi.fn(async () => {
      throw new Error("not needed");
    }),
    myProfile: vi.fn(async () => {
      throw new Error("not needed");
    }),
  });
  return render(
    <MemoryRouter>
      <RatingsPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
}

describe("Ratings - the tab of the API bots (D-106)", () => {
  // The page asks for the current month; only Date is faked, so the timers of the page and of userEvent run as usual.
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-15T12:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("asks for the bots' boards only when the tab is opened, and lists both with the bot badge", async () => {
    const botRatings = vi.fn(async () => bots);
    renderRatings(botRatings);
    await screen.findByText(/No one has qualified yet/);
    expect(botRatings).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "Bots" }));
    expect(await screen.findByText(/Bots written by other developers/)).toBeInTheDocument();
    expect(botRatings).toHaveBeenCalledWith("2026-10");
    expect(await screen.findAllByText("Alpha (txapi1…abc)")).toHaveLength(2); // once per board
    expect(screen.getByText(/^\+120\.5\d* PLAY$/)).toBeInTheDocument();
    expect(screen.getAllByText("BOT").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole("heading", { name: "Richest" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Prophets" })).toBeInTheDocument();
  });

  it("a server without the boards shows the empty state, not an error page", async () => {
    renderRatings(
      vi.fn(async () => {
        throw new Error("no such call");
      }),
    );
    const user = userEvent.setup();
    await user.click(await screen.findByRole("tab", { name: "Bots" }));
    expect(await screen.findByText(/Bots written by other developers/)).toBeInTheDocument();
    expect(screen.getAllByText(/No one has qualified yet/).length).toBeGreaterThan(0);
  });
});
