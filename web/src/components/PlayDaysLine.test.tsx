import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PlayDaysLine } from "./PlayDaysLine";

describe("PlayDaysLine", () => {
  it("says the run and the best, in one line", () => {
    render(<PlayDaysLine current={3} best={5} />);
    expect(screen.getByLabelText("Played 3 days in a row now, best 5")).toHaveTextContent("Played 3 days in a row · best 5");
  });

  it("one day is 'day'", () => {
    render(<PlayDaysLine current={1} best={1} />);
    expect(screen.getByText("Played 1 day in a row")).toBeInTheDocument();
  });

  it("zero is a calm state: a plain line, the best if there was one, nothing urging", () => {
    const { container } = render(<PlayDaysLine current={0} best={4} />);
    expect(container).toHaveTextContent("No run of days right now · best 4");
    expect(container.textContent).not.toMatch(/lose|streak|hurry|today|bonus/i);
  });

  it("a player with no days at all sees no 'best 0'", () => {
    const { container } = render(<PlayDaysLine current={0} best={0} />);
    expect(container).toHaveTextContent("No run of days right now");
    expect(container.textContent).not.toMatch(/best/);
  });
});
