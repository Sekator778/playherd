import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ScrollManager } from "./ScrollManager";

let scrollTo: ReturnType<typeof vi.fn>;

function setScrollY(y: number) {
  Object.defineProperty(window, "scrollY", { value: y, configurable: true });
}

function Nav() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate("/play/BTC")}>open room</button>
      <button onClick={() => navigate("/play/ETH")}>open another room</button>
      <button onClick={() => navigate("/?c=sports", { replace: true })}>filter in place</button>
      <button onClick={() => navigate(-1)}>back</button>
    </>
  );
}

function renderManager() {
  return render(
    <MemoryRouter>
      <ScrollManager />
      <Nav />
    </MemoryRouter>,
  );
}

/** The manager records the offset a frame after the scroll event. */
async function scrollTheLobbyTo(y: number) {
  setScrollY(y);
  window.dispatchEvent(new Event("scroll"));
  await act(async () => {
    await new Promise((r) => setTimeout(r, 40));
  });
}

beforeEach(() => {
  scrollTo = vi.fn();
  vi.spyOn(window, "scrollTo").mockImplementation(scrollTo as unknown as typeof window.scrollTo);
  setScrollY(0);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("ScrollManager — a screen starts at the top, Back returns to where it was", () => {
  it("leaves the first screen alone", () => {
    renderManager();
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("a link to another screen starts at the top — a lobby scrolled down must not open the room scrolled down", async () => {
    const user = userEvent.setup();
    renderManager();
    await scrollTheLobbyTo(900);
    await user.click(screen.getByRole("button", { name: "open room" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 0);
  });

  it("Back restores the offset the lobby was left at", async () => {
    const user = userEvent.setup();
    renderManager();
    await scrollTheLobbyTo(640);
    await user.click(screen.getByRole("button", { name: "open room" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 0);
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 640);
  });

  it("each screen keeps its own offset", async () => {
    const user = userEvent.setup();
    renderManager();
    await scrollTheLobbyTo(300);
    await user.click(screen.getByRole("button", { name: "open room" }));
    await scrollTheLobbyTo(120); // scrolled down in the BTC room
    await user.click(screen.getByRole("button", { name: "open another room" }));
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 120);
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 300);
  });

  it("a filter or a search that rewrites the URL in place leaves the page where it is", async () => {
    const user = userEvent.setup();
    renderManager();
    await scrollTheLobbyTo(500);
    scrollTo.mockClear();
    await user.click(screen.getByRole("button", { name: "filter in place" }));
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("an entry never scrolled restores to the top", async () => {
    const user = userEvent.setup();
    renderManager();
    await user.click(screen.getByRole("button", { name: "open room" }));
    await user.click(screen.getByRole("button", { name: "open another room" }));
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(scrollTo).toHaveBeenLastCalledWith(0, 0);
  });
});
