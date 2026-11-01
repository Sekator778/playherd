import { describe, expect, it } from "vitest";
import type { CategoryDto } from "./contract";
import { CATEGORY_ORDER, CATEGORY_TITLES, categoryTitle, sortCategories, symbolMeta } from "./marketMeta";

describe("categories (T-1)", () => {
  it("are in the order of T-1 / the architecture's ribbon, each with a title", () => {
    expect(CATEGORY_ORDER).toEqual(["crypto", "politics", "sports", "stocks", "indices", "commodities"]);
    expect(CATEGORY_ORDER.map(categoryTitle)).toEqual(["Crypto", "Politics", "Sports", "Stocks", "Indices", "Commodities"]);
    expect(Object.keys(CATEGORY_TITLES).sort()).toEqual([...CATEGORY_ORDER].sort());
  });

  it("sortCategories puts any arrival order into T-1 order, unknown ids last", () => {
    const cat = (id: string): CategoryDto => ({ id: id as CategoryDto["id"], title: id, marketCount: 1 });
    const sorted = sortCategories([cat("commodities"), cat("sports"), cat("weather"), cat("crypto"), cat("indices"), cat("politics")]);
    expect(sorted.map((c) => c.id)).toEqual(["crypto", "politics", "sports", "indices", "commodities", "weather"]);
  });

  it("sortCategories does not mutate its input", () => {
    const input: CategoryDto[] = [
      { id: "sports", title: "Sports", marketCount: 1 },
      { id: "crypto", title: "Crypto", marketCount: 1 },
    ];
    sortCategories(input);
    expect(input.map((c) => c.id)).toEqual(["sports", "crypto"]);
  });
});

describe("symbolMeta — a glyph and a colour for any symbol", () => {
  it("knows the crypto majors and the non-crypto perps of the default catalog", () => {
    expect(symbolMeta("BTC")).toEqual({ glyph: "₿", color: "#f7931a" });
    expect(symbolMeta("ETH").glyph).toBe("Ξ");
    for (const s of ["BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE", "NVDA", "TSLA", "SP500", "XYZ100", "GOLD", "BRENTOIL"]) {
      expect(symbolMeta(s).glyph.length).toBeGreaterThan(0);
      expect(symbolMeta(s).color).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it("falls back deterministically: the same symbol always gets the same badge", () => {
    expect(symbolMeta("GER")).toEqual(symbolMeta("GER"));
    expect(symbolMeta("RUNOFF")).toEqual(symbolMeta("RUNOFF"));
  });

  it("the fallback glyph is the symbol's first letters, upper-cased, and the colour is an hsl()", () => {
    expect(symbolMeta("GER").glyph).toBe("GE");
    expect(symbolMeta("RUNOFF").glyph).toBe("RU");
    expect(symbolMeta("lula").glyph).toBe("LU");
    expect(symbolMeta("X").glyph).toBe("X");
    expect(symbolMeta("GER").color).toMatch(/^hsl\(\d{1,3} 62% 52%\)$/);
  });

  it("different symbols mostly get different colours", () => {
    const colors = new Set(["GER", "POR", "NOR", "RUNOFF", "SHUTDOWN", "TURNOUT", "PEPE", "WIF", "BONK", "ARB"].map((s) => symbolMeta(s).color));
    expect(colors.size).toBeGreaterThanOrEqual(8);
  });

  it("copes with an empty or odd symbol, and with names that live on Object.prototype", () => {
    expect(symbolMeta("").glyph).toBe("?");
    expect(symbolMeta("---").glyph).toBe("?");
    expect(symbolMeta("constructor").glyph).toBe("CO");
    expect(symbolMeta("__proto__").glyph).toBe("PR");
  });
});
