import { describe, expect, it } from "vitest";
import { crowdSplitOf, herdSideOf, isOpenMarket, kickoffOf, leadSeries, marketPool, nextCloseMs, roundPriceInfo, subtitleOf } from "./marketModel";
import { NOW, catalogue, market, seriesDto } from "./testFixtures";

describe("crowdSplitOf", () => {
  it("is the share of the stake on each side, whole percent, summing to 100", () => {
    expect(crowdSplitOf({ upStake: "3000.000", downStake: "1000.000" })).toEqual({ upPct: 75, downPct: 25, totalStake: 4000 });
    expect(crowdSplitOf({ upStake: "1.000", downStake: "2.000" })).toMatchObject({ upPct: 33, downPct: 67 });
  });

  it("is null while nothing is staked — never an invented 50/50", () => {
    expect(crowdSplitOf({ upStake: "0.000", downStake: "0.000" })).toBeNull();
    expect(crowdSplitOf({})).toBeNull();
  });
});

describe("herdSideOf", () => {
  it("takes the server's majorityDirection when it sends one, a tie included", () => {
    expect(herdSideOf({ majorityDirection: "DOWN", upStake: "900.000", downStake: "100.000" })).toBe("DOWN");
    expect(herdSideOf({ majorityDirection: null, upStake: "900.000", downStake: "100.000" })).toBeNull();
  });

  it("reads the stakes when a server does not send it; equal stakes are no herd", () => {
    expect(herdSideOf({ upStake: "900.000", downStake: "100.000" })).toBe("UP");
    expect(herdSideOf({ upStake: "100.000", downStake: "900.000" })).toBe("DOWN");
    expect(herdSideOf({ upStake: "100.000", downStake: "100.000" })).toBeNull();
    expect(herdSideOf({})).toBeNull();
  });
});

describe("market helpers", () => {
  const [btc, , , runoff, ger, old] = catalogue();

  it("a market is open unless it is CLOSED", () => {
    expect(isOpenMarket(btc)).toBe(true);
    expect(isOpenMarket(old)).toBe(false);
  });

  it("the lead series is the first (shortest) one", () => {
    expect(leadSeries(btc)?.durationSec).toBe(180);
    expect(leadSeries({ series: [] })).toBeUndefined();
  });

  it("the pool is both sides' stake in the lead series' open round", () => {
    expect(marketPool(btc)).toBe(4000);
    expect(marketPool(market("X"))).toBe(0);
    expect(marketPool({ series: [] })).toBe(0);
  });

  it("the next close is the earlier of the betting window and the event's own end", () => {
    expect(nextCloseMs(btc)).toBe(NOW + 75_000);
    expect(nextCloseMs(ger)).toBe(NOW + 30_000); // betting closes before the 40 min event end
    expect(nextCloseMs({ series: [seriesDto({ bettingClosesAtMs: NOW + 900_000 })], endsAtMs: NOW + 60_000 })).toBe(NOW + 60_000);
    expect(nextCloseMs(runoff)).toBe(NOW + 75_000);
  });
});

describe("kickoffOf — a sports game against its kick-off (MarketDto.eventStartsAtMs)", () => {
  it("before it: how long is left; at and after it: live", () => {
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW + 7_500_000 }, NOW)).toEqual({ state: "upcoming", inMs: 7_500_000 });
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW }, NOW)).toEqual({ state: "live" });
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW - 60_000 }, NOW)).toEqual({ state: "live" });
  });
  it("nothing for a market without a kick-off — null and absent alike", () => {
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: null }, NOW)).toBeNull();
    expect(kickoffOf({ status: "ACTIVE" }, NOW)).toBeNull();
  });
  it("nothing for a closed market: a card that is closed does not say the game is live", () => {
    expect(kickoffOf({ status: "CLOSED", eventStartsAtMs: NOW - 60_000 }, NOW)).toBeNull();
  });
});

describe("roundPriceInfo — a round prints its prices from ITS OWN fields, so a departed market reads right", () => {
  it("the round's own kind, price scale and source win over the market's", () => {
    expect(roundPriceInfo({ kind: "ODDS", priceDecimals: 6, source: "polymarket" }, { kind: "PERP", priceDecimals: 2, source: "hyperliquid" })).toEqual({
      format: { kind: "ODDS", priceDecimals: 6 },
      source: "polymarket",
    });
  });

  it("needs no market: that is the point (the market left markets() ten minutes after it closed)", () => {
    expect(roundPriceInfo({ kind: "PERP", priceDecimals: 7, source: "hyperliquid" })).toEqual({ format: { kind: "PERP", priceDecimals: 7 }, source: "hyperliquid" });
  });

  it("a server that does not send the fields yet: the market's, when it is still listed", () => {
    expect(roundPriceInfo({}, { kind: "ODDS", priceDecimals: 6, source: "polymarket" })).toEqual({ format: { kind: "ODDS", priceDecimals: 6 }, source: "polymarket" });
  });

  it("…and with no market either, the price scale is read off the price strings — and the source stays UNSAID, never 'Hyperliquid'", () => {
    expect(roundPriceInfo({ openPrice: "0.523000", closePrice: "0.534000" })).toEqual({ format: { kind: "PERP", priceDecimals: 6 }, source: undefined });
    expect(roundPriceInfo({ openPrice: null, closePrice: "84910.00" }).format.priceDecimals).toBe(2);
    expect(roundPriceInfo({}).format.priceDecimals).toBe(2); // nothing at all to read: cents
    expect(roundPriceInfo({}).source).toBeUndefined();
  });

  it("a garbage price scale is not used", () => {
    expect(roundPriceInfo({ priceDecimals: Number.NaN, openPrice: "1.5000" }).format.priceDecimals).toBe(4);
    expect(roundPriceInfo({ priceDecimals: -1 }, { kind: "PERP", priceDecimals: 3, source: "hyperliquid" }).format.priceDecimals).toBe(3);
    expect(roundPriceInfo({ priceDecimals: 2.5 }).format.priceDecimals).toBe(2);
  });
});

describe("subtitleOf — a subtitle that repeats its title says only the outcome", () => {
  const sub = (category: "sports" | "politics" | "crypto", title: string, subtitle: string | null) => subtitleOf({ category, title, subtitle });

  it("a team of a sports game: '<team> to win' — the real Steelers market of the live stand", () => {
    expect(sub("sports", "Steelers vs. Browns", "Steelers · Steelers vs. Browns")).toBe("Steelers to win");
    expect(sub("sports", "Philadelphia Phillies vs. Atlanta Braves", "Philadelphia Phillies · Philadelphia Phillies vs. Atlanta Braves")).toBe("Philadelphia Phillies to win");
  });

  it("a yes/no or draw/over/under outcome is never made 'to win'", () => {
    expect(sub("sports", "Will the Steelers win?", "Yes · Will the Steelers win?")).toBe("Yes");
    expect(sub("sports", "Germany vs. Serbia", "Draw · Germany vs. Serbia")).toBe("Draw");
    expect(sub("sports", "Germany vs. Serbia", "Over · Germany vs. Serbia")).toBe("Over");
  });

  it("a politics market that repeats its question: the outcome alone ('Yes'), the question stays under it", () => {
    expect(sub("politics", "Will the U.S. invade Iran before 2027?", "Yes · Will the U.S. invade Iran before 2027?")).toBe("Yes");
  });

  it("compares ignoring case and spacing; anything that is NOT the title is shown as the server sent it", () => {
    expect(sub("sports", " steelers vs. browns ", "Steelers ·  STEELERS VS. BROWNS")).toBe("Steelers to win");
    expect(sub("politics", "Will Lula win the 2026 Brazilian presidential election?", "Yes · Brazil Presidential Election")).toBe("Yes · Brazil Presidential Election");
    expect(sub("sports", "Philadelphia Phillies vs. Atlanta Braves: O/U 8.5", "Over · Philadelphia Phillies vs. Atlanta Braves")).toBe("Over · Philadelphia Phillies vs. Atlanta Braves");
    expect(sub("sports", "Germany vs. Serbia", "Germany · UEFA Nations League")).toBe("Germany · UEFA Nations League");
  });

  it("no subtitle (a perp), a blank one, or one with no context part: as is, or null", () => {
    expect(sub("crypto", "Bitcoin", null)).toBeNull();
    expect(sub("sports", "X vs. Y", "   ")).toBeNull();
    expect(sub("sports", "X vs. Y", "Draw")).toBe("Draw");
  });
});
