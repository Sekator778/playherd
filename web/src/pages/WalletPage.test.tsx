import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { mockApi } from "../api/mockApi";
import { PLAY_ASSET_ID, RECOMMENDED_DEPOSIT_FEE } from "../components/playAsset";
import { WalletPage } from "./WalletPage";

/** Signs the shared `mockApi` singleton in once for this file — same pattern as
 *  `components/BetSlip.test.tsx` — so every test starts with a signed-in player and their
 *  starter-pack balance (10,000 PLAY, `STARTER_PACK_PLAY`). */
const REGISTERED_ADDRESS = "txch1wallettestaddress0000000000000000000000000000000000q";
beforeAll(async () => {
  const challenge = await mockApi.authChallenge({});
  await mockApi.authVerify({ nonce: challenge.nonce, address: REGISTERED_ADDRESS, publicKey: "pk", signature: "sig" });
  await mockApi.setNickname("walletTester");
});

// The mock's account now says what is withdrawable (balance above the starter pack, economy plan B2), which is nothing for a fresh
// player. These tests are about the withdrawal form itself, so they run as against a server that sends no `withdrawable` (the whole
// balance may leave); `WalletPage.economy.test.tsx` covers the limit.
const realMyAccount = mockApi.myAccount.bind(mockApi);
beforeEach(() => {
  vi.spyOn(mockApi, "myAccount").mockImplementation(async () => ({ ...(await realMyAccount()), withdrawable: undefined }));
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

describe("Deposit from wallet (D-53: real deposit address, no self-report form)", () => {
  it("shows the player's own deposit address with a QR code and a copy button", async () => {
    renderWallet();
    await screen.findByText("Deposit from wallet");

    const addressEl = await screen.findByLabelText("Deposit address");
    expect(addressEl.textContent).toMatch(/^txch1/);
    expect(screen.getByRole("img", { name: /QR code/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy address" })).toBeInTheDocument();
    expect(screen.getByText(/credited after confirmation on testnet11/)).toBeInTheDocument();
  });

  it("the same address is returned on every call — stable for the whole session", async () => {
    const first = await mockApi.myDepositAddress();
    const second = await mockApi.myDepositAddress();
    expect(first.address).toBe(second.address);
  });

  it("has no leftover self-report deposit form (the exploit's form, task item 1)", async () => {
    renderWallet();
    await screen.findByText("Deposit from wallet");
    expect(screen.queryByLabelText("Amount you sent (PLAY)")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "I sent it" })).not.toBeInTheDocument();
  });
});

describe("Withdraw to wallet — form and validation (D-53/D-57)", () => {
  it("shows the registered address as the (non-editable) destination", async () => {
    renderWallet();
    await screen.findByText("Withdraw to wallet");
    expect(screen.getByText(/Destination — your registered address/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /destination|address/i })).not.toBeInTheDocument();
  });

  it("Withdraw is disabled until a valid positive amount is entered", async () => {
    renderWallet();
    await screen.findByText("Withdraw to wallet");
    const button = screen.getByRole("button", { name: "Withdraw" });
    expect(button).toBeDisabled();

    const input = screen.getByLabelText("Amount (PLAY)");
    fireEvent.change(input, { target: { value: "0" } });
    expect(button).toBeDisabled();

    fireEvent.change(input, { target: { value: "100" } });
    expect(button).not.toBeDisabled();
  });

  it("rejects an amount with more than 3 decimal places", async () => {
    renderWallet();
    await screen.findByText("Withdraw to wallet");
    fireEvent.change(screen.getByLabelText("Amount (PLAY)"), { target: { value: "1.2345" } });
    expect(await screen.findByText(/At most 3 decimal places/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  });

  it("rejects an amount over the current balance", async () => {
    renderWallet();
    await screen.findByText("Withdraw to wallet");
    fireEvent.change(screen.getByLabelText("Amount (PLAY)"), { target: { value: "999999999" } });
    expect(await screen.findByText(/Not enough PLAY/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  });
});

describe("Withdraw to wallet — lifecycle (task's FIXED INTERFACE: queued → in_flight → confirmed|failed)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  async function tick(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });
  }

  it("moves from QUEUED to IN_FLIGHT to a terminal state, with the amount already debited", async () => {
    renderWallet();
    await tick(0); // flush the initial myAccount()/myLedger() load — fake timers need an
    // explicit tick even for a plain promise resolution (same pattern as
    // `fx/useSettlementWatcher.test.tsx`'s own `tick(0)`; bare `findByText` hangs here because
    // Testing Library's `waitFor` defers to the (fake) `setTimeout` clock too).
    screen.getByText("Withdraw to wallet");
    const balanceBefore = await mockApi.myAccount().then((a) => a.balance);

    fireEvent.change(screen.getByLabelText("Amount (PLAY)"), { target: { value: "500" } });
    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    await tick(0);

    expect(screen.getByText("QUEUED")).toBeInTheDocument();
    const balanceAfterRequest = await mockApi.myAccount().then((a) => a.balance);
    expect(Number(balanceAfterRequest)).toBe(Number(balanceBefore) - 500);

    await tick(2500); // mockApi's chainState: submitted (500ms) → queued (1500ms) → in_flight (2500ms)
    expect(screen.getByText("IN FLIGHT")).toBeInTheDocument();

    await tick(1500); // mockApi's terminal outcome fires at 4000ms total
    await tick(1500); // the page's own poll catching up to the now-settled state
    expect(screen.getByText(/^(CONFIRMED|FAILED)$/)).toBeInTheDocument();
  });

  it("the amount field is cleared and re-enabled for another withdrawal right after submitting", async () => {
    renderWallet();
    await tick(0);
    screen.getByText("Withdraw to wallet");
    const input = screen.getByLabelText("Amount (PLAY)") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "250" } });
    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    await tick(0);
    screen.getByText("QUEUED");
    expect(input.value).toBe("");
  });
});

describe("Activity history", () => {
  it("still lists the starter pack even with the real deposit/withdraw UI enabled", async () => {
    renderWallet();
    await screen.findByText("Activity");
    expect(await screen.findByText("Starter pack")).toBeInTheDocument();
  });
});

describe("keyboard/pointer smoke test via userEvent (no fake timers)", () => {
  it("typing an amount with the keyboard enables Withdraw the same as a raw change event", async () => {
    const user = userEvent.setup();
    renderWallet();
    await screen.findByText("Withdraw to wallet");
    await user.type(screen.getByLabelText("Amount (PLAY)"), "100");
    expect(screen.getByRole("button", { name: "Withdraw" })).not.toBeDisabled();
  });
});

describe("Sage friction hints (docs/research/sage_real_e2e.md, Friction section)", () => {
  it("four things to know, on the Wallet screen where the friction happens", async () => {
    renderWallet();
    const card = (await screen.findByRole("heading", { name: "Using Sage? Four things to know" })).closest(".card") as HTMLElement;
    expect(card).not.toBeNull();
    expect(within(card).getAllByRole("listitem")).toHaveLength(4);
    expect(within(card).getByText(/Sage starts on mainnet — switch it to/)).toHaveTextContent("testnet11");
    expect(within(card).getByText(/Each sign-in is a new WalletConnect connection/)).toBeInTheDocument();
  });

  it("PLAY shows as 'Unknown CAT' in Sage — so the card shows its full asset id, with a copy button", async () => {
    const user = userEvent.setup(); // installs the clipboard stub the copy button writes to
    renderWallet();
    const id = await screen.findByLabelText("PLAY asset id");
    expect(id.textContent).toBe(PLAY_ASSET_ID);
    expect(PLAY_ASSET_ID).toMatch(/^0x[0-9a-f]{64}$/);
    expect(screen.getByText(/Unknown CAT/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Copy asset id" }));
    expect(await navigator.clipboard.readText()).toBe(PLAY_ASSET_ID);
    expect(await screen.findByRole("button", { name: "Copied!" })).toBeInTheDocument();
  });

  it("names a fee that works on a busy testnet11", async () => {
    renderWallet();
    expect(await screen.findByText(RECOMMENDED_DEPOSIT_FEE)).toBeInTheDocument();
    expect(RECOMMENDED_DEPOSIT_FEE).toBe("0.0005 TXCH");
    expect(screen.getByText(/rejects a transfer with a zero fee/)).toBeInTheDocument();
  });

  it("the PLAY asset id is the testnet11 issuance from the docs", () => {
    // docs/research/week1_testnet_results.md:60
    expect(PLAY_ASSET_ID).toBe("0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70");
  });
});
