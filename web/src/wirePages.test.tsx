/**
 * The whole app — the real `App`, `Layout`, `ApiProvider` and `RealApi` — rendered over what the server REALLY answers
 * (`api/wire/*.json`, written by the server's tests; see `api/wireSource.ts`), one screen after another. Finding 14 of the
 * parity review: every other page test builds its data from `contract.ts`, so a server that spells a field differently
 * passes them all and the player sees "NaN SEC". Here every screen must show no "NaN", "undefined", "Invalid Date" or
 * "null", no control or heading without a name, no console error — and the numbers that matter are printed in their
 * market's own scale (the 7-digit DOGE price is not "$0.09", an odds price is a percentage).
 *
 * If a listed wire file is missing, every test here fails with the name of the file and what to run.
 */
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import App from "./App";
import { ApiProvider } from "./api/ApiContext";
import { reloadFavoritesFromStorage } from "./api/favorites";
import type { MarketDto, MyPositionDto, PublicPositionDto, RoundBetting, RoundCancelled, RoundRunning, RoundSettled, RoundsPairDto } from "./api/contract";
import { resetEngineStateForTests } from "./api/engineState";
import { realApi } from "./api/realApi";
import { WIRE_HELP, missingWireFiles, wire, wireFetch, wireResponse, type WireOverrides } from "./api/wireSource";
import { sourceName } from "./components/marketModel";
import { formatPercent, formatPlayAmount, formatPrice } from "./format";

// ---------------------------------------------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------------------------------------------

let consoleError: MockInstance<typeof console.error>;

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  reloadFavoritesFromStorage();
  resetEngineStateForTests();
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** The app as `main.tsx` mounts it, on the server's answers. `signedIn: false` makes `currentPlayer()` answer `null`. */
function renderApp(path: string, opts: { signedIn?: boolean; overrides?: WireOverrides } = {}) {
  // Fail HERE, with the file names, rather than as a page that shows an error state and a test that times out.
  const missing = missingWireFiles();
  if (missing.length > 0) throw new Error(`Missing wire fixture(s): ${missing.map((n) => `${n}.json`).join(", ")}.\n${WIRE_HELP}`);
  const fetchStub = wireFetch({ ...(opts.signedIn === false ? { currentPlayer: () => ({ body: null }) } : {}), ...opts.overrides });
  vi.stubGlobal("fetch", fetchStub);
  const view = render(
    <MemoryRouter initialEntries={[path]}>
      <ApiProvider api={realApi}>
        <App />
      </ApiProvider>
    </MemoryRouter>,
  );
  return { ...view, fetchStub };
}

/** The words a screen must never print: what a number or a date becomes when a field is not what the client expected. */
const BROKEN = /\bNaN\b|\bundefined\b|Invalid Date|\[object Object\]|\bnull\b|Infinity/;

/** Controls and headings a player (or a screen reader) must be able to name. */
const NAMED = "h1, h2, h3, h4, button, a[href], [role=tab], label, th, summary";

function unnamed(root: HTMLElement): string[] {
  const offenders: string[] = [];
  for (const el of Array.from(root.querySelectorAll<HTMLElement>(NAMED))) {
    if (el.closest("[aria-hidden='true']") || el.hidden) continue;
    // A label that wraps its control (the lobby's search box) is named by that control, not by text of its own.
    if (el.tagName === "LABEL" && el.querySelector("input, select, textarea")) continue;
    const text = (el.textContent ?? "").replace(/\s+/g, "");
    const named = text !== "" || !!el.getAttribute("aria-label")?.trim() || !!el.getAttribute("title")?.trim() || !!el.getAttribute("aria-labelledby") || el.querySelector("img[alt]:not([alt=''])");
    if (!named) offenders.push(el.outerHTML.slice(0, 160));
  }
  return offenders;
}

/** The page as it stands: nothing broken printed, nothing unnamed, nothing the console complained about. */
function expectCleanPage(label: string) {
  const text = document.body.textContent ?? "";
  const broken = text.match(new RegExp(BROKEN.source, "g")) ?? [];
  expect(broken, `${label}: the page prints ${broken.join(", ")} — around: …${text.slice(Math.max(0, text.search(BROKEN) - 60), text.search(BROKEN) + 60)}…`).toEqual([]);
  expect(unnamed(document.body), `${label}: controls or headings with no name`).toEqual([]);
  expect(consoleError.mock.calls.map((c) => c.map(String).join(" ").slice(0, 300)), `${label}: console.error was called`).toEqual([]);
}

/** Lets the polls and effects of a page land (the app asks for markets, positions, the account, results, …). */
async function settle(ms = 150) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
}

const markets = () => wireResponse<{ markets: MarketDto[] }>("markets").markets;
const marketOf = (id: string) => markets().find((m) => m.id === id);
const pair = () => wireResponse<RoundsPairDto>("rounds.hourly");
/** The market `rounds.hourly.json` was recorded for (`rounds(interval, marketId?)`: omitted → BTC). */
const roomMarketId = () => ((wire("rounds.hourly").args[1] as string | null | undefined) ?? "BTC");
/** `marketOf` for a market a fixture is about: the message says which two files disagree. */
function marketOfFixture(id: string, fixture: string): MarketDto {
  const market = marketOf(id);
  if (!market) throw new Error(`${fixture}.json is about market "${id}", which markets.json does not list — the two files must come from one server state`);
  return market;
}

// ---------------------------------------------------------------------------------------------------------------

describe("the wire fixtures this file reads", () => {
  it("are all there", () => {
    const missing = missingWireFiles();
    expect(missing, `Missing wire fixture(s): ${missing.map((n) => `${n}.json`).join(", ")}.\n${WIRE_HELP}`).toEqual([]);
  });
});

describe("Lobby — a card for every market, each price in its own scale", () => {
  it("every listed market has a card; every price is printed with its market's decimals (odds as a percentage)", async () => {
    renderApp("/");
    const cards = await screen.findAllByRole("article");
    await settle();
    expect(cards.length).toBeGreaterThanOrEqual(markets().filter((m) => m.status === "ACTIVE").length);
    for (const market of markets()) {
      const card = document.querySelector<HTMLElement>(`[data-market-id="${CSS.escape(market.id)}"]`);
      expect(card, `no card for market ${market.id}`).not.toBeNull();
      expect(card!.querySelector(".mk-card__symbol")).toHaveTextContent(market.symbol);
      if (market.price) {
        const expected = formatPrice(market.price.price, market);
        expect(card!, `${market.id}: price ${market.price.price} should read ${expected}`).toHaveTextContent(expected);
      }
      expect(card!.textContent ?? "").not.toBe("");
    }
    expectCleanPage("lobby");
  });

  it("a sports card does not repeat its title under itself", async () => {
    renderApp("/?c=sports");
    await screen.findAllByRole("article");
    for (const card of Array.from(document.querySelectorAll<HTMLElement>(".mk-card"))) {
      const title = card.querySelector(".mk-card__title")?.textContent?.trim() ?? "";
      const question = card.querySelector(".mk-card__question")?.textContent?.trim();
      if (question) expect(title.endsWith(question), `"${title}" repeats the question "${question}"`).toBe(false);
    }
  });

  it("signed out too (the other state of every screen)", async () => {
    renderApp("/", { signedIn: false });
    await screen.findAllByRole("article");
    await settle();
    expect(screen.getAllByRole("button", { name: "CONNECT WALLET" }).length).toBeGreaterThan(0);
    expectCleanPage("lobby, signed out");
  });
});

describe("Pair room — in every phase of its pair of rounds", () => {
  const phases: Array<[string, (p: RoundsPairDto) => RoundsPairDto]> = [
    ["a running round and the next one open for bets, as the server answered", (p) => p],
    ["a settled round and the next one open", (p) => ({ ...p, running: wireResponse<RoundSettled>("round.settled") })],
    ["a running round and an open round that was cancelled", (p) => ({ ...p, betting: wireResponse<RoundCancelled>("round.cancelled") })],
    ["a cancelled round and one open for bets", (p) => ({ ...p, running: wireResponse<RoundCancelled>("round.cancelled") })],
  ];

  it.each(phases)("%s: the page is clean and its numbers are right", async (_name, make) => {
    renderApp(`/play/${encodeURIComponent(roomMarketId())}`, { overrides: { rounds: () => ({ body: make(pair()) }) } });
    const market = marketOfFixture(roomMarketId(), "rounds.hourly");
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(market.symbol);
    await settle(300);
    const built = make(pair());
    if (built.betting.phase === "BETTING") {
      expect(document.body.textContent).toMatch(/players? ha(s|ve) voted/);
    } else {
      expect(screen.getByText("This round was cancelled.")).toBeInTheDocument();
    }
    if (built.running.phase === "RUNNING" && built.running.crowd) {
      // the crowd's position is printed in the market's scale
      expect(screen.getByText(`Entry price: ${formatPrice(built.running.crowd.entryPrice, market)}`)).toBeInTheDocument();
    }
    if (market.price) expect(document.body.textContent).toContain(formatPrice(market.price.price, market));
    expectCleanPage(`room, ${_name}`);
  });

  it("a market with 7 price digits keeps its five significant ones (never '$0.09')", async () => {
    const seven = markets().find((m) => m.kind === "PERP" && m.priceDecimals >= 6);
    if (!seven) return; // the stand's catalogue at capture time had none; the unit tests cover the formatting
    renderApp(`/play/${encodeURIComponent(seven.id)}`);
    await screen.findByRole("heading", { level: 1 });
    await settle(300);
    expect(document.body.textContent).toContain(formatPrice(seven.price!.price, seven));
    expect(document.body.textContent).not.toMatch(/\$0\.09\b|\$0\.10\b/);
  });

  it("an odds market's room reads in percent and says whose odds they are", async () => {
    const odds = markets().find((m) => m.kind === "ODDS" && m.status === "ACTIVE");
    if (!odds) return;
    renderApp(`/play/${encodeURIComponent(odds.id)}`);
    await screen.findByRole("heading", { level: 1 });
    await settle(300);
    expect(document.body.textContent).toContain(formatPrice(odds.price!.price, odds));
    expect(screen.getByText(/Odds from Polymarket, published by our team's key/)).toBeInTheDocument();
    expectCleanPage(`room of ${odds.id}`);
  });

  it("a closed market's room says so", async () => {
    const closed = markets().find((m) => m.status === "CLOSED");
    if (!closed) return;
    renderApp(`/play/${encodeURIComponent(closed.id)}`);
    expect(await screen.findByText("This market is closed")).toBeInTheDocument();
    expectCleanPage(`room of ${closed.id}`);
  });
});

describe("Round page — each phase, prices in the ROUND's own scale", () => {
  const route = (name: "round.betting" | "round.running" | "round.settled" | "round.cancelled") => `/round/${encodeURIComponent(wire(name).args[0] as string)}`;

  it("betting: the headcount and the slip", async () => {
    renderApp(route("round.betting"));
    const round = wireResponse<RoundBetting>("round.betting");
    expect(await screen.findByText("OPEN FOR BETS")).toBeInTheDocument();
    expect(screen.getByText(`${round.participantCount} players have voted`)).toBeInTheDocument();
    await settle();
    expectCleanPage("round, betting");
  });

  it("running: the open price, the crowd's position and every player — all in the round's scale", async () => {
    renderApp(route("round.running"));
    const round = wireResponse<RoundRunning>("round.running");
    expect(await screen.findByText("LIVE")).toBeInTheDocument();
    const format = { kind: round.kind, priceDecimals: round.priceDecimals };
    if (round.openPrice !== null) expect(screen.getByText(`Open price: ${formatPrice(round.openPrice, format)}`)).toBeInTheDocument();
    if (round.crowd) expect(screen.getByText(`Entry price: ${formatPrice(round.crowd.entryPrice, format)}`)).toBeInTheDocument();
    await settle();
    expectCleanPage("round, running");
  });

  it("settled: closed UP/DOWN/FLAT with both prices, the open price, who won what", async () => {
    renderApp(route("round.settled"));
    const round = wireResponse<RoundSettled>("round.settled");
    expect(await screen.findByText("SETTLED")).toBeInTheDocument();
    const format = { kind: round.kind, priceDecimals: round.priceDecimals };
    const side = Number(round.closePrice) === Number(round.openPrice) ? "FLAT" : Number(round.closePrice) > Number(round.openPrice) ? "UP" : "DOWN";
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(`${round.asset} closed ${side} — ${formatPrice(round.openPrice, format)} → ${formatPrice(round.closePrice, format)}`);
    expect(screen.getByText(`Open price: ${formatPrice(round.openPrice, format)}`)).toBeInTheDocument();
    await settle();
    expectCleanPage("round, settled");
  });

  it("cancelled: the server's reason, the refunds, and a name for the market — even though its market has left the list", async () => {
    renderApp(route("round.cancelled"));
    const round = wireResponse<RoundCancelled>("round.cancelled");
    expect(await screen.findByText("This round was cancelled.")).toBeInTheDocument();
    expect(screen.getByText(round.cancellation.reason)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`All ${round.cancellation.refundedBetCount} bets were refunded`))).toBeInTheDocument();
    expect(document.body.textContent).toContain(round.asset);
    await settle();
    expectCleanPage("round, cancelled");
  });

  it("a settled round is right even when its market is NOT in markets() (the market leaves the list ten minutes after it closes)", async () => {
    const round = wireResponse<RoundSettled>("round.settled");
    renderApp(route("round.settled"), {
      overrides: { markets: () => ({ body: { ...wireResponse<{ serverTimeMs: number; categories: unknown[]; markets: MarketDto[] }>("markets"), markets: markets().filter((m) => m.id !== round.marketId) } }) },
    });
    const format = { kind: round.kind, priceDecimals: round.priceDecimals };
    await screen.findByText("SETTLED");
    await settle();
    expect(screen.getByText(`Open price: ${formatPrice(round.openPrice, format)}`)).toBeInTheDocument();
    expectCleanPage("round, settled, market gone");
  });
});

describe("Your bets — the positions strip", () => {
  /** The fixture's own positions; and — whatever it holds — one bet waiting and one running, made from the REAL rounds so the strip is
   *  always exercised on real-shaped prices. */
  function stripPositions(): MyPositionDto[] {
    const recorded = wireResponse<MyPositionDto[]>("myPositions");
    const running = pair().running as RoundRunning;
    const betting = pair().betting as RoundBetting;
    const market = marketOfFixture(running.marketId, "rounds.hourly");
    const series = market.series[0];
    const mine = (round: RoundRunning | RoundBetting): Pick<MyPositionDto, "marketId" | "symbol" | "interval" | "durationSec" | "direction" | "leverage" | "stake"> => ({
      marketId: round.marketId,
      symbol: round.asset,
      interval: round.window.interval,
      durationSec: series.durationSec,
      direction: "UP",
      leverage: 5,
      stake: "250.000",
    });
    const own: PublicPositionDto | undefined = running.positions[0];
    return [
      ...recorded,
      ...(own
        ? [
            {
              ...mine(running),
              roundId: running.roundId,
              phase: "RUNNING" as const,
              entryPrice: own.entryPrice,
              liquidationPrice: own.liquidationPrice,
              size: own.size,
              liquidated: false,
              untilMs: running.window.endMs,
            },
          ]
        : []),
      { ...mine(betting), roundId: betting.roundId, phase: "BETTING", entryPrice: null, liquidationPrice: null, size: null, liquidated: false, untilMs: betting.window.startMs },
    ];
  }

  it("each bet is a card with its symbol, side, leverage, stake and a time — nothing broken", async () => {
    const positions = stripPositions();
    renderApp("/", { overrides: { myPositions: () => ({ body: positions }) } });
    const strip = await screen.findByRole("region", { name: "Your open bets and positions" });
    await settle(300);
    const cards = within(strip).getAllByRole("link");
    expect(cards).toHaveLength(positions.length);
    for (const position of positions) {
      const card = cards.find((c) => c.getAttribute("href")?.includes(encodeURIComponent(position.marketId)));
      expect(card, `no card for ${position.roundId}`).toBeDefined();
      expect(card!).toHaveTextContent(position.symbol);
      expect(card!).toHaveTextContent(`x${position.leverage}`);
      expect(card!).toHaveTextContent(`stake ${formatPlayAmount(position.stake)}`);
    }
    expectCleanPage("positions strip");
  });

  it("the bets the server recorded, as they are", async () => {
    renderApp("/");
    await settle(400);
    expectCleanPage("positions strip, as recorded");
  });
});

describe("Status — what the server says about itself", () => {
  it("round lengths, the game engine, the markets, the oracle — and no 'NaN SEC'", async () => {
    renderApp("/status", { signedIn: false });
    const info = wireResponse<{ roundDurationsSec: { HOURLY: number; DAILY: number }; engineReady?: boolean }>("serverInfo");
    expect(await screen.findByText(/Every market has two series of rounds: \d+ (SEC|MIN|HOUR|HOURS|DAY|DAYS) and \d+ (SEC|MIN|HOUR|HOURS|DAY|DAYS)\./)).toBeInTheDocument();
    expect(Number.isFinite(info.roundDurationsSec.HOURLY)).toBe(true);
    const engine = (await screen.findByText("Game engine")).closest(".status-fact") as HTMLElement;
    expect(engine).toHaveTextContent(info.engineReady === false ? "Not ready." : "Ready.");
    await settle(300);
    const rows = document.querySelectorAll(".status-market");
    expect(rows.length).toBe(markets().length);
    for (const market of markets()) {
      if (market.price) expect(document.body.textContent).toContain(formatPrice(market.price.price, market));
    }
    expectCleanPage("status");
  });

  it("every row of the cancellations log names its market, its time, its reason", async () => {
    renderApp("/status", { signedIn: false });
    const items = wireResponse<{ items: Array<{ roundId: string; asset?: string; reason: string }> }>("cancellations").items;
    await waitFor(() => expect(document.querySelectorAll(".status-cancel")).toHaveLength(items.length));
    for (const [i, row] of items.entries()) {
      const el = document.querySelectorAll<HTMLElement>(".status-cancel")[i];
      expect(el.querySelector(".status-cancel__market")?.textContent, `${row.roundId} shows no market`).toBe(row.asset);
      expect(el).toHaveTextContent(row.reason);
    }
    expectCleanPage("status, cancellations");
  });
});

describe("The exchange behind a price and a round (Р-76), on the server's own answers", () => {
  const backed = () => markets().find((m) => m.status === "ACTIVE" && m.backupSource);
  const fromBackup = () => markets().find((m) => m.status === "ACTIVE" && m.price?.venue && m.price.venue !== m.source);

  it("every source the server names is named on the page — the Status page prints each market's own exchange and its backup", async () => {
    renderApp("/status", { signedIn: false });
    await waitFor(() => expect(document.querySelectorAll(".status-market")).toHaveLength(markets().length));
    await settle(300);
    for (const market of markets()) {
      const row = Array.from(document.querySelectorAll<HTMLElement>(".status-market")).find((r) => r.querySelector("strong")?.textContent === market.symbol && r.textContent?.includes(formatPrice(market.price?.price ?? "x", market)));
      expect(row, `no row for ${market.id}`).toBeDefined();
      const line = row!.querySelector(".status-market__id .faint")?.textContent ?? "";
      expect(line, `${market.id}: source`).toContain(sourceName(market.source)!);
      if (market.backupSource) expect(line, `${market.id}: backup`).toContain(`Backup: ${sourceName(market.backupSource)}`);
      else expect(line, `${market.id}: names a backup it has not`).not.toContain("Backup");
    }
    // one row per source of an open market, none of them 'Hyperliquid' because nobody said
    const named = new Set(markets().filter((m) => m.status === "ACTIVE").flatMap((m) => [m.source, m.backupSource ?? null]).filter((s): s is NonNullable<typeof s> => s !== null));
    const rows = within(screen.getByRole("list", { name: "Sources behind the prices" })).getAllByRole("listitem");
    expect(rows.map((r) => r.querySelector("strong")?.textContent).sort()).toEqual([...named].map((s) => sourceName(s)!).sort());
    expectCleanPage("status, sources");
  });

  it("a market with a backup names it in its room's oracle line", async () => {
    const market = backed();
    if (!market) return; // the capture had no market with a backup; the unit tests cover the wording
    renderApp(`/play/${encodeURIComponent(market.id)}`);
    await screen.findByRole("heading", { level: 1 });
    await settle(300);
    expect(screen.getByText(new RegExp(`Backup: ${sourceName(market.backupSource)}, used for a round only if ${sourceName(market.source)} has no price when it starts\\.`))).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`Price from ${sourceName(market.source)}, published by our team's key`))).toBeInTheDocument();
    expectCleanPage(`room of ${market.id}`);
  });

  it("a price that is the backup's is marked on the card and in the room", async () => {
    const market = fromBackup();
    if (!market) return; // every price of the capture was its market's own
    const name = sourceName(market.price!.venue)!;
    renderApp("/");
    await screen.findAllByRole("article");
    await settle();
    const card = document.querySelector<HTMLElement>(`[data-market-id="${CSS.escape(market.id)}"]`)!;
    expect(card.querySelector(".mk-card__venue"), `${market.id}: the card's mark`).toHaveTextContent(`${name} price`);
    expectCleanPage("lobby, a backup price");
  });

  it("…and the room says so beside the number and in the chart's legend", async () => {
    const market = fromBackup();
    if (!market) return;
    const name = sourceName(market.price!.venue)!;
    renderApp(`/play/${encodeURIComponent(market.id)}`);
    await screen.findByRole("heading", { level: 1 });
    await settle(300);
    expect(document.querySelector(".room-venue")).toHaveTextContent(`${name} price`);
    expect(document.body.textContent).toContain(`Latest point: ${name}'s price`);
    expectCleanPage(`room of ${market.id}, a backup price`);
  });

  it("a round that ran on the backup says so on the round page — made from the server's own settled round and its market", async () => {
    const market = backed();
    if (!market) return;
    const round = { ...wireResponse<RoundSettled>("round.settled"), marketId: market.id, source: market.backupSource! };
    renderApp(`/round/${encodeURIComponent(round.roundId)}`, {
      overrides: { round: () => ({ body: round }) },
    });
    await screen.findByText("SETTLED");
    await settle(300);
    expect(screen.getByRole("note")).toHaveTextContent(`This round ran on ${sourceName(market.backupSource)}, the backup exchange — ${sourceName(market.source)} had no price when it started.`);
    expect(screen.getByText(new RegExp(`^Price from ${sourceName(market.backupSource)}, published by our team's key`))).toBeInTheDocument();
    expectCleanPage("round, settled on the backup");
  });
});

describe("Stats — the herd, the ratings, the player's own history", () => {
  it("the crowd account: the herd's capital and every market's account", async () => {
    renderApp(`/stats?m=${encodeURIComponent(((wire("crowdAccount").args[0] as string | null | undefined) ?? "BTC"))}`, { signedIn: false });
    const overview = wireResponse<{ totalCapital: string; markets: unknown[] }>("crowdOverview");
    expect(await screen.findByText(`The whole herd — ${overview.markets.length} markets`)).toBeInTheDocument();
    expect(document.body.textContent).toContain(`$${Number(overview.totalCapital).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);
    await settle(400);
    const account = wireResponse<{ capital: string }>("crowdAccount");
    expect(document.body.textContent).toContain(`$${Number(account.capital).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);
    expectCleanPage("stats, crowd");
  });

  it("the ratings", async () => {
    renderApp("/stats/ratings", { signedIn: false });
    const ratings = wireResponse<{ rich: Array<{ player: { displayName: string } }> }>("ratings");
    if (ratings.rich[0]) expect(await screen.findByText(ratings.rich[0].player.displayName)).toBeInTheDocument();
    else expect(await screen.findByText(/No one has qualified yet/)).toBeInTheDocument();
    await settle();
    expectCleanPage("stats, ratings");
  });

  it("the player's own history", async () => {
    renderApp("/stats/history");
    await screen.findByRole("heading", { name: "Round history" });
    await settle();
    expectCleanPage("stats, history");
  });
});

describe("Profile — a player's round history", () => {
  it("each row names its market and its length, with a signed net result; the streak and the season tiles", async () => {
    const profile = wireResponse<{ player: { playerId: string; displayName: string }; history: Array<{ roundId: string; asset: string; stake: string; payout: string; liquidated: boolean }> }>("publicProfile");
    renderApp(`/profile/${encodeURIComponent(profile.player.playerId)}`, { signedIn: false });
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(profile.player.displayName);
    await settle(300);
    const tiles = document.querySelectorAll<HTMLElement>(".result-tile");
    // the fixture is the player's own, complete view; a visitor sees the wins only (Р-90)
    const shown = profile.history.filter((h) => !h.liquidated && Number(h.payout) > Number(h.stake));
    expect(tiles).toHaveLength(shown.length);
    expect(shown.length).toBeLessThan(profile.history.length);
    for (const [i, entry] of shown.entries()) {
      const tile = tiles[i];
      expect(tile.querySelector(".result-tile__title")).toHaveTextContent(entry.asset);
      const net = entry.liquidated ? -Number(entry.stake) : Number(entry.payout) - Number(entry.stake);
      expect(tile.querySelector(".result-tile__net")).toHaveTextContent(`${formatPlayAmount(net, { sign: true })} PLAY`);
    }
    expectCleanPage("profile");
  });

  it("an unknown player is 'Player not found, or no longer playing.' — by the server's code", async () => {
    renderApp("/profile/nobody", { signedIn: false, overrides: { publicProfile: () => ({ status: 404, body: { error: { code: "NOT_FOUND", message: "unknown player nobody" } } }) } });
    expect(await screen.findByText("Player not found, or no longer playing.")).toBeInTheDocument();
  });
});

describe("Wallet — the balance and the history", () => {
  it("the balance, and every ledger row with a label, a date and a signed amount", async () => {
    renderApp("/wallet");
    const account = wireResponse<{ balance: string }>("myAccount");
    await screen.findByText("Withdraw to wallet");
    await settle(300);
    expect(document.body.textContent).toContain(`${formatPlayAmount(account.balance)} PLAY`);
    const ledger = wireResponse<{ items: Array<{ delta: string }> }>("myLedger").items;
    const rows = Array.from(document.querySelectorAll<HTMLElement>(".list-card .list-row")).filter((r) => /PLAY$/.test(r.textContent ?? ""));
    expect(rows).toHaveLength(ledger.length);
    for (const [i, entry] of ledger.entries()) expect(rows[i]).toHaveTextContent(`${formatPlayAmount(entry.delta, { sign: true })} PLAY`);
    expectCleanPage("wallet");
  });
});

describe("Percentages and amounts are formatted the way the app formats them", () => {
  it("the herd badge of every card says its return, signed", async () => {
    renderApp("/");
    await screen.findAllByRole("article");
    for (const market of markets().filter((m) => m.status === "ACTIVE" && m.crowd.roundsTraded > 0)) {
      const card = document.querySelector<HTMLElement>(`[data-market-id="${CSS.escape(market.id)}"]`)!;
      expect(card, `${market.id}: the herd badge`).toHaveTextContent(formatPercent(market.crowd.returnPct, { sign: true }));
    }
  });
});
