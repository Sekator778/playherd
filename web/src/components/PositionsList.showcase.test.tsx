import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { PositionsList } from "./RoundParts";
import { OTHER_PLAYER, publicPosition } from "./testFixtures";

/** D-90: the showcase shows winners — another player's loss, ruin or liquidation is not listed; the player's own result is. */

function renderList(positions: ReturnType<typeof publicPosition>[], signedIn = true) {
  return render(
    <MemoryRouter>
      <PositionsList positions={positions} settled />
    </MemoryRouter>,
    { wrapper: apiWrapper(fakeApi({}), signedIn ? TEST_PLAYER : null) },
  );
}

const rival = (over: Parameters<typeof publicPosition>[0]) =>
  publicPosition({ player: { ...OTHER_PLAYER, nickname: "rival", displayName: "rival" }, playerId: "npc-1", ...over });

describe("PositionsList — only winners of other players (D-90)", () => {
  it("another player's gain is shown", () => {
    renderList([rival({ payout: "130.000", stake: "100.000" })]);
    expect(screen.getByText("+30")).toBeInTheDocument();
  });

  it("another player's loss and liquidation are not: the row names the player and the leverage, nothing about the ruin", () => {
    renderList([
      rival({ playerId: "npc-1", payout: "60.000", stake: "100.000" }),
      rival({ playerId: "npc-2", player: { ...OTHER_PLAYER, nickname: "loser", displayName: "loser" }, payout: "0.000", stake: "100.000", liquidated: true }),
    ]);
    expect(screen.getByText("loser")).toBeInTheDocument();
    expect(document.querySelector(".player-chip__result")).toBeNull();
    expect(document.body.textContent).not.toMatch(/-40|-100|liq/);
  });

  it("the player's own loss is shown to them", () => {
    renderList([publicPosition({ playerId: TEST_PLAYER.playerId, player: { ...OTHER_PLAYER, nickname: "p1", displayName: "p1", isBot: false }, payout: "0.000", stake: "100.000", liquidated: true })]);
    expect(screen.getByText(/-100/)).toBeInTheDocument();
    expect(screen.getByText(/liq/)).toBeInTheDocument();
  });
});
