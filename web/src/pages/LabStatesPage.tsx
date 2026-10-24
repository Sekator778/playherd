/**
 * Hidden "/lab/states" — every designed STATE of the Р-69 screens on one page, drawn by the real components
 * from fixed data, so the states that a live stand rarely produces (a stale price, a market with no price
 * yet, a closed one with the server's reason, a game before and after kick-off, a round with no opening price,
 * each way an oracle record can be, a zero win streak, a liquidation pinned off the chart, the mid-round
 * liquidation moment and its quiet settlement summary) can be looked at, screenshotted and argued about without
 * waiting for them. Same rule as `/lab`: not in any nav, nothing here touches a session or money, and the data
 * below is invented.
 */
import { useState } from "react";
import { Link } from "react-router-dom";
import type { Direction, MarketDto, MyPositionDto } from "../api/contract";
import { reportEngineNotReady, reportEngineReady } from "../api/engineState";
import { AnchorLine } from "../components/AnchorLine";
import { HerdPicker } from "../components/BetSlip";
import { OracleDisclosureLine, RoundVenueNote } from "../components/Common";
import { EngineNotice } from "../components/EngineBanner";
import { LazyCrowdChart } from "../components/LazyCrowdChart";
import { MarketCard } from "../components/MarketCard";
import { PositionCard } from "../components/PositionsStrip";
import { PriceChart } from "../components/PriceChart";
import { buildChartLines } from "../components/priceChartModel";
import { CrowdPositionCard } from "../components/RoundParts";
import { StreakBadge } from "../components/StreakBadge";
import { LastResults } from "../components/LastResults";
import { OwnBetWaiting, WaitingForOpenCard } from "../components/WaitingForOpen";
import { liquidationInfoOf, liquidationMomentEvent, quietSummaryOf, refundedNoteEvent } from "../fx/liquidationMoment";
import { publish } from "../fx/resultEventBus";

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
    label: "A crypto market on its main exchange, with a backup behind it (Р-76) — nothing to mark while the price is Coinbase's own",
    market: market("SOL", { title: "Solana", source: "coinbase", backupSource: "kraken", price: { price: "117.84", asOfMs: NOW - 600, change24h: 0.0187, stale: false, venue: null } }),
  },
  {
    label: "The same market while Coinbase has no fresh price — the number is Kraken's, and the card says so under it",
    market: market("ETH", {
      title: "Ethereum",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "2696.80", asOfMs: NOW - 800, change24h: -0.0141, stale: false, venue: "kraken" },
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
    label: "Closed — the server sent no reason (the generic line)",
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
  {
    label: "Closed — with the server's own reason, as it sent it",
    market: market("POR", {
      kind: "ODDS",
      category: "sports",
      status: "CLOSED",
      title: "Will Portugal win against Denmark?",
      subtitle: "Portugal · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      closedAtMs: NOW - 180_000,
      closeReason: "Price left the 3%–97% range",
      price: { price: "0.970000", asOfMs: NOW - 180_000, change24h: null, stale: false },
    }),
  },
  {
    label: "Closed — the market moved to a new price source (Р-76: the server's reason as it sent it; open bets were refunded)",
    market: market("XRP", {
      status: "CLOSED",
      title: "XRP",
      source: "hyperliquid",
      priceDecimals: 4,
      closedAtMs: NOW - 240_000,
      closeReason: "Moved to a new price source: Coinbase",
      price: { price: "1.4964", asOfMs: NOW - 240_000, change24h: 0.0042, stale: false },
    }),
  },
  {
    label: "A game before kick-off — 'Starts in 2h 05m'; the odds' 24 h change is in points (0.64 → 0.65 is +1.0 pt)",
    market: market("NOR", {
      kind: "ODDS",
      category: "sports",
      title: "Will Norway win against Wales?",
      subtitle: "Norway · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      endsAtMs: null,
      eventStartsAtMs: NOW + 125 * 60_000 + 30_000,
      price: { price: "0.650000", asOfMs: NOW - 500, change24h: 0.65 / 0.64 - 1, stale: false },
    }),
  },
  {
    label: "The same game after kick-off — a LIVE mark",
    market: market("SWE", {
      kind: "ODDS",
      category: "sports",
      title: "Will Sweden win against Estonia?",
      subtitle: "Sweden · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      endsAtMs: null,
      eventStartsAtMs: NOW - 25 * 60_000,
      price: { price: "0.380000", asOfMs: NOW - 500, change24h: 0.38 / 0.41 - 1, stale: false },
    }),
  },
];

const POSITIONS: Array<{ label: string; position: MyPositionDto; price: string }> = [
  {
    label: "Waiting for its round",
    price: "83451.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-a", interval: "HOURLY", durationSec: 180, phase: "BETTING", direction: "UP", leverage: 15, stake: "500.000", entryPrice: null, liquidationPrice: null, liquidated: false, untilMs: NOW + 41_000 },
  },
  {
    label: "Running, in profit",
    price: "83900.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-b", interval: "HOURLY", durationSec: 180, phase: "RUNNING", direction: "UP", leverage: 15, stake: "500.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: false, untilMs: NOW + 95_000 },
  },
  {
    label: "Running, losing, close to liquidation",
    price: "78900.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-c", interval: "DAILY", durationSec: 900, phase: "RUNNING", direction: "UP", leverage: 15, stake: "300.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: false, untilMs: NOW + 640_000 },
  },
  {
    label: "Running, but the round has no opening price yet — no P&L, no meter, no zero",
    price: "83900.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-e", interval: "HOURLY", durationSec: 180, phase: "RUNNING", direction: "UP", leverage: 15, stake: "500.000", entryPrice: null, liquidationPrice: null, size: null, liquidated: false, untilMs: NOW + 70_000 },
  },
  {
    label: "Liquidated",
    price: "77000.00",
    position: { marketId: "BTC", symbol: "BTC", roundId: "lab-d", interval: "HOURLY", durationSec: 180, phase: "RUNNING", direction: "UP", leverage: 15, stake: "300.000", entryPrice: "83451.00", liquidationPrice: "78000.00", liquidated: true, untilMs: NOW + 20_000 },
  },
];

/** A liquidated running position — what `myPositions()` reports the instant the path touches its liquidation price. */
const LIQUIDATED_POSITION: MyPositionDto = {
  marketId: "BTC",
  symbol: "BTC",
  roundId: "BTC-1h-lab-liq",
  interval: "HOURLY",
  durationSec: 180,
  phase: "RUNNING",
  direction: "UP",
  leverage: 25,
  stake: "300.000",
  entryPrice: "83451.00",
  liquidationPrice: "80100.00",
  size: "0.08986000",
  liquidated: true,
  untilMs: NOW + 90_000,
};

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
        <h2>A round with no opening price yet (openPrice is null)</h2>
        <p className="faint">
          The round page and the room put this where the crowd's position and the list of players would be; the player's own bet (from
          myPositions) gets its own card. No figure anywhere — never a zero.
        </p>
        <div className="stack" style={{ gap: 12 }}>
          <WaitingForOpenCard symbol="BTC" length="3 MIN" />
          <OwnBetWaiting position={{ direction: "UP", leverage: 15, stake: "500.000", symbol: "BTC" }} />
        </div>
      </section>

      <section className="lab-section">
        <h2>The liquidation moment in the middle of the round, and its quiet summary at settlement</h2>
        <p className="faint">
          Tap to fire them through the same bus the real watchers use: the first is the moment when myPositions() first reports the position
          liquidated (the round is still running); the second is what the settlement of that same round shows instead of a second flash.
        </p>
        <div className="lab-row">
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => publish({ ...liquidationMomentEvent(LIQUIDATED_POSITION, "lab", Date.now()), id: `lab-liq-${Date.now()}` })}
          >
            Fire: liquidated mid-round
          </button>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() =>
              publish(quietSummaryOf({ ...liquidationMomentEvent(LIQUIDATED_POSITION, "lab", Date.now()), id: `lab-quiet-${Date.now()}` }))
            }
          >
            Fire: the quiet summary at settlement
          </button>
        </div>
        <p className="faint">
          Also: <Link to="/status">the Status page</Link> (public) and <Link to="/lab">every result moment ×tier</Link>.
        </p>
      </section>

      <section className="lab-section">
        <h2>A liquidated bet whose round was cancelled — the stake is refunded</h2>
        <p className="faint">
          The moment said "you lose most of your stake… if it is cancelled, every bet in it is refunded". When that happens the position just
          leaves the strip and the balance goes up — so one quiet line says it (and a player who was away is told when the app opens).
        </p>
        <div className="lab-row">
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() =>
              publish(
                refundedNoteEvent(
                  { roundId: "lab-cancelled", marketId: "BTC", asset: "BTC", window: { interval: "HOURLY", startMs: NOW - 180_000, endMs: NOW } },
                  `lab-${Date.now()}`,
                  liquidationInfoOf(LIQUIDATED_POSITION),
                  Date.now(),
                ),
              )
            }
          >
            Fire: round cancelled — stake refunded
          </button>
        </div>
      </section>

      <section className="lab-section">
        <h2>The server is restarting (503 ENGINE_NOT_READY) — a notice, not an error</h2>
        <p className="faint">
          Every screen keeps what it shows and loads again by itself; a button that needs the server says so. Below: the notice as it sits
          under the header, and the switch that turns the real one on for this page (it clears itself when the server answers).
        </p>
        <EngineNotice />
        <div className="lab-row">
          <button type="button" className="btn btn--secondary" onClick={() => reportEngineNotReady()}>
            Show the banner on this page
          </button>
          <button type="button" className="btn btn--secondary" onClick={() => reportEngineReady(Date.now() + 1)}>
            Back to normal
          </button>
        </div>
      </section>

      <section className="lab-section">
        <h2>The crowd's round that closed where it opened — flat, neither a win nor a loss</h2>
        <div className="stack" style={{ gap: 10, maxWidth: 420 }}>
          <CrowdPositionCard
            phase="SETTLED"
            crowd={{
              roundId: "lab-flat",
              traded: true,
              direction: "UP",
              leverage: 7,
              margin: "10000.00",
              entryPrice: "83451.00",
              liquidated: false,
              capitalBefore: "103400.00",
              capitalAfter: "103400.00",
            }}
            market={market("BTC")}
          />
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
        <h2>The backup exchange (Р-76) — the oracle line, a round that runs on it, and the chart while the main exchange is silent</h2>
        <p className="faint">
          A crypto market names its backup in the oracle line. A round runs entirely on one exchange, chosen when it starts — when that was
          the backup, the round says so, running and settled. The chart is the main exchange's ticks alone: while it is silent the line
          stops, and only the latest point is the backup's, which its legend says.
        </p>
        <div className="stack" style={{ gap: 10 }}>
          <OracleDisclosureLine source="coinbase" backup="kraken" />
          <p className="faint">The round's own page — running, then settled:</p>
          <RoundVenueNote source="kraken" market={{ source: "coinbase", backupSource: "kraken" }} phase="RUNNING" />
          <RoundVenueNote source="kraken" market={{ source: "coinbase", backupSource: "kraken" }} phase="SETTLED" />
          <p className="faint">The room, which also shows the round open for bets (its exchange is chosen when it starts):</p>
          <RoundVenueNote source="kraken" market={{ source: "coinbase", backupSource: "kraken" }} phase="RUNNING" subject="live" />
          <PriceChart
            market={market0}
            ticks={TICKS.slice(0, 250)}
            current={{ price: "83580.00", asOfMs: NOW }}
            lines={[]}
            loading={false}
            error={false}
            backup={{ name: "Kraken", primaryName: "Coinbase" }}
          />
        </div>
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
          <p className="faint">With the market's place in the oracle record (MarketDto.oracleIndex)</p>
          <AnchorLine
            roundId="xyz_NVDA-1h-6"
            resultsHash={HASH}
            anchor={{ seq: 416, state: "confirmed", height: 4_738_990 }}
            symbol="NVDA"
            oracleIndex={7}
          />
        </div>
      </section>

      <section className="lab-section">
        <h2>Win streak</h2>
        <div className="stack" style={{ gap: 10 }}>
          <StreakBadge current={5} best={7} />
          <StreakBadge current={1} best={1} />
          <StreakBadge current={0} best={4} />
          <StreakBadge current={0} best={0} />
          <LastResults results={["won", "lost", "flat", "won", "won"]} />
          <LastResults results={["lost", "lost"]} />
        </div>
      </section>
    </div>
  );
}
