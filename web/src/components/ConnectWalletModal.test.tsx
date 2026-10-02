import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider, useSession } from "../api/ApiContext";
import { fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api, ServerInfoDto } from "../api/contract";
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

async function openModal(api?: Api) {
  render(
    <MemoryRouter>
      <ApiProvider api={api}>
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

/** A server that describes itself (`serverInfo()`), with a signed-out visitor. */
function serverApi(info: Partial<ServerInfoDto>): Api {
  return fakeApi({ currentPlayer: async () => null, serverInfo: async () => serverInfoDto(info) });
}

describe("Connect-wallet modal — a server can switch the browser test wallet off (ServerInfoDto.testWallet)", () => {
  it("testWallet: false — no test-wallet button, no TEST WALLET tag, no 'or'; Sage is the way in and leads", async () => {
    wc.projectId = "project-123";
    const dialog = await openModal(serverApi({ testWallet: false }));
    expect(await within(dialog).findByText(/signs you in with your own Chia wallet \(Sage\)/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
    expect(within(dialog).queryByText("TEST WALLET")).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/not Sage/)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("separator")).not.toBeInTheDocument();
    const sage = within(dialog).getByRole("button", { name: "Connect with Sage" });
    expect(sage).toBeEnabled();
    expect(sage).toHaveClass("btn--primary");
  });

  it("testWallet: false and no WalletConnect project id — says there is no way in yet instead of offering a dead button as the only choice", async () => {
    wc.projectId = null;
    const dialog = await openModal(serverApi({ testWallet: false }));
    expect(await within(dialog).findByText(/Sign-in isn't available on this server yet/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Connect with Sage" })).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
  });

  it.each([["true", { testWallet: true }], ["absent", {}]])("testWallet %s — offered, exactly as before", async (_label, info) => {
    const dialog = await openModal(serverApi(info));
    expect(await within(dialog).findByRole("button", { name: "Continue with test wallet" })).toHaveClass("btn--primary");
    expect(within(dialog).getByRole("button", { name: "Connect with Sage" })).toHaveClass("btn--secondary");
  });

  it("a server that cannot be reached does not take the test wallet away", async () => {
    const api = fakeApi({
      currentPlayer: async () => null,
      serverInfo: async () => {
        throw new Error("offline");
      },
    });
    const dialog = await openModal(api);
    expect(await within(dialog).findByRole("button", { name: "Continue with test wallet" })).toBeInTheDocument();
  });
});
