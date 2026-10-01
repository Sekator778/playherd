import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StreakBadge } from "./StreakBadge";

describe("StreakBadge", () => {
  it("a streak: the number, the words, the best — and the plain rules, no urging", () => {
    render(<StreakBadge current={3} best={7} />);
    expect(screen.getByRole("group", { name: "Win streak: 3 in a row now, best 7" })).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText(/wins in a row/)).toBeInTheDocument();
    expect(screen.getByText(/Best:/)).toHaveTextContent("Best: 7 · a loss resets it, a flat round doesn't");
    expect(screen.queryByText(/don't lose|keep it alive|hurry/i)).not.toBeInTheDocument();
  });

  it("one win is 'win', not 'wins'", () => {
    render(<StreakBadge current={1} best={1} />);
    expect(screen.getByText(/win in a row/)).toBeInTheDocument();
    expect(screen.queryByText(/wins in a row/)).not.toBeInTheDocument();
  });

  it("zero is designed, not blank: a quiet state that says how one starts", () => {
    const { container } = render(<StreakBadge current={0} best={0} />);
    expect(screen.getByText("No win streak right now")).toBeInTheDocument();
    expect(screen.getByText(/Best:/)).toHaveTextContent("Best: 0 · a win starts one");
    expect(container.firstChild).toHaveClass("streak--off");
    expect(screen.getByRole("group")).toHaveAccessibleName("Win streak: 0 in a row now, best 0");
  });

  it("zero now but a best before: it says the next win starts a new one", () => {
    render(<StreakBadge current={0} best={5} />);
    expect(screen.getByText(/Best:/)).toHaveTextContent("Best: 5 · the next win starts a new one");
  });

  it("an active streak wears the gold state", () => {
    const { container } = render(<StreakBadge current={2} best={2} />);
    expect(container.firstChild).toHaveClass("streak--on");
  });
});
