import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProvider, useSession } from "../api/ApiContext";
import { fakeApi, serverInfoDto, TEST_PLAYER } from "../api/apiTestUtils";
import { ApiRequestError, type Api, type ServerInfoDto } from "../api/contract";
import { ConnectWalletModal } from "./ConnectWalletModal";

const wc = vi.hoisted(() => ({ projectId: null as string | null }));

// The browser test wallet's WASM crypto is not what these tests are about — a stand-in that "signs" instantly.
vi.mock("../wallet/testWallet", () => ({
  getTestWallet: () => ({ address: "txch1testwallet" }),
  signTestWalletMessage: () => ({ address: "txch1testwallet", publicKey: "pk", signature: "sig" }),
}));

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

/** Where the router is, for the tests that ask whether a button moved the player. */
function LocationProbe() {
  const { pathname, search } = useLocation();
  return <p data-testid="location">{pathname + search}</p>;
}

async function openModal(api?: Api, path = "/") {
  render(
    <MemoryRouter initialEntries={[path]}>
      <ApiProvider api={api}>
        <Opener />
        <ConnectWalletModal />
        <LocationProbe />
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

  it("starts UNKNOWN: until the server has answered, neither way in is offered — never the test wallet 'as before'", async () => {
    let answer: (info: ServerInfoDto) => void = () => {};
    const api = fakeApi({ currentPlayer: async () => null, serverInfo: () => new Promise<ServerInfoDto>((resolve) => (answer = resolve)) });
    const dialog = await openModal(api);
    expect(within(dialog).getByRole("status", { name: /Asking the server/ })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Connect with Sage" })).not.toBeInTheDocument();
    expect(within(dialog).queryByText("TEST WALLET")).not.toBeInTheDocument();
    answer(serverInfoDto({ testWallet: false }));
    expect(await within(dialog).findByText(/signs you in with your own Chia wallet \(Sage\)/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
  });

  it("a server that cannot be reached is said, with a retry — nothing is offered on a guess, and a later answer applies", async () => {
    let up = false;
    const serverInfo = vi.fn(async () => {
      if (!up) throw new Error("offline");
      return serverInfoDto({ testWallet: false });
    });
    const dialog = await openModal(fakeApi({ currentPlayer: async () => null, serverInfo }));
    expect(await within(dialog).findByText(/Can't reach the server right now/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
    up = true;
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "Try again" }));
    // the retry reached the server, which says it has no test wallet: the sheet follows it
    expect(await within(dialog).findByText(/signs you in with your own Chia wallet \(Sage\)/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Continue with test wallet" })).not.toBeInTheDocument();
  });
});

/** A server that signs a visitor in as a NEW player, with a nickname step that answers as the test says. */
function newPlayerApi(setNickname: Api["setNickname"]) {
  let player: typeof TEST_PLAYER | null = null;
  return fakeApi({
    currentPlayer: async () => player,
    serverInfo: async () => serverInfoDto(),
    authChallenge: async () => ({ nonce: "n", message: "sign me", expiresAtMs: 1 }),
    authVerify: async () => {
      player = { ...TEST_PLAYER, nickname: "" };
      return { player, isNewPlayer: true };
    },
    setNickname,
  });
}

async function toNicknameStep(setNickname: Api["setNickname"], path = "/") {
  const dialog = await openModal(newPlayerApi(setNickname), path);
  const user = userEvent.setup();
  await user.click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
  const field = await within(dialog).findByLabelText("Nickname", {}, { timeout: 3000 });
  return { dialog, user, field };
}

describe("Connect-wallet modal — the nickname step (the server's rule, said before and after the question)", () => {
  it("states the rule beside the field, always", async () => {
    const { dialog } = await toNicknameStep(vi.fn(async () => ({ ...TEST_PLAYER })));
    expect(within(dialog).getByText("3–24 characters: letters A–Z, digits, space, _ . -")).toBeInTheDocument();
  });

  it.each([
    ["ab", /Too short — at least 3 characters/],
    ["Саша", /Only letters A–Z, digits, space, _ \. - are allowed — not: С а ш/],
    ["moon🚀", /not: 🚀/],
  ])("%j: the reason in words, CONTINUE off, and the server is not asked", async (typed, reason) => {
    const setNickname = vi.fn(async () => ({ ...TEST_PLAYER }));
    const { dialog, user, field } = await toNicknameStep(setNickname);
    await user.type(field, typed);
    expect(within(dialog).getByRole("alert")).toHaveTextContent(reason);
    expect(within(dialog).getByRole("button", { name: "CONTINUE" })).toBeDisabled();
    await user.keyboard("{Enter}");
    expect(setNickname).not.toHaveBeenCalled();
  });

  it("an empty field complains of nothing yet; a valid nickname goes through trimmed", async () => {
    const setNickname = vi.fn(async (nickname: string) => ({ ...TEST_PLAYER, nickname }));
    const { dialog, user, field } = await toNicknameStep(setNickname);
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "CONTINUE" })).toBeDisabled();
    await user.type(field, "  moon Walker ");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(setNickname).toHaveBeenCalledWith("moon Walker");
    expect(await within(dialog).findByText(/You've got 10,000 PLAY/)).toBeInTheDocument();
  });

  it("a nickname the server refuses anyway: its sentence stays beside the field, the step stays, typing clears it", async () => {
    const refusal = "nickname must be 3-24 characters: letters, digits, space, '_', '.', '-' only";
    const setNickname = vi.fn(async () => {
      throw new ApiRequestError("INVALID_NICKNAME", refusal);
    });
    const { dialog, user, field } = await toNicknameStep(setNickname);
    await user.type(field, "fine name");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(refusal);
    expect(within(dialog).getByRole("heading", { name: "Pick a nickname" })).toBeInTheDocument();
    await user.type(field, "!");
    expect(within(dialog).getByRole("alert")).not.toHaveTextContent(refusal);
  });

  it("the server restarting (503 ENGINE_NOT_READY) is said in the app's own words, and the player may try again", async () => {
    const setNickname = vi
      .fn<Api["setNickname"]>()
      .mockRejectedValueOnce(new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering — nothing was changed, please try again in a moment"))
      .mockResolvedValueOnce({ ...TEST_PLAYER, nickname: "fine name" });
    const { dialog, user, field } = await toNicknameStep(setNickname);
    await user.type(field, "fine name");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The server is restarting — try again in a moment.");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(await within(dialog).findByText(/You've got 10,000 PLAY/)).toBeInTheDocument();
  });
});

/** A server that signs a visitor in as a player it already knows (`isNewPlayer: false`) — one who has a nickname of their own, or none. */
function returningPlayerApi(nickname: string, setNickname: Api["setNickname"] = vi.fn(async (n: string) => ({ ...TEST_PLAYER, nickname: n }))) {
  const wallet = "txch1abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuv";
  let player: typeof TEST_PLAYER | null = null;
  return fakeApi({
    currentPlayer: async () => player,
    serverInfo: async () => serverInfoDto(),
    authChallenge: async () => ({ nonce: "n", message: "sign me", expiresAtMs: 1 }),
    authVerify: async () => {
      player = { ...TEST_PLAYER, walletAddress: wallet, nickname };
      return { player, isNewPlayer: false };
    },
    setNickname,
  });
}

describe("Connect-wallet modal — the starter pack is told once, to the player who just got it (first tester pass, D1)", () => {
  it("a returning player is signed in and the sheet goes away: no 'You've got 10,000 PLAY' over a balance that is not 10,000", async () => {
    const dialog = await openModal(returningPlayerApi("Tester_One"));
    await userEvent.setup().click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument(), { timeout: 3000 });
    expect(screen.queryByText(/You've got/)).not.toBeInTheDocument();
  });

  it("a returning player stays on the screen they signed in from", async () => {
    const dialog = await openModal(returningPlayerApi("Tester_One"), "/play/spot_ETH?side=down");
    await userEvent.setup().click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument(), { timeout: 3000 });
    expect(screen.getByTestId("location")).toHaveTextContent("/play/spot_ETH?side=down");
  });

  it("one who left their first sign-in at the nickname step (the server names them by their address) is asked for it once more — and told nothing about a pack", async () => {
    const setNickname = vi.fn(async (nickname: string) => ({ ...TEST_PLAYER, nickname }));
    const dialog = await openModal(returningPlayerApi("txch1a…stuv", setNickname));
    const user = userEvent.setup();
    await user.click(await within(dialog).findByRole("button", { name: "Continue with test wallet" }));
    await user.type(await within(dialog).findByLabelText("Nickname", {}, { timeout: 3000 }), "moon Walker");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(setNickname).toHaveBeenCalledWith("moon Walker");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByText(/You've got/)).not.toBeInTheDocument();
  });

  it("a new player still gets the pack, after the nickname", async () => {
    const { dialog, user, field } = await toNicknameStep(vi.fn(async (nickname: string) => ({ ...TEST_PLAYER, nickname })));
    await user.type(field, "moon Walker");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    expect(await within(dialog).findByRole("heading", { name: "You've got 10,000 PLAY" })).toBeInTheDocument();
  });
});

describe("Connect-wallet modal — PLACE A BET on a first sign-in does not throw the screen away (first tester pass, D1)", () => {
  const pressPlaceABet = async (path: string) => {
    const { dialog, user, field } = await toNicknameStep(vi.fn(async (nickname: string) => ({ ...TEST_PLAYER, nickname })), path);
    await user.type(field, "moon Walker");
    await user.click(within(dialog).getByRole("button", { name: "CONTINUE" }));
    await user.click(await within(dialog).findByRole("button", { name: "PLACE A BET" }));
  };

  it("from a room: the room stays, with its side", async () => {
    await pressPlaceABet("/play/spot_ETH?side=down");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/play/spot_ETH?side=down");
  });

  it("from the lobby: the lobby stays, on its panel", async () => {
    await pressPlaceABet("/?c=sports");
    expect(screen.getByTestId("location")).toHaveTextContent("/?c=sports");
  });

  it.each(["/stats", "/how-it-works", "/wallet"])("from %s, where nothing can be bet: the lobby", async (path) => {
    await pressPlaceABet(path);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/);
  });
});
