import { createContext, useContext } from "react";
import type { Locale } from "./locale";
import { createT, type TFn } from "./translate";

export interface I18nValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: TFn;
}

/** Without a provider the UI is English — which is what component tests rely on. */
export const I18nContext = createContext<I18nValue>({ locale: "en", setLocale: () => {}, t: createT("en") });

/** The locale the UI is currently showing, for code that runs outside React (an error thrown by
 *  the API layer that the UI then prints). Kept in sync by `I18nProvider`. */
let activeLocale: Locale = "en";

export function setActiveLocale(locale: Locale): void {
  activeLocale = locale;
}

/** Translate with whatever locale the UI is showing right now. */
export const tNow: TFn = (key, vars) => createT(activeLocale)(key, vars);

/** `t(key, vars?)` for the current locale. */
export function useT(): TFn {
  return useContext(I18nContext).t;
}

/** Current locale and its setter (the footer switcher). */
export function useLocale(): { locale: Locale; setLocale: (locale: Locale) => void } {
  const { locale, setLocale } = useContext(I18nContext);
  return { locale, setLocale };
}
