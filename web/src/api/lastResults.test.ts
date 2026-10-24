import { describe, expect, it } from "vitest";
import { classifyRound, countResults, lastResults } from "./lastResults";

const r = (stake: string, payout: string) => ({ stake, payout });

describe("last results", () => {
  it("classifies as the streak does: payout above stake wins, below loses, equal is flat, liquidation (0) loses", () => {
    expect(classifyRound(r("10", "19.85"))).toBe("won");
    expect(classifyRound(r("10", "4.2"))).toBe("lost");
    expect(classifyRound(r("10", "10"))).toBe("flat");
    expect(classifyRound(r("10", "0"))).toBe("lost");
  });
  it("takes the 5 newest of a newest-first history and returns them oldest first", () => {
    const history = [r("1", "2"), r("1", "0"), r("1", "1"), r("1", "3"), r("1", "0"), r("1", "9")];
    expect(lastResults(history)).toEqual(["lost", "won", "flat", "lost", "won"]);
  });
  it("fewer than 5 rounds shows what there is; none shows nothing", () => {
    expect(lastResults([r("1", "2")])).toEqual(["won"]);
    expect(lastResults([])).toEqual([]);
  });
  it("counts", () => {
    expect(countResults(["won", "won", "lost", "flat", "won"])).toEqual({ won: 3, lost: 1, flat: 1 });
  });
});
