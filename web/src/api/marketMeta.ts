/**
 * Display metadata for markets and categories that the server does not send: the order and titles
 * of the lobby panels (Р-69, Т-1) and a glyph + colour for a symbol when a market has no source
 * image (`MarketDto.iconUrl === null`, every `PERP`). Pure data and pure functions — nothing here
 * is a source of truth for a market's parameters (those are `MarketDto`'s).
 */
import type { CategoryDto, CategoryId } from "./contract";

/**
 * The lobby panels in display order — Т-1 (`docs/research/markets_architecture.md` §2) lists
 * `crypto`, `politics`, `sports`, `stocks`, `indices`, `commodities`, and §9's category ribbon
 * "All · Crypto · Politics · Sports · Stocks · Indices · Commodities" agrees. (Т-1's prose says the
 * first three follow Alex's words in Р-69, whose order is sports, politics, crypto — the list is
 * what is implemented; change the order HERE only.)
 */
export const CATEGORY_ORDER: readonly CategoryId[] = ["crypto", "politics", "sports", "stocks", "indices", "commodities"];

export const CATEGORY_TITLES: Readonly<Record<CategoryId, string>> = {
  crypto: "Crypto",
  politics: "Politics",
  sports: "Sports",
  stocks: "Stocks",
  indices: "Indices",
  commodities: "Commodities",
};

export function categoryTitle(id: CategoryId): string {
  return CATEGORY_TITLES[id];
}

/** `categories` in Т-1 order, whatever order they arrived in (unknown ids, from a newer server, last). */
export function sortCategories(categories: readonly CategoryDto[]): CategoryDto[] {
  const rank = (id: string): number => {
    const i = (CATEGORY_ORDER as readonly string[]).indexOf(id);
    return i === -1 ? CATEGORY_ORDER.length : i;
  };
  return [...categories].sort((a, b) => rank(a.id) - rank(b.id));
}

export interface SymbolMeta {
  /** One or two characters to draw inside the market's round badge. */
  glyph: string;
  /** Badge colour (a CSS colour string), readable on a dark background. */
  color: string;
}

/** Known symbols: the crypto majors and the non-crypto perps of the default catalog. Brand-ish
 *  colours, not logos — a colour and a glyph carry no trademark. */
const KNOWN_SYMBOLS: Readonly<Record<string, SymbolMeta>> = {
  BTC: { glyph: "₿", color: "#f7931a" },
  ETH: { glyph: "Ξ", color: "#627eea" },
  SOL: { glyph: "◎", color: "#14c38e" },
  XRP: { glyph: "X", color: "#3b82c4" },
  DOGE: { glyph: "Ð", color: "#c2a633" },
  HYPE: { glyph: "H", color: "#4fd1c5" },
  NVDA: { glyph: "N", color: "#76b900" },
  TSLA: { glyph: "T", color: "#e0324d" },
  SP500: { glyph: "S", color: "#4c6ef5" },
  XYZ100: { glyph: "X", color: "#8b5cf6" },
  GOLD: { glyph: "Au", color: "#d4a017" },
  BRENTOIL: { glyph: "Br", color: "#7c6f64" },
};

function hashString(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * Glyph and colour for a card/badge: the known table above, otherwise a deterministic fallback —
 * the symbol's first one or two letters/digits and an HSL colour derived from a hash of the
 * symbol — so an `ODDS` market ("GER", "RUNOFF") or any future listing always gets the same,
 * distinct-looking badge on every screen and every reload.
 */
export function symbolMeta(symbol: string): SymbolMeta {
  const known = Object.hasOwn(KNOWN_SYMBOLS, symbol) ? KNOWN_SYMBOLS[symbol] : undefined;
  if (known) return known;
  const letters = symbol.replace(/[^A-Za-z0-9]/g, "");
  const glyph = (letters.length >= 3 ? letters.slice(0, 2) : letters.slice(0, 1)).toUpperCase() || "?";
  const hue = hashString(symbol) % 360;
  return { glyph, color: `hsl(${hue} 62% 52%)` };
}
