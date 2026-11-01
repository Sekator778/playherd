import { describe, expect, it } from "vitest";
import { DAY_MS, HOUR_MS, SIM_BASE_MS, windowFor } from "./clock";
import { MOCK_MARKETS, MOCK_NEW_LISTING_ID, mockMarketById, perpPriceDecimals, type MockMarketSpec } from "./mockMarkets";
import { TICK_MS, change24hAt, ohlcFor, priceAt, priceStrAt, ticksBetween } from "./mockPrices";
import { rngFor } from "./rng";

const BTC = mockMarketById("BTC")!;
const ODDS = MOCK_MARKETS.filter((m) => m.kind === "ODDS");
const PERPS = MOCK_MARKETS.filter((m) => m.kind === "PERP");

function fractionDigits(price: string): number {
  return price.split(".")[1]?.length ?? 0;
}

describe("the market catalog (docs/research/markets_architecture.md Т-3, Т-4)", () => {
  it("has 19 markets in 6 categories: 6 crypto, 2 stocks, 2 indices, 2 commodities, 4 politics, 3 sports", () => {
    expect(MOCK_MARKETS).toHaveLength(19);
    const count = (category: string) => MOCK_MARKETS.filter((m) => m.category === category).length;
    expect(["crypto", "stocks", "indices", "commodities", "politics", "sports"].map(count)).toEqual([6, 2, 2, 2, 4, 3]);
    expect(MOCK_MARKETS.filter((m) => m.kind === "ODDS").every((m) => m.category === "politics" || m.category === "sports")).toBe(true);
  });

  it("market ids are unique and match [A-Za-z0-9_]{1,32}; symbols are card tickers", () => {
    expect(new Set(MOCK_MARKETS.map((m) => m.id)).size).toBe(19);
    for (const m of MOCK_MARKETS) expect(m.id).toMatch(/^[A-Za-z0-9_]{1,32}$/);
    expect(PERPS.map((m) => m.symbol)).toEqual(["BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE", "NVDA", "TSLA", "SP500", "XYZ100", "GOLD", "BRENTOIL"]);
  });

  it("PERP priceDecimals follow Т-3 at the listing price (BTC fixed at 2), maxLeverage is min(40, Hyperliquid's)", () => {
    for (const m of PERPS) {
      expect(m.priceDecimals).toBe(m.id === "BTC" ? 2 : perpPriceDecimals(m.startPrice));
      expect(m.maxLeverage).toBeGreaterThanOrEqual(2);
      expect(m.maxLeverage).toBeLessThanOrEqual(40);
    }
    expect(perpPriceDecimals(83451)).toBe(2);
    expect(perpPriceDecimals(150.12)).toBe(3);
    expect(perpPriceDecimals(1.4964)).toBe(5);
    expect(perpPriceDecimals(0.094534)).toBe(7);
    const byId = (id: string) => mockMarketById(id)!;
    expect([byId("BTC"), byId("ETH"), byId("SOL"), byId("XRP"), byId("DOGE"), byId("HYPE")].map((m) => m.maxLeverage)).toEqual([40, 25, 20, 20, 10, 10]);
    expect(byId("xyz_SP500").maxLeverage).toBe(40); // Hyperliquid says 50, Т-4 caps at 40
  });

  it("ODDS markets: priceDecimals 6, maxLeverage 10, a question, a subtitle — an end date (politics) or a kick-off (sports)", () => {
    expect(ODDS).toHaveLength(7);
    for (const m of ODDS) {
      expect(m.priceDecimals).toBe(6);
      expect(m.maxLeverage).toBe(10);
      expect(m.title.endsWith("?")).toBe(true);
      expect(m.subtitle).toMatch(/·/);
      if (m.category === "sports") {
        // a game's source "end date" is its kick-off (`MarketDto.endsAtMs` / `eventStartsAtMs`)
        expect(m.endsAtMs).toBeNull();
        expect(m.eventStartsAtMs).toBeGreaterThan(SIM_BASE_MS);
      } else {
        expect(m.endsAtMs).toBeGreaterThan(SIM_BASE_MS);
        expect(m.eventStartsAtMs).toBeNull();
      }
    }
  });

  it("the oracle index follows the listing order, BTC first as 0 (Т-9)", () => {
    expect(MOCK_MARKETS.map((m) => m.oracleIndex)).toEqual(MOCK_MARKETS.map((_, i) => i));
    expect(BTC.oracleIndex).toBe(0);
  });

  it("exactly one market closes during a session — a sports game that kicks off first — and says why", () => {
    const closing = MOCK_MARKETS.filter((m) => m.closesAtMs !== null);
    expect(closing).toHaveLength(1);
    expect(closing[0].kind).toBe("ODDS");
    expect(closing[0].closeReason).toBe("The source market ended");
    expect(closing[0].eventStartsAtMs!).toBeGreaterThan(SIM_BASE_MS); // not started when the session opens…
    expect(closing[0].eventStartsAtMs!).toBeLessThan(closing[0].closesAtMs!); // …live for a while, then closed
    expect(closing[0].closesAtMs).toBeGreaterThan(SIM_BASE_MS);
  });

  it("exactly one market was listed moments ago: it has no price from before that, so no opening price for the running round", () => {
    const fresh = MOCK_MARKETS.filter((m) => m.priceFromMs !== null);
    expect(fresh.map((m) => m.id)).toEqual([MOCK_NEW_LISTING_ID]);
    expect(fresh[0].priceFromMs!).toBeLessThan(SIM_BASE_MS); // there is a price when the session opens…
    expect(fresh[0].priceFromMs!).toBeGreaterThan(windowFor("HOURLY", SIM_BASE_MS).startMs); // …but the round window began before it
  });
});

describe("priceAt — a pure, seeded function of time", () => {
  it("returns the same price for the same instant, however often it is asked", () => {
    for (const m of MOCK_MARKETS) {
      const t = SIM_BASE_MS + 123_456_789;
      expect(priceAt(m, t)).toBe(priceAt(m, t));
      expect(priceStrAt(m, t)).toBe(priceStrAt(m, t));
    }
  });

  it("every price string carries exactly priceDecimals fractional digits", () => {
    for (const m of MOCK_MARKETS) {
      for (let k = 0; k < 20; k++) {
        const price = priceStrAt(m, SIM_BASE_MS + k * 7_919_000);
        expect(fractionDigits(price)).toBe(m.priceDecimals);
      }
    }
  });

  it("PERP prices stay positive and near their starting level across a simulated fortnight", () => {
    for (const m of PERPS) {
      for (let t = SIM_BASE_MS - 7 * DAY_MS; t <= SIM_BASE_MS + 7 * DAY_MS; t += 3 * HOUR_MS + 17_000) {
        const ratio = priceAt(m, t) / m.startPrice;
        expect(ratio).toBeGreaterThan(0.4);
        expect(ratio).toBeLessThan(2.5);
      }
    }
  });

  it("ODDS prices never leave 0.10-0.90 (checked every 7 minutes over 20 simulated days)", () => {
    for (const m of ODDS) {
      let min = 1;
      let max = 0;
      for (let t = SIM_BASE_MS - 10 * DAY_MS; t <= SIM_BASE_MS + 10 * DAY_MS; t += 7 * 60_000 + 3_000) {
        const p = priceAt(m, t);
        min = Math.min(min, p);
        max = Math.max(max, p);
      }
      expect(min).toBeGreaterThanOrEqual(0.1);
      expect(max).toBeLessThanOrEqual(0.9);
    }
  });

  it("BTC keeps the mock's old day-to-day walk: its daily anchors are 83 451 · exp(cumulated seeded returns)", () => {
    // Independent re-derivation of the anchor on day 1: day 1's log-return, same seed and formula as the old `btcPriceAt`.
    const rng = rngFor("btc-daily-return", 1);
    const a = rng();
    const b = rng();
    const anchorDay1 = 83_451 * Math.exp((a + b - 1) * 0.035 + 0.0008);
    const price = priceAt(BTC, SIM_BASE_MS + DAY_MS);
    // the finer octaves (hour, 10 min, minute, tick) add at most ~1% of texture on top
    expect(Math.abs(price / anchorDay1 - 1)).toBeLessThan(0.011);
  });

  it("change24h is the relative move over the last 24 simulated hours — null for a market with no price from that long ago", () => {
    for (const m of MOCK_MARKETS) {
      const t = SIM_BASE_MS + 5 * HOUR_MS;
      if (m.id === MOCK_NEW_LISTING_ID) {
        expect(change24hAt(m, t)).toBeNull();
        continue;
      }
      expect(change24hAt(m, t)).toBeCloseTo(priceAt(m, t) / priceAt(m, t - DAY_MS) - 1, 12);
    }
  });
});

describe("ticksBetween — the mock's priceSeries", () => {
  const ETH = mockMarketById("ETH")!;

  it("lists ticks on the 3 s grid, oldest first, after (exclusive) .. until (inclusive)", () => {
    const after = SIM_BASE_MS - 20_000;
    const until = SIM_BASE_MS + 10_000;
    const ticks = ticksBetween(ETH, after, until, 600);
    expect(ticks.length).toBeGreaterThan(0);
    for (let i = 0; i < ticks.length; i++) {
      expect(ticks[i].atMs % TICK_MS).toBe(0);
      expect(ticks[i].atMs).toBeGreaterThan(after);
      expect(ticks[i].atMs).toBeLessThanOrEqual(until);
      if (i > 0) expect(ticks[i].atMs - ticks[i - 1].atMs).toBe(TICK_MS);
    }
    expect(ticks[ticks.length - 1].atMs).toBe(Math.floor(until / TICK_MS) * TICK_MS);
  });

  it("is STRICTLY after `after`: a tick exactly at it is never returned again (the poll already holds it)", () => {
    const onGrid = Math.floor(SIM_BASE_MS / TICK_MS) * TICK_MS;
    const ticks = ticksBetween(ETH, onGrid, onGrid + 5 * TICK_MS, 600);
    expect(ticks.map((t) => t.atMs)).toEqual([1, 2, 3, 4, 5].map((k) => onGrid + k * TICK_MS));
  });

  it("when more than `max` exist, returns `max` of them thinned EVENLY across the whole range — both ends included", () => {
    const after = SIM_BASE_MS - DAY_MS;
    const ticks = ticksBetween(ETH, after, SIM_BASE_MS, 600);
    expect(ticks).toHaveLength(600);
    const newest = Math.floor(SIM_BASE_MS / TICK_MS) * TICK_MS;
    const oldest = (Math.floor(after / TICK_MS) + 1) * TICK_MS;
    expect(ticks[0].atMs).toBe(oldest); // the range's start is still on the chart…
    expect(ticks[ticks.length - 1].atMs).toBe(newest); // …and so is the newest tick
    // evenly: every gap is the same number of grid steps, give or take the rounding of one step
    const total = (newest - oldest) / TICK_MS;
    const gaps = ticks.slice(1).map((t, i) => (t.atMs - ticks[i].atMs) / TICK_MS);
    for (const gap of gaps) {
      expect(gap).toBeGreaterThanOrEqual(Math.floor(total / 599));
      expect(gap).toBeLessThanOrEqual(Math.ceil(total / 599));
    }
    expect(ticks.every((t) => t.atMs % TICK_MS === 0)).toBe(true);
  });

  it("a thinned tick has the price the dense series has at that instant", () => {
    const thinned = ticksBetween(ETH, SIM_BASE_MS - DAY_MS, SIM_BASE_MS, 600);
    for (const t of thinned.slice(0, 40)) expect(t.price).toBe(priceStrAt(ETH, t.atMs));
  });

  it("never returns a tick from before the market had a price", () => {
    const listed = mockMarketById(MOCK_NEW_LISTING_ID)!;
    const ticks = ticksBetween(listed, SIM_BASE_MS - DAY_MS, SIM_BASE_MS, 600);
    expect(ticks.length).toBeGreaterThan(0);
    expect(ticks[0].atMs).toBeGreaterThanOrEqual(listed.priceFromMs!);
    expect(ticks.length).toBeLessThan(60); // two simulated minutes of 3 s ticks, not a day of them
  });

  it("two overlapping calls agree on every tick's time AND price (the incremental poll never contradicts itself)", () => {
    const all = ticksBetween(ETH, SIM_BASE_MS - 60_000, SIM_BASE_MS + 60_000, 600);
    const later = ticksBetween(ETH, SIM_BASE_MS - 30_000, SIM_BASE_MS + 60_000, 600);
    const byTime = new Map(all.map((t) => [t.atMs, t.price] as const));
    for (const t of later) expect(byTime.get(t.atMs)).toBe(t.price);
  });

  it("is empty when nothing is newer", () => {
    expect(ticksBetween(ETH, SIM_BASE_MS, SIM_BASE_MS, 600)).toEqual([]);
  });
});

describe("ohlcFor — a round's open, close and extremes", () => {
  it("low <= min(open, close) and high >= max(open, close), for every market and both series", () => {
    for (const m of MOCK_MARKETS) {
      for (const interval of ["HOURLY", "DAILY"] as const) {
        const window = windowFor(interval, SIM_BASE_MS - 3 * DAY_MS);
        const o = ohlcFor(m, window);
        const [open, close, low, high] = [o.open, o.close, o.low, o.high].map(Number);
        expect(low).toBeLessThanOrEqual(Math.min(open, close));
        expect(high).toBeGreaterThanOrEqual(Math.max(open, close));
        expect(fractionDigits(o.open)).toBe(m.priceDecimals);
        expect(fractionDigits(o.low)).toBe(m.priceDecimals);
      }
    }
  });

  it("close(N) = open(N+1): consecutive rounds of a series meet at the same price (D-40)", () => {
    for (const m of [mockMarketById("ETH")!, mockMarketById("PM900105")!]) {
      const w1 = windowFor("HOURLY", SIM_BASE_MS - 5 * HOUR_MS);
      const w2 = windowFor("HOURLY", w1.endMs);
      expect(ohlcFor(m, w1).close).toBe(ohlcFor(m, w2).open);
    }
  });

  it("a running round's extremes so far only widen as the round goes on, and never exceed the settled ones", () => {
    const m = mockMarketById("SOL")!;
    const window = windowFor("HOURLY", SIM_BASE_MS);
    const settled = ohlcFor(m, window);
    let prevLow = Infinity;
    let prevHigh = -Infinity;
    for (let until = window.startMs; until <= window.endMs; until += 4 * 60_000) {
      const so = ohlcFor(m, window, until);
      expect(so.open).toBe(settled.open);
      expect(Number(so.low)).toBeLessThanOrEqual(prevLow === Infinity ? Number(so.open) : prevLow);
      expect(Number(so.high)).toBeGreaterThanOrEqual(prevHigh === -Infinity ? Number(so.open) : prevHigh);
      expect(Number(so.low)).toBeGreaterThanOrEqual(Number(settled.low));
      expect(Number(so.high)).toBeLessThanOrEqual(Number(settled.high));
      prevLow = Number(so.low);
      prevHigh = Number(so.high);
    }
    expect(ohlcFor(m, window, window.endMs)).toEqual(settled);
  });

  it("is deterministic: asking twice gives the same round", () => {
    const m = mockMarketById("xyz_NVDA")!;
    const window = windowFor("DAILY", SIM_BASE_MS - 9 * DAY_MS);
    expect(ohlcFor(m, window)).toEqual(ohlcFor(m, window));
  });
});

describe("the walks are decorrelated across markets (no two cards look like one price)", () => {
  it("24h changes of the six crypto markets are not all alike", () => {
    const changes = PERPS.slice(0, 6).map((m: MockMarketSpec) => change24hAt(m, SIM_BASE_MS)!);
    const distinct = new Set(changes.map((c) => c.toFixed(3)));
    expect(distinct.size).toBeGreaterThanOrEqual(5);
  });
});
