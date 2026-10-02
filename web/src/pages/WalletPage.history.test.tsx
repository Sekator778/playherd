import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, type Api, type LedgerEntryDto, type LedgerEntryType, type Page, type PageParams } from "../api/contract";
import { WalletPage } from "./WalletPage";

const entry = (sequence: number, atMs: number, type: LedgerEntryType = "SETTLEMENT", delta = "5.000"): LedgerEntryDto => ({
  sequence,
  type,
  delta,
  roundId: type === "SETTLEMENT" ? `BTC-1h-${sequence}` : null,
  atMs,
});

/** A ledger that pages as the server does (`GameService.myLedger`): newest first, `before` strictly before, 20 by default. */
function ledgerOf(all: LedgerEntryDto[]) {
  return vi.fn(async (params?: PageParams): Promise<Page<LedgerEntryDto>> => {
    const limit = params?.limit ?? 20;
    const rest = params?.before !== undefined ? all.filter((e) => e.atMs < params.before!) : all;
    return { items: rest.slice(0, limit), hasMore: rest.length > limit };
  });
}

const account = (balance = "10000.000") => ({ playerId: TEST_PLAYER.playerId, balance, currentSeason: "2026-10", inPlay: "0.000" });

function renderWallet(methods: Partial<Api>) {
  const api = fakeApi({
    myAccount: vi.fn(async () => account()),
    myDepositAddress: vi.fn(async () => ({ address: "txch1depositaddress" })),
    ...methods,
  });
  render(
    <MemoryRouter>
      <WalletPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
  return api;
}

/** The history's rows (the page's other lists — the settings rows — carry no amount). */
const rowTexts = () =>
  Array.from(document.querySelectorAll(".list-card .list-row"))
    .map((row) => row.textContent ?? "")
    .filter((text) => /PLAY$/.test(text));
const settlementRows = () => rowTexts().filter((t) => t.includes("Round settled"));

describe("Wallet history — what each entry type is called", () => {
  it("an operator correction has its own label, signed both ways — a credit and a debit", async () => {
    renderWallet({
      myLedger: ledgerOf([entry(3, 3000, "OPERATOR_CORRECTION", "-250.000"), entry(2, 2000, "OPERATOR_CORRECTION", "30.000"), entry(1, 1000, "STARTER_PACK", "10000.000")]),
    });
    expect(await screen.findAllByText("Operator correction")).toHaveLength(2);
    const texts = rowTexts();
    expect(texts[0]).toContain("-250 PLAY");
    expect(texts[1]).toContain("+30 PLAY");
    expect(texts[2]).toContain("Starter pack");
  });

  it("a type this build has never heard of is named readably, never a row with an amount and no name", async () => {
    renderWallet({ myLedger: ledgerOf([entry(1, 1000, "SOME_NEW_TYPE" as LedgerEntryType, "12.500")]) });
    expect(await screen.findByText("Some new type")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/undefined/);
  });
});

describe("Wallet history — a settlement is a PAYOUT: the round's result comes from its net, not from the payout's sign", () => {
  const rowOf = (text: string) => Array.from(document.querySelectorAll<HTMLElement>(".list-card .list-row")).find((r) => (r.textContent ?? "").includes(text))!;
  const iconColor = (row: HTMLElement) => row.querySelector<HTMLElement>(".list-row__icon")!.style.color;

  it("a bet of 100 that paid back 99.83 is LOST −0.17: red icon, the word, the net — while the credit stays +99.83", async () => {
    renderWallet({
      myLedger: ledgerOf([entry(3, 3000, "SETTLEMENT", "99.830"), entry(2, 2000, "STAKE_DEBIT", "-100.000")].map((e) => ({ ...e, roundId: "ETH-1h-1" }))),
    });
    await screen.findByText("Round settled");
    const settled = rowOf("Round settled");
    expect(settled).toHaveTextContent("lost -0.17 PLAY");
    expect(settled).toHaveTextContent("+99.83 PLAY"); // the ledger movement is true and stays
    expect(iconColor(settled)).toBe("var(--down)");
    expect(settled.querySelector(".ledger-result")).toHaveClass("outcome-lost");
    // not a win dressed in green
    expect(settled).not.toHaveTextContent("won");
  });

  it("a win, an even round and a liquidation (payout 0) each say what they were", async () => {
    const rows: LedgerEntryDto[] = [
      { ...entry(6, 6000, "SETTLEMENT", "104.960"), roundId: "W" },
      { ...entry(5, 5000, "SETTLEMENT", "150.000"), roundId: "E" },
      { ...entry(4, 4000, "SETTLEMENT", "0.000"), roundId: "L" },
      { ...entry(3, 3000, "STAKE_DEBIT", "-100.000"), roundId: "W" },
      { ...entry(2, 2000, "STAKE_DEBIT", "-150.000"), roundId: "E" },
      { ...entry(1, 1000, "STAKE_DEBIT", "-300.000"), roundId: "L" },
    ];
    renderWallet({ myLedger: ledgerOf(rows) });
    await screen.findAllByText("Round settled");
    const results = Array.from(document.querySelectorAll(".ledger-result")).map((r) => r.textContent?.replace(/\s+/g, " ").trim());
    expect(results).toEqual(["won +4.96 PLAY", "even 0 PLAY", "lost -300 PLAY"]);
  });

  it("a settlement whose stake is on an older page makes NO claim about the round: a neutral icon, no 'won', no trophy", async () => {
    renderWallet({ myLedger: ledgerOf([{ ...entry(9, 9000, "SETTLEMENT", "99.830"), roundId: "ETH-1h-1" }]) });
    await screen.findByText("Round settled");
    const settled = rowOf("Round settled");
    expect(settled.querySelector(".ledger-result")).toBeNull();
    expect(iconColor(settled)).toBe("var(--text-muted)");
    expect(settled).not.toHaveTextContent(/won|lost|even/);
  });
});

describe("Wallet history — paging", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  /** 24 entries in groups of three that share one millisecond (the settlements of a player in three markets whose
   *  rounds end at one boundary), the page being 20 — so a page boundary falls inside a group. */
  const grouped = () => Array.from({ length: 24 }, (_, i) => entry(24 - i, 100_000 - Math.floor(i / 3) * 10_000, "SETTLEMENT", `${24 - i}.000`));

  it("Load more shows EVERY entry once — none that shares a millisecond with the page's last row is skipped", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWallet({ myLedger: ledgerOf(grouped()) });
    await waitFor(() => expect(settlementRows()).toHaveLength(20));
    await user.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(settlementRows()).toHaveLength(24));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    // each exactly once (every row carries its own amount: +1 … +24)
    const amounts = settlementRows().map((t) => Number(/\+(\d+) PLAY/.exec(t)![1]));
    expect([...amounts].sort((a, b) => a - b)).toEqual(Array.from({ length: 24 }, (_, i) => i + 1));
  });

  it("the 5 s poll does not throw away the pages 'Load more' added", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const all = Array.from({ length: 25 }, (_, i) => entry(25 - i, 1_000_000 - i * 1000));
    const myLedger = ledgerOf(all);
    renderWallet({ myLedger });
    await waitFor(() => expect(settlementRows()).toHaveLength(20));
    await user.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(settlementRows().length).toBeGreaterThan(20));
    const loaded = settlementRows().length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    expect(settlementRows()).toHaveLength(loaded); // not 25 → 20
    expect(myLedger.mock.calls.filter(([p]) => p === undefined).length).toBeGreaterThan(1); // the poll did run
  });

  it("a new entry from the poll joins the TOP of what is loaded, nothing loaded is lost", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const all = Array.from({ length: 25 }, (_, i) => entry(25 - i, 1_000_000 - i * 1000));
    renderWallet({ myLedger: ledgerOf(all) });
    await waitFor(() => expect(settlementRows()).toHaveLength(20));
    await user.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(settlementRows().length).toBeGreaterThan(20));
    const loaded = settlementRows().length;

    all.unshift(entry(26, 2_000_000, "DEPOSIT", "700.000"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    await waitFor(() => expect(rowTexts()[0]).toContain("Deposit confirmed"));
    expect(rowTexts()).toHaveLength(loaded + 1);
  });

  it("a burst bigger than a page between two polls leaves no hole", async () => {
    const all = Array.from({ length: 10 }, (_, i) => entry(10 - i, 1_000_000 - i * 1000));
    renderWallet({ myLedger: ledgerOf(all) });
    await waitFor(() => expect(settlementRows()).toHaveLength(10));
    // 30 settlements land at once (18 markets and a few more): more than the 20 a page holds
    all.unshift(...Array.from({ length: 30 }, (_, i) => entry(40 - i, 2_000_000 - i * 10)));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    await waitFor(() => expect(settlementRows()).toHaveLength(40));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("a failing 'Load more' keeps what is shown and says so", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const paged = ledgerOf(grouped());
    const myLedger = vi.fn(async (params?: PageParams) => {
      if (params?.before !== undefined) throw new Error("down");
      return paged(params);
    });
    renderWallet({ myLedger });
    await waitFor(() => expect(settlementRows()).toHaveLength(20));
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't load older activity. Try again.");
    expect(settlementRows()).toHaveLength(20);
    expect(screen.getByRole("button", { name: "Load more" })).toBeEnabled();
  });
});

describe("Wallet — a server that is restarting (503 ENGINE_NOT_READY) is not an error of this page", () => {
  const engineNotReady = () => new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering — nothing was changed, please try again in a moment");

  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it("at the first load: the skeleton, no error card — and the wallet fills in by itself when the server is back", async () => {
    let up = false;
    const history = ledgerOf([entry(1, 1000, "STARTER_PACK", "10000.000")]);
    renderWallet({
      myAccount: vi.fn(async () => {
        if (!up) throw engineNotReady();
        return account();
      }),
      myLedger: history,
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText("Something went wrong.")).not.toBeInTheDocument();
    up = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    expect(await screen.findByText("Starter pack")).toBeInTheDocument();
  });

  it("after the wallet loaded: the numbers stay on screen, no error card replaces them", async () => {
    let down = false;
    renderWallet({
      myAccount: vi.fn(async () => {
        if (down) throw engineNotReady();
        return account("7777.000");
      }),
      myLedger: ledgerOf([entry(1, 1000, "STARTER_PACK", "10000.000")]),
    });
    await screen.findByText("Starter pack");
    expect(document.body.textContent).toContain("7,777 PLAY");
    down = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500);
    });
    expect(screen.getByText("Starter pack")).toBeInTheDocument();
    expect(document.body.textContent).toContain("7,777 PLAY");
    expect(screen.queryByText("Something went wrong.")).not.toBeInTheDocument();
    // a restart is not "couldn't refresh": the banner says it; this page adds no line of its own
    expect(screen.queryByText(/Couldn't refresh/)).not.toBeInTheDocument();
  });

  it("any OTHER failure after it loaded keeps the numbers too, and says they may be old", async () => {
    let down = false;
    renderWallet({
      myAccount: vi.fn(async () => {
        if (down) throw new Error("Network error — check your connection and try again.");
        return account("7777.000");
      }),
      myLedger: ledgerOf([entry(1, 1000, "STARTER_PACK", "10000.000")]),
    });
    await screen.findByText("Starter pack");
    down = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5200);
    });
    expect(screen.getByText("Starter pack")).toBeInTheDocument();
    expect(await screen.findByText("Couldn't refresh — these are the last numbers we had.")).toBeInTheDocument();
  });

  it("a first load that fails for any other reason is an error with a retry", async () => {
    renderWallet({
      myAccount: vi.fn(async () => {
        throw new Error("down");
      }),
      myLedger: ledgerOf([]),
    });
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("withdrawing while the server restarts: the app's own words, not the raw text", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWallet({
      myLedger: ledgerOf([entry(1, 1000, "STARTER_PACK", "10000.000")]),
      requestWithdrawal: vi.fn(async () => {
        throw engineNotReady();
      }),
    });
    await screen.findByText("Withdraw to wallet");
    await user.type(screen.getByLabelText("Amount (PLAY)"), "200");
    await user.click(screen.getByRole("button", { name: "Withdraw" }));
    expect(await screen.findByText("The server is restarting — try again in a moment.")).toBeInTheDocument();
    expect(screen.queryByText(/game engine is recovering/)).not.toBeInTheDocument();
  });
});
