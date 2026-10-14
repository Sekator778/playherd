/** Interface languages. English is the source and the default (`en.ts`); the others are typed against
 *  its shape, so a missing or extra key fails `tsc`. */
export type Locale = "en" | "uk" | "ru" | "es" | "zh" | "hi";

export const LOCALES: readonly Locale[] = ["en", "uk", "ru", "es", "zh", "hi"];

/** Each language named in itself — what the footer switcher shows. */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: "English",
  uk: "Українська",
  ru: "Русский",
  es: "Español",
  zh: "中文（简体）",
  hi: "हिन्दी",
};

/** The BCP 47 tag for `<html lang>`: the script is named for Chinese, so the browser picks Simplified glyphs and fonts. */
export const HTML_LANG: Record<Locale, string> = {
  en: "en",
  uk: "uk",
  ru: "ru",
  es: "es",
  zh: "zh-Hans",
  hi: "hi",
};

export const LOCALE_STORAGE_KEY = "herd.locale";

function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

/** The locale a browser language tag asks for: `uk*` → uk, `ru*` → ru, `es*` → es, `zh*` (any script or region, so
 *  `zh-TW` too — Traditional text is not translated, Simplified is the nearest) → zh, `hi*` → hi; otherwise `null`. */
function localeOfTag(raw: string | null | undefined): Locale | null {
  const language = (raw ?? "").toLowerCase().split(/[-_]/)[0];
  return isLocale(language) && language !== "en" ? language : null;
}

/** Saved choice first; else the first browser language we have a translation for; anything else → en. Storage access
 *  can throw (private mode, blocked site data), so every read is guarded. */
export function detectLocale(): Locale {
  try {
    const saved = window.localStorage.getItem(LOCALE_STORAGE_KEY);
    if (isLocale(saved)) return saved;
  } catch {
    // Storage unavailable — fall through to the browser's language list.
  }
  const languages =
    typeof navigator === "undefined"
      ? []
      : navigator.languages && navigator.languages.length > 0
        ? navigator.languages
        : [navigator.language];
  for (const raw of languages) {
    const found = localeOfTag(raw);
    if (found) return found;
  }
  return "en";
}

export function saveLocale(locale: Locale): void {
  try {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // The choice then lasts for this page load only.
  }
}
