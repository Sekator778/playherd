/** Interface languages. English is the source and the default (`en.ts`); `uk` and `ru` are typed
 *  against its shape (`uk.ts`, `ru.ts`), so a missing or extra key fails `tsc`. */
export type Locale = "en" | "uk" | "ru";

export const LOCALES: readonly Locale[] = ["en", "uk", "ru"];

/** Each language named in itself — what the footer switcher shows. */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: "English",
  uk: "Українська",
  ru: "Русский",
};

export const LOCALE_STORAGE_KEY = "herd.locale";

function isLocale(value: unknown): value is Locale {
  return value === "en" || value === "uk" || value === "ru";
}

/** Saved choice first; else the first browser language we have a translation for (`uk*` → uk,
 *  `ru*` → ru); anything else → en. Storage access can throw (private mode, blocked site data),
 *  so every read is guarded. */
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
    const tag = (raw ?? "").toLowerCase();
    if (tag === "uk" || tag.startsWith("uk-")) return "uk";
    if (tag === "ru" || tag.startsWith("ru-")) return "ru";
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
