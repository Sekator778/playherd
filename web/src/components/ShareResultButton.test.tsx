import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ShareResultButton } from "./ShareResultButton";
import type { ShareResultInput } from "./shareCard";

const result: ShareResultInput = { roundId: "BTC-1h-1000", symbol: "BTC", direction: "UP", mode: "FIXED", leverage: 2, stake: 100, net: -100, liquidated: false };

describe("ShareResultButton", () => {
  it("a loss has the same button as a win; pressing it shares a link with the player's own code and src=share", async () => {
    const api = fakeApi({ myInvite: vi.fn(async () => ({ code: "ab12cd34", referredCount: 0 })) });
    const share = vi.fn(async () => {});
    const download = vi.fn(() => true);
    render(<ShareResultButton result={result} env={{ nav: { share, canShare: () => true }, download }} />, { wrapper: apiWrapper(api, TEST_PLAYER) });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Share this result" }));
    await waitFor(() => expect(share).toHaveBeenCalled());
    const data = (share.mock.calls as unknown as Array<[ShareData]>)[0][0];
    expect(data.url).toMatch(/\/round\/BTC-1h-1000\?ref=ab12cd34&src=share$/);
    expect(data.text).toContain("−100 PLAY");
    await screen.findByText("Shared.");
  });

  it("without Web Share (and here without a canvas) the link is copied and the player is told", async () => {
    const api = fakeApi({ myInvite: vi.fn(async () => ({ code: "ab12cd34", referredCount: 0 })) });
    const download = vi.fn(() => true);
    const writeText = vi.fn(async () => {});
    render(<ShareResultButton result={{ ...result, net: 12 }} env={{ nav: { clipboard: { writeText } }, download }} />, { wrapper: apiWrapper(api, TEST_PLAYER) });
    await userEvent.setup().click(screen.getByRole("button", { name: "Share this result" }));
    await screen.findByText("Link copied.");
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining("ref=ab12cd34&src=share"));
  });

  it("a press does not reach the card or link around it", async () => {
    const api = fakeApi({ myInvite: vi.fn(async () => ({ code: "ab12cd34", referredCount: 0 })) });
    const outer = vi.fn();
    render(
      <div onClick={outer}>
        <ShareResultButton result={result} env={{ nav: { clipboard: { writeText: async () => {} } }, download: () => true }} />
      </div>,
      { wrapper: apiWrapper(api, TEST_PLAYER) },
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Share this result" }));
    await screen.findByText("Link copied.");
    expect(outer).not.toHaveBeenCalled();
  });
});
