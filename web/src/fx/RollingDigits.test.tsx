import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { rollingPlayFormat } from "../format";
import { setForcedReducedMotion } from "./reducedMotion";
import { RollingDigits } from "./RollingDigits";

const format = (n: number) => `${Math.round(n)} PLAY`;

afterEach(() => {
  setForcedReducedMotion(null);
});

describe("RollingDigits", () => {
  it("renders the initial value on mount", () => {
    render(<RollingDigits value={100} format={format} />);
    expect(screen.getByText("100 PLAY")).toBeInTheDocument();
  });

  it("under prefers-reduced-motion: reduce, a value change renders synchronously — no roll (direction.md §6)", () => {
    setForcedReducedMotion(true);
    const { rerender } = render(<RollingDigits value={100} format={format} />);
    expect(screen.getByText("100 PLAY")).toBeInTheDocument();

    rerender(<RollingDigits value={-1985} format={format} />);
    expect(screen.getByText("-1985 PLAY")).toBeInTheDocument();
  });
});

describe("RollingDigits with a PLAY balance — no third decimal on the way (first tester pass, D12: the pill)", () => {
  afterEach(() => vi.useRealTimers());

  /** Every frame of a count-up from `from` to `to`, read off the screen. */
  async function frames(from: number, to: number): Promise<string[]> {
    vi.useFakeTimers();
    const { container, rerender } = render(<RollingDigits value={from} format={rollingPlayFormat(from)} durationMs={500} />);
    rerender(<RollingDigits value={to} format={rollingPlayFormat(to)} durationMs={500} />);
    const seen: string[] = [];
    for (let i = 0; i < 40; i++) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(16);
      });
      seen.push(container.textContent ?? "");
    }
    return seen;
  }

  it("a balance of two decimals counts up in two decimals, frame after frame, and lands on its own number", async () => {
    const seen = await frames(9_957.65, 9_559.45);
    expect(new Set(seen).size).toBeGreaterThan(5); // it really did roll
    expect(seen.every((text) => /^\d,\d{3}\.\d{2}$/.test(text)), seen.join(" | ")).toBe(true);
    expect(seen[seen.length - 1]).toBe("9,559.45");
  });

  it("a whole balance counts in whole PLAY", async () => {
    const seen = await frames(9_957.65, 10_000);
    expect(seen.every((text) => /^(9,\d{3}|10,000)$/.test(text)), seen.join(" | ")).toBe(true);
    expect(seen[seen.length - 1]).toBe("10,000");
  });
});
