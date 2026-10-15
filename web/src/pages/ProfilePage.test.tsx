import { act, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, type ProfileRoundEntryDto, type PublicProfileDto } from "../api/contract";
import { reportEngineNotReady, reportEngineReady, resetEngineStateForTests } from "../api/engineState";
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
    // the signed-in player's own profile (TEST_PLAYER): complete; another player's is `otherPlayersProfile`
    player: { ...TEST_PLAYER, displayName: "p1 (txch1p…1)" },
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

describe("Profile — odds rows are worded as odds (ProfileRoundEntryDto.kind)", () => {
  it("an ODDS row wears the ODDS tag, a perp row does not", async () => {
    renderProfile(
      profile({
        history: [
          entry({ roundId: "PM900104-1h-1", marketId: "PM900104", asset: "GER", kind: "ODDS", durationSec: 180 }),
          entry({ roundId: "1h-1", kind: "PERP" }),
        ],
      }),
    );
    const rows = await screen.findAllByRole("link", { name: /PLAY/ });
    expect(within(rows[0]).getByText("ODDS")).toBeInTheDocument();
    expect(rows[0].querySelector(".result-tile__title")).toHaveTextContent("GER ODDS · 3 MIN");
    expect(within(rows[1]).queryByText("ODDS")).not.toBeInTheDocument();
  });

  it("a row from a server that sends no kind still reads as odds when its market is on the list as one", async () => {
    renderProfile(profile({ history: [entry({ roundId: "PM900104-1h-1", marketId: "PM900104", asset: "GER" })] }));
    const row = await screen.findByRole("link", { name: /PLAY/ });
    expect(await within(row).findByText("ODDS")).toBeInTheDocument();
  });
});

describe("Profile — 'player not found' is the server's code (404 NOT_FOUND), not its wording", () => {
  function renderFailing(error: Error) {
    const api = fakeApi({
      publicProfile: vi.fn(async () => {
        throw error;
      }),
      markets: vi.fn(async () => marketsOf(catalogue())),
    });
    render(
      <MemoryRouter initialEntries={["/profile/nobody"]}>
        <Routes>
          <Route path="/profile/:playerId" element={<ProfilePage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, TEST_PLAYER) },
    );
  }

  it("the real server's 404 — whatever its message says — is 'Player not found, or no longer playing.'", async () => {
    renderFailing(new ApiRequestError("NOT_FOUND", "unknown player nobody"));
    expect(await screen.findByText("Player not found, or no longer playing.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("a plain Error that merely SAYS 'Player not found.' is not trusted: it is a failure with a retry", async () => {
    renderFailing(new Error("Player not found."));
    expect(await screen.findByText("Something went wrong.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("any other server error is the generic failure with a retry", async () => {
    renderFailing(new ApiRequestError("OTHER", "boom"));
    expect(await screen.findByText("Something went wrong.")).toBeInTheDocument();
  });
});

describe("Profile — a server that is restarting (503 ENGINE_NOT_READY)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    resetEngineStateForTests();
  });
  afterEach(() => {
    vi.useRealTimers();
    resetEngineStateForTests();
  });

  it("is not an error: the skeleton stays, and the profile loads by itself the moment the engine is back", async () => {
    let up = false;
    const publicProfile = vi.fn(async () => {
      if (!up) throw new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering");
      return profile({ history: [entry()] });
    });
    const api = fakeApi({ publicProfile, markets: vi.fn(async () => marketsOf(catalogue())) });
    render(
      <MemoryRouter initialEntries={["/profile/npc-1"]}>
        <Routes>
          <Route path="/profile/:playerId" element={<ProfilePage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, TEST_PLAYER) },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    expect(screen.queryByText("Something went wrong.")).not.toBeInTheDocument();
    expect(publicProfile).toHaveBeenCalledTimes(1);

    // what the transport does while the engine is down, and when an engine-backed read works again
    act(() => reportEngineNotReady());
    up = true;
    act(() => reportEngineReady(Date.now() + 1));
    expect(await screen.findByText("Round history")).toBeInTheDocument();
    expect(publicProfile).toHaveBeenCalledTimes(2);
  });
});


describe("Profile — another player's profile shows wins only (Р-90)", () => {
  const rival = { playerId: "npc-1", nickname: "rival", walletAddress: "txch1rival", displayName: "rival (txch1r…ival)" };
  const record = { season: "2026-10", baselineBalance: "10000.000", deposited: "0.000", withdrawn: "0.000", roundsPlayed: 4, directionEligibleRounds: 4, directionCorrectRounds: 2 };
  const mixed = [
    entry({ roundId: "w", payout: "150.000" }),
    entry({ roundId: "l", payout: "0.000" }),
    entry({ roundId: "q", liquidated: true, payout: "7.000" }),
    entry({ roundId: "f", payout: "100.000" }),
  ];

  it("lists the wins, not the lost, liquidated or flat rounds, and no balance change", async () => {
    renderProfile(profile({ player: rival, history: mixed, seasonRecord: record }));
    const rows = await screen.findAllByRole("link", { name: /PLAY/ });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute("href", "/round/w");
    expect(screen.queryByText("LIQ")).not.toBeInTheDocument();
    expect(screen.queryByText("Balance change this season")).not.toBeInTheDocument();
    expect(screen.getByText("Rounds played")).toBeInTheDocument();
  });

  it("the player's own profile stays complete, with the balance change", async () => {
    renderProfile(profile({ history: mixed, seasonRecord: record }));
    expect(await screen.findAllByRole("link", { name: /PLAY/ })).toHaveLength(4);
    expect(screen.getByText("Balance change this season")).toBeInTheDocument();
  });

  it("a profile that is not found (a retired bot) is a neutral page without balance or history", async () => {
    const api = fakeApi({
      publicProfile: vi.fn(async () => {
        throw new ApiRequestError("NOT_FOUND", "unknown player npc-1");
      }),
      markets: vi.fn(async () => marketsOf(catalogue())),
    });
    render(
      <MemoryRouter initialEntries={["/profile/npc-1"]}>
        <Routes>
          <Route path="/profile/:playerId" element={<ProfilePage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, TEST_PLAYER) },
    );
    expect(await screen.findByText("Player not found, or no longer playing.")).toBeInTheDocument();
    expect(screen.queryByText("Round history")).not.toBeInTheDocument();
  });
});
