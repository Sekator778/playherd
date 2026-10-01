import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider, useSession } from "../api/ApiContext";
import { ConnectWalletModal } from "./ConnectWalletModal";

const wc = vi.hoisted(() => ({ projectId: null as string | null }));

vi.mock("../wallet/walletConnect", async (importOriginal) => {
  const original = await importOriginal<typeof import("../wallet/walletConnect")>();
  return {
    ...original,
    getWalletConnectProjectId: () => wc.projectId,
    // A pairing that shows its link and then waits for Sage forever: enough to see the QR step.
    connectAndSignWithSage: vi.fn((_id: string, _message: unknown, onUri: (uri: string) => void) => {
      onUri("wc:0123456789abcdef@2?relay-protocol=irn&symKey=00");
      return new Promise(() => {});
    }),
  };
});

function Opener() {
  const { openConnectModal } = useSession();
  return (
    <button type="button" onClick={openConnectModal}>
      open the modal
    </button>
  );
}

async function openModal() {
  render(
    <MemoryRouter>
      <ApiProvider>
        <Opener />
        <ConnectWalletModal />
      </ApiProvider>
    </MemoryRouter>,
  );
  await userEvent.setup().click(screen.getByRole("button", { name: "open the modal" }));
  return screen.getByRole("dialog");
}

beforeEach(() => {
  wc.projectId = null;
});

describe("Connect-wallet modal — the test wallet leads (HANDOFF §6 п.10)", () => {
  it("the test wallet is the primary action and comes first; Sage is the second, quieter one — both stay", async () => {
    const dialog = await openModal();
    const buttons = within(dialog).getAllByRole("button");
    const names = buttons.map((b) => b.textContent);
    expect(names.indexOf("Continue with test wallet")).toBeGreaterThanOrEqual(0);
    expect(names.indexOf("Continue with test wallet")).toBeLessThan(names.indexOf("Connect with Sage"));

    const testWallet = within(dialog).getByRole("button", { name: "Continue with test wallet" });
    const sage = within(dialog).getByRole("button", { name: "Connect with Sage" });
    expect(testWallet).toHaveClass("btn--primary");
    expect(sage).toHaveClass("btn--secondary");
    expect(sage).not.toHaveClass("btn--primary");
  });

  it("says plainly that the test wallet is not Sage and moves no funds", async () => {
    const dialog = await openModal();
    expect(within(dialog).getByText("TEST WALLET")).toBeInTheDocument();
    expect(within(dialog).getByText(/not Sage/)).toBeInTheDocument();
    expect(within(dialog).getByText(/doesn't move any funds/)).toBeInTheDocument();
  });

  it("without a WalletConnect project id the Sage button is off, with the reason", async () => {
    const dialog = await openModal();
    expect(within(dialog).getByRole("button", { name: "Connect with Sage" })).toBeDisabled();
    expect(within(dialog).getByText("Coming soon — needs WalletConnect project id.")).toBeInTheDocument();
    // The test wallet is never blocked by it.
    expect(within(dialog).getByRole("button", { name: "Continue with test wallet" })).toBeEnabled();
  });

  it("with a project id Sage is on, and a line says it needs testnet11", async () => {
    wc.projectId = "project-123";
    const dialog = await openModal();
    expect(within(dialog).getByRole("button", { name: "Connect with Sage" })).toBeEnabled();
    expect(within(dialog).getByText(/Sage needs to be on testnet11/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Coming soon/)).not.toBeInTheDocument();
  });

  it("the Sage step shows the pairing QR, the copyable link — and the two things that trip a first sign-in", async () => {
    wc.projectId = "project-123";
    const dialog = await openModal();
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "Connect with Sage" }));
    expect(await within(dialog).findByRole("img", { name: /WalletConnect pairing QR code/ })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Copy connection link" })).toBeInTheDocument();
    expect(within(dialog).getByText(/Sage starts on mainnet/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Every sign-in is a new connection/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Settings → WalletConnect/)).toBeInTheDocument();
  });

  it("can be cancelled", async () => {
    const dialog = await openModal();
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
