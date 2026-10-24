import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LastResults } from "./LastResults";

describe("LastResults", () => {
  it("one accessible label with the counts; newest on the right", () => {
    render(<LastResults results={["lost", "won", "flat", "won", "won"]} />);
    expect(screen.getByRole("group", { name: "Last 5 rounds: 3 won, 1 lost, 1 flat" })).toBeInTheDocument();
  });
  it("a single round is 'round'", () => {
    render(<LastResults results={["lost"]} />);
    expect(screen.getByRole("group", { name: "Last 1 round: 0 won, 1 lost, 0 flat" })).toBeInTheDocument();
  });
  it("marks carry a letter, not only a colour", () => {
    const { container } = render(<LastResults results={["won", "lost", "flat"]} />);
    expect(Array.from(container.querySelectorAll("li")).map((li) => li.textContent)).toEqual(["W", "L", "="]);
  });
  it("nothing without a settled round", () => {
    const { container } = render(<LastResults results={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
