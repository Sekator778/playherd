import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeAll, describe, expect, it } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { mockApi } from "../api/mockApi";
import { WalletPage } from "./WalletPage";

/** Signs the shared `mockApi` singleton in once for this file — same pattern as
 *  `components/BetSlip.test.tsx` — so every test starts with a signed-in player and their
 *  starter-pack balance (10,000 PLAY, `STARTER_PACK_PLAY`). */
beforeAll(async () => {
  const challenge = await mockApi.authChallenge({});
  const address = "txch1wallettestaddress0000000000000000000000000000000000q";
  await mockApi.authVerify({ nonce: challenge.nonce, address, publicKey: "pk", signature: "sig" });
  await mockApi.setNickname("walletTester");
});

function renderWallet() {
  return render(
    <MemoryRouter>
      <ApiProvider>
        <WalletPage />
      </ApiProvider>
    </MemoryRouter>,
  );
}

describe("Withdraw/Deposit — turned off honestly (independent critique, 2026-09-26: unverified self-report deposit)", () => {
  it("Withdraw has no input or button — an honest notice instead of a dead form", async () => {
    renderWallet();
    await screen.findByText("Withdraw to wallet");

    expect(screen.queryByLabelText("Amount (PLAY)")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
    expect(screen.getByText(/we don't yet verify a withdrawal on chain/)).toBeInTheDocument();
  });

  it("Deposit has no input or button — the self-report exploit's form is gone entirely, not just disabled", async () => {
    renderWallet();
    await screen.findByText("Deposit from wallet");

    expect(screen.queryByLabelText("Amount you sent (PLAY)")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "I sent it" })).not.toBeInTheDocument();
    expect(screen.getByText(/no on-chain check at all/)).toBeInTheDocument();
  });

  it("the wallet still has no other enabled button that submits a deposit/withdrawal request", async () => {
    renderWallet();
    await screen.findByText("Activity");

    const buttons = screen.queryAllByRole("button");
    for (const b of buttons) {
      expect(b.textContent).not.toMatch(/withdraw|deposit|sent it/i);
    }
  });
});
