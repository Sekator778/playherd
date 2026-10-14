import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { en } from "./en";
import { uk } from "./uk";
import { ru } from "./ru";
import { es } from "./es";
import { zh } from "./zh";
import { hi } from "./hi";
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

const FLAT = {
  en: flatten(en as unknown as Tree),
  uk: flatten(uk as unknown as Tree),
  ru: flatten(ru as unknown as Tree),
  es: flatten(es as unknown as Tree),
  zh: flatten(zh as unknown as Tree),
  hi: flatten(hi as unknown as Tree),
};
const OTHERS = ["uk", "ru", "es", "zh", "hi"] as const;
/** How many `|`-separated forms a plural string has per language (CLDR: en/es/hi one|other, uk/ru one|few|many|other, zh none). */
const FORMS = { en: 2, uk: 4, ru: 4, es: 2, zh: 1, hi: 2 } as const;

const placeholders = (s: string) => [...new Set(s.match(/\{\w+\}/g) ?? [])].sort();

describe("dictionaries", () => {
  it("every language has exactly the keys of en (tsc checks it too; this guards the runtime shape)", () => {
    const keys = Object.keys(FLAT.en).sort();
    for (const locale of OTHERS) expect(Object.keys(FLAT[locale]).sort(), locale).toEqual(keys);
  });

  it("no value is empty in any language", () => {
    for (const locale of ["en", ...OTHERS] as const) {
      for (const [key, value] of Object.entries(FLAT[locale])) {
        expect(value.trim(), `${locale}:${key}`).not.toBe("");
      }
    }
  });

  it("every translation uses the same {placeholders} as the English text", () => {
    for (const [key, value] of Object.entries(FLAT.en)) {
      for (const locale of OTHERS) expect(placeholders(FLAT[locale][key]), `${locale}:${key}`).toEqual(placeholders(value));
    }
  });

  it("every translation keeps the same **bold** and [[link]] markup as the English text", () => {
    for (const [key, value] of Object.entries(FLAT.en)) {
      for (const locale of OTHERS) {
        expect(FLAT[locale][key].split("**").length, `${locale}:${key}`).toBe(value.split("**").length);
        expect(FLAT[locale][key].split("[[").length, `${locale}:${key}`).toBe(value.split("[[").length);
      }
    }
  });

  it("plural strings carry the number of forms the language has (en/es/hi 2, uk/ru 4, zh 1)", () => {
    for (const [key, value] of Object.entries(FLAT.en)) {
      const isPlural = value.includes("|");
      for (const locale of ["en", ...OTHERS] as const) {
        expect(FLAT[locale][key].split("|"), `${locale}:${key}`).toHaveLength(isPlural ? FORMS[locale] : 1);
      }
    }
  });
});

describe("t()", () => {
  it("interpolates {name} placeholders", () => {
    expect(createT("en")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — coming soon");
    expect(createT("uk")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — незабаром");
    expect(createT("ru")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — скоро");
    expect(createT("es")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH: próximamente");
    expect(createT("zh")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — 即将上线");
    expect(createT("hi")("room.comingSoonTitle", { symbol: "ETH" })).toBe("ETH — जल्द आ रहा है");
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

  it("picks Spanish plural forms (one / other)", () => {
    const t = createT("es");
    expect(t("room.playersInRound", { count: 1 })).toBe("1 jugador en esta ronda");
    expect(t("room.playersInRound", { count: 2 })).toBe("2 jugadores en esta ronda");
    expect(t("room.playersInRound", { count: 0 })).toBe("0 jugadores en esta ronda");
    expect(t("lobby.count", { count: 21 })).toBe("21 mercados");
  });

  it("picks Hindi plural forms (one covers 0 and 1, other the rest)", () => {
    const t = createT("hi");
    expect(t("lobby.count", { count: 1 })).toBe("1 बाज़ार");
    expect(t("lobby.count", { count: 0 })).toBe("0 बाज़ार");
    expect(t("lobby.tabAria", { title: "क्रिप्टो", count: 1 })).toBe("क्रिप्टो, 1 खुला बाज़ार");
    expect(t("lobby.tabAria", { title: "क्रिप्टो", count: 5 })).toBe("क्रिप्टो, 5 खुले बाज़ार");
  });

  it("Chinese has one string that reads right with any number", () => {
    const t = createT("zh");
    for (const count of [0, 1, 2, 5, 21]) expect(t("room.playersInRound", { count })).toBe(`本回合 ${count} 名玩家`);
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

  it("maps es-MX to Spanish, zh-CN and zh-TW to Chinese, hi-IN to Hindi", () => {
    for (const [tag, locale] of [["es-MX", "es"], ["es", "es"], ["zh-CN", "zh"], ["zh-TW", "zh"], ["zh-Hans-CN", "zh"], ["hi-IN", "hi"]] as const) {
      setLanguages([tag]);
      expect(detectLocale(), tag).toBe(locale);
    }
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
