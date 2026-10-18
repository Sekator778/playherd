import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeAll, describe, expect, it } from "vitest";
import { ApiProvider } from "../api/ApiContext";
import { mockApi } from "../api/mockApi";
import { MyProfilePage } from "./MyProfilePage";

/** Same "sign the shared `mockApi` singleton in once" pattern as `WalletPage.test.tsx` — this
 *  file's own isolated module instance of `mockApi` (vitest gives each test file a fresh module
 *  graph), so it doesn't leak into `App.test.tsx`'s anonymous-session assertions. */
const REGISTERED_ADDRESS = "txch1myprofiletestaddress00000000000000000000000000000000q";
beforeAll(async () => {
  const challenge = await mockApi.authChallenge({});
  await mockApi.authVerify({ nonce: challenge.nonce, address: REGISTERED_ADDRESS, publicKey: "pk", signature: "sig" });
  await mockApi.setNickname("profileTester");
});

function renderMyProfile() {
  return render(
    <MemoryRouter>
      <ApiProvider>
        <MyProfilePage />
      </ApiProvider>
    </MemoryRouter>,
  );
}

describe("Profile tab (player review fix item 2: was the wallet, now the player's own profile)", () => {
  it("shows the player's own profile (nickname, round history) by default, not the wallet", async () => {
    renderMyProfile();
    // A brand-new mock player has no `seasonRecord` yet (0 rounds played, `ProfilePage`'s stats
    // grid only renders once one exists) — "Round history" and the player's own name are always
    // there regardless, so they're what identifies the Overview segment here.
    expect(await screen.findByText("Round history")).toBeInTheDocument();
    expect(screen.getByText(/profileTester/)).toBeInTheDocument();
    expect(screen.getByText("No rounds played yet.")).toBeInTheDocument();
    // The wallet's own heading/content aren't shown until the "Wallet" sub-tab is picked.
    expect(screen.queryByText("Deposit from wallet")).not.toBeInTheDocument();
  });

  it("switching to the Wallet sub-tab shows the balance and deposit/withdraw/settings — same content as /wallet", async () => {
    renderMyProfile();
    await screen.findByText("Round history");
    await userEvent.click(screen.getByRole("tab", { name: "Wallet" }));
    expect(await screen.findByText("Balance")).toBeInTheDocument();
    expect(await screen.findByText("Deposit from wallet")).toBeInTheDocument();
    expect(screen.getByText("Withdraw to wallet")).toBeInTheDocument();
    expect(screen.getByText("Settings")).toBeInTheDocument();
  });

  it("switching back to Overview leaves the wallet content", async () => {
    renderMyProfile();
    await screen.findByText("Round history");
    await userEvent.click(screen.getByRole("tab", { name: "Wallet" }));
    await screen.findByText("Deposit from wallet");
    await userEvent.click(screen.getByRole("tab", { name: "Overview" }));
    expect(screen.getByText("Round history")).toBeInTheDocument();
    expect(screen.queryByText("Deposit from wallet")).not.toBeInTheDocument();
  });
});

describe("Invite link block on the player's own profile", () => {
  it("shows the link with a copy button and the counter; no reward wording", async () => {
    renderMyProfile();
    const input = (await screen.findByLabelText("Your invite link")) as HTMLInputElement;
    expect(input.value).toMatch(/\/\?ref=[0-9a-f]{8}$/);
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(screen.getByText("Joined through your link: 0")).toBeInTheDocument();
    expect(screen.queryByText(/reward|bonus|prize/i)).not.toBeInTheDocument();
  });

  it("the counter follows the server's number", async () => {
    (mockApi as unknown as { simulateInvited(n: number): void }).simulateInvited(3);
    renderMyProfile();
    expect(await screen.findByText("Joined through your link: 3")).toBeInTheDocument();
  });
});
