import { describe, expect, it } from "vitest";
import type { CancellationDto } from "../api/contract";
import { NOW as FIXTURE_NOW, catalogue, market, spotCatalogue } from "../components/testFixtures";
import {
  ageText,
  betsCount,
  describePriceSource,
  formatOperatorBalance,
  freshnessOf,
  marketsList,
  mergeCancellations,
  nextCancellationsParams,
  priceSourceOf,
  roundLengthsOf,
  sourceName,
  summarizeMarkets,
  summarizeSources,
  verifierCommand,
} from "./statusModel";

describe("where prices come from, in plain words", () => {
  it("the server's own priceSource wins; an older server's mode implies it", () => {
    expect(priceSourceOf({ mode: "live", priceSource: "synthetic" })).toBe("synthetic");
    expect(priceSourceOf({ mode: "live" })).toBe("live");
    expect(priceSourceOf({ mode: "replay" })).toBe("replay");
    expect(priceSourceOf({ mode: "live", priceSource: "replay" })).toBe("replay");
  });

  it("live: names every source the prices and odds come from — the exchanges, the backup, Polymarket — and says rounds settle on them", () => {
    const live = describePriceSource({ mode: "live", priceSource: "live" });
    expect(live.title).toBe("Live");
    expect(live.detail).toMatch(/Coinbase/);
    expect(live.detail).toMatch(/Hyperliquid/);
    expect(live.detail).toMatch(/Kraken and Coinbase as backups/);
    expect(live.detail).toMatch(/Kraken/);
    expect(live.detail).toMatch(/odds from Polymarket/);
    expect(live.detail).toMatch(/settle on them/);
  });

  it("synthetic: says plainly that nothing follows a real market", () => {
    const synthetic = describePriceSource({ mode: "live", priceSource: "synthetic" });
    expect(synthetic.title).toBe("Simulated");
    expect(synthetic.detail).toMatch(/No real source/);
    expect(synthetic.detail).toMatch(/Nothing here follows a real market/);
  });

  it("replay: the recorded span and the speed-up, and that it is not today's price", () => {
    const replay = describePriceSource({
      mode: "replay",
      replay: { sourceFromIso: "2026-09-01T00:00:00Z", sourceToIso: "2026-09-08T00:00:00Z", speedup: 60 },
    });
    expect(replay.title).toBe("Replay");
    expect(replay.detail).toBe("Recorded BTC prices from 2026-09-01 to 2026-09-08, replayed 60× faster than they happened. Only BTC, and not today's price.");
    expect(describePriceSource({ mode: "replay" }).detail).toMatch(/not today's prices/);
  });
});

describe("how old a price is", () => {
  it("is exact to the second while young, then minutes, then hours — never negative", () => {
    expect(ageText(0)).toBe("0 s ago");
    expect(ageText(4_000)).toBe("4 s ago");
    expect(ageText(59_999)).toBe("59 s ago");
    expect(ageText(60_000)).toBe("1 min ago");
    expect(ageText(125 * 60_000)).toBe("2 h 5 min ago");
    expect(ageText(3 * 3_600_000)).toBe("3 h ago");
    expect(ageText(-5_000)).toBe("0 s ago");
  });
});

describe("freshnessOf — one market's price: closed, none yet, stale, live", () => {
  const NOW = 1_000_000;
  it("live: the word and the age", () => {
    expect(freshnessOf(market("BTC", { price: { price: "100.00", asOfMs: NOW - 3_000, change24h: 0, stale: false } }), NOW)).toEqual({ kind: "fresh", word: "Live", ageMs: 3_000 });
  });
  it("stale is the server's own flag, and is said as a word", () => {
    expect(freshnessOf(market("NVDA", { price: { price: "231.70", asOfMs: NOW - 190_000, change24h: 0, stale: true } }), NOW)).toEqual({ kind: "stale", word: "Stale", ageMs: 190_000 });
  });
  it("no price yet is not a zero age", () => {
    expect(freshnessOf(market("NEW", { price: null }), NOW)).toEqual({ kind: "none", word: "No price yet", ageMs: null });
  });
  it("a closed market is closed, with its last price's age", () => {
    expect(freshnessOf(market("OLD", { status: "CLOSED", price: { price: "0.31", asOfMs: NOW - 600_000, change24h: 0, stale: false } }), NOW)).toEqual({ kind: "closed", word: "Closed", ageMs: 600_000 });
  });
  it("a price from the future (clock skew) is age 0, not negative", () => {
    expect(freshnessOf(market("BTC", { price: { price: "1.00", asOfMs: NOW + 5_000, change24h: 0, stale: false } }), NOW).ageMs).toBe(0);
  });
});

describe("summarizeMarkets", () => {
  it("counts every state once, and they add up to the total", () => {
    const NOW = 1_000_000;
    const list = [
      market("A", { price: { price: "1.00", asOfMs: NOW, change24h: 0, stale: false } }),
      market("B", { price: { price: "1.00", asOfMs: NOW, change24h: 0, stale: false } }),
      market("C", { price: { price: "1.00", asOfMs: NOW - 99_000, change24h: 0, stale: true } }),
      market("D", { price: null }),
      market("E", { status: "CLOSED" }),
    ];
    expect(summarizeMarkets(list, NOW)).toEqual({ total: 5, fresh: 2, stale: 1, noPrice: 1, closed: 1 });
    expect(summarizeMarkets([], NOW)).toEqual({ total: 0, fresh: 0, stale: 0, noPrice: 0, closed: 0 });
  });
});

describe("summarizeSources — which exchange is behind which market, from the open markets themselves", () => {
  const ids = (markets: Array<{ id: string }>) => markets.map((m) => m.id);

  it("the exchanges first (Coinbase, Kraken, Hyperliquid), then Polymarket — each with the markets it prices and the ones it backs", () => {
    const sources = summarizeSources([...spotCatalogue(), ...catalogue().filter((m) => m.id === "PM900101" || m.id === "xyz_NVDA")], FIXTURE_NOW);
    expect(sources.map((s) => s.name)).toEqual(["Coinbase", "Kraken", "Hyperliquid", "Polymarket"]);
    const [coinbase, kraken, hyperliquid, polymarket] = sources;
    expect(ids(coinbase.main)).toEqual(["spot_BTC", "spot_ETH", "spot_DOGE"]);
    expect(ids(coinbase.backupFor)).toEqual(["spot_HYPE"]); // Coinbase stands behind Hyperliquid's spot market
    expect(ids(kraken.main)).toEqual([]);
    expect(ids(kraken.backupFor)).toEqual(["spot_BTC", "spot_ETH", "spot_DOGE"]);
    expect(ids(hyperliquid.main)).toEqual(["spot_HYPE", "xyz_NVDA"]); // the closed legacy BTC perpetual is not served by anyone any more
    expect(ids(hyperliquid.backupFor)).toEqual([]);
    expect(ids(polymarket.main)).toEqual(["PM900101"]);
  });

  it("the backup whose price is on screen now is said — only where the shown price is its own (MarketPriceDto.venue)", () => {
    const [coinbase, kraken] = summarizeSources(spotCatalogue(), FIXTURE_NOW);
    expect(ids(kraken.shownFor)).toEqual(["spot_ETH"]); // spot_ETH's price is Kraken's; the others' are Coinbase's own
    expect(ids(coinbase.shownFor)).toEqual([]);
  });

  it("how the main markets' prices are doing: live, stale, no price yet — counted once each", () => {
    const stale = market("spot_SOL", { source: "coinbase", backupSource: "kraken", price: { price: "117.84", asOfMs: FIXTURE_NOW - 99_000, change24h: 0, stale: true } });
    const waiting = market("spot_XRP", { source: "coinbase", backupSource: "kraken", price: null });
    const [coinbase] = summarizeSources([...spotCatalogue(), stale, waiting], FIXTURE_NOW);
    expect(coinbase).toMatchObject({ fresh: 3, stale: 1, noPrice: 1 });
    expect(coinbase.fresh + coinbase.stale + coinbase.noPrice).toBe(coinbase.main.length);
  });

  it("a closed market is served by no one; no markets, no sources", () => {
    const closed = market("old", { status: "CLOSED", source: "coinbase", backupSource: "kraken" });
    expect(summarizeSources([closed], FIXTURE_NOW)).toEqual([]);
    expect(summarizeSources([], FIXTURE_NOW)).toEqual([]);
  });

  it("a source this build does not know is listed after the known ones, under the name the server sent", () => {
    const odd = market("X1", { source: "bybit" as never });
    const sources = summarizeSources([odd, ...spotCatalogue()], FIXTURE_NOW);
    expect(sources.map((s) => s.name)).toEqual(["Coinbase", "Kraken", "Hyperliquid", "Bybit"]);
  });
});

describe("small wordings", () => {
  it("names the source as a player knows it — every one, and none by default as Hyperliquid", () => {
    expect(sourceName("coinbase")).toBe("Coinbase");
    expect(sourceName("kraken")).toBe("Kraken");
    expect(sourceName("hyperliquid")).toBe("Hyperliquid");
    expect(sourceName("polymarket")).toBe("Polymarket");
    expect(sourceName("synthetic")).toBe("Simulated");
    expect(sourceName("bybit")).toBe("Bybit"); // a newer server's exchange: as sent, not 'Hyperliquid'
  });

  it("a list of tickers while they are few, how many once they are not", () => {
    const m = (symbol: string) => ({ symbol });
    expect(marketsList([m("BTC"), m("ETH"), m("SOL")])).toBe("BTC, ETH, SOL");
    expect(marketsList([m("A"), m("B"), m("C"), m("D"), m("E"), m("F")])).toBe("A, B, C, D, E, F");
    expect(marketsList([m("A"), m("B"), m("C"), m("D"), m("E"), m("F"), m("G")])).toBe("7 markets");
    expect(marketsList([m("A"), m("B"), m("C")], 2)).toBe("3 markets");
    expect(marketsList([])).toBe("");
  });

  it("one bet / several bets", () => {
    expect(betsCount(1)).toBe("1 bet");
    expect(betsCount(0)).toBe("0 bets");
    expect(betsCount(19)).toBe("19 bets");
  });
});

describe("the operator's balance, in TXCH from mojos", () => {
  it("1 TXCH is 10^12 mojos; four decimals; thousands separated", () => {
    expect(formatOperatorBalance("500000000000")).toBe("0.5000 TXCH");
    expect(formatOperatorBalance("1000000000000")).toBe("1.0000 TXCH");
    expect(formatOperatorBalance("1234567890123456")).toBe("1,234.5678 TXCH");
    expect(formatOperatorBalance("0")).toBe("0.0000 TXCH");
    expect(formatOperatorBalance("999")).toBe("0.0000 TXCH"); // below 0.0001: shown as it rounds down, the title has the mojos
  });
  it("exact for amounts beyond a double's precision", () => {
    expect(formatOperatorBalance("9007199254740993000000")).toBe("9,007,199,254.7409 TXCH");
  });
  it("unknown is a dash; something that is not a number is shown as it came, not guessed", () => {
    expect(formatOperatorBalance(null)).toBe("—");
    expect(formatOperatorBalance(undefined)).toBe("—");
    expect(formatOperatorBalance("12.5")).toBe("12.5 mojos");
    expect(formatOperatorBalance("lots")).toBe("lots mojos");
  });
});

describe("the verifier command", () => {
  it("is filled in for this server — launcher id and origin", () => {
    expect(verifierCommand("c5cb79", "https://herd.example")).toBe("python3 tools/verify-oracle/verify_oracle.py c5cb79 --stand-url https://herd.example");
  });
  it("with no launcher id it keeps a placeholder rather than inventing one", () => {
    expect(verifierCommand(null, "http://localhost:8480")).toContain("<launcher id>");
    expect(verifierCommand("", "http://localhost:8480")).toContain("<launcher id>");
  });
});

describe("paging the cancellations log (newest first, cursor = a timestamp, strictly before)", () => {
  const c = (roundId: string, cancelledAtMs: number): CancellationDto => ({ roundId, reason: "r", cancelledAtMs, refundedBetCount: 1 });

  it("the next page is asked from ONE millisecond after the oldest known — a market that closes cancels several rounds at one instant", () => {
    expect(nextCancellationsParams([c("a", 300), c("b", 200), c("c", 200)], 10)).toEqual({ limit: 10, before: 201 });
    expect(nextCancellationsParams([], 10)).toEqual({ limit: 10 });
  });

  it("merging drops what is already held (by round id) and keeps newest first", () => {
    const known = [c("a", 300), c("b", 200)];
    const merged = mergeCancellations(known, [c("b", 200), c("c", 200), c("d", 100)]);
    expect(merged.map((x) => x.roundId)).toEqual(["a", "b", "c", "d"]);
    expect(mergeCancellations(known, known)).toEqual(known);
  });
});

describe("roundLengthsOf — the 'Round lengths' row is printed only when both numbers can be read", () => {
  it("the contract's HOURLY / DAILY keys, in the words of the round chips", () => {
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: 180, DAILY: 900 } })).toEqual({ short: "3 MIN", long: "15 MIN" });
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: 3600, DAILY: 86_400 } })).toEqual({ short: "1 HOUR", long: "1 DAY" });
  });

  it("anything else is null — never 'NaN SEC' (the lowercase keys of a server that disagrees with the contract, a null, zero)", () => {
    const lowercase = { hourly: 180, daily: 900 } as unknown as { HOURLY: number; DAILY: number };
    expect(roundLengthsOf({ roundDurationsSec: lowercase })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: 180 } as unknown as { HOURLY: number; DAILY: number } })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: Number.NaN, DAILY: 900 } })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: 180, DAILY: 0 } })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: { HOURLY: -1, DAILY: 900 } })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: null as unknown as { HOURLY: number; DAILY: number } })).toBeNull();
    expect(roundLengthsOf({ roundDurationsSec: "180" as unknown as { HOURLY: number; DAILY: number } })).toBeNull();
  });
});
