import { describe, expect, it } from "vitest";
import type { LedgerEntryDto, LedgerEntryType } from "../api/contract";
import { settlementNets } from "./walletModel";

let sequence = 0;
const entry = (type: LedgerEntryType, delta: string, roundId: string | null, seq = ++sequence): LedgerEntryDto => ({ sequence: seq, type, delta, roundId, atMs: seq * 1000 });

describe("settlementNets — the result of a round, from the ledger, never the sign of its payout", () => {
  it("a loss that still paid something back is a LOSS: stake 100, paid 99.83 → net −0.17 (the first live run showed a trophy)", () => {
    const rows = [entry("STAKE_DEBIT", "-100.000", "ETH-1h-1"), entry("SETTLEMENT", "99.830", "ETH-1h-1")];
    expect(settlementNets(rows).get("ETH-1h-1")).toBe(-0.17);
  });

  it("a win, an even round and a liquidation (payout 0 — the whole stake)", () => {
    const rows = [
      entry("STAKE_DEBIT", "-100.000", "W"),
      entry("STAKE_DEBIT", "-150.000", "E"),
      entry("STAKE_DEBIT", "-300.000", "L"),
      entry("SETTLEMENT", "104.960", "W"),
      entry("SETTLEMENT", "150.000", "E"),
      entry("SETTLEMENT", "0.000", "L"),
    ];
    const nets = settlementNets(rows);
    expect(nets.get("W")).toBe(4.96);
    expect(nets.get("E")).toBe(0);
    expect(nets.get("L")).toBe(-300);
  });

  it("order in the list does not matter (the wallet lists newest first)", () => {
    const rows = [entry("SETTLEMENT", "99.830", "R"), entry("STAKE_DEBIT", "-100.000", "R", 1)];
    rows[0].sequence = 2;
    expect(settlementNets(rows).get("R")).toBe(-0.17);
  });

  it("a bet edited before the round (refund, then a new debit) nets to the last stake against the payout", () => {
    const rows = [
      entry("STAKE_DEBIT", "-100.000", "R"),
      entry("STAKE_REFUND", "100.000", "R"),
      entry("STAKE_DEBIT", "-250.000", "R"),
      entry("SETTLEMENT", "300.000", "R"),
    ];
    expect(settlementNets(rows).get("R")).toBe(50);
  });

  it("a page that cuts the round short makes no claim: the debit is not loaded → no net", () => {
    expect(settlementNets([entry("SETTLEMENT", "99.830", "R")]).has("R")).toBe(false);
  });

  it("…and neither does one that starts at a REFUND (its debit is on an older page): the sum would be wrong", () => {
    const rows = [entry("STAKE_REFUND", "100.000", "R", 2), entry("STAKE_DEBIT", "-250.000", "R", 3), entry("SETTLEMENT", "300.000", "R", 4)];
    expect(settlementNets(rows).has("R")).toBe(false);
  });

  it("a round with a debit but no settlement yet has no result; rows with no round (starter pack, deposits) are left alone", () => {
    const rows = [entry("STAKE_DEBIT", "-100.000", "R"), entry("STARTER_PACK", "10000.000", null), entry("DEPOSIT", "500.000", null)];
    expect(settlementNets(rows).size).toBe(0);
  });

  it("sums are exact to the milli-PLAY (no 0.1 + 0.2 float noise)", () => {
    const rows = [entry("STAKE_DEBIT", "-0.100", "R"), entry("SETTLEMENT", "0.300", "R")];
    expect(settlementNets(rows).get("R")).toBe(0.2);
  });
});
