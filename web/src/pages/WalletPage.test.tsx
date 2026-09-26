import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

describe("Withdraw/Deposit amount validation (wave2 fix, 2026-09-26 — amountInput.ts)", () => {
  it("Withdraw: a negative amount is rejected, not silently treated as valid", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount (PLAY)");

    await user.clear(input);
    await user.type(input, "-50");

    expect(await screen.findByText("Enter an amount greater than 0.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  });

  it("Withdraw: more than 3 decimal places is rejected", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount (PLAY)");

    await user.clear(input);
    await user.type(input, "10.1234");

    expect(await screen.findByText("At most 3 decimal places.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  });

  it("Withdraw: over balance is still rejected, same as before", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount (PLAY)");

    await user.clear(input);
    await user.type(input, "999999");

    expect(await screen.findByText(/You only have .* PLAY\./)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  });

  it("Withdraw: a valid positive amount within balance enables the button", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount (PLAY)");

    await user.clear(input);
    await user.type(input, "123.45");

    expect(screen.getByRole("button", { name: "Withdraw" })).toBeEnabled();
  });

  it("Deposit: a negative amount is rejected", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount you sent (PLAY)");

    await user.clear(input);
    await user.type(input, "-1");

    expect(await screen.findByText("Enter an amount greater than 0.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "I sent it" })).toBeDisabled();
  });

  it("Deposit: more than 3 decimals is rejected even though there's no balance ceiling", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount you sent (PLAY)");

    await user.clear(input);
    await user.type(input, "5.6789");

    expect(await screen.findByText("At most 3 decimal places.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "I sent it" })).toBeDisabled();
  });

  it("Deposit: a large but valid positive amount is accepted (no balance ceiling)", async () => {
    renderWallet();
    const user = userEvent.setup();
    const input = await screen.findByLabelText("Amount you sent (PLAY)");

    await user.clear(input);
    await user.type(input, "1000000");

    expect(screen.getByRole("button", { name: "I sent it" })).toBeEnabled();
  });
});
