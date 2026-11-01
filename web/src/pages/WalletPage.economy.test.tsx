import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, LedgerEntryDto, LedgerEntryType } from "../api/contract";
import { WalletPage } from "./WalletPage";

/** Economy stage C (C6, D-92): "withdrawable", the starter pack's line, and the four fee rows of the history. */

const entry = (sequence: number, type: LedgerEntryType, delta: string): LedgerEntryDto => ({ sequence, type, delta, roundId: null, atMs: 1000 * sequence });

function renderWallet(account: { balance: string; withdrawable?: string }, ledger: LedgerEntryDto[] = [], methods: Partial<Api> = {}) {
  const api = fakeApi({
    myAccount: vi.fn(async () => ({ playerId: TEST_PLAYER.playerId, currentSeason: "2026-10", inPlay: "0.000", ...account })),
    myLedger: vi.fn(async () => ({ items: ledger, hasMore: false })),
    myDepositAddress: vi.fn(async () => ({ address: "txch1depositaddress" })),
    ...methods,
  });
  render(
    <MemoryRouter>
      <WalletPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return api;
}

describe("Wallet — what can be withdrawn (C6)", () => {
  it("shows 'Withdrawable' under the balance, with the one line that says the starter pack stays in the game", async () => {
    renderWallet({ balance: "10250.000", withdrawable: "250.000" });
    expect(await screen.findByText("Withdrawable")).toBeInTheDocument();
    expect(screen.getByText("250 PLAY", { selector: ".wallet-withdrawable__value" })).toBeInTheDocument();
    expect(screen.getByText("Your starter pack stays in the game; winnings and deposits above it can be withdrawn.")).toBeInTheDocument();
  });

  it("nothing above the pack: withdrawable is 0, not the balance", async () => {
    renderWallet({ balance: "10000.000", withdrawable: "0.000" });
    await screen.findByText("Withdrawable");
    expect(screen.getByText("0 PLAY", { selector: ".wallet-withdrawable__value" })).toBeInTheDocument();
  });

  it("a server that does not send the field shows no 'Withdrawable' line (the whole balance is checked, as before)", async () => {
    renderWallet({ balance: "10000.000" });
    await screen.findByText("Balance");
    await waitFor(() => expect(screen.getByText("Withdraw to wallet")).toBeInTheDocument());
    expect(screen.queryByText("Withdrawable")).not.toBeInTheDocument();
    expect(screen.getByText("Balance: 10,000 PLAY")).toBeInTheDocument();
  });

  it("the withdraw form is bounded by the withdrawable amount, and says why when a bigger amount is typed", async () => {
    const requestWithdrawal = vi.fn();
    renderWallet({ balance: "10250.000", withdrawable: "250.000" }, [], { requestWithdrawal });
    const amount = await screen.findByLabelText("Amount (PLAY)");
    expect(screen.getByText("Withdrawable: 250 PLAY", { selector: ".field-hint" })).toBeInTheDocument();
    await userEvent.setup().type(amount, "300");
    expect(await screen.findByText("You can withdraw up to 250 PLAY — the starter pack stays in the game.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
    expect(requestWithdrawal).not.toHaveBeenCalled();
  });
});

describe("Wallet — the fee rows of the history (C6)", () => {
  it("the Herd fee, the network fee and their refunds have names, not raw type words", async () => {
    renderWallet({ balance: "10000.000", withdrawable: "0.000" }, [
      entry(5, "FEE_NETWORK_REFUND", "0.100"),
      entry(4, "FEE_HERD_REFUND", "0.250"),
      entry(3, "FEE_NETWORK", "-0.100"),
      entry(2, "FEE_HERD", "-0.250"),
      entry(1, "STARTER_PACK", "10000.000"),
    ]);
    expect(await screen.findByText("Herd fee")).toBeInTheDocument();
    expect(screen.getByText("Network fee")).toBeInTheDocument();
    expect(screen.getByText("Herd fee refunded")).toBeInTheDocument();
    expect(screen.getByText("Network fee refunded")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Fee herd|FEE_HERD|Fee network/);
  });
});
