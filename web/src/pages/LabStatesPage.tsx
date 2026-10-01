/**
 * Hidden "/lab/states" — every designed STATE of the Р-69 screens on one page, drawn by the real components
 * from fixed data, so the states that a live stand rarely produces (a stale price, a market with no price
 * yet, a closed one, each way an oracle record can be, a zero win streak, a liquidation pinned off the chart)
 * can be looked at, screenshotted and argued about without waiting for them. Same rule as `/lab`: not in any
 * nav, nothing here touches a session or money, and the data below is invented.
 */
import { useState } from "react";
import type { Direction, MarketDto, MyPositionDto } from "../api/contract";
import { AnchorLine } from "../components/AnchorLine";
import { HerdPicker } from "../components/BetSlip";
import { LazyCrowdChart } from "../components/LazyCrowdChart";
import { MarketCard } from "../components/MarketCard";
import { PositionCard } from "../components/PositionsStrip";
import { PriceChart } from "../components/PriceChart";
import { buildChartLines } from "../components/priceChartModel";
import { StreakBadge } from "../components/StreakBadge";

/** A fixed "now" so every countdown on the page reads the same and the page does not tick. */
const NOW = Date.UTC(2026, 9, 1, 12, 0, 0);
const NOOP = () => {};

function series(over: Partial<MarketDto["series"][number]> = {}): MarketDto["series"][number] {
  return {
    interval: "HOURLY",
    durationSec: 180,
    bettingClosesAtMs: NOW + 72_000,
    bettingRoundId: "lab-1",
    runningRoundId: "lab-0",
    runningEndsAtMs: NOW + 72_000,
    participantCount: 14,
    upStake: "3800.000",
    downStake: "2400.000",
    majorityDirection: "UP",
    ...over,
  };
}

function market(id: string, over: Partial<MarketDto> = {}): MarketDto {
  return {
    id,
    kind: "PERP",
    category: "crypto",
    symbol: id,
    title: id,
    subtitle: null,
    iconUrl: null,
    maxLeverage: 40,
    priceDecimals: 2,
    status: "ACTIVE",
    endsAtMs: null,
    source: "hyperliquid",
    price: { price: "83451.00", asOfMs: NOW - 800, change24h: 0.0098, stale: false },
    series: [series(), series({ interval: "DAILY", durationSec: 900 })],
    crowd: { capital: "103400.00", returnPct: 0.034, roundsTraded: 20, roundsWon: 12, lastOutcome: "WIN" },
    ...over,
  };
}

const CARDS: Array<{ label: string; market: MarketDto; hot?: boolean; favorite?: boolean }> = [
  { label: "Live perp", market: market("BTC", { title: "Bitcoin" }), hot: true, favorite: true },
  {
    label: "Odds market",
    market: market("RUNOFF", {
      kind: "ODDS",
      category: "politics",
      title: "Will Brazil's 2026 presidential election go to a runoff?",
      subtitle: "Yes · Brazil election",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      endsAtMs: NOW + 5 * 3_600_000 + 12 * 60_000,
      price: { price: "0.580000", asOfMs: NOW - 500, change24h: -0.0212, stale: false },
      crowd: { capital: "98600.00", returnPct: -0.014, roundsTraded: 9, roundsWon: 3, lastOutcome: "LOSS" },
      series: [series({ upStake: "300.000", downStake: "900.000", majorityDirection: "DOWN", participantCount: 6 }), series({ interval: "DAILY", durationSec: 900 })],
    }),
  },
  {
    label: "Stale price",
    market: market("NVDA", { title: "NVIDIA", category: "stocks", price: { price: "231.70", asOfMs: NOW - 190_000, change24h: 0.0021, stale: true } }),
  },
  {
    label: "No price yet",
    market: market("NEW", { title: "A market that has just been listed", price: null, crowd: { capital: "100000.00", returnPct: 0, roundsTraded: 0, roundsWon: 0, lastOutcome: null }, series: [series({ participantCount: 0, upStake: "0.000", downStake: "0.000", majorityDirection: null }), series({ interval: "DAILY", durationSec: 900 })] }),
  },
  {
    label: "Closed",
    market: market("GER", {
      kind: "ODDS",
      category: "sports",
      status: "CLOSED",
      title: "Will Germany win against Serbia?",
      subtitle: "Germany · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      price: { price: "0.710000", asOfMs: NOW - 400_000, change24h: 0.04, stale: false },
    }),
  },
];

const POSITIONS: Array<{ label: string; position: MyPositionDto; price: string }> = [
  {
    label: "Waiting for its round",
    price: "83451.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-a", interval: "HOURLY", phase: "BETTING", direction: "UP", leverage: 15, stake: "500.000", entryPrice: null, liquidationPrice: null, liquidated: false, untilMs: NOW + 41_000 },
  },
  {
    label: "Running, in profit",
    price: "83900.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-b", interval: "HOURLY", phase: "RUNNING", direction: "UP", leverage: 15, stake: "500.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: false, untilMs: NOW + 95_000 },
  },
  {
    label: "Running, losing, close to liquidation",
    price: "78900.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-c", interval: "DAILY", phase: "RUNNING", direction: "UP", leverage: 15, stake: "300.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: false, untilMs: NOW + 640_000 },
  },
  {
    label: "Liquidated",
    price: "77000.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-d", interval: "HOURLY", phase: "RUNNING", direction: "UP", leverage: 15, stake: "300.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: true, untilMs: NOW + 20_000 },
  },
];

const TICKS = Array.from({ length: 300 }, (_, i) => ({
  atMs: NOW - (299 - i) * 3_000,
  price: (83451 + Math.sin(i / 7) * 60 + i * 0.4).toFixed(2),
}));

const HASH = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

/** Three and a half hours of a crowd account on a stand with 3-minute rounds: a point every 3 minutes. */
const SHORT_HISTORY = Array.from({ length: 70 }, (_, i) => {
  const wave = Math.sin(i / 5) * 900 + i * 25;
  return {
    roundId: `lab-short-${i}`,
    atMs: NOW - (69 - i) * 180_000,
    seasonBoundary: null,
    crowdCapital: (100_000 + wave).toFixed(2),
    holdCapital: (100_000 + Math.cos(i / 8) * 600).toFixed(2),
    antiCrowdCapital: (100_000 - wave * 0.6).toFixed(2),
  };
});

export function LabStatesPage() {
  const [favorites, setFavorites] = useState<ReadonlySet<string>>(new Set(["BTC"]));
  const [direction, setDirection] = useState<Direction>("UP");
  const market0 = CARDS[0].market;
  const lines = buildChartLines({
    openPrice: "83451.00",
    own: { direction: "UP", leverage: 15, entryPrice: "83451.00", liquidationPrice: "78000.00" },
    crowd: { direction: "DOWN", leverage: 9, entryPrice: "83451.00", liquidationPrice: "83700.00" },
  });

  return (
    <div className="stack" style={{ gap: 24 }}>
      <div className="card">
        <h1>States lab</h1>
        <p className="muted">
          Hidden page: every designed state of the market cards, the positions strip, the price chart, the anchor line and the win streak,
          from invented data. Nothing here is a market or a position.
        </p>
      </div>

      <section className="lab-section">
        <h2>Market cards</h2>
        <div className="stack" style={{ gap: 14 }}>
          {CARDS.map((c) => (
            <div key={c.label}>
              <p className="faint">{c.label}</p>
              <MarketCard
                market={c.market}
                nowMs={NOW}
                hot={c.hot}
                favorite={favorites.has(c.market.id) || c.favorite === true}
                onToggleFavorite={(id) =>
                  setFavorites((prev) => {
                    const next = new Set(prev);
                    if (!next.delete(id)) next.add(id);
                    return next;
                  })
                }
              />
            </div>
          ))}
        </div>
      </section>

      <section className="lab-section">
        <h2>Positions strip cards</h2>
        <div className="stack" style={{ gap: 12, alignItems: "flex-start" }}>
          {POSITIONS.map((p) => (
            <div key={p.label}>
              <p className="faint">{p.label}</p>
              <PositionCard
                position={p.position}
                nowMs={NOW}
                market={market("BTC", { title: "Bitcoin", price: { price: p.price, asOfMs: NOW, change24h: 0.01, stale: false } })}
              />
            </div>
          ))}
        </div>
      </section>

      <section className="lab-section">
        <h2>Price chart — with a position (entry = round open; the player's liquidation is far, so it is pinned to the edge)</h2>
        <PriceChart
          market={market0}
          ticks={TICKS}
          current={{ price: "83580.00", asOfMs: NOW }}
          lines={lines}
          roundStartMs={NOW - 140_000}
          loading={false}
          error={false}
        />
      </section>

      <section className="lab-section">
        <h2>Crowd chart over a short history — the axis shows the time of day, the chips are hours</h2>
        <LazyCrowdChart history={SHORT_HISTORY} variant="full" symbol="BTC" />
      </section>

      <section className="lab-section">
        <h2>With the herd / Against the herd</h2>
        <p className="faint">The herd is on UP</p>
        <HerdPicker herd="UP" direction={direction} onPick={setDirection} />
        <p className="faint">No herd yet</p>
        <HerdPicker herd={null} direction={direction} onPick={NOOP} />
      </section>

      <section className="lab-section">
        <h2>Anchored on Chia — every way an oracle record can be</h2>
        <div className="stack" style={{ gap: 10 }}>
          <AnchorLine roundId="BTC-1h-1" resultsHash={HASH} anchor={{ seq: 412, state: "confirmed", height: 4_738_927 }} />
          <AnchorLine roundId="BTC-1h-2" anchor={{ seq: 413, state: "in_flight", height: null }} />
          <AnchorLine roundId="BTC-1h-3" anchor={{ seq: 414, state: "queued", height: null }} />
          <AnchorLine roundId="BTC-1h-4" anchor={{ seq: 0, state: "unknown", height: null }} />
          <AnchorLine roundId="BTC-1h-5" resultsHash={HASH} anchor={{ seq: 415, state: "conflict", height: null }} />
        </div>
      </section>

      <section className="lab-section">
        <h2>Win streak</h2>
        <div className="stack" style={{ gap: 10 }}>
          <StreakBadge current={5} best={7} />
          <StreakBadge current={1} best={1} />
          <StreakBadge current={0} best={4} />
          <StreakBadge current={0} best={0} />
        </div>
      </section>
    </div>
  );
}
