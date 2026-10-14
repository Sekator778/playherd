import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { en } from "./en";
import { uk } from "./uk";
import { ru } from "./ru";
import { LOCALE_STORAGE_KEY, detectLocale, saveLocale } from "./locale";
import { createT } from "./translate";

type Tree = { [key: string]: string | Tree };

/** Flattens the dictionary into `group.key → text`. */
function flatten(tree: Tree, prefix = ""): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(tree)) {
    if (typeof v === "string") out[`${prefix}${k}`] = v;
    else Object.assign(out, flatten(v, `${prefix}${k}.`));
  }
  return out;
}

const FLAT = { en: flatten(en as unknown as Tree), uk: flatten(uk as unknown as Tree), ru: flatten(ru as unknown as Tree) };

const placeholders = (s: string) => [...new Set(s.match(/\{\w+\}/g) ?? [])].sort();

describe("dictionaries", () => {
  it("uk and ru have exactly the keys of en (tsc checks it too; this guards the runtime shape)", () => {
    const keys = Object.keys(FLAT.en).sort();
    expect(Object.keys(FLAT.uk).sort()).toEqual(keys);
    expect(Object.keys(FLAT.ru).sort()).toEqual(keys);
  });

  it("no value is empty in any language", () => {
    for (const locale of ["en", "uk", "ru"] as const) {
      for (const [key, value] of Object.entries(FLAT[locale])) {
        expect(value.trim(), `${locale}:${key}`).not.toBe("");
      }
    }
  });

  it("every translation uses the same {placeholders} as the English text", () => {
    for (const [key, value] of Object.entries(FLAT.en)) {
      expect(placeholders(FLAT.uk[key]), `uk:${key}`).toEqual(placeholders(value));
      expect(placeholders(FLAT.ru[key]), `ru:${key}`).toEqual(placeholders(value));
    }
  });

  it("plural strings carry one|other in English and one|few|many|other in uk and ru", () => {
    for (const [key, value] of Object.entries(FLAT.en)) {
      const isPlural = value.includes("|");
      expect(FLAT.uk[key].includes("|"), `uk:${key}`).toBe(isPlural);
      expect(FLAT.ru[key].includes("|"), `ru:${key}`).toBe(isPlural);
      if (isPlural) {
        expect(value.split("|"), `en:${key}`).toHaveLength(2);
        expect(FLAT.uk[key].split("|"), `uk:${key}`).toHaveLength(4);
        expect(FLAT.ru[key].split("|"), `ru:${key}`).toHaveLength(4);
      }
    }
  });
});

describe("t()", () => {
  it("interpolates {name} placeholders", () => {
    expect(createT("en")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — coming soon");
    expect(createT("uk")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — незабаром");
    expect(createT("ru")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — скоро");
  });

  it("picks English plural forms (one / other)", () => {
    const t = createT("en");
    expect(t("room.playersInRound", { count: 1 })).toBe("1 player in this round");
    expect(t("room.playersInRound", { count: 2 })).toBe("2 players in this round");
    expect(t("room.playersInRound", { count: 0 })).toBe("0 players in this round");
  });

  it("picks Ukrainian plural forms (one / few / many)", () => {
    const t = createT("uk");
    expect(t("room.playersInRound", { count: 1 })).toBe("1 гравець у цьому раунді");
    expect(t("room.playersInRound", { count: 3 })).toBe("3 гравці в цьому раунді");
    expect(t("room.playersInRound", { count: 5 })).toBe("5 гравців у цьому раунді");
    expect(t("room.playersInRound", { count: 11 })).toBe("11 гравців у цьому раунді");
    expect(t("room.playersInRound", { count: 21 })).toBe("21 гравець у цьому раунді");
    expect(t("room.playersInRound", { count: 22 })).toBe("22 гравці в цьому раунді");
  });

  it("picks Russian plural forms (one / few / many)", () => {
    const t = createT("ru");
    expect(t("room.playersInRound", { count: 1 })).toBe("1 игрок в этом раунде");
    expect(t("room.playersInRound", { count: 3 })).toBe("3 игрока в этом раунде");
    expect(t("room.playersInRound", { count: 5 })).toBe("5 игроков в этом раунде");
    expect(t("room.playersInRound", { count: 11 })).toBe("11 игроков в этом раунде");
    expect(t("room.playersInRound", { count: 21 })).toBe("21 игрок в этом раунде");
  });

  it("falls back to the key itself for an unknown key", () => {
    // @ts-expect-error — deliberately not a real key
    expect(createT("en")("nope.missing")).toBe("nope.missing");
  });
});

describe("detectLocale()", () => {
  function setLanguages(languages: string[]) {
    Object.defineProperty(window.navigator, "languages", { value: languages, configurable: true });
    Object.defineProperty(window.navigator, "language", { value: languages[0], configurable: true });
  }

  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
    setLanguages(["en-US"]);
  });

  it("defaults to English for jsdom's en-US", () => {
    setLanguages(["en-US"]);
    expect(detectLocale()).toBe("en");
  });

  it("maps uk-UA to Ukrainian and ru-RU to Russian", () => {
    setLanguages(["uk-UA"]);
    expect(detectLocale()).toBe("uk");
    setLanguages(["ru-RU"]);
    expect(detectLocale()).toBe("ru");
  });

  it("falls back to English for any other language", () => {
    setLanguages(["de-DE"]);
    expect(detectLocale()).toBe("en");
  });

  it("takes the first supported language of the browser's list", () => {
    setLanguages(["de-DE", "ru", "uk"]);
    expect(detectLocale()).toBe("ru");
  });

  it("a saved choice wins over the browser language", () => {
    setLanguages(["uk-UA"]);
    saveLocale("ru");
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("ru");
    expect(detectLocale()).toBe("ru");
  });

  it("ignores a saved value that is not a supported locale", () => {
    setLanguages(["uk-UA"]);
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "fr");
    expect(detectLocale()).toBe("uk");
  });

  it("survives storage throwing on read and on write", () => {
    setLanguages(["uk-UA"]);
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(detectLocale()).toBe("uk");
    expect(() => saveLocale("ru")).not.toThrow();
  });
});
