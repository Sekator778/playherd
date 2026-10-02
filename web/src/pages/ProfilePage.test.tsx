import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { ProfileRoundEntryDto, PublicProfileDto } from "../api/contract";
import { catalogue, marketsOf } from "../components/testFixtures";
import { ProfilePage } from "./ProfilePage";

const entry = (over: Partial<ProfileRoundEntryDto> = {}): ProfileRoundEntryDto => ({
  roundId: "1h-1",
  interval: "HOURLY",
  marketId: "BTC",
  asset: "BTC",
  settledAtMs: Date.UTC(2026, 8, 25, 14, 0),
  direction: "UP",
  leverage: 10,
  stake: "100.000",
  liquidated: false,
  directionCorrect: true,
  payout: "150.000",
  ...over,
});

function profile(over: Partial<PublicProfileDto> = {}): PublicProfileDto {
  return {
    player: { playerId: "npc-1", nickname: "rival", walletAddress: "txch1rival", displayName: "rival (txch1r…ival)" },
    seasonRecord: null,
    history: [],
    ...over,
  };
}

function renderProfile(p: PublicProfileDto) {
  const api = fakeApi({
    publicProfile: vi.fn(async () => p),
    markets: vi.fn(async () => marketsOf(catalogue())),
  });
  return render(
    <MemoryRouter initialEntries={["/profile/npc-1"]}>
      <Routes>
        <Route path="/profile/:playerId" element={<ProfilePage />} />
      </Routes>
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
}

describe("Profile — win streak (Р-71)", () => {
  it("shows the current streak and the best", async () => {
    renderProfile(profile({ streak: { current: 3, best: 7 } }));
    expect(await screen.findByRole("group", { name: "Win streak: 3 in a row now, best 7" })).toBeInTheDocument();
  });

  it("is designed for zero", async () => {
    renderProfile(profile({ streak: { current: 0, best: 0 } }));
    expect(await screen.findByText("No win streak right now")).toBeInTheDocument();
  });

  it("is not there at all on a server that sends no streak", async () => {
    renderProfile(profile());
    await screen.findByText("Round history");
    expect(screen.queryByRole("group", { name: /Win streak/ })).not.toBeInTheDocument();
  });
});

describe("Profile — history rows name the market", () => {
  it("shows the market symbol and the series' real length, signed net result and the icon's word", async () => {
    renderProfile(
      profile({
        history: [
          entry({ roundId: "1h-1", payout: "150.000" }),
          entry({ roundId: "ETH-1h-2", marketId: "ETH", asset: "ETH", direction: "DOWN", leverage: 5, interval: "DAILY", payout: "0.000" }),
        ],
      }),
    );
    const rows = await screen.findAllByRole("link", { name: /PLAY/ });
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("BTC")).toBeInTheDocument();
    // What the round was first (market · length), what the player did and when underneath.
    expect(rows[0].querySelector(".result-tile__title")).toHaveTextContent("BTC · 3 MIN");
    expect(rows[0].querySelector(".result-tile__meta")).toHaveTextContent(/▲ UP · x10 · 2026-09-25 14:00 UTC/);
    expect(rows[0]).toHaveTextContent("+50 PLAY");
    expect(rows[1].querySelector(".result-tile__title")).toHaveTextContent("ETH · 15 MIN");
    expect(rows[1].querySelector(".result-tile__meta")).toHaveTextContent(/▼ DOWN · x5/);
    expect(rows[1]).toHaveTextContent("-100 PLAY");
    expect(rows[0]).toHaveAttribute("href", "/round/1h-1");
  });

  it("never says Hourly or Daily", async () => {
    renderProfile(profile({ history: [entry({ interval: "DAILY" })] }));
    await screen.findByRole("link", { name: /PLAY/ });
    expect(screen.queryByText(/Hourly|Daily/)).not.toBeInTheDocument();
  });

  it("a market that has left the list keeps its symbol and simply has no length", async () => {
    renderProfile(profile({ history: [entry({ marketId: "PM_GONE", asset: "OLD" })] }));
    const row = await screen.findByRole("link", { name: /PLAY/ });
    expect(row.querySelector(".result-tile__title")).toHaveTextContent(/^OLD$/);
    expect(row.querySelector(".result-tile__meta")).toHaveTextContent(/▲ UP · x10/);
    expect(row).not.toHaveTextContent(/MIN|HOUR/);
  });

  it("a row's own durationSec names the round, even when the market has left the list — and wins over the market's series", async () => {
    renderProfile(
      profile({
        history: [
          entry({ roundId: "PM_GONE-1h-1", marketId: "PM_GONE", asset: "OLD", durationSec: 900 }), // not on the list at all
          entry({ roundId: "1h-2", durationSec: 3600 }), // BTC's series on the list is 3 MIN; this round was an hour
        ],
      }),
    );
    const rows = await screen.findAllByRole("link", { name: /PLAY/ });
    expect(rows[0].querySelector(".result-tile__title")).toHaveTextContent("OLD · 15 MIN");
    expect(rows[1].querySelector(".result-tile__title")).toHaveTextContent("BTC · 1 HOUR");
    expect(screen.queryByText(/Hourly|Daily/)).not.toBeInTheDocument();
  });

  it("a row with no durationSec (a server that predates it) falls back to the market's series", async () => {
    renderProfile(profile({ history: [entry({ interval: "DAILY" })] }));
    const row = await screen.findByRole("link", { name: /PLAY/ });
    expect(row.querySelector(".result-tile__title")).toHaveTextContent("BTC · 15 MIN");
  });

  it("a liquidation reads as the whole stake, with the tag", async () => {
    renderProfile(profile({ history: [entry({ liquidated: true, payout: "0.000", stake: "250.000" })] }));
    const row = await screen.findByRole("link", { name: /PLAY/ });
    expect(row).toHaveTextContent("-250 PLAY");
    expect(row).toHaveTextContent("LIQ");
    expect(row.className).toContain("result-tile--liquidated");
  });

  it("an empty history says so", async () => {
    renderProfile(profile());
    expect(await screen.findByText("No rounds played yet.")).toBeInTheDocument();
  });
});
