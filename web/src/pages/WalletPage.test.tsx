import { act, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { mockApi } from "../api/mockApi";
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

function renderWallet() {
  return render(
    <MemoryRouter>
      <ApiProvider>
        <WalletPage />
      </ApiProvider>
    </MemoryRouter>,
  );
}

describe("Deposit from wallet (Р-53: real deposit address, no self-report form)", () => {
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

describe("Withdraw to wallet — form and validation (Р-53/Р-57)", () => {
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
