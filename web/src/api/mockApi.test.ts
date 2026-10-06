/**
 * The mock's multi-market world (Р-69, `docs/research/markets_architecture.md` §8), driven through
 * the `Api` interface exactly as the screens drive it. Timing tests use fake timers: the simulated
 * clock is `performance.now() × 60` (`clock.ts`), which Vitest's fake timers control.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError, MIN_STAKE_PLAY, type RoundBetting } from "./contract";
import { SIM_SPEED, buildRoundId, parseRoundId, simNowMs } from "./clock";
import {
  LONG,
  SHORT,
  initialMargin,
  liquidationPrice,
  priceStr,
  sizeForMargin,
} from "./liquidation";
import { mockApi } from "./mockApi";
import { crowdOutcomeOf } from "./mockCrowdHistory";
import { CLOSED_LISTING_MS, MOCK_CLOSING_AT_MS, MOCK_MARKETS, MOCK_NEW_LISTING_ID, mockMarketById } from "./mockMarkets";
import { priceStrAt, TICK_MS } from "./mockPrices";

const ADDRESS = "txch1mockapitestaddress0000000000000000000000000000000000q";
const CLOSING = mockMarketById("PM900104")!; // the sports market that closes during a session

async function signIn(): Promise<void> {
  const challenge = await mockApi.authChallenge({});
  await mockApi.authVerify({ nonce: challenge.nonce, address: ADDRESS, publicKey: "pk", signature: "sig" });
  await mockApi.setNickname("mockApiTester");
}

async function bettingRoundOf(marketId: string, interval: "HOURLY" | "DAILY" = "HOURLY"): Promise<RoundBetting> {
  const pair = await mockApi.rounds(interval, marketId);
  if (pair.betting.phase !== "BETTING") throw new Error(`expected an open betting round on ${marketId}`);
  return pair.betting;
}

/** Moves the simulated clock to `targetSimMs` (fake timers must be on; a target in the past is a no-op). */
async function advanceSimClockTo(targetSimMs: number): Promise<void> {
  const realMs = (targetSimMs - simNowMs()) / SIM_SPEED;
  if (realMs > 0) await vi.advanceTimersByTimeAsync(Math.ceil(realMs));
}

async function expectApiError(promise: Promise<unknown>, code: string): Promise<ApiRequestError> {
  const err = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiRequestError);
  expect((err as ApiRequestError).code).toBe(code);
  return err as ApiRequestError;
}

describe("markets() — the lobby in one call", () => {
  it("lists the 19 markets in catalog order, with the categories of Т-1 and their counts", async () => {
    const res = await mockApi.markets();
    expect(res.markets.map((m) => m.id)).toEqual(MOCK_MARKETS.map((m) => m.id));
    expect(res.categories).toEqual([
      { id: "crypto", title: "Crypto", marketCount: 6 },
      { id: "politics", title: "Politics", marketCount: 4 },
      { id: "sports", title: "Sports", marketCount: 3 },
      { id: "stocks", title: "Stocks", marketCount: 2 },
      { id: "indices", title: "Indices", marketCount: 2 },
      { id: "commodities", title: "Commodities", marketCount: 2 },
    ]);
    expect(res.serverTimeMs).toBeGreaterThan(0);
  });

  it("every market card is complete and internally consistent", async () => {
    const { markets } = await mockApi.markets();
    for (const m of markets) {
      const spec = mockMarketById(m.id)!;
      expect(m).toMatchObject({
        kind: spec.kind,
        category: spec.category,
        symbol: spec.symbol,
        title: spec.title,
        subtitle: spec.subtitle,
        maxLeverage: spec.maxLeverage,
        priceDecimals: spec.priceDecimals,
        endsAtMs: spec.endsAtMs,
        eventStartsAtMs: spec.eventStartsAtMs,
        oracleIndex: spec.oracleIndex,
        closedAtMs: null,
        closeReason: null,
        source: spec.source,
        backupSource: spec.backupSource,
        status: "ACTIVE",
      });
      expect(m.price).not.toBeNull();
      expect(m.price!.price.split(".")[1]).toHaveLength(m.priceDecimals);
      expect(m.price!.stale).toBe(false);
      // A market listed less than a day ago has no price from 24 h back, so the source gives no change.
      expect(typeof m.price!.change24h).toBe(m.id === MOCK_NEW_LISTING_ID ? "object" : "number");
      if (m.kind === "ODDS") {
        expect(Number(m.price!.price)).toBeGreaterThanOrEqual(0.1);
        expect(Number(m.price!.price)).toBeLessThanOrEqual(0.9);
      }
      expect(m.crowd.capital).toMatch(/^\d+\.\d{2}$/);
      expect(m.crowd.roundsTraded).toBeGreaterThanOrEqual(m.crowd.roundsWon);

      // two series, shortest first, with the mock's own lengths (1 h and 1 d)
      expect(m.series.map((s) => [s.interval, s.durationSec])).toEqual([
        ["HOURLY", 3600],
        ["DAILY", 86_400],
      ]);
      for (const s of m.series) {
        expect(parseRoundId(s.bettingRoundId)).toMatchObject({ marketId: m.id, interval: s.interval });
        expect(parseRoundId(s.runningRoundId)).toMatchObject({ marketId: m.id, interval: s.interval });
        expect(s.bettingClosesAtMs).toBe(s.runningEndsAtMs); // betting closes when the running round ends
        expect(Number(s.upStake) + Number(s.downStake)).toBeGreaterThanOrEqual(0);
      }
    }
  });

  it("the sources are the server's (Р-76): crypto on an exchange with a backup behind it, stocks, indices and commodities on Hyperliquid, odds on Polymarket", async () => {
    const { markets } = await mockApi.markets();
    const sources = Object.fromEntries(markets.map((m) => [m.id, [m.source, m.backupSource ?? null]]));
    expect(sources).toMatchObject({
      BTC: ["coinbase", "kraken"],
      ETH: ["coinbase", "kraken"],
      SOL: ["coinbase", "kraken"],
      XRP: ["coinbase", "kraken"],
      DOGE: ["coinbase", "kraken"],
      HYPE: ["hyperliquid", "coinbase"],
      xyz_NVDA: ["hyperliquid", null],
      xyz_GOLD: ["hyperliquid", null],
      PM900101: ["polymarket", null],
    });
    // no market lists itself as its own backup
    for (const m of markets) expect(m.backupSource ?? null).not.toBe(m.source);
  });

  it("the mock never goes to a backup: no price names another exchange, and the leverage caps are the server's spot defaults", async () => {
    const { markets } = await mockApi.markets();
    for (const m of markets) expect(m.price?.venue ?? null, m.id).toBeNull();
    const caps = Object.fromEntries(markets.filter((m) => m.category === "crypto").map((m) => [m.id, m.maxLeverage]));
    expect(caps).toEqual({ BTC: 40, ETH: 25, SOL: 20, XRP: 20, DOGE: 10, HYPE: 10 }); // herd.markets.spot.max-leverage
  });

  it("each market has its own bots, so the crowds differ (Т-8)", async () => {
    const { markets } = await mockApi.markets();
    const counts = markets.map((m) => m.series[0].participantCount);
    expect(new Set(counts).size).toBeGreaterThan(5);
    const bot = (id: string) => markets.find((m) => m.id === id)!.series[0].participantCount;
    expect(bot("BTC")).toBeGreaterThanOrEqual(12); // BTC keeps its original 12-32
    expect(bot("BTC")).toBeLessThanOrEqual(32);
  });

  it("the live split on the card is the live split of the betting round", async () => {
    const { markets } = await mockApi.markets();
    for (const m of markets.slice(0, 4)) {
      const betting = await bettingRoundOf(m.id);
      const series = m.series[0];
      // The clock moves between the two calls; only compare when both saw the same betting round.
      if (series.bettingRoundId !== betting.roundId) continue;
      expect(series.participantCount).toBe(betting.participantCount);
      expect(series.upStake).toBe(betting.upStake);
      expect(series.downStake).toBe(betting.downStake);
      expect(series.majorityDirection).toBe(betting.majorityDirection);
    }
  });
});

describe("rounds / round — per market", () => {
  it.each(MOCK_MARKETS.map((m) => m.id))("%s: both series come back with this market's ids, symbol and price scale", async (marketId) => {
    const spec = mockMarketById(marketId)!;
    for (const interval of ["HOURLY", "DAILY"] as const) {
      const pair = await mockApi.rounds(interval, marketId);
      expect(pair.interval).toBe(interval);
      for (const round of [pair.running, pair.betting]) {
        expect(round.marketId).toBe(marketId);
        expect(round.asset).toBe(spec.symbol);
        expect(parseRoundId(round.roundId)).toEqual({ marketId, interval, startMs: round.window.startMs });
      }
      expect(pair.running.phase).toBe("RUNNING");
      expect(pair.betting.phase).toBe("BETTING");
      if (pair.running.phase === "RUNNING" && pair.running.openPrice !== null) {
        expect(pair.running.openPrice.split(".")[1]).toHaveLength(spec.priceDecimals);
        for (const p of pair.running.positions) {
          expect(p.entryPrice).toBe(pair.running.openPrice);
          expect(p.liquidationPrice.split(".")[1]).toHaveLength(spec.priceDecimals);
          expect(p.leverage).toBeLessThanOrEqual(spec.maxLeverage);
        }
      }
    }
  });

  it("omitting the market means BTC, with the legacy id form", async () => {
    const pair = await mockApi.rounds("HOURLY");
    expect(pair.betting.marketId).toBe("BTC");
    expect(pair.betting.roundId).toMatch(/^1h-\d+$/);
    expect((await mockApi.rounds("DAILY")).betting.roundId).toMatch(/^1d-\d+$/);
    expect((await mockApi.rounds("HOURLY", "ETH")).betting.roundId).toMatch(/^ETH-1h-\d+$/);
  });

  it("round(id) resolves an id of any market, in the phase its window is in", async () => {
    const pair = await mockApi.rounds("HOURLY", "xyz_GOLD");
    const running = await mockApi.round(pair.running.roundId);
    const betting = await mockApi.round(pair.betting.roundId);
    expect(running).toMatchObject({ phase: "RUNNING", marketId: "xyz_GOLD", asset: "GOLD" });
    expect(betting).toMatchObject({ phase: "BETTING", marketId: "xyz_GOLD" });
    const pastId = buildRoundId("xyz_GOLD", "HOURLY", pair.running.window.startMs - 3_600_000);
    expect(await mockApi.round(pastId)).toMatchObject({ phase: "SETTLED", marketId: "xyz_GOLD" });
  });

  it("an unknown market is MARKET_NOT_FOUND; a malformed or off-grid round id is a plain not-found", async () => {
    await expectApiError(mockApi.rounds("HOURLY", "NOPE"), "MARKET_NOT_FOUND");
    await expectApiError(mockApi.round("NOPE-1h-3600000"), "MARKET_NOT_FOUND");
    await expectApiError(mockApi.crowdAccount("NOPE"), "MARKET_NOT_FOUND");
    await expectApiError(mockApi.referencePrice("NOPE"), "MARKET_NOT_FOUND");
    await expectApiError(mockApi.priceSeries("NOPE"), "MARKET_NOT_FOUND");
    await expect(mockApi.round("garbage")).rejects.toThrow("Round not found.");
    await expect(mockApi.round("ETH-1h-3600001")).rejects.toThrow("Round not found.");
  });

  it("a settled round's open and close price are the neighbours' (close(N) = open(N+1))", async () => {
    const pair = await mockApi.rounds("HOURLY", "SOL");
    const prevId = buildRoundId("SOL", "HOURLY", pair.running.window.startMs - 3_600_000);
    const prev = await mockApi.round(prevId);
    if (prev.phase !== "SETTLED" || pair.running.phase !== "RUNNING") throw new Error("unexpected phases");
    expect(prev.closePrice).toBe(pair.running.openPrice);
  });

  it("a settled round's positions pay out with the market's own math", async () => {
    const spec = mockMarketById("xyz_NVDA")!;
    const pair = await mockApi.rounds("HOURLY", "xyz_NVDA");
    const settled = await mockApi.round(buildRoundId("xyz_NVDA", "HOURLY", pair.running.window.startMs - 3_600_000));
    if (settled.phase !== "SETTLED") throw new Error("expected SETTLED");
    expect(settled.positions.length).toBeGreaterThan(0);
    for (const p of settled.positions) {
      const side = p.direction === "UP" ? LONG : SHORT;
      const size = sizeForMargin(p.stake!, p.leverage, settled.openPrice, spec);
      expect(p.size).toBe(size.toFixed(8));
      expect(p.liquidationPrice).toBe(priceStr(liquidationPrice(size, settled.openPrice, p.leverage, side, spec), spec));
      // margin recovered from the size is the stake, to the cent (8 dp sizes close the 5 dp gap)
      expect(initialMargin(size, settled.openPrice, p.leverage, spec).toFixed(2)).toBe(Number(p.stake).toFixed(2));
      if (p.liquidated) expect(p.payout).toBe("0.000");
    }
  });
});

describe("previewLiquidation — per market, with the market's leverage cap", () => {
  it("matches liquidation.ts for the market", async () => {
    const eth = mockMarketById("ETH")!;
    const res = await mockApi.previewLiquidation({
      marketId: "ETH",
      direction: "UP",
      leverage: 10,
      stake: "500.000",
      referencePrice: "2694.40",
    });
    const size = sizeForMargin("500.000", 10, "2694.40", eth);
    expect(res.entryPriceUsed).toBe("2694.40");
    expect(res.liquidationPrice).toBe("2474.45"); // docs python3 decimal vector, see liquidation.test.ts
    expect(res.liquidationPrice).toBe(priceStr(liquidationPrice(size, "2694.40", 10, LONG, eth), eth));
    expect(res.percentToLiquidation).toBeGreaterThan(0);
  });

  it("an ODDS market previews in probabilities with 6 digits", async () => {
    const res = await mockApi.previewLiquidation({
      marketId: "PM900101",
      direction: "DOWN",
      leverage: 10,
      stake: "250.000",
      referencePrice: "0.523000",
    });
    expect(res.liquidationPrice).toBe("0.547905");
  });

  it("without a marketId it is BTC, 40x allowed, referencing the current BTC price", async () => {
    const res = await mockApi.previewLiquidation({ direction: "UP", leverage: 40, stake: "100.000" });
    expect(res.entryPriceUsed).toMatch(/^\d+\.\d{2}$/);
    expect(Number(res.liquidationPrice)).toBeLessThan(Number(res.entryPriceUsed));
  });

  it.each([
    ["ETH", 25],
    ["SOL", 20],
    ["DOGE", 10],
    ["xyz_XYZ100", 30],
    ["PM900104", 10],
  ])("%s: leverage up to %i is fine, one more is INVALID_LEVERAGE", async (marketId, cap) => {
    const base = { marketId, direction: "UP" as const, stake: "100.000" };
    await expect(mockApi.previewLiquidation({ ...base, leverage: cap })).resolves.toBeDefined();
    await expectApiError(mockApi.previewLiquidation({ ...base, leverage: cap + 1 }), "INVALID_LEVERAGE");
    await expectApiError(mockApi.previewLiquidation({ ...base, leverage: 1 }), "INVALID_LEVERAGE");
    await expectApiError(mockApi.previewLiquidation({ ...base, leverage: 5.5 }), "INVALID_LEVERAGE");
  });

  it("an unknown market is MARKET_NOT_FOUND", async () => {
    await expectApiError(mockApi.previewLiquidation({ marketId: "NOPE", direction: "UP", leverage: 5, stake: "100.000" }), "MARKET_NOT_FOUND");
  });
});

describe("referencePrice / priceSeries — one price per instant", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("btcReferencePrice is referencePrice('BTC'), and markets() agrees with both at the same instant", async () => {
    const [btc, viaId, markets] = await Promise.all([mockApi.btcReferencePrice(), mockApi.referencePrice("BTC"), mockApi.markets()]);
    expect(btc).toEqual(viaId);
    expect(markets.markets[0].price!.price).toBe(btc.price);
    for (const m of markets.markets) expect((await mockApi.referencePrice(m.id)).price).toBe(m.price!.price);
  });

  it("the last tick of priceSeries is the price at that tick's time, and ticks are 3 s apart, oldest first", async () => {
    const series = await mockApi.priceSeries("ETH");
    expect(series.marketId).toBe("ETH");
    expect(series.priceDecimals).toBe(2);
    expect(series.ticks.length).toBeGreaterThan(100); // 15 simulated minutes at 3 s
    expect(series.ticks.length).toBeLessThanOrEqual(600);
    const last = series.ticks[series.ticks.length - 1];
    expect(last.price).toBe(priceStrAt(mockMarketById("ETH")!, last.atMs));
    expect(simNowMs() - last.atMs).toBeLessThan(TICK_MS);
    for (let i = 1; i < series.ticks.length; i++) expect(series.ticks[i].atMs - series.ticks[i - 1].atMs).toBe(TICK_MS);
  });

  it("sinceMs returns only newer ticks, and polling incrementally never disagrees with a full reload", async () => {
    const first = await mockApi.priceSeries("SOL");
    const since = first.ticks[first.ticks.length - 1].atMs;
    await vi.advanceTimersByTimeAsync(2_000); // 2 real seconds = 120 simulated seconds = 40 ticks
    const next = await mockApi.priceSeries("SOL", since);
    expect(next.ticks.length).toBeGreaterThanOrEqual(39);
    expect(next.ticks.every((t) => t.atMs > since)).toBe(true);
    const full = await mockApi.priceSeries("SOL");
    const fullByTime = new Map(full.ticks.map((t) => [t.atMs, t.price] as const));
    for (const t of next.ticks) expect(fullByTime.get(t.atMs)).toBe(t.price);
    // nothing new yet
    expect((await mockApi.priceSeries("SOL", next.ticks[next.ticks.length - 1].atMs)).ticks).toEqual([]);
  });

  it("never returns more than 600 points, even for a very old sinceMs", async () => {
    const res = await mockApi.priceSeries("BTC", simNowMs() - 3 * 86_400_000);
    expect(res.ticks).toHaveLength(600);
  });
});

describe("placeBet and the player's positions across markets", () => {
  beforeEach(async () => {
    await signIn();
  });

  it("a bet above the market's leverage cap is INVALID_LEVERAGE; at the cap it is accepted", async () => {
    const eth = await bettingRoundOf("ETH");
    await expectApiError(mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 26, stake: MIN_STAKE_PLAY }), "INVALID_LEVERAGE");
    await expectApiError(mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 1, stake: MIN_STAKE_PLAY }), "INVALID_LEVERAGE");
    await expect(mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 25, stake: MIN_STAKE_PLAY })).resolves.toMatchObject({ leverage: 25 });

    const doge = await bettingRoundOf("DOGE");
    await expectApiError(mockApi.placeBet({ roundId: doge.roundId, direction: "DOWN", leverage: 11, stake: MIN_STAKE_PLAY }), "INVALID_LEVERAGE");

    const odds = await bettingRoundOf("PM900101");
    await expectApiError(mockApi.placeBet({ roundId: odds.roundId, direction: "UP", leverage: 11, stake: MIN_STAKE_PLAY }), "INVALID_LEVERAGE");
    await expect(mockApi.placeBet({ roundId: odds.roundId, direction: "UP", leverage: 10, stake: MIN_STAKE_PLAY })).resolves.toBeDefined();

    // BTC still goes to 40x
    const btc = await bettingRoundOf("BTC");
    await expect(mockApi.placeBet({ roundId: btc.roundId, direction: "UP", leverage: 40, stake: MIN_STAKE_PLAY })).resolves.toMatchObject({ leverage: 40 });
  });

  it("a round of an unknown market is MARKET_NOT_FOUND, a past round is not open for betting", async () => {
    await expectApiError(mockApi.placeBet({ roundId: "NOPE-1h-3600000", direction: "UP", leverage: 5, stake: MIN_STAKE_PLAY }), "MARKET_NOT_FOUND");
    const pair = await mockApi.rounds("HOURLY", "ETH");
    await expectApiError(
      mockApi.placeBet({ roundId: pair.running.roundId, direction: "UP", leverage: 5, stake: MIN_STAKE_PLAY }),
      "ROUND_NOT_OPEN_FOR_BETTING",
    );
  });

  it("one player can hold a position in several markets in the same window (Т-5), all listed in myPositions()", async () => {
    const eth = await bettingRoundOf("ETH");
    const sol = await bettingRoundOf("SOL");
    const gold = await bettingRoundOf("xyz_GOLD", "DAILY");
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 5, stake: "200.000" });
    await mockApi.placeBet({ roundId: sol.roundId, direction: "DOWN", leverage: 8, stake: "300.000" });
    await mockApi.placeBet({ roundId: gold.roundId, direction: "UP", leverage: 20, stake: "400.000" });

    const positions = await mockApi.myPositions();
    expect(positions).toHaveLength(3);
    const byMarket = new Map(positions.map((p) => [p.marketId, p] as const));
    expect(byMarket.get("ETH")).toMatchObject({
      symbol: "ETH",
      roundId: eth.roundId,
      interval: "HOURLY",
      phase: "BETTING",
      direction: "UP",
      leverage: 5,
      stake: "200.000",
      entryPrice: null,
      liquidationPrice: null,
      liquidated: false,
      untilMs: eth.window.startMs,
    });
    expect(byMarket.get("SOL")).toMatchObject({ direction: "DOWN", leverage: 8, stake: "300.000" });
    expect(byMarket.get("xyz_GOLD")).toMatchObject({ interval: "DAILY", symbol: "GOLD", stake: "400.000" });
    // soonest first
    expect(positions.map((p) => p.untilMs)).toEqual([...positions.map((p) => p.untilMs)].sort((a, b) => a - b));

    const account = await mockApi.myAccount();
    expect(account.inPlay).toBe("900.000");
    expect(account.balance).toBe("9100.000");
    // the betting round of each market shows the player's own bet
    expect(((await mockApi.rounds("HOURLY", "ETH")).betting as RoundBetting).myBet).toMatchObject({ stake: "200.000" });
    expect(((await mockApi.rounds("HOURLY", "SOL")).betting as RoundBetting).myBet).toMatchObject({ stake: "300.000" });
  });

  it("cancelBet refunds the stake and drops the position", async () => {
    const eth = await bettingRoundOf("ETH");
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 5, stake: "250.000" });
    expect((await mockApi.myAccount()).inPlay).toBe("250.000");
    await mockApi.cancelBet(eth.roundId);
    expect(await mockApi.myPositions()).toEqual([]);
    const account = await mockApi.myAccount();
    expect(account.inPlay).toBe("0.000");
    expect(account.balance).toBe("10000.000");
  });

  it("myPositions needs a session, like the real server", async () => {
    await mockApi.signOut();
    await expectApiError(mockApi.myPositions(), "NOT_SIGNED_IN");
  });

  it("the market's card shows the player's own bet in its live split", async () => {
    const eth = await bettingRoundOf("ETH");
    const before = (await mockApi.markets()).markets.find((m) => m.id === "ETH")!.series[0];
    if (before.bettingRoundId !== eth.roundId) return; // the clock rolled over between the calls
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 5, stake: "1000.000" });
    const after = (await mockApi.markets()).markets.find((m) => m.id === "ETH")!.series[0];
    expect(after.participantCount).toBe(before.participantCount + 1);
    expect(Number(after.upStake)).toBeCloseTo(Number(before.upStake) + 1000, 3);
  });
});

describe("settling the player's own bets, in any market", () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    await signIn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("an ETH bet settles with ETH's round, lands in history with its market, and fires an unseen result", async () => {
    const eth = await bettingRoundOf("ETH");
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 10, stake: "500.000" });
    await advanceSimClockTo(eth.window.endMs + 60_000);

    const unseen = await mockApi.unseenResults!();
    expect(unseen).toHaveLength(1);
    expect(unseen[0]).toMatchObject({
      roundId: eth.roundId,
      marketId: "ETH",
      asset: "ETH",
      interval: "HOURLY",
      leverage: 10,
      stake: "500.000",
      settledAtMs: eth.window.endMs - 1,
    });

    // the same number the round page shows for the player's position
    const settled = await mockApi.round(eth.roundId);
    if (settled.phase !== "SETTLED") throw new Error("expected SETTLED");
    const own = settled.positions.find((p) => p.playerId === "me")!;
    expect(own.payout).toBe(unseen[0].payout);
    expect(own.liquidated).toBe(unseen[0].liquidated);
    const ledger = (await mockApi.myLedger()).items;
    expect(ledger[0]).toMatchObject({ type: "SETTLEMENT", roundId: eth.roundId, delta: unseen[0].payout });
    expect(await mockApi.myPositions()).toEqual([]);
    expect((await mockApi.myAccount()).inPlay).toBe("0.000");
    expect(Number((await mockApi.myAccount()).balance)).toBeCloseTo(9500 + Number(unseen[0].payout), 3);
  });

  it("the position is RUNNING with its entry and liquidation price once the round starts", async () => {
    const sol = await bettingRoundOf("SOL");
    await mockApi.placeBet({ roundId: sol.roundId, direction: "DOWN", leverage: 20, stake: "300.000" });
    await advanceSimClockTo(sol.window.startMs + 10 * 60_000);
    const [position] = await mockApi.myPositions();
    expect(position).toMatchObject({ marketId: "SOL", phase: "RUNNING", untilMs: sol.window.endMs });
    const running = await mockApi.round(sol.roundId);
    if (running.phase !== "RUNNING") throw new Error("expected RUNNING");
    const own = running.positions.find((p) => p.playerId === "me")!;
    expect(position.entryPrice).toBe(own.entryPrice);
    expect(position.liquidationPrice).toBe(own.liquidationPrice);
    expect(position.liquidated).toBe(own.liquidated);
    expect(position.entryPrice!.split(".")[1]).toHaveLength(3); // SOL: 3 decimals
  });

  it("a liquidation seen while the round is running is never undone at settlement", async () => {
    // Watch the next DAILY round of several volatile markets through its whole day: every position
    // that has been reported liquidated at some moment stays liquidated at every later moment and
    // at settlement (the extremes of a round only widen).
    const markets = ["PM900106", "PM900105", "PM900102", "SOL", "ETH"];
    const rounds = await Promise.all(markets.map((id) => bettingRoundOf(id, "DAILY")));
    const window = rounds[0].window;
    const seen = new Map<string, Set<string>>(markets.map((id) => [id, new Set<string>()]));
    for (let hour = 1; hour <= 23; hour += 2) {
      await advanceSimClockTo(window.startMs + hour * 3_600_000);
      for (const round of rounds) {
        const running = await mockApi.round(round.roundId);
        if (running.phase !== "RUNNING") throw new Error("expected RUNNING");
        const nowLiquidated = new Set(running.positions.filter((p) => p.liquidated).map((p) => p.playerId));
        for (const id of seen.get(round.marketId)!) expect(nowLiquidated.has(id)).toBe(true);
        seen.set(round.marketId, nowLiquidated);
      }
    }
    expect([...seen.values()].reduce((sum, ids) => sum + ids.size, 0)).toBeGreaterThan(0); // the scenario really contains liquidations
    await advanceSimClockTo(window.endMs + 1_000);
    for (const round of rounds) {
      const settled = await mockApi.round(round.roundId);
      if (settled.phase !== "SETTLED") throw new Error("expected SETTLED");
      for (const id of seen.get(round.marketId)!) {
        const p = settled.positions.find((q) => q.playerId === id)!;
        expect(p.liquidated).toBe(true);
        expect(p.payout).toBe("0.000");
      }
    }
  });
});

describe("the market that closes during a session (Т-10)", () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    await signIn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("is ACTIVE before it closes and CLOSED after, the sports category count follows", async () => {
    const before = await mockApi.markets();
    expect(before.markets.find((m) => m.id === CLOSING.id)!.status).toBe("ACTIVE");
    expect(before.categories.find((c) => c.id === "sports")!.marketCount).toBe(3);

    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    const after = await mockApi.markets();
    const card = after.markets.find((m) => m.id === CLOSING.id)!;
    expect(card.status).toBe("CLOSED"); // still listed, so a card can say why it left
    expect(after.markets).toHaveLength(19);
    expect(after.categories.find((c) => c.id === "sports")!.marketCount).toBe(2);
    expect(after.markets.filter((m) => m.status === "CLOSED").map((m) => m.id)).toEqual([CLOSING.id]);
    // the card says why and when, in the source's own words
    expect(card).toMatchObject({ closedAtMs: MOCK_CLOSING_AT_MS, closeReason: "The source market ended" });
    // its price is frozen at the closing moment
    expect(card.price!.asOfMs).toBe(MOCK_CLOSING_AT_MS);
    expect((await mockApi.referencePrice(CLOSING.id)).asOfMs).toBe(MOCK_CLOSING_AT_MS);
  });

  it("cancels its running and betting rounds, with the market-closed reason", async () => {
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    for (const interval of ["HOURLY", "DAILY"] as const) {
      const pair = await mockApi.rounds(interval, CLOSING.id);
      expect(pair.running.phase).toBe("CANCELLED");
      expect(pair.betting.phase).toBe("CANCELLED");
      if (pair.betting.phase === "CANCELLED") {
        expect(pair.betting.cancellation.reason).toMatch(/Market closed/);
        expect(pair.betting.cancellation.cancelledAtMs).toBe(MOCK_CLOSING_AT_MS);
        expect(pair.betting.cancellation.refundedBetCount).toBeGreaterThan(0);
      }
      expect(await mockApi.round(pair.running.roundId)).toMatchObject({ phase: "CANCELLED", marketId: CLOSING.id });
    }
    // the cancellation log shows them too
    const log = await mockApi.cancellations({ limit: 50 });
    expect(log.items.filter((c) => c.roundId.startsWith(`${CLOSING.id}-`)).length).toBe(4);
    expect(log.items[log.items.length - 1].reason).toMatch(/Automatic cancellation/); // the fixed BTC one stays, oldest
  });

  it("placeBet on it is MARKET_CLOSED", async () => {
    const betting = await bettingRoundOf(CLOSING.id);
    await mockApi.placeBet({ roundId: betting.roundId, direction: "UP", leverage: 5, stake: MIN_STAKE_PLAY }); // open before closing
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    const err = await expectApiError(
      mockApi.placeBet({ roundId: betting.roundId, direction: "UP", leverage: 5, stake: MIN_STAKE_PLAY }),
      "MARKET_CLOSED",
    );
    expect(err.message).toMatch(/closed/);
    const pair = await mockApi.rounds("HOURLY", CLOSING.id);
    await expectApiError(
      mockApi.placeBet({ roundId: pair.betting.roundId, direction: "DOWN", leverage: 5, stake: MIN_STAKE_PLAY }),
      "MARKET_CLOSED",
    );
  });

  it("refunds a bet that was open when it closed: stake back, no settlement, no position, no result moment", async () => {
    const h = await bettingRoundOf(CLOSING.id);
    const d = await bettingRoundOf(CLOSING.id, "DAILY");
    await mockApi.placeBet({ roundId: h.roundId, direction: "UP", leverage: 5, stake: "300.000" });
    await mockApi.placeBet({ roundId: d.roundId, direction: "DOWN", leverage: 7, stake: "200.000" });
    expect((await mockApi.myAccount()).balance).toBe("9500.000");
    expect((await mockApi.myPositions()).map((p) => p.marketId)).toEqual([CLOSING.id, CLOSING.id]);

    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);

    const account = await mockApi.myAccount();
    expect(account.balance).toBe("10000.000");
    expect(account.inPlay).toBe("0.000");
    expect(await mockApi.myPositions()).toEqual([]);
    expect(await mockApi.unseenResults!()).toEqual([]);
    const refunds = (await mockApi.myLedger()).items.filter((e) => e.type === "STAKE_REFUND");
    expect(refunds.map((e) => e.delta).sort()).toEqual(["200.000", "300.000"]);
    expect(refunds.map((e) => e.roundId).sort()).toEqual([d.roundId, h.roundId].sort());
    // the cancelled round counts the player's refunded bet among its refunded bets
    const cancelled = await mockApi.round(h.roundId);
    expect(cancelled).toMatchObject({ phase: "CANCELLED" });
    // and cancelBet on an already refunded bet is a harmless no-op
    await expect(mockApi.cancelBet(h.roundId)).resolves.toBeUndefined();
  });

  it("a round that had already ended when it closed settles normally", async () => {
    const pair = await mockApi.rounds("HOURLY", CLOSING.id);
    const pastId = buildRoundId(CLOSING.id, "HOURLY", pair.running.window.startMs - 3_600_000);
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    expect(await mockApi.round(pastId)).toMatchObject({ phase: "SETTLED", marketId: CLOSING.id });
  });

  it("leaves markets() ten (real) minutes after closing — the card has said why it left", async () => {
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + CLOSED_LISTING_MS - 60_000);
    expect((await mockApi.markets()).markets.map((m) => m.id)).toContain(CLOSING.id);
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + CLOSED_LISTING_MS + 60_000);
    const res = await mockApi.markets();
    expect(res.markets.map((m) => m.id)).not.toContain(CLOSING.id);
    expect(res.markets).toHaveLength(18);
    // …but its rounds stay reachable by id (the registry keeps closed markets)…
    await expect(mockApi.rounds("HOURLY", CLOSING.id)).resolves.toBeDefined();
    // …and it keeps its row in the herd's overview, marked closed, with its own title (`CrowdOverviewDto`)
    const row = (await mockApi.crowdOverview()).markets.find((r) => r.marketId === CLOSING.id);
    expect(row).toMatchObject({ symbol: "GER", title: "Will Germany win against Serbia?", status: "CLOSED" });
  });
});

describe("crowd accounts, one per market (Т-6)", () => {
  it("each market has its own history with holdCapital and antiCrowdCapital, from its own listing day", async () => {
    const btc = await mockApi.crowdAccount();
    expect(btc.marketId).toBe("BTC");
    expect(btc.symbol).toBe("BTC");
    expect(btc.history.length).toBeGreaterThan(250);
    expect(btc.history[0].atMs).toBe(Date.UTC(2026, 0, 2) - 1); // BTC: day one is 2026-01-01, as it always was
    expect(btc.history[0].roundId).toMatch(/^1d-\d+$/);

    const hype = await mockApi.crowdAccount("HYPE");
    expect(hype.marketId).toBe("HYPE");
    expect(hype.history[0].atMs).toBe(Date.UTC(2026, 3, 11) - 1);
    expect(hype.history[0].roundId).toMatch(/^HYPE-1d-\d+$/);
    expect(hype.history.length).toBeLessThan(btc.history.length);

    const odds = await mockApi.crowdAccount("PM900101");
    expect(odds.history.length).toBeLessThan(10);
    expect(odds.history.length).toBeGreaterThan(0);

    for (const account of [btc, hype, odds]) {
      expect(account.capital).toBe(account.history[account.history.length - 1].crowdCapital);
      for (const p of account.history) {
        expect(p.holdCapital).toMatch(/^\d+\.\d{2}$/);
        expect(p.antiCrowdCapital).toMatch(/^\d+\.\d{2}$/);
        expect(p.crowdCapital).toMatch(/^\d+\.\d{2}$/);
      }
      expect(account.history[0].seasonBoundary).not.toBeNull();
    }
    // the hold line starts at the starting capital's neighbourhood: first point = price(day 1) / price(day 0) × 100 000
    expect(Number(hype.history[0].holdCapital)).toBeGreaterThan(80_000);
    expect(Number(hype.history[0].holdCapital)).toBeLessThan(125_000);
  });

  it("markets() card, crowdAccount() and crowdOverview() tell the same story", async () => {
    vi.useFakeTimers();
    try {
      const [{ markets }, overview] = await Promise.all([mockApi.markets(), mockApi.crowdOverview()]);
      expect(overview.markets).toHaveLength(19);
      expect(overview.totalStartingCapital).toBe("1900000.00");
      expect(Number(overview.totalCapital)).toBeCloseTo(overview.markets.reduce((s, m) => s + Number(m.capital), 0), 1);
      expect(overview.returnPct).toBeCloseTo(Number(overview.totalCapital) / 1_900_000 - 1, 6);
      for (const row of overview.markets) {
        const card = markets.find((m) => m.id === row.marketId)!;
        expect(row).toMatchObject({
          symbol: card.symbol,
          title: card.title,
          status: card.status,
          category: card.category,
          capital: card.crowd.capital,
          returnPct: card.crowd.returnPct,
          roundsTraded: card.crowd.roundsTraded,
          roundsWon: card.crowd.roundsWon,
        });
        expect((await mockApi.crowdAccount(row.marketId)).capital).toBe(card.crowd.capital);
        expect(card.crowd.returnPct).toBeCloseTo(Number(card.crowd.capital) / 100_000 - 1, 6);
      }
    } finally {
      vi.useRealTimers();
    }
  });

  it("is deterministic: the same market gives the same history on every call", async () => {
    expect(await mockApi.crowdAccount("ETH")).toEqual(await mockApi.crowdAccount("ETH"));
  });
});

describe("betFeed — the full live feed of a round", () => {
  it("lists a betting round's placed bets oldest first with increasing seq, and pages with sinceSeq", async () => {
    vi.useFakeTimers();
    try {
      const betting = await bettingRoundOf("ETH");
      await advanceSimClockTo(betting.window.startMs - 1_000); // the end of the betting period: every bot has bet
      const feed = await mockApi.betFeed!(betting.roundId);
      expect(feed).toMatchObject({ roundId: betting.roundId, marketId: "ETH", asset: "ETH", hasMore: false });
      expect(feed.entries.length).toBeGreaterThan(5);
      for (let i = 1; i < feed.entries.length; i++) {
        expect(feed.entries[i].seq).toBeGreaterThan(feed.entries[i - 1].seq);
        expect(feed.entries[i].atMs).toBeGreaterThanOrEqual(feed.entries[i - 1].atMs);
      }
      const cursor = feed.entries[2].seq;
      const rest = await mockApi.betFeed!(betting.roundId, cursor);
      expect(rest.entries.map((e) => e.seq)).toEqual(feed.entries.filter((e) => e.seq > cursor).map((e) => e.seq));
      for (const e of feed.entries) {
        expect(e.isBot).toBe(false); // the mock NPCs are plain players
        expect(e.leverage).toBeLessThanOrEqual(25);
      }
    } finally {
      vi.useRealTimers();
    }
  });

  it("grows as the betting period goes on", async () => {
    vi.useFakeTimers();
    try {
      const betting = await bettingRoundOf("SOL");
      const early = await mockApi.betFeed!(betting.roundId);
      await advanceSimClockTo(betting.window.startMs - 1_000);
      const late = await mockApi.betFeed!(betting.roundId);
      expect(late.entries.length).toBeGreaterThanOrEqual(early.entries.length);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("what did not change for BTC and the old callers", () => {
  it("home() still describes BTC: hourly + daily pairs, the BTC crowd account, ratings", async () => {
    const home = await mockApi.home();
    expect(home.hourly.betting.marketId).toBe("BTC");
    expect(home.hourly.betting.roundId).toMatch(/^1h-\d+$/);
    expect(home.daily.betting.roundId).toMatch(/^1d-\d+$/);
    expect(home.crowd.marketId).toBe("BTC");
    expect(home.topRich.length).toBeGreaterThan(0);
  });

  it("serverInfo stays unimplemented in the mock (there is no server to report on)", () => {
    expect(mockApi.serverInfo).toBeUndefined();
  });

  it("NPC histories name a market and respect its leverage cap", async () => {
    for (const id of ["npc-0", "npc-3", "npc-17", "npc-39"]) {
      const profile = await mockApi.publicProfile(id);
      for (const entry of profile.history) {
        const parsed = parseRoundId(entry.roundId)!;
        expect(parsed.marketId).toBe(entry.marketId);
        const spec = mockMarketById(entry.marketId)!;
        expect(entry.asset).toBe(spec.symbol);
        expect(entry.leverage).toBeLessThanOrEqual(spec.maxLeverage);
      }
    }
    const markets = new Set((await Promise.all(Array.from({ length: 40 }, (_, i) => mockApi.publicProfile(`npc-${i}`)))).flatMap((p) => p.history.map((h) => h.marketId)));
    expect(markets.has("BTC")).toBe(true);
    expect(markets.size).toBeGreaterThan(4);
  });

  it("a signed-in player's profile carries a win streak", async () => {
    await signIn();
    const profile = await mockApi.myProfile();
    expect(profile.streak).toEqual({ current: 0, best: 0 });
  });
});

describe("priceSeries — the contract's wording (ticks with atMs > sinceMs, at most 600, thinned evenly beyond)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("with a sinceMs on a tick, that tick is NOT in the answer — only strictly newer ones", async () => {
    const first = await mockApi.priceSeries("ETH");
    const since = first.ticks[first.ticks.length - 5].atMs;
    const next = await mockApi.priceSeries("ETH", since);
    expect(next.ticks.map((t) => t.atMs)).toEqual(first.ticks.slice(-4).map((t) => t.atMs));
  });

  it("a very old sinceMs gets 600 ticks spread over the WHOLE range — from its start to the newest tick — not the newest 600 in a row", async () => {
    const since = simNowMs() - 3 * 86_400_000;
    const res = await mockApi.priceSeries("BTC", since);
    expect(res.ticks).toHaveLength(600);
    expect(res.ticks[0].atMs - since).toBeLessThan(TICK_MS + 1); // the range starts at the first tick after sinceMs
    expect(simNowMs() - res.ticks[res.ticks.length - 1].atMs).toBeLessThan(TICK_MS); // …and ends at the newest
    const gaps = res.ticks.slice(1).map((t, i) => t.atMs - res.ticks[i].atMs);
    expect(Math.max(...gaps) - Math.min(...gaps)).toBeLessThanOrEqual(TICK_MS); // evenly thinned
    expect(Math.min(...gaps)).toBeGreaterThan(TICK_MS); // really thinned: more than one grid step apart
  });

  it("the default (no sinceMs) is the last 15 minutes, unthinned", async () => {
    const res = await mockApi.priceSeries("SOL");
    expect(res.ticks.length).toBeGreaterThan(290);
    expect(res.ticks.length).toBeLessThanOrEqual(300);
    for (let i = 1; i < res.ticks.length; i++) expect(res.ticks[i].atMs - res.ticks[i - 1].atMs).toBe(TICK_MS);
  });
});

describe("a market listed moments ago — the round window it was handed has no opening price (RoundRunning.openPrice === null)", () => {
  const FRESH = mockMarketById(MOCK_NEW_LISTING_ID)!;

  beforeEach(async () => {
    vi.useFakeTimers();
    await signIn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("both series' RUNNING rounds are open-price-less: no price, no positions, no crowd position", async () => {
    for (const interval of ["HOURLY", "DAILY"] as const) {
      const pair = await mockApi.rounds(interval, FRESH.id);
      expect(pair.running).toMatchObject({ phase: "RUNNING", openPrice: null, positions: [], crowd: null, marketId: FRESH.id });
      // betting on the NEXT round is open as usual, with its own bots
      expect(pair.betting.phase).toBe("BETTING");
      expect((pair.betting as RoundBetting).participantCount).toBeGreaterThan(0);
    }
    expect(await mockApi.round((await mockApi.rounds("HOURLY", FRESH.id)).running.roundId)).toMatchObject({ phase: "RUNNING", openPrice: null });
  });

  it("the card is there with a price (the first ticks have come) — and no 24 h change, which the source cannot give yet", async () => {
    const card = (await mockApi.markets()).markets.find((m) => m.id === FRESH.id)!;
    expect(card.status).toBe("ACTIVE");
    expect(card.price).not.toBeNull();
    expect(card.price!.change24h).toBeNull();
    expect(card.crowd.roundsTraded).toBe(0); // listed moments ago: the herd has not traded here
  });

  it("every other market has its opening price, always", async () => {
    for (const spec of MOCK_MARKETS.filter((m) => m.id !== FRESH.id)) {
      const pair = await mockApi.rounds("HOURLY", spec.id);
      expect(pair.running.phase === "RUNNING" && pair.running.openPrice !== null).toBe(true);
    }
  });

  it("when the window ends the next round has its price and its positions — and the window that never opened is cancelled, not settled", async () => {
    const before = await mockApi.rounds("HOURLY", FRESH.id);
    const neverOpened = before.running.roundId;
    await advanceSimClockTo(before.running.window.endMs + 60_000);

    const after = await mockApi.rounds("HOURLY", FRESH.id);
    expect(after.running.roundId).not.toBe(neverOpened);
    expect(after.running.phase === "RUNNING" && after.running.openPrice !== null && after.running.positions.length > 0).toBe(true);
    const old = await mockApi.round(neverOpened);
    expect(old).toMatchObject({ phase: "CANCELLED", marketId: FRESH.id });
    if (old.phase === "CANCELLED") expect(old.cancellation.reason).toMatch(/No opening price/);
  });

  it("a bet on the next round becomes a RUNNING position with its entry, liquidation price and size once that round starts", async () => {
    const betting = await bettingRoundOf(FRESH.id);
    await mockApi.placeBet({ roundId: betting.roundId, direction: "UP", leverage: 5, stake: "400.000" });
    await advanceSimClockTo(betting.window.startMs + 60_000);
    const [position] = await mockApi.myPositions();
    expect(position).toMatchObject({ marketId: FRESH.id, phase: "RUNNING", durationSec: 3600, stake: "400.000", liquidated: false });
    expect(position.entryPrice).not.toBeNull();
    expect(position.liquidationPrice).not.toBeNull();
    expect(position.size).toMatch(/^\d+\.\d{8}$/);
  });

  it("its price series starts at its first price, not a day before it", async () => {
    const res = await mockApi.priceSeries(FRESH.id, simNowMs() - 86_400_000);
    expect(res.ticks.length).toBeGreaterThan(0);
    expect(res.ticks[0].atMs).toBeGreaterThanOrEqual(FRESH.priceFromMs!);
  });
});

describe("what a market card says about a game, a close and the oracle (kick-off, closeReason, oracleIndex)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("a sports game has a kick-off and no end date; the rest have no kick-off", async () => {
    const { markets } = await mockApi.markets();
    for (const m of markets) {
      if (m.category === "sports") {
        expect(m.endsAtMs).toBeNull();
        expect(m.eventStartsAtMs).toBeGreaterThan(simNowMs());
      } else {
        expect(m.eventStartsAtMs).toBeNull();
      }
    }
  });

  it("the game that closes kicks off first: upcoming, then live, then closed with its reason", async () => {
    const card = async () => (await mockApi.markets()).markets.find((m) => m.id === CLOSING.id)!;
    expect((await card()).eventStartsAtMs!).toBeGreaterThan(simNowMs());
    await advanceSimClockTo(CLOSING.eventStartsAtMs! + 60_000);
    expect(simNowMs()).toBeGreaterThan((await card()).eventStartsAtMs!);
    expect((await card()).status).toBe("ACTIVE"); // kicked off, still taking bets: the card says LIVE
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    expect(await card()).toMatchObject({ status: "CLOSED", closedAtMs: MOCK_CLOSING_AT_MS, closeReason: "The source market ended" });
  });

  it("every market names its place in the oracle record: BTC is 0, the rest in listing order", async () => {
    const { markets } = await mockApi.markets();
    expect(markets[0]).toMatchObject({ id: "BTC", oracleIndex: 0 });
    expect(markets.map((m) => m.oracleIndex)).toEqual(markets.map((_, i) => i));
  });
});

describe("the player's own positions and history carry their round's real length (durationSec) and size", () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    await signIn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("a waiting bet has the length and no size; a running one has the size the server opened, as 8 dp lots", async () => {
    const eth = await bettingRoundOf("ETH");
    const gold = await bettingRoundOf("xyz_GOLD", "DAILY");
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 10, stake: "500.000" });
    await mockApi.placeBet({ roundId: gold.roundId, direction: "DOWN", leverage: 20, stake: "300.000" });

    const waiting = await mockApi.myPositions();
    expect(waiting.find((p) => p.marketId === "ETH")).toMatchObject({ phase: "BETTING", interval: "HOURLY", durationSec: 3600, size: null });
    expect(waiting.find((p) => p.marketId === "xyz_GOLD")).toMatchObject({ phase: "BETTING", interval: "DAILY", durationSec: 86_400, size: null });

    await advanceSimClockTo(eth.window.startMs + 60_000);
    const running = (await mockApi.myPositions()).find((p) => p.marketId === "ETH")!;
    const spec = mockMarketById("ETH")!;
    expect(running).toMatchObject({ phase: "RUNNING", durationSec: 3600 });
    // the size the server opened = the stake at the round's entry price, exactly the engine's rule
    expect(running.size).toBe(sizeForMargin("500.000", 10, running.entryPrice!, spec).toFixed(8));
  });

  it("a settled bet's history row says how long its round was — for any series", async () => {
    const eth = await bettingRoundOf("ETH");
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 5, stake: "200.000" });
    await advanceSimClockTo(eth.window.endMs + 60_000);
    const [row] = (await mockApi.myProfile()).history;
    expect(row).toMatchObject({ marketId: "ETH", interval: "HOURLY", durationSec: 3600 });
    expect((await mockApi.unseenResults!())[0]).toMatchObject({ durationSec: 3600 });
  });

  it("NPC histories carry it too", async () => {
    const profile = await mockApi.publicProfile("npc-3");
    for (const entry of profile.history) expect(entry.durationSec).toBe(entry.interval === "HOURLY" ? 3600 : 86_400);
  });
});

describe("the contract's round fields: every round carries its market's kind, price scale and source", () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    await signIn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it.each(MOCK_MARKETS.map((m) => m.id))("%s: running, betting and a settled round all say so", async (marketId) => {
    const spec = mockMarketById(marketId)!;
    const pair = await mockApi.rounds("HOURLY", marketId);
    const settled = await mockApi.round(buildRoundId(marketId, "HOURLY", pair.running.window.startMs - 3_600_000));
    for (const round of [pair.running, pair.betting, settled]) {
      expect(round).toMatchObject({ kind: spec.kind, priceDecimals: spec.priceDecimals, source: spec.source });
    }
  });

  it("a cancelled round says so too, and its cancellation names the market (and its symbol)", async () => {
    await advanceSimClockTo(MOCK_CLOSING_AT_MS + 60_000);
    const pair = await mockApi.rounds("HOURLY", CLOSING.id);
    if (pair.betting.phase !== "CANCELLED") throw new Error("expected CANCELLED");
    expect(pair.betting).toMatchObject({ kind: "ODDS", priceDecimals: 6, source: "polymarket" });
    expect(pair.betting.cancellation).toMatchObject({ marketId: CLOSING.id, asset: CLOSING.symbol });
    const btc = (await mockApi.cancellations({ limit: 50 })).items.find((c) => c.reason.startsWith("Automatic cancellation"))!;
    expect(btc).toMatchObject({ marketId: "BTC", asset: "BTC" });
    // every row of the log names its market
    for (const row of (await mockApi.cancellations({ limit: 50 })).items) expect(row.asset).toBeTruthy();
  });

  it("the fixed cancelled BTC round says it was cancelled after five minutes without a price, as the server does (Р-79), not after an hour", async () => {
    const btc = (await mockApi.cancellations({ limit: 50 })).items.find((c) => c.reason.startsWith("Automatic cancellation"))!;
    expect(btc.reason).toContain("within 5 minutes of this round's close");
    expect(btc.reason).not.toMatch(/1 hour/);
    const round = await mockApi.round(btc.roundId);
    if (round.phase !== "CANCELLED") throw new Error("expected CANCELLED");
    expect(round.cancellation.reason).toBe(btc.reason);
    expect(round.cancellation.cancelledAtMs - round.window.endMs).toBe(5 * 60_000);
  });

  it("a settled bet's history row names its market's kind", async () => {
    const odds = await bettingRoundOf("PM900101");
    await mockApi.placeBet({ roundId: odds.roundId, direction: "UP", leverage: 3, stake: "200.000" });
    await advanceSimClockTo(odds.window.endMs + 60_000);
    const [row] = (await mockApi.myProfile()).history;
    expect(row).toMatchObject({ marketId: "PM900101", kind: "ODDS" });
  });
});

describe("'not found' is the contract's code NOT_FOUND — the web never has to read the message", () => {
  beforeEach(async () => {
    await signIn();
  });

  it("an unknown or off-grid round, an unknown player, an unknown withdrawal", async () => {
    await expectApiError(mockApi.round("garbage"), "NOT_FOUND");
    await expectApiError(mockApi.round("ETH-1h-3600001"), "NOT_FOUND");
    await expectApiError(mockApi.betFeed!("garbage"), "NOT_FOUND");
    await expectApiError(mockApi.publicProfile("no-such-player"), "NOT_FOUND");
    await expectApiError(mockApi.withdrawal("nope"), "NOT_FOUND");
  });

  it("an unknown MARKET stays MARKET_NOT_FOUND", async () => {
    await expectApiError(mockApi.round("NOPE-1h-3600000"), "MARKET_NOT_FOUND");
  });
});

describe("the crowd's last outcome: a flat round is FLAT, not a loss (MarketCrowdBriefDto.lastOutcome)", () => {
  it("a liquidation first, then flat, then won, else lost", () => {
    expect(crowdOutcomeOf({ liquidated: true, flat: false, won: false })).toBe("LIQUIDATED");
    expect(crowdOutcomeOf({ liquidated: false, flat: true, won: false })).toBe("FLAT");
    expect(crowdOutcomeOf({ liquidated: false, flat: false, won: true })).toBe("WIN");
    expect(crowdOutcomeOf({ liquidated: false, flat: false, won: false })).toBe("LOSS");
  });

  it("every market's brief has a last outcome from the contract's set, and wins never exceed the rounds traded", async () => {
    for (const m of (await mockApi.markets()).markets) {
      expect([null, "WIN", "LOSS", "LIQUIDATED", "FLAT"]).toContain(m.crowd.lastOutcome);
      expect(m.crowd.roundsWon).toBeLessThanOrEqual(m.crowd.roundsTraded);
    }
  });
});
