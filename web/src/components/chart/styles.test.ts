import { describe, expect, it } from "vitest";
import { allowedStyles, bucketMsFor, defaultStyle, isChartStyle, resolveStyle } from "./styles";

describe("which looks a market allows", () => {
  it("a price market: line and candles, opening on candles", () => {
    expect(allowedStyles("PERP")).toEqual(["line", "candles"]);
    expect(defaultStyle("PERP")).toBe("candles");
  });

  it("an odds market: the line only", () => {
    expect(allowedStyles("ODDS")).toEqual(["line"]);
    expect(defaultStyle("ODDS")).toBe("line");
  });

  it("the default is always one of the allowed looks", () => {
    for (const kind of ["PERP", "ODDS"] as const) expect(allowedStyles(kind)).toContain(defaultStyle(kind));
  });
});

describe("resolveStyle", () => {
  it("a stored look the market allows is used", () => {
    expect(resolveStyle("PERP", "line")).toBe("line");
    expect(resolveStyle("PERP", "candles")).toBe("candles");
  });

  it("a stored look the market does not allow falls back to the market's default", () => {
    expect(resolveStyle("ODDS", "candles")).toBe("line");
  });

  it("nothing stored, or something that is not a look, falls back to the default", () => {
    expect(resolveStyle("PERP", null)).toBe("candles");
    expect(resolveStyle("PERP", "bars")).toBe("candles");
    expect(resolveStyle("PERP", 3)).toBe("candles");
    expect(isChartStyle("line")).toBe(true);
    expect(isChartStyle("bars")).toBe(false);
  });
});

describe("bucketMsFor", () => {
  it("1M → 5 s, 5M → 15 s, 15M → 30 s; no chip yet (first minute) → 5 s", () => {
    expect(bucketMsFor("1M")).toBe(5_000);
    expect(bucketMsFor("5M")).toBe(15_000);
    expect(bucketMsFor("15M")).toBe(30_000);
    expect(bucketMsFor(null)).toBe(5_000);
  });
});
