/**
 * Feedback in the whole app (D-81): the strip on every main screen, the entry points that outlive it (the footer, the
 * profile menu), and a message sent end to end through the mock — the real `Layout`, the real routes, the real form.
 */
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import { ApiProvider } from "../api/ApiContext";
import { reloadFavoritesFromStorage } from "../api/favorites";
import { FEEDBACK_DISMISSED_KEY, FEEDBACK_SENT_KEY, resetFeedbackForTests } from "../api/feedback";
import { mockApi, mockFeedbackMessages } from "../api/mockApi";

function renderApp(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ApiProvider>
        <App />
      </ApiProvider>
    </MemoryRouter>,
  );
}

async function signIn(nickname = "fbTester"): Promise<void> {
  const challenge = await mockApi.authChallenge({});
  await mockApi.authVerify({ nonce: challenge.nonce, address: "txch1layoutfeedbacktest00000000000000000000000000000000000q", publicKey: "pk", signature: "sig" });
  await mockApi.setNickname(nickname);
}

const stripEl = () => document.querySelector(".feedback-strip");
const footer = () => screen.getByRole("contentinfo");

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  reloadFavoritesFromStorage();
  resetFeedbackForTests();
  // `ScrollManager` scrolls the window on every navigation; jsdom has no `scrollTo`.
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});

afterEach(async () => {
  await mockApi.signOut();
  vi.restoreAllMocks();
});

describe("the strip is on every main screen, for a visitor who is not signed in", () => {
  it.each(["/", "/play/BTC", "/stats", "/wallet", "/profile/me", "/status", "/how-it-works"])("%s", async (path) => {
    renderApp(path);
    await waitFor(() => expect(stripEl()).toBeInTheDocument());
    expect(stripEl()).toHaveTextContent("Tell us what you liked and what you didn't");
  });

  it("and on a screen that does not exist", async () => {
    renderApp("/this-route-does-not-exist");
    await waitFor(() => expect(stripEl()).toBeInTheDocument());
  });
});

describe("the strip for a signed-in player", () => {
  it("is there too — signing in neither adds it nor takes it away", async () => {
    await signIn();
    renderApp("/");
    await screen.findByRole("button", { name: /fbTester/ });
    expect(stripEl()).toBeInTheDocument();
  });
});

describe("the page keeps room for the strip only while it is up", () => {
  it("while it is up the page carries the class that reserves the room under the content", async () => {
    const { container } = renderApp("/how-it-works");
    expect(container.querySelector(".page")).toHaveClass("page--feedback-strip");
  });

  it("closing the strip gives the room back", async () => {
    const user = userEvent.setup();
    const { container } = renderApp("/how-it-works");
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(container.querySelector(".page")).not.toHaveClass("page--feedback-strip");
    expect(stripEl()).not.toBeInTheDocument();
  });
});

describe("closing the strip — and what stays", () => {
  it("× closes it for good on this browser: it writes herd.feedback.dismissed, and a fresh load of any screen has none", async () => {
    const user = userEvent.setup();
    const first = renderApp("/how-it-works");
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(window.localStorage.getItem(FEEDBACK_DISMISSED_KEY)).toBe("1");
    first.unmount();

    renderApp("/status");
    expect(await screen.findByRole("heading", { level: 1, name: "Status" })).toBeInTheDocument();
    expect(stripEl()).not.toBeInTheDocument();
  });

  it("the footer keeps its Feedback entry, closed strip or not", async () => {
    const user = userEvent.setup();
    renderApp("/how-it-works");
    expect(within(footer()).getByRole("button", { name: "Feedback" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    expect(within(footer()).getByRole("button", { name: "Feedback" })).toBeInTheDocument();
  });

  it("the footer's other links are as they were: How it works and Status", () => {
    renderApp("/how-it-works");
    expect(within(footer()).getByRole("link", { name: "How it works" })).toHaveAttribute("href", "/how-it-works");
    expect(within(footer()).getByRole("link", { name: "Status" })).toHaveAttribute("href", "/status");
  });

  it("with the strip closed, the footer's Feedback still opens the form", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
    renderApp("/how-it-works");
    await user.click(within(footer()).getByRole("button", { name: "Feedback" }));
    expect(await screen.findByRole("dialog", { name: "Feedback" })).toBeInTheDocument();
  });

  it("after a message was sent (the strip gone with it) the footer still opens the form", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(FEEDBACK_SENT_KEY, "1");
    renderApp("/how-it-works");
    expect(stripEl()).not.toBeInTheDocument();
    await user.click(within(footer()).getByRole("button", { name: "Feedback" }));
    expect(await screen.findByRole("dialog", { name: "Feedback" })).toBeInTheDocument();
  });
});

describe("the profile menu", () => {
  it("has a Feedback entry for a signed-in player, between Wallet and Sign out", async () => {
    const user = userEvent.setup();
    await signIn();
    renderApp("/how-it-works");
    await user.click(await screen.findByRole("button", { name: /fbTester/ }));
    const names = within(screen.getByRole("menu")).getAllByRole("menuitem").map((item) => item.textContent);
    expect(names).toEqual(["My profile", "Wallet", "Feedback", "Sign out"]);
  });

  it("opens the form, and closes the menu", async () => {
    const user = userEvent.setup();
    await signIn();
    renderApp("/how-it-works");
    await user.click(await screen.findByRole("button", { name: /fbTester/ }));
    await user.click(screen.getByRole("menuitem", { name: "Feedback" }));
    expect(await screen.findByRole("dialog", { name: "Feedback" })).toBeInTheDocument();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("is there when the strip is closed, too", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(FEEDBACK_DISMISSED_KEY, "1");
    await signIn();
    renderApp("/how-it-works");
    await user.click(await screen.findByRole("button", { name: /fbTester/ }));
    expect(screen.getByRole("menuitem", { name: "Feedback" })).toBeInTheDocument();
  });
});

describe("a message, end to end", () => {
  it("a visitor: the strip opens the form, Send stores the message with the screen it was opened on, the strip goes", async () => {
    const user = userEvent.setup();
    renderApp("/how-it-works");
    const before = mockFeedbackMessages().length;
    await user.click(screen.getByRole("button", { name: /Tell us what you liked/ }));
    const dialog = await screen.findByRole("dialog", { name: "Feedback" });
    await user.type(within(dialog).getByRole("textbox"), "Liked the chart. The slip needs a bigger PLACE BET.");
    await user.click(within(dialog).getByRole("button", { name: "Send" }));

    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    expect(mockFeedbackMessages()).toHaveLength(before + 1);
    const stored = mockFeedbackMessages()[before];
    expect(stored).toMatchObject({
      message: "Liked the chart. The slip needs a bigger PLACE BET.",
      page: "/how-it-works",
      client: "web",
      playerId: null,
    });
    expect(window.localStorage.getItem(FEEDBACK_SENT_KEY)).toBe("1");
    expect(stripEl()).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("a signed-in player: the message is kept with the player's id", async () => {
    const user = userEvent.setup();
    await signIn();
    renderApp("/status");
    await screen.findByRole("button", { name: /fbTester/ });
    const before = mockFeedbackMessages().length;
    await user.click(within(footer()).getByRole("button", { name: "Feedback" }));
    await user.type(await screen.findByRole("textbox"), "Hello from a player");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByRole("heading", { name: "Thanks — we read every message." });
    expect(mockFeedbackMessages()[before]).toMatchObject({ message: "Hello from a player", page: "/status", playerId: "me" });
  });

  it("the page is the path only: the query string of the screen it was opened on is not sent", async () => {
    const user = userEvent.setup();
    renderApp("/?c=sports&q=derby");
    await screen.findByRole("heading", { level: 1, name: "Markets" });
    const before = mockFeedbackMessages().length;
    await user.click(within(footer()).getByRole("button", { name: "Feedback" }));
    await user.type(await screen.findByRole("textbox"), "search is good");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByRole("heading", { name: "Thanks — we read every message." });
    expect(mockFeedbackMessages()[before].page).toBe("/");
  });

  it("with the strip closed first, a visitor can still send: the footer, the form, the thanks", async () => {
    const user = userEvent.setup();
    renderApp("/how-it-works");
    await user.click(screen.getByRole("button", { name: "Close the feedback banner" }));
    const before = mockFeedbackMessages().length;
    await user.click(within(footer()).getByRole("button", { name: "Feedback" }));
    await user.type(await screen.findByRole("textbox"), "closed the banner, still have a thought");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    expect(mockFeedbackMessages()).toHaveLength(before + 1);
  });

  it("Cancel leaves the strip where it was: opening the form is not closing the invitation", async () => {
    const user = userEvent.setup();
    renderApp("/how-it-works");
    await user.click(screen.getByRole("button", { name: /Tell us what you liked/ }));
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(stripEl()).toBeInTheDocument();
    expect(stripEl()).not.toHaveClass("feedback-strip--away");
    expect(window.localStorage.getItem(FEEDBACK_DISMISSED_KEY)).toBeNull();
  });
});
