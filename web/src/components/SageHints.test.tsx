import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api } from "../api/contract";
import { PLAY_ASSET_ID, playAssetIdOf } from "./playAsset";
import { SageDepositHints } from "./SageHints";

const SERVER_ID = `0x${"ab12".repeat(16)}`;

function renderHints(api: Api) {
  return render(<SageDepositHints />, { wrapper: apiWrapper(api) });
}

describe("the PLAY asset id the Sage hints show — the server's when it sends one (ServerInfoDto.playAssetId)", () => {
  it("shows the server's id, and copies the server's id", async () => {
    const user = userEvent.setup(); // installs the clipboard stub the copy button writes to
    renderHints(fakeApi({ serverInfo: async () => serverInfoDto({ playAssetId: SERVER_ID }) }));
    expect(await screen.findByText(SERVER_ID)).toHaveAccessibleName("PLAY asset id");
    expect(screen.queryByText(PLAY_ASSET_ID)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Copy asset id" }));
    expect(await navigator.clipboard.readText()).toBe(SERVER_ID);
  });

  it("falls back to this build's id until the server has answered, and when it sends none", async () => {
    renderHints(fakeApi({ serverInfo: async () => serverInfoDto() }));
    expect(screen.getByLabelText("PLAY asset id")).toHaveTextContent(PLAY_ASSET_ID); // before the answer
    await screen.findByLabelText("PLAY asset id");
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.getByLabelText("PLAY asset id")).toHaveTextContent(PLAY_ASSET_ID); // answered, no playAssetId in it
  });

  it("an Api with no serverInfo (the mock build) shows this build's id", () => {
    renderHints(fakeApi({}));
    expect(screen.getByLabelText("PLAY asset id")).toHaveTextContent(PLAY_ASSET_ID);
  });

  it("a server that cannot be reached leaves this build's id in place", async () => {
    renderHints(
      fakeApi({
        serverInfo: async () => {
          throw new Error("offline");
        },
      }),
    );
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.getByLabelText("PLAY asset id")).toHaveTextContent(PLAY_ASSET_ID);
  });
});

describe("playAssetIdOf", () => {
  it("takes a well-formed id from the server, normalised to lower case with the 0x", () => {
    expect(playAssetIdOf({ playAssetId: SERVER_ID })).toBe(SERVER_ID);
    expect(playAssetIdOf({ playAssetId: SERVER_ID.slice(2).toUpperCase() })).toBe(SERVER_ID); // no 0x, upper case
    expect(playAssetIdOf({ playAssetId: `  ${SERVER_ID}\n` })).toBe(SERVER_ID);
    expect(playAssetIdOf({ playAssetId: `0X${"AB12".repeat(16)}` })).toBe(SERVER_ID);
  });

  it("never shows something that is not an asset id — a player would paste it into Sage", () => {
    for (const bad of ["", "   ", "0x1234", "not-an-asset", `0x${"zz".repeat(32)}`, `${SERVER_ID}00`]) {
      expect(playAssetIdOf({ playAssetId: bad })).toBe(PLAY_ASSET_ID);
    }
  });

  it("absent info or field is the build-time id", () => {
    expect(playAssetIdOf(null)).toBe(PLAY_ASSET_ID);
    expect(playAssetIdOf(undefined)).toBe(PLAY_ASSET_ID);
    expect(playAssetIdOf({})).toBe(PLAY_ASSET_ID);
  });
});
