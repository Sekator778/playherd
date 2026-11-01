import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api, CancellationDto, MarketDto, Page, PageParams, ServerInfoDto } from "../api/contract";
import { NOW, catalogue, market, marketsOf, spotCatalogue } from "../components/testFixtures";
import { StatusPage } from "./StatusPage";

const cancel = (roundId: string, cancelledAtMs: number, reason = "No reliable price within an hour", refundedBetCount = 4): CancellationDto => ({
  roundId,
  reason,
  cancelledAtMs,
  refundedBetCount,
});

/** A cancellations log that pages like the contract says: newest first, `before` is strictly before. */
function logOf(all: CancellationDto[]): Api["cancellations"] {
  return vi.fn(async (params?: PageParams): Promise<Page<CancellationDto>> => {
    const limit = params?.limit ?? 20;
    const rest = params?.before !== undefined ? all.filter((c) => c.cancelledAtMs < params.before!) : all;
    return { items: rest.slice(0, limit), hasMore: rest.length > limit };
  });
}

interface Setup {
  info?: Partial<ServerInfoDto> | "unsupported" | "error";
  markets?: MarketDto[];
  log?: CancellationDto[];
  methods?: Partial<Api>;
}

function renderStatus(setup: Setup = {}) {
  const methods: Partial<Api> = {
    markets: vi.fn(async () => marketsOf(setup.markets ?? catalogue())),
    cancellations: logOf(setup.log ?? []),
    ...setup.methods,
  };
  if (setup.info === "error") {
    methods.serverInfo = vi.fn(async () => {
      throw new Error("down");
    });
  } else if (setup.info !== "unsupported") {
    const over = setup.info ?? {};
    methods.serverInfo = vi.fn(async () => serverInfoDto(over));
  }
  const api = fakeApi(methods);
  render(
    <MemoryRouter>
      <StatusPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api) },
  );
  return api;
}

const section = (name: RegExp | string) => screen.getByRole("region", { name });

describe("Status — mode and price source, in plain words", () => {
  it("a live server: the mode, where prices come from and that rounds settle on them, the round lengths, the bots", async () => {
    renderStatus();
    const card = await waitFor(() => {
      const el = section("Mode and price source");
      expect(within(el).getByText("Live", { selector: "dd" })).toBeInTheDocument();
      return el;
    });
    expect(card).toHaveTextContent(
      /Prices\s*Live\.\s*Prices and odds are the sources' own current numbers — prices from the exchanges themselves \(Coinbase and Hyperliquid, with Kraken and Coinbase as backups\), odds from Polymarket/,
    );
    expect(card).toHaveTextContent("Every market has two series of rounds: 3 MIN and 15 MIN.");
    expect(card).toHaveTextContent("20 labelled test bots play next to real players");
    expect(card).toHaveTextContent(/marked BOT/);
  });

  it("a simulated source says nothing follows a real market", async () => {
    renderStatus({ info: { priceSource: "synthetic" } });
    expect(await screen.findByText(/No real source: the server makes up prices/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing here follows a real market/)).toBeInTheDocument();
  });

  it("replay mode names the recorded span and the speed-up", async () => {
    renderStatus({
      info: { mode: "replay", priceSource: "replay", replay: { sourceFromIso: "2026-09-01T00:00:00Z", sourceToIso: "2026-09-08T00:00:00Z", speedup: 60 } },
    });
    expect(await screen.findByText(/Recorded BTC prices from 2026-09-01 to 2026-09-08, replayed 60× faster/)).toBeInTheDocument();
    expect(within(section("Mode and price source")).getByText("Replay (a test mode)")).toBeInTheDocument();
  });

  it("a server that sends no round lengths (an older one) is not made to crash — the row is simply not there", async () => {
    renderStatus({ info: { roundDurationsSec: undefined as unknown as { HOURLY: number; DAILY: number } } });
    expect(await screen.findByText(/Prices and odds are the sources' own current numbers/)).toBeInTheDocument();
    expect(screen.queryByText(/Round lengths/)).not.toBeInTheDocument();
  });

  it("no bots, no bots line", async () => {
    renderStatus({ info: { bots: 0 } });
    await screen.findByText(/Every market has two series of rounds/);
    expect(screen.queryByText(/test bots play next to real players/)).not.toBeInTheDocument();
  });

  it("a mock build has no server to describe: it says so, and does not call it an error", async () => {
    renderStatus({ info: "unsupported" });
    expect(await screen.findByText(/runs on built-in demo data, not on a server/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing is anchored in a demo build/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // …while the markets and the cancellations, which the mock does serve, still show
    expect(await screen.findByRole("heading", { name: "Markets and their prices" })).toBeInTheDocument();
    expect(await screen.findByText("No round has been cancelled so far.")).toBeInTheDocument();
  });

  it("a server that does not answer is an error with a retry — and the other sections still show", async () => {
    const user = userEvent.setup();
    const api = renderStatus({ info: "error" });
    const server = await waitFor(() => within(section("Mode and price source")).getByRole("alert"));
    expect(server).toHaveTextContent("Couldn't reach the server.");
    expect(within(section("Anchored on Chia")).getByRole("alert")).toHaveTextContent("Couldn't reach the server.");
    expect(await screen.findByText(/^\d+ markets?/)).toBeInTheDocument(); // markets are a separate source
    (api.serverInfo as ReturnType<typeof vi.fn>).mockResolvedValue(serverInfoDto());
    await user.click(within(section("Mode and price source")).getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/Every market has two series of rounds/)).toBeInTheDocument();
  });
});

describe("Status — every market, its source and how old its price is", () => {
  const priced = (id: string, over: Partial<MarketDto> = {}, ageMs = 2_000, stale = false) =>
    market(id, { symbol: id, price: { price: "100.00", asOfMs: NOW - ageMs, change24h: 0, stale }, ...over });

  it("one row per market: ticker, source, asset number, price, a word for its state and its age", async () => {
    renderStatus({
      markets: [
        priced("BTC", { oracleIndex: 0, title: "Bitcoin" }),
        priced("GER", { kind: "ODDS", source: "polymarket", oracleIndex: 14, priceDecimals: 6, price: { price: "0.640000", asOfMs: NOW - 1_000, change24h: null, stale: false } }),
      ],
    });
    await waitFor(() => expect(document.querySelector(".status-markets")).not.toBeNull());
    const [btc, ger] = Array.from(document.querySelectorAll(".status-market"));
    expect(btc).toHaveTextContent("BTC");
    expect(btc).toHaveTextContent("Hyperliquid · asset #0");
    expect(btc).toHaveTextContent("$100.00");
    expect(btc).toHaveTextContent("Live");
    expect(btc).toHaveTextContent(/\d+ s ago/);
    expect(ger).toHaveTextContent("Polymarket · asset #14");
    expect(ger).toHaveTextContent("64.0%");
  });

  it("a stale price says STALE in words, not only in colour; a market with no price yet says so; a closed one says closed", async () => {
    renderStatus({
      markets: [
        priced("BTC"),
        priced("NVDA", {}, 190_000, true),
        market("NEW", { symbol: "NEW", price: null }),
        priced("OLD", { status: "CLOSED" }),
      ],
    });
    await screen.findByText("4 markets", { selector: "strong" });
    const rows = Array.from(document.querySelectorAll(".status-market"));
    expect(rows.map((r) => r.querySelector(".status-chip")?.textContent)).toEqual(["Live", "Stale", "No price yet", "Closed"]);
    expect(rows[1]).toHaveTextContent(/Stale.*\d+ min ago/);
    expect(rows[2].querySelector(".status-market__price")).toHaveTextContent("—"); // no price: a dash, never a zero
    expect(rows[2]).not.toHaveTextContent("ago");
    expect(screen.getByText(/4 markets/).parentElement).toHaveTextContent("4 markets · 1 live · 1 stale · 1 waiting for a first price · 1 closed");
  });

  it("a summary that adds up, and the honest note on what stale means", async () => {
    renderStatus({ markets: [priced("A"), priced("B"), priced("C", {}, 99_000, true)] });
    expect(await screen.findByText("3 markets", { selector: "strong" })).toBeInTheDocument();
    expect(screen.getByText(/3 markets/).parentElement).toHaveTextContent("3 markets · 2 live · 1 stale");
    expect(screen.getByText(/A price is stale when its source has sent nothing newer within its limit/)).toBeInTheDocument();
    expect(screen.getByText(/betting on that market, for that round length, pauses meanwhile/)).toBeInTheDocument();
    expect(screen.queryByText(/Betting stays open meanwhile/)).toBeNull(); // it does not: the round that waits for a price holds the betting round
  });

  it("says plainly that a round without a price holds up the rounds after it and pauses betting, for how long and counted from when, and that an empty one is skipped (D-77, D-79)", async () => {
    renderStatus({ markets: [priced("A")] });
    const note = (await screen.findByText(/A price is stale when its source has sent nothing newer within its limit/)).textContent ?? "";
    expect(note).toContain("A round that ends with no reliable price holds up the rounds after it");
    expect(note).toContain("betting on that market, for that round length, pauses meanwhile");
    expect(note).toContain(
      "for five minutes at most (fifteen for Polymarket's odds, two when the market's other exchange is still sending prices), counted again after the server restarts",
    );
    expect(note).toContain("then the round is cancelled and every bet refunded (see the log below)");
    expect(note).toContain("A round nobody bet on, if its price can't be read, is skipped.");
    expect(note).not.toContain("never holds up");
    expect(note).not.toContain("stays open");
    expect(note).not.toContain("within an hour");
  });

  it("states: loading, a market list that never came, nothing listed", async () => {
    renderStatus({ methods: { markets: vi.fn(() => new Promise<never>(() => {})) } });
    expect(section("Markets and their prices").querySelector(".skeleton")).not.toBeNull();
  });

  it("an empty list says so", async () => {
    renderStatus({ markets: [] });
    expect(await screen.findByText("No market is listed right now.")).toBeInTheDocument();
  });

  it("a failing first load of the list is an error with a retry", async () => {
    renderStatus({ methods: { markets: vi.fn(async () => Promise.reject(new Error("down"))) } });
    expect(await within(section("Markets and their prices")).findByRole("alert")).toHaveTextContent("Couldn't load the markets.");
  });
});

describe("Status — a crypto market's backup exchange, and the sources behind the prices (D-76)", () => {
  const rowsOf = () => Array.from(document.querySelectorAll<HTMLElement>(".status-market"));
  /** The new catalogue plus a stock and an odds market — every kind of source on one page. */
  const mixed = () => [...spotCatalogue(), ...catalogue().filter((m) => m.id === "xyz_NVDA" || m.id === "PM900101")];

  it("a market names its own exchange and its backup next to it ('Coinbase · Backup: Kraken · asset #20')", async () => {
    renderStatus({ markets: spotCatalogue() });
    await waitFor(() => expect(rowsOf()).toHaveLength(5));
    const [btc, , doge, hype, legacy] = rowsOf();
    expect(btc).toHaveTextContent("BTC");
    expect(btc.querySelector(".status-market__id .faint")).toHaveTextContent("Coinbase · Backup: Kraken · asset #20");
    expect(doge.querySelector(".status-market__id .faint")).toHaveTextContent("Coinbase · Backup: Kraken · asset #22");
    expect(hype.querySelector(".status-market__id .faint")).toHaveTextContent("Hyperliquid · Backup: Coinbase · asset #23");
    // the legacy perpetual names no backup — it has none — and says no such thing
    expect(legacy.querySelector(".status-market__id .faint")).toHaveTextContent(/^Hyperliquid · asset #0$/);
  });

  it("a price that is the backup's says so on its row — Coinbase has no fresh price, the number is Kraken's", async () => {
    renderStatus({ markets: spotCatalogue() });
    await waitFor(() => expect(rowsOf()).toHaveLength(5));
    const [btc, eth] = rowsOf();
    expect(eth.querySelector(".status-market__venue")).toHaveTextContent("Showing Kraken's price");
    expect(eth).toHaveTextContent("$2,696.80");
    expect(btc.querySelector(".status-market__venue")).toBeNull();
    expect(document.querySelectorAll(".status-market__venue")).toHaveLength(1);
  });

  it("a closed market's row (the legacy BTC the new market replaced) says closed, and why in the server's own words — and marks no price", async () => {
    renderStatus({ markets: spotCatalogue() });
    await waitFor(() => expect(rowsOf()).toHaveLength(5));
    const legacy = rowsOf()[4];
    expect(legacy.querySelector(".status-chip")).toHaveTextContent("Closed");
    expect(legacy.querySelector(".status-market__reason")).toHaveTextContent("Moved to a new price source: Coinbase");
    expect(legacy.querySelector(".status-market__venue")).toBeNull();
    // an open market says no reason: it has none
    expect(rowsOf()[0].querySelector(".status-market__reason")).toBeNull();
  });

  it("a closed market the server gave no reason for says none — nothing made up", async () => {
    renderStatus({ markets: [market("OLD", { symbol: "OLD", status: "CLOSED" })] });
    await waitFor(() => expect(rowsOf()).toHaveLength(1));
    expect(rowsOf()[0].querySelector(".status-market__reason")).toBeNull();
  });

  it("one row per source: the exchanges first, each with what it is the main source of, what it backs, and how its prices are doing", async () => {
    renderStatus({ markets: mixed() });
    const list = await screen.findByRole("list", { name: "Sources behind the prices" });
    const rows = within(list).getAllByRole("listitem");
    expect(rows.map((r) => r.querySelector("strong")?.textContent)).toEqual(["Coinbase", "Kraken", "Hyperliquid", "Polymarket"]);
    const [coinbase, kraken, hyperliquid, polymarket] = rows;
    expect(coinbase).toHaveTextContent("Main source of BTC, ETH, DOGE");
    expect(coinbase).toHaveTextContent("Backup for HYPE");
    expect(coinbase).toHaveTextContent("3 live");
    expect(kraken).toHaveTextContent("Backup for BTC, ETH, DOGE");
    expect(kraken).not.toHaveTextContent("Main source of");
    expect(kraken.querySelector(".status-chip")).toBeNull(); // no market of its own: nothing to count
    expect(hyperliquid).toHaveTextContent("Main source of HYPE, NVDA");
    expect(hyperliquid).toHaveTextContent("1 live");
    expect(hyperliquid).toHaveTextContent("1 stale"); // NVDA's price is old
    expect(polymarket).toHaveTextContent("Main source of RUNOFF");
  });

  it("the backup whose price is on screen now says so: its main exchange has no fresh price", async () => {
    renderStatus({ markets: mixed() });
    const list = await screen.findByRole("list", { name: "Sources behind the prices" });
    const [, kraken, , polymarket] = within(list).getAllByRole("listitem");
    expect(kraken).toHaveTextContent("Its price is shown now for ETH: the main exchange has no fresh price");
    expect(polymarket).not.toHaveTextContent("shown now");
  });

  it("a stale main source and one with no price yet are said in words, not only in colour", async () => {
    const markets = [
      market("spot_SOL", { source: "coinbase", backupSource: "kraken", price: { price: "117.84", asOfMs: NOW - 99_000, change24h: 0, stale: true } }),
      market("spot_XRP", { source: "coinbase", backupSource: "kraken", price: null }),
    ];
    renderStatus({ markets });
    const list = await screen.findByRole("list", { name: "Sources behind the prices" });
    const [coinbase] = within(list).getAllByRole("listitem");
    expect(coinbase.querySelector(".status-chip--stale")).toHaveTextContent("1 stale");
    expect(coinbase).toHaveTextContent("1 no price yet");
  });

  it("says what a backup means: a round runs on one exchange — only when some market has one", async () => {
    renderStatus({ markets: spotCatalogue() });
    expect(await screen.findByText(/A crypto market has a backup exchange\. A round runs entirely on one exchange, chosen when it starts/)).toBeInTheDocument();
    expect(screen.getByText(/its open, its close and every liquidation come from that exchange alone/)).toBeInTheDocument();
  });

  it("no market with a backup, no word about backups — an odds-only page makes no claim about exchanges", async () => {
    renderStatus({ markets: catalogue().filter((m) => m.kind === "ODDS") });
    await screen.findByText(/^\d+ markets?/);
    const markets = section("Markets and their prices");
    expect(markets.textContent).not.toMatch(/backup/i);
    expect(within(markets).getByRole("list", { name: "Sources behind the prices" })).toHaveTextContent("Polymarket");
  });

  it("an empty list shows no sources list", async () => {
    renderStatus({ markets: [] });
    await screen.findByText("No market is listed right now.");
    expect(screen.queryByRole("list", { name: "Sources behind the prices" })).not.toBeInTheDocument();
  });

  it("a market of a source this build does not know is named as the server sent it — never as Hyperliquid", async () => {
    renderStatus({ markets: [market("X1", { symbol: "X1", source: "bybit" as never, oracleIndex: 3 })] });
    await waitFor(() => expect(rowsOf()).toHaveLength(1));
    expect(rowsOf()[0].querySelector(".status-market__id .faint")).toHaveTextContent("Bybit · asset #3");
    expect(section("Markets and their prices").textContent).not.toMatch(/Hyperliquid/);
  });
});

describe("Status — what is anchored on Chia", () => {
  it("the network, the launcher id with a copy button, the last confirmed record, what waits, the oldest record, the balance", async () => {
    renderStatus();
    const oracle = await waitFor(() => {
      const el = section("Anchored on Chia");
      expect(within(el).getByLabelText("Oracle launcher id")).toBeInTheDocument();
      return el;
    });
    expect(oracle).toHaveTextContent(/testnet11 — a test network; its coins and PLAY have no real value/);
    expect(within(oracle).getByLabelText("Oracle launcher id")).toHaveTextContent("c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20");
    expect(within(oracle).getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(oracle).toHaveTextContent("Last confirmed record#412 at block 4,738,927");
    expect(oracle).toHaveTextContent("Waiting to be anchored2 records");
    expect(oracle).toHaveTextContent("Operator balance0.5000 TXCH");
  });

  it("explains the oldest record in one line: earlier ones can only be checked on the chain", async () => {
    renderStatus();
    expect(await screen.findByText("#1146")).toBeInTheDocument();
    expect(screen.getByText(/The lowest record number this server's database knows; earlier ones were published before this database existed and can only be checked on the chain\./)).toBeInTheDocument();
  });

  it("says it is one key, ours — not a decentralized oracle", async () => {
    renderStatus();
    expect(await screen.findByText(/It is one key — ours — not a decentralized oracle/)).toBeInTheDocument();
  });

  it("links the market registry", async () => {
    renderStatus();
    const link = await screen.findByRole("link", { name: "Market registry (registry.json)" });
    expect(link).toHaveAttribute("href", "/api/markets/registry.json");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
  });

  it("how to check it yourself: the steps, and the verifier command filled in for this server, copyable", async () => {
    const user = userEvent.setup(); // installs the clipboard stub the copy button writes to
    renderStatus();
    expect(await screen.findByRole("heading", { name: "Check it yourself" })).toBeInTheDocument();
    const command = screen.getByLabelText("Verifier command");
    expect(command.textContent).toBe(
      `python3 tools/verify-oracle/verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 --stand-url ${window.location.origin}`,
    );
    expect(screen.getByText(/needs none of our keys/)).toBeInTheDocument();
    expect(screen.getByText(/CONFLICTS: 0/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Copy command" }));
    expect(await navigator.clipboard.readText()).toBe(command.textContent);
  });

  it("an oracle with nothing confirmed, nothing known of its oldest record and no balance: says 'none yet' and dashes, no zeros", async () => {
    renderStatus({
      info: { oracle: { network: "testnet11", launcherId: "abc", lastConfirmedSeq: null, lastConfirmedHeight: null, pendingRecords: 0, operatorBalanceMojos: null, firstSeq: null } },
    });
    const oracle = await waitFor(() => {
      const el = section("Anchored on Chia");
      expect(within(el).getByLabelText("Oracle launcher id")).toBeInTheDocument();
      return el;
    });
    expect(oracle).toHaveTextContent("Last confirmed recordnone yet");
    expect(oracle).toHaveTextContent("Waiting to be anchored0 records");
    expect(oracle).toHaveTextContent("Operator balance—");
    expect(oracle).not.toHaveTextContent("Oldest record here");
    expect(oracle).not.toHaveTextContent("Records go out in batches"); // nothing waits
  });

  it("a record number without its block (a record that is not confirmed yet) is not given a made-up block", async () => {
    renderStatus({
      info: { oracle: { network: "testnet11", launcherId: "abc", lastConfirmedSeq: 7, lastConfirmedHeight: null, pendingRecords: 1, operatorBalanceMojos: "1", firstSeq: undefined } },
    });
    const oracle = await waitFor(() => {
      const el = section("Anchored on Chia");
      expect(within(el).getByLabelText("Oracle launcher id")).toBeInTheDocument();
      return el;
    });
    expect(oracle).toHaveTextContent("Last confirmed record#7");
    expect(oracle).not.toHaveTextContent("at block");
    expect(oracle).toHaveTextContent("1 record");
    expect(oracle).not.toHaveTextContent("1 records");
  });
});

describe("Status — cancelled rounds", () => {
  it("each with when, why, how many bets were refunded, and a link to the round", async () => {
    renderStatus({ log: [cancel("BTC-1h-1735689600000", Date.UTC(2026, 9, 1, 14, 5), "Automatic cancellation: no reliable price within 1 hour", 4)] });
    const log = await waitFor(() => {
      const el = section("Cancelled rounds");
      expect(within(el).getByText("Automatic cancellation: no reliable price within 1 hour")).toBeInTheDocument();
      return el;
    });
    expect(log).toHaveTextContent("2026-10-01 14:05 UTC");
    expect(log).toHaveTextContent("4 bets refunded");
    expect(within(log).getByRole("link", { name: "BTC-1h-1735689600000" })).toHaveAttribute("href", "/round/BTC-1h-1735689600000");
    expect(log).toHaveTextContent(/Every bet in it is refunded\s*— liquidated ones too/);
  });

  it("one bet is '1 bet', not '1 bets'", async () => {
    renderStatus({ log: [cancel("r1", 100, "x", 1)] });
    expect(await screen.findByText("1 bet refunded")).toBeInTheDocument();
  });

  it("empty is said, not left blank", async () => {
    renderStatus({ log: [] });
    expect(await screen.findByText("No round has been cancelled so far.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show older" })).not.toBeInTheDocument();
  });

  it("pages: 'Show older' loads the next ones, newest first, and goes away when there are no more", async () => {
    const user = userEvent.setup();
    const all = Array.from({ length: 13 }, (_, i) => cancel(`R${i}`, 10_000 - i * 100, `reason ${i}`));
    const api = renderStatus({ log: all });
    await screen.findByText("reason 0");
    expect(document.querySelectorAll(".status-cancel")).toHaveLength(10);
    await user.click(screen.getByRole("button", { name: "Show older" }));
    await waitFor(() => expect(document.querySelectorAll(".status-cancel")).toHaveLength(13));
    expect(Array.from(document.querySelectorAll(".status-cancel__reason")).map((e) => e.textContent)).toEqual(all.map((c) => c.reason));
    expect(screen.queryByRole("button", { name: "Show older" })).not.toBeInTheDocument();
    expect(api.cancellations).toHaveBeenLastCalledWith({ limit: 10, before: 10_000 - 9 * 100 + 1 });
  });

  it("rounds cancelled at the same instant (a market closing cancels several) are not skipped at a page boundary", async () => {
    const user = userEvent.setup();
    // 12 rounds, the 10th and 11th and 12th cancelled at the same millisecond as the page's last row
    const all = [
      ...Array.from({ length: 9 }, (_, i) => cancel(`A${i}`, 9_000 - i * 100)),
      cancel("T1", 8_000),
      cancel("T2", 8_000),
      cancel("T3", 8_000),
    ];
    renderStatus({ log: all });
    await waitFor(() => expect(document.querySelectorAll(".status-cancel")).toHaveLength(10));
    await user.click(screen.getByRole("button", { name: "Show older" }));
    await waitFor(() => expect(document.querySelectorAll(".status-cancel")).toHaveLength(12));
    const ids = Array.from(document.querySelectorAll(".status-cancel__round a")).map((a) => a.textContent);
    expect(ids).toEqual(expect.arrayContaining(["T1", "T2", "T3"]));
    expect(new Set(ids).size).toBe(12); // each exactly once
  });

  it("a failing 'Show older' keeps what is shown and says so", async () => {
    const user = userEvent.setup();
    const all = Array.from({ length: 12 }, (_, i) => cancel(`R${i}`, 10_000 - i * 100));
    const paged = logOf(all);
    const cancellations = vi.fn(async (params?: PageParams) => {
      if (params?.before !== undefined) throw new Error("down");
      return paged(params);
    });
    renderStatus({ methods: { cancellations } });
    await screen.findByText("Show older");
    await user.click(screen.getByRole("button", { name: "Show older" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't load older cancellations. Try again.");
    expect(document.querySelectorAll(".status-cancel")).toHaveLength(10);
    expect(screen.getByRole("button", { name: "Show older" })).toBeEnabled();
  });

  it("the whole log failing on the first load is an error state", async () => {
    renderStatus({ methods: { cancellations: vi.fn(async () => Promise.reject(new Error("down"))) } });
    expect(await within(section("Cancelled rounds")).findByRole("alert")).toHaveTextContent("Couldn't load the log.");
  });
});

describe("Status — it is a plain public page", () => {
  it("needs no sign-in (nothing here is asked of a session) and links on to How it works", async () => {
    const api = renderStatus();
    await screen.findByRole("heading", { level: 1, name: "Status" });
    expect(screen.getByRole("link", { name: "How it works →" })).toHaveAttribute("href", "/how-it-works");
    expect(api.serverInfo).toBeDefined();
    expect(screen.queryByRole("button", { name: /connect wallet/i })).not.toBeInTheDocument();
  });

  it("says nothing it cannot back: no marketing words", async () => {
    renderStatus();
    await screen.findByText(/Every market has two series of rounds/);
    expect(document.body.textContent).not.toMatch(/guarantee|trustless|revolution|risk-free|100% safe|world-class|cutting-edge/i);
  });
});

describe("Status — a row whose numbers cannot be read is left out, never printed as NaN", () => {
  it("round lengths in the server's lowercase keys (hourly/daily) — the row is hidden, nothing says NaN", async () => {
    renderStatus({ info: { roundDurationsSec: { hourly: 180, daily: 900 } as unknown as ServerInfoDto["roundDurationsSec"] } });
    await screen.findByText("Mode");
    const server = section("Mode and price source");
    expect(within(server).queryByText("Round lengths")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NaN|undefined/);
  });

  it("a null or half-filled roundDurationsSec is the same", async () => {
    renderStatus({ info: { roundDurationsSec: { HOURLY: 180 } as unknown as ServerInfoDto["roundDurationsSec"] } });
    await screen.findByText("Mode");
    expect(screen.queryByText("Round lengths")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NaN|undefined/);
  });

  it("the contract's keys print as before", async () => {
    renderStatus({ info: { roundDurationsSec: { HOURLY: 180, DAILY: 900 } } });
    expect(await screen.findByText("Every market has two series of rounds: 3 MIN and 15 MIN.")).toBeInTheDocument();
  });
});

describe("Status — the game engine, from serverInfo().engineReady", () => {
  it("ready — said plainly (and an absent flag means ready, as the contract has it)", async () => {
    renderStatus({ info: { engineReady: true } });
    const fact = (await screen.findByText("Game engine")).closest(".status-fact") as HTMLElement;
    expect(fact).toHaveTextContent("Ready.");
    expect(fact).not.toHaveTextContent("Not ready");
  });

  it("an absent flag reads as ready", async () => {
    renderStatus({ info: {} });
    const fact = (await screen.findByText("Game engine")).closest(".status-fact") as HTMLElement;
    expect(fact).toHaveTextContent("Ready.");
  });

  it("false: not ready, why, and that it answers again by itself — no alarm, no promise of a time", async () => {
    renderStatus({ info: { engineReady: false } });
    const fact = (await screen.findByText("Game engine")).closest(".status-fact") as HTMLElement;
    expect(fact).toHaveTextContent("Not ready.");
    expect(fact).toHaveTextContent(/rebuilding its state after a failure/);
    expect(fact).toHaveTextContent(/answer again by themselves/);
    expect(fact).not.toHaveTextContent("Ready.");
  });
});

describe("Status — cancelled rounds name the market", () => {
  it("a row with the server's asset shows it before the time", async () => {
    renderStatus({ log: [{ ...cancel("PM9-1h-1", Date.UTC(2026, 9, 1, 14, 5)), marketId: "PM9", asset: "GER" }] });
    const row = (await waitFor(() => {
      const el = document.querySelector(".status-cancel");
      expect(el).not.toBeNull();
      return el;
    })) as HTMLElement;
    expect(within(row).getByText("GER")).toBeInTheDocument();
    expect(row.querySelector(".status-cancel__when")).toHaveTextContent("GER2026-10-01 14:05 UTC");
  });

  it("a row from a server that names no market shows none — and nothing like 'undefined'", async () => {
    renderStatus({ log: [cancel("BTC-1h-1", 5_000)] });
    await screen.findByText("4 bets refunded");
    expect(document.querySelector(".status-cancel__market")).toBeNull();
    expect(document.body.textContent).not.toMatch(/undefined|null/);
  });
});

describe("Status — the log's refresh leaves no silent hole after a burst", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  const rows = (from: number, count: number) => Array.from({ length: count }, (_, i) => cancel(`R${from + count - i}`, 100_000 + (from + count - i) * 1000, `reason ${from + count - i}`));
  const shownIds = () => Array.from(document.querySelectorAll(".status-cancel__round a")).map((a) => a.textContent);

  it("25 rounds cancelled between two refreshes (the page shows 10): the refresh pages until it meets what is shown — all 35, once each", async () => {
    const all = rows(0, 10); // R10…R1
    renderStatus({ methods: { cancellations: logOf(all) } });
    await waitFor(() => expect(shownIds()).toHaveLength(10));

    all.unshift(...rows(10, 25)); // an outage cancels 25 more: R35…R11 above R10…R1
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_500);
    });
    await waitFor(() => expect(shownIds()).toHaveLength(35));
    expect(shownIds()).toEqual(all.map((c) => c.roundId)); // newest first, no hole, no repeat
    expect(screen.queryByRole("button", { name: "Show older" })).not.toBeInTheDocument();
  });

  it("a burst bigger than a refresh will walk: the list starts over from the newest rows and 'Show older' brings the rest — never a gap with no hint", async () => {
    const all = rows(0, 10);
    renderStatus({ methods: { cancellations: logOf(all) } });
    await waitFor(() => expect(shownIds()).toHaveLength(10));

    all.unshift(...rows(10, 200));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_500);
    });
    await waitFor(() => expect(shownIds().length).toBeGreaterThan(10));
    const shown = shownIds();
    expect(shown).toEqual(all.slice(0, shown.length).map((c) => c.roundId)); // a contiguous run from the newest
    expect(shown.length).toBeLessThan(all.length);
    expect(screen.getByRole("button", { name: "Show older" })).toBeInTheDocument(); // and the rest is one tap away
  });

  it("a refresh with nothing new changes nothing (and 'Show older' loaded pages are kept)", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const all = rows(0, 25);
    renderStatus({ methods: { cancellations: logOf(all) } });
    await waitFor(() => expect(shownIds()).toHaveLength(10));
    await user.click(screen.getByRole("button", { name: "Show older" }));
    // (the next page is asked from one millisecond past the oldest row, so it repeats that row: 9 new ones, not 10)
    await waitFor(() => expect(shownIds()).toHaveLength(19));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_500);
    });
    expect(shownIds()).toEqual(all.slice(0, 19).map((c) => c.roundId));
    expect(screen.getByRole("button", { name: "Show older" })).toBeInTheDocument();
  });
});
