import { describe, expect, it } from "vitest";
import {
  backupPriceNote,
  backupPriceOf,
  backupRoundNote,
  backupRoundOf,
  crowdSplitOf,
  defaultMarket,
  herdSideOf,
  isOpenMarket,
  kickoffOf,
  leadSeries,
  marketPool,
  nextCloseMs,
  oddsMarketPhrase,
  originPhrase,
  roundPriceInfo,
  sourceName,
  subtitleOf,
  subtitleParts,
} from "./marketModel";
import { NOW, catalogue, market, seriesDto, spotCatalogue } from "./testFixtures";

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

describe("defaultMarket — what a screen opens on when nothing says which market", () => {
  const spot = spotCatalogue(); // spot_BTC, spot_ETH, spot_DOGE, spot_HYPE open; the legacy BTC perpetual closed, last in the list
  const ids = (list: ReturnType<typeof spotCatalogue>) => defaultMarket(list)?.id;

  it("is the open market whose symbol is BTC: spot_BTC once the legacy BTC perpetual was replaced and closed", () => {
    expect(ids(spot)).toBe("spot_BTC");
    expect(ids([...spot].reverse())).toBe("spot_BTC"); // the order of the list does not decide
  });

  it("is the legacy BTC on a server that never replaced it, and never a closed BTC", () => {
    expect(ids(catalogue())).toBe("BTC");
    expect(ids(spot.filter((m) => m.id === "BTC" || m.id === "spot_ETH"))).toBe("spot_ETH"); // BTC closed: the first open crypto market
  });

  it("is the first open crypto market when none of them is BTC", () => {
    expect(ids(spot.filter((m) => m.id !== "spot_BTC"))).toBe("spot_ETH");
    expect(ids([market("xyz_NVDA", { category: "stocks", symbol: "NVDA" }), ...spot.filter((m) => m.id === "spot_DOGE")])).toBe("spot_DOGE");
  });

  it("is nothing when no crypto market is open — the caller decides what then", () => {
    expect(ids([])).toBeUndefined();
    expect(ids(spot.filter((m) => m.id === "BTC"))).toBeUndefined();
    expect(ids([market("xyz_NVDA", { category: "stocks", symbol: "NVDA" })])).toBeUndefined();
  });

  it("a market of another category with the symbol BTC is no bitcoin pair", () => {
    const lookalike = market("PM_BTC", { category: "politics", symbol: "BTC" });
    expect(ids([lookalike, ...spot])).toBe("spot_BTC");
    expect(ids([lookalike])).toBeUndefined();
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

describe("subtitleParts — what the percentage is OF, and the context a narrow card may cut (first tester pass, D7)", () => {
  const parts = (category: "sports" | "politics", title: string, subtitle: string | null) => subtitleParts({ category, title, subtitle });

  it("outcome first, context after: 'Spain to win' · 'UEFA Nations League'", () => {
    expect(parts("sports", "Will Spain win on 2026-10-06?", "Spain to win · UEFA Nations League")).toEqual({ outcome: "Spain to win", context: "UEFA Nations League" });
    expect(parts("sports", "Germany vs. Serbia", "Germany · UEFA Nations League")).toEqual({ outcome: "Germany", context: "UEFA Nations League" });
  });

  it("a subtitle that repeated its title is only the outcome ('Steelers to win'), with no context", () => {
    expect(parts("sports", "Steelers vs. Browns", "Steelers · Steelers vs. Browns")).toEqual({ outcome: "Steelers to win", context: null });
    expect(parts("politics", "Will the U.S. invade Iran before 2027?", "Yes · Will the U.S. invade Iran before 2027?")).toEqual({ outcome: "Yes", context: null });
  });

  it("splits at the first ' · ' only", () => {
    expect(parts("sports", "A vs. B", "A to win · ATP · Tokyo")).toEqual({ outcome: "A to win", context: "ATP · Tokyo" });
  });

  it("no subtitle: nothing", () => {
    expect(parts("sports", "X vs. Y", null)).toBeNull();
    expect(parts("sports", "X vs. Y", "  ")).toBeNull();
  });
});

describe("sourceName / originPhrase — every source the UI names is named, none is 'Hyperliquid' by default", () => {
  it("names the five sources as a player knows them", () => {
    expect(sourceName("coinbase")).toBe("Coinbase");
    expect(sourceName("kraken")).toBe("Kraken");
    expect(sourceName("hyperliquid")).toBe("Hyperliquid");
    expect(sourceName("polymarket")).toBe("Polymarket");
    expect(sourceName("synthetic")).toBe("Simulated");
  });

  it("a source this build does not know is printed as the server sent it (capitalised) — never as another source", () => {
    expect(sourceName("bybit")).toBe("Bybit");
    expect(sourceName(" okx ")).toBe("Okx");
    expect(sourceName("constructor")).toBe("Constructor"); // not a lookup into Object.prototype
    expect(sourceName("toString")).toBe("ToString");
  });

  it("no name at all is null — a page then claims no source", () => {
    expect(sourceName(undefined)).toBeNull();
    expect(sourceName(null)).toBeNull();
    expect(sourceName("")).toBeNull();
    expect(sourceName("   ")).toBeNull();
    expect(originPhrase(undefined)).toBeNull();
    expect(originPhrase(null)).toBeNull();
  });

  it("the disclosure phrases: price from an exchange, odds from Polymarket, simulated demo prices", () => {
    expect(originPhrase("coinbase")).toBe("Price from Coinbase");
    expect(originPhrase("kraken")).toBe("Price from Kraken");
    expect(originPhrase("hyperliquid")).toBe("Price from Hyperliquid");
    expect(originPhrase("polymarket")).toBe("Odds from Polymarket");
    expect(originPhrase("synthetic")).toBe("Simulated demo prices (no live source)");
    expect(originPhrase("bybit")).toBe("Price from Bybit");
  });

  it("an odds tag names whose odds they are only when the market says", () => {
    expect(oddsMarketPhrase("polymarket")).toBe("A Polymarket odds market");
    expect(oddsMarketPhrase("synthetic")).toBe("A simulated odds market");
    expect(oddsMarketPhrase(undefined)).toBe("An odds market");
  });
});

describe("backupPriceOf — the shown price is the backup exchange's (MarketPriceDto.venue)", () => {
  const [, eth, , hype] = spotCatalogue();
  const [btc] = spotCatalogue();

  it("a venue other than the market's own says which exchange the number is from, and whose it stands in for", () => {
    expect(backupPriceOf(eth)).toEqual({ name: "Kraken", primaryName: "Coinbase" });
    expect(backupPriceOf({ source: "hyperliquid", price: { price: "87.235", asOfMs: NOW, change24h: null, stale: false, venue: "coinbase" } })).toEqual({
      name: "Coinbase",
      primaryName: "Hyperliquid",
    });
  });

  it("the main exchange's price is nothing to mark: a null venue, an absent one, one equal to the market's own source, no price", () => {
    expect(backupPriceOf(btc)).toBeNull(); // venue: null
    expect(backupPriceOf(hype)).toBeNull();
    expect(backupPriceOf(market("X"))).toBeNull(); // an older server sends no venue at all
    expect(backupPriceOf({ source: "coinbase", price: { price: "1.00", asOfMs: NOW, change24h: null, stale: false, venue: "coinbase" } })).toBeNull();
    expect(backupPriceOf({ source: "coinbase", price: null })).toBeNull();
  });

  it("says why in one sentence", () => {
    expect(backupPriceNote({ name: "Kraken", primaryName: "Coinbase" })).toBe("Coinbase has no fresh price right now, so this is Kraken's — its backup.");
    expect(backupPriceNote({ name: "Kraken", primaryName: null })).toBe("The main exchange has no fresh price right now, so this is Kraken's — its backup.");
  });
});

describe("backupRoundOf — a round that runs on its market's backup exchange (RoundBase.source, Р-76)", () => {
  const [btc, , , hype] = spotCatalogue();

  it("a round on the backup says which exchange, and which main exchange could not price its start", () => {
    expect(backupRoundOf({ source: "kraken" }, btc)).toEqual({ name: "Kraken", primaryName: "Coinbase" });
    expect(backupRoundOf({ source: "coinbase" }, hype)).toEqual({ name: "Coinbase", primaryName: "Hyperliquid" });
  });

  it("a round on the main exchange says nothing", () => {
    expect(backupRoundOf({ source: "coinbase" }, btc)).toBeNull();
    expect(backupRoundOf({ source: "hyperliquid" }, hype)).toBeNull();
  });

  it("and nothing is said when it cannot be known: no market on the list, a market with no backup, a round naming no or another exchange", () => {
    expect(backupRoundOf({ source: "kraken" }, undefined)).toBeNull();
    expect(backupRoundOf({ source: "kraken" }, market("PERP"))).toBeNull(); // a perp has no backup
    expect(backupRoundOf({}, btc)).toBeNull();
    expect(backupRoundOf({ source: null }, btc)).toBeNull();
    expect(backupRoundOf({ source: "polymarket" }, btc)).toBeNull();
  });

  it("the note is in the tense of the round's phase, and says whose prices they are", () => {
    const kraken = { name: "Kraken", primaryName: "Coinbase" };
    expect(backupRoundNote(kraken, "RUNNING")).toBe(
      "This round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.",
    );
    expect(backupRoundNote(kraken, "SETTLED")).toBe(
      "This round ran on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation were Kraken's.",
    );
  });

  it("in a room — which also shows the round open for bets, whose exchange is not chosen yet — it is about the live round, not 'this' one", () => {
    const kraken = { name: "Kraken", primaryName: "Coinbase" };
    expect(backupRoundNote(kraken, "RUNNING", "live")).toBe(
      "The live round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.",
    );
    expect(backupRoundNote(kraken, "SETTLED", "live")).toBe(
      "The round that just ended ran on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation were Kraken's.",
    );
  });
});
