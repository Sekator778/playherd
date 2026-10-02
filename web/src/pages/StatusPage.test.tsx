import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api, CancellationDto, MarketDto, Page, PageParams, ServerInfoDto } from "../api/contract";
import { NOW, catalogue, market, marketsOf } from "../components/testFixtures";
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
    expect(card).toHaveTextContent(/Prices\s*Live\.\s*Prices and odds are the sources' own current numbers — perpetual prices from Hyperliquid, odds from Polymarket/);
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
    expect(screen.getByText(/Betting stays open meanwhile/)).toBeInTheDocument();
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
