import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import type { Api, SupplyDto } from "../api/contract";
import { catalogue, marketsOf } from "../components/testFixtures";
import { StatusPage } from "./StatusPage";

/** Economy stage C (C5, D-90): the public supply block of the status page. */

const COIN = "0x4f6e8d8f5d2b0a7c9e1b3a5d7f9c2e4a6b8d0f1a3c5e7092b4d6f8a0c2e4b6d8";

const supplyDto = (over: Partial<SupplyDto> = {}): SupplyDto => ({
  economyActive: true,
  issued: "100000000.000",
  onChainTreasury: "99998100.000",
  treasuryCoinId: COIN,
  heldByPlayers: "31900.000",
  platformReserve: "99966200.000",
  withdrawnOnChain: "1900.000",
  booksBalanced: true,
  chainConsistent: true,
  circuitBreakerOpen: false,
  atMs: 1_000,
  ...over,
});

function renderStatus(methods: Partial<Api> = {}) {
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(catalogue())),
    cancellations: vi.fn(async () => ({ items: [], hasMore: false })),
    serverInfo: vi.fn(async () => serverInfoDto()),
    ...methods,
  });
  render(
    <MemoryRouter>
      <StatusPage />
    </MemoryRouter>,
    { wrapper: apiWrapper(api) },
  );
  return api;
}

const supplyCard = () => screen.getByRole("region", { name: "Supply" });

describe("Status — the Supply block", () => {
  it("issued, the treasury coin on chain with its id (copyable), held by players, the platform reserve, withdrawn, and that the books balance", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto()) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("100,000,000.000 PLAY"));
    const card = supplyCard();
    expect(card).toHaveTextContent(/Issued\s*100,000,000.000 PLAY/);
    expect(card).toHaveTextContent(/Treasury coin on chain\s*99,998,100.000 PLAY/);
    expect(within(card).getByLabelText("Treasury coin id")).toHaveTextContent(COIN);
    expect(within(card).getByRole("button", { name: /copy/i })).toBeInTheDocument();
    expect(card).toHaveTextContent(/Held by players\s*31,900.000 PLAY/);
    expect(card).toHaveTextContent(/Platform reserve\s*99,966,200.000 PLAY/);
    expect(card).toHaveTextContent(/Withdrawn on chain\s*1,900.000 PLAY/);
    expect(card).toHaveTextContent("Balanced — every PLAY is accounted for.");
    expect(card).toHaveTextContent("Agree with the chain.");
  });

  it("the reserve is the only platform figure: no word of fees, revenue or the platform's own account", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto()) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("Platform reserve"));
    expect(supplyCard().textContent ?? "").not.toMatch(/herd|fee|revenue|income|profit/i);
  });

  it("books that do not balance, and a chain that differs, are said", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto({ booksBalanced: false, chainConsistent: false })) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("Out of balance."));
    expect(supplyCard()).toHaveTextContent("Differ from the chain by more than pending deposits and withdrawals explain.");
  });

  it("a chain that cannot be read: no coin, no invented number, and it says so", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto({ onChainTreasury: null, treasuryCoinId: null, chainConsistent: null })) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("The chain couldn't be read just now."));
    expect(supplyCard()).not.toHaveTextContent("Treasury coin on chain");
  });

  it("an open circuit breaker is said, without a figure", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto({ circuitBreakerOpen: true })) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("Safety limit is on: new bets are limited to a lower leverage until the reserve recovers."));
  });

  it("before the economy cutover the accounts are not split: issued and the chain only", async () => {
    renderStatus({ supply: vi.fn(async () => supplyDto({ economyActive: false })) });
    await waitFor(() => expect(supplyCard()).toHaveTextContent("Issued"));
    expect(supplyCard()).not.toHaveTextContent("Held by players");
    expect(supplyCard()).not.toHaveTextContent("Platform reserve");
  });

  it("a build without the call (the demo) says it is demo data", async () => {
    renderStatus();
    expect(await screen.findByText("Demo data — this build has no chain to check against.")).toBeInTheDocument();
  });

  it("a failing call is an error state with a retry, not a blank card", async () => {
    renderStatus({
      supply: vi.fn(async () => {
        throw new Error("down");
      }),
    });
    expect(await screen.findByText("Couldn't load the supply.")).toBeInTheDocument();
  });
});
