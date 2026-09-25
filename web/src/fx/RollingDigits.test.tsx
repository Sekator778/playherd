import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
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
