import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, MAX_API_BOTS, type ApiBotDto, type ApiBotKeyDto } from "../api/contract";
import { BotsBlock } from "./BotsBlock";

const KEY = `herd_${"k".repeat(43)}`;

const bot = (over: Partial<ApiBotDto> = {}): ApiBotDto => ({
  playerId: "api-1",
  nickname: "Alpha",
  keyHint: "herd_...kkkk",
  keyActive: true,
  createdAtMs: 1,
  ...over,
});

function renderBlock(methods: Parameters<typeof fakeApi>[0]) {
  return render(
    <MemoryRouter>
      <BotsBlock />
    </MemoryRouter>,
    { wrapper: apiWrapper(fakeApi(methods), TEST_PLAYER) },
  );
}

describe("BotsBlock - the person's API bots on the own profile", () => {
  it("shows nothing when the bots cannot be loaded (a server without the feature, or a failure)", async () => {
    const myBots = vi.fn(async () => {
      throw new ApiRequestError("NOT_FOUND", "unknown RPC method");
    });
    const { container } = renderBlock({ myBots });
    await waitFor(() => expect(myBots).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("lists each bot with its key hint, a revoked one as such, and links to the docs", async () => {
    renderBlock({ myBots: vi.fn(async () => [bot(), bot({ playerId: "api-2", nickname: "Beta", keyHint: null, keyActive: false })]) });
    const list = await screen.findByRole("list", { name: "Your bots" });
    const [alpha, beta] = within(list).getAllByRole("listitem");
    expect(alpha).toHaveTextContent("Alpha");
    expect(alpha).toHaveTextContent("Key herd_...kkkk");
    expect(beta).toHaveTextContent("Beta");
    expect(beta).toHaveTextContent("Key revoked");
    expect(within(beta).queryByRole("button", { name: "Revoke key" })).not.toBeInTheDocument();
    expect(within(alpha).getByRole("button", { name: "Revoke key" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "API documentation" })).toHaveAttribute("href", "/developers");
    expect(screen.getByText(/cannot withdraw to a wallet/)).toBeInTheDocument();
  });

  it("creates a bot: the key is shown once, copyable, and gone when the person says it is saved", async () => {
    const created: ApiBotKeyDto = { bot: bot(), apiKey: KEY };
    const createBot = vi.fn(async () => created);
    renderBlock({ myBots: vi.fn(async () => []), createBot });
    const user = userEvent.setup();
    expect(await screen.findByText("You have no bots yet.")).toBeInTheDocument();

    const create = screen.getByRole("button", { name: "Create bot key" });
    expect(create).toBeDisabled();
    await user.type(screen.getByLabelText("Bot name"), "  Alpha ");
    await user.click(create);

    expect(createBot).toHaveBeenCalledWith("Alpha");
    const shown = await screen.findByLabelText("API key of Alpha");
    expect((shown as HTMLInputElement).value).toBe(KEY);
    expect(screen.getByText(/shown only once/)).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Your bots" })).toHaveTextContent("Alpha");
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect((screen.getByLabelText("Bot name") as HTMLInputElement).value).toBe("");

    await user.click(screen.getByRole("button", { name: "I have saved the key" }));
    expect(screen.queryByLabelText("API key of Alpha")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(KEY);
  });

  it("at the most bots there is no form, only the reason", async () => {
    const bots = Array.from({ length: MAX_API_BOTS }, (_, i) => bot({ playerId: `api-${i}`, nickname: `Bot ${i}` }));
    renderBlock({ myBots: vi.fn(async () => bots) });
    expect(await screen.findByText(`You have the most bots one person can create (${MAX_API_BOTS}).`)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create bot key" })).not.toBeInTheDocument();
  });

  it("regenerates a key (the new one is shown, the old hint replaced) and revokes one", async () => {
    const regenerateBotKey = vi.fn(async () => ({ bot: bot({ keyHint: "herd_...zzzz" }), apiKey: `herd_${"z".repeat(43)}` }));
    const revokeBotKey = vi.fn(async () => bot({ keyHint: null, keyActive: false }));
    renderBlock({ myBots: vi.fn(async () => [bot()]), regenerateBotKey, revokeBotKey });
    const user = userEvent.setup();
    await screen.findByRole("list", { name: "Your bots" });

    await user.click(screen.getByRole("button", { name: "Regenerate key" }));
    expect(regenerateBotKey).toHaveBeenCalledWith("api-1");
    expect(((await screen.findByLabelText("API key of Alpha")) as HTMLInputElement).value).toBe(`herd_${"z".repeat(43)}`);
    expect(screen.getByRole("list", { name: "Your bots" })).toHaveTextContent("Key herd_...zzzz");

    await user.click(screen.getByRole("button", { name: "Revoke key" }));
    expect(revokeBotKey).toHaveBeenCalledWith("api-1");
    await waitFor(() => expect(screen.getByRole("list", { name: "Your bots" })).toHaveTextContent("Key revoked"));
    expect(screen.queryByLabelText("API key of Alpha")).not.toBeInTheDocument();
  });

  it("says why a creation failed, in the server's words, and keeps what was typed", async () => {
    const createBot = vi.fn(async () => {
      throw new ApiRequestError("INVALID_NICKNAME", "bot name must be 3-24 characters");
    });
    renderBlock({ myBots: vi.fn(async () => []), createBot });
    const user = userEvent.setup();
    await screen.findByText("You have no bots yet.");
    await user.type(screen.getByLabelText("Bot name"), "ab");
    await user.click(screen.getByRole("button", { name: "Create bot key" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("bot name must be 3-24 characters");
    expect((screen.getByLabelText("Bot name") as HTMLInputElement).value).toBe("ab");
  });
});
