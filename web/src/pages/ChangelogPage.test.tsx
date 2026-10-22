import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { DICTIONARIES, type Dict } from "../i18n/translate";
import { LOCALES } from "../i18n/locale";
import { ChangelogPage } from "./ChangelogPage";
import { CHANGELOG } from "./changelog/entries";

function text(dict: Dict, key: string): unknown {
  return key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], dict);
}

describe("What's new (/changelog)", () => {
  it("renders the title, the intro and every entry's items, with dated headings", () => {
    render(
      <MemoryRouter>
        <ChangelogPage />
      </MemoryRouter>,
    );
    expect(screen.getByRole("heading", { level: 1, name: "What's new in Herd" })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 2 })).toHaveLength(CHANGELOG.length);
    expect(screen.getByRole("heading", { name: "October 20, 2026" })).toBeInTheDocument();
    expect(screen.getByText(/New bet mode, Win or lose/)).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(CHANGELOG.reduce((n, e) => n + e.items.length, 0));
  });

  it("lists the days newest first, once each, with 2 to 5 items a day", () => {
    const dates = CHANGELOG.map((e) => e.date);
    expect(dates).toEqual([...dates].sort().reverse());
    expect(new Set(dates).size).toBe(dates.length);
    for (const e of CHANGELOG) expect(e.items.length).toBeGreaterThanOrEqual(2);
    for (const e of CHANGELOG) expect(e.items.length).toBeLessThanOrEqual(5);
  });

  it("has every item key and the page chrome in all six languages", () => {
    const keys = [...CHANGELOG.flatMap((e) => e.items), "changelog.title", "changelog.intro", "changelog.back", "layout.whatsNew"];
    for (const locale of LOCALES) {
      for (const key of keys) {
        const value = text(DICTIONARIES[locale], key);
        expect(typeof value, `${locale}: ${key}`).toBe("string");
        expect((value as string).length, `${locale}: ${key}`).toBeGreaterThan(0);
      }
    }
  });

  it("never calls PLAY or the money a toy, fake or not real, in any language", () => {
    const banned = /toy|fake|play money|not real|іграшков|игрушечн|juguete|玩具|खिलौन|mainnet/i;
    for (const locale of LOCALES) {
      const dict = DICTIONARIES[locale].changelog as Record<string, unknown>;
      const all = JSON.stringify(dict);
      expect(all, locale).not.toMatch(banned);
    }
  });
});
