import { en } from "./en";
import { uk } from "./uk";
import { ru } from "./ru";
import { es } from "./es";
import { zh } from "./zh";
import { hi } from "./hi";
import type { Locale } from "./locale";

/** `en.ts` is the source of truth: its shape, with every leaf widened from a literal to `string`,
 *  is what `uk.ts` and `ru.ts` must match exactly. */
type Widen<T> = { [K in keyof T]: T[K] extends string ? string : Widen<T[K]> };
export type Dict = Widen<typeof en>;

type Paths<T, P extends string = ""> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Paths<T[K], `${P}${K}.`>;
}[keyof T & string];
/** Every dotted key path in the dictionary, e.g. `"lobby.title"`. */
export type TKey = Paths<typeof en>;

export type TVars = Record<string, string | number>;
export type TFn = (key: TKey, vars?: TVars) => string;

export const DICTIONARIES: Record<Locale, Dict> = { en, uk, ru, es, zh, hi };

function lookup(dict: Dict, key: string): string {
  let node: unknown = dict;
  for (const part of key.split(".")) {
    if (node === null || typeof node !== "object") return key;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === "string" ? node : key;
}

/** Plural categories, in the order a plural string lists its forms: en/es/hi `one|other`, uk and ru
 *  `one|few|many|other` (CLDR). */
const PLURAL_ORDER = ["one", "few", "many", "other"] as const;

function pluralForm(locale: Locale, count: number, forms: string[]): string {
  const category = new Intl.PluralRules(locale).select(count);
  if (forms.length === 2) return category === "one" ? forms[0] : forms[1];
  const index = PLURAL_ORDER.indexOf(category as (typeof PLURAL_ORDER)[number]);
  return forms[index === -1 ? forms.length - 1 : index] ?? forms[forms.length - 1];
}

/** Builds `t(key, vars?)` for one locale. `{name}` is replaced from `vars`. A string that holds
 *  several `|`-separated forms is a plural: the form is picked from `vars.count` by the locale's
 *  `Intl.PluralRules`. */
export function createT(locale: Locale): TFn {
  const dict = DICTIONARIES[locale];
  return (key, vars) => {
    let text = lookup(dict, key);
    if (vars && typeof vars.count === "number" && text.includes("|")) {
      text = pluralForm(locale, vars.count, text.split("|"));
    }
    if (!vars) return text;
    return text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
  };
}

/** English translator — the default for pure display models and for components rendered without
 *  an {@link I18nProvider} (existing tests run on it). */
export const tEn: TFn = createT("en");
