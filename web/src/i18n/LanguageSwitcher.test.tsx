import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import { ApiProvider } from "../api/ApiContext";
import { I18nProvider } from "./I18nProvider";
import { LOCALE_STORAGE_KEY } from "./locale";

function renderApp(initialPath: string) {
  return render(
    <I18nProvider>
      <MemoryRouter initialEntries={[initialPath]}>
        <ApiProvider>
          <App />
        </ApiProvider>
      </MemoryRouter>
    </I18nProvider>,
  );
}

describe("footer language switcher", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    document.documentElement.lang = "en";
  });

  it("is on the page and names each language in itself", () => {
    renderApp("/how-it-works");
    const select = screen.getByRole("combobox", { name: "Language" });
    expect(select).toHaveValue("en");
    expect(screen.getByRole("option", { name: "English" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Українська" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Русский" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
  });

  it("switches the visible text and <html lang> to Ukrainian, then Russian, and saves the choice", async () => {
    const user = userEvent.setup();
    renderApp("/how-it-works");
    expect(screen.getByRole("heading", { level: 1, name: "How it works" })).toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: "Language" }), "uk");
    expect(screen.getByRole("heading", { level: 1, name: "Як це працює" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("uk");
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("uk");
    // Tab bar and footer follow too.
    expect(screen.getByRole("link", { name: "Статистика" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Мова" })).toHaveValue("uk");

    await user.selectOptions(screen.getByRole("combobox", { name: "Мова" }), "ru");
    expect(screen.getByRole("heading", { level: 1, name: "Как это работает" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("ru");
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("ru");

    await user.selectOptions(screen.getByRole("combobox", { name: "Язык" }), "en");
    expect(screen.getByRole("heading", { level: 1, name: "How it works" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
  });

  it("starts in the saved language and also translates data-driven screens (lobby)", async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "uk");
    renderApp("/");
    expect(await screen.findByRole("heading", { name: "Обери ринок" })).toBeInTheDocument();
    expect(await screen.findByText(/[ув] цьому раунді/)).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("uk");
  });

  it("is available on every page, including the 404 page", () => {
    renderApp("/no/such/page");
    expect(screen.getByText("Page not found.")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Language" })).toBeInTheDocument();
  });
});
