import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { I18nContext, setActiveLocale, type I18nValue } from "./context";
import { HTML_LANG, detectLocale, saveLocale, type Locale } from "./locale";
import { createT } from "./translate";

/** Holds the current locale (saved choice, else the browser's language), keeps `<html lang>` in
 *  sync and re-renders everything below it when the footer switcher changes it. */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => detectLocale());

  useEffect(() => {
    setActiveLocale(locale);
    document.documentElement.lang = HTML_LANG[locale];
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    saveLocale(next);
    setLocaleState(next);
  }, []);

  const value = useMemo<I18nValue>(() => ({ locale, setLocale, t: createT(locale) }), [locale, setLocale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
