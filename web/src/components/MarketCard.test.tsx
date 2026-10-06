import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { MarketCard } from "./MarketCard";
import { NOW, catalogue } from "./testFixtures";

function renderCard(id: string, over: Record<string, unknown> = {}) {
  const base = catalogue().find((m) => m.id === id)!;
  return render(
    <MemoryRouter>
      <MarketCard market={{ ...base, ...over }} nowMs={NOW} favorite={false} onToggleFavorite={() => {}} />
    </MemoryRouter>,
  );
}

describe("MarketCard — an odds card says what its percentage is the probability OF (first tester pass, D7)", () => {
  it("on a line of its own, outcome and context apart: the outcome is never the part that is cut", () => {
    const { container } = renderCard("PM900104");
    const about = container.querySelector(".mk-card__about") as HTMLElement;
    expect(about).not.toBeNull();
    // the line is the card's own, outside the id column between the icon and the price — where it had a hundred pixels
    expect(container.querySelector(".mk-card__id")?.contains(about)).toBe(false);
    expect(about.querySelector(".mk-card__title")).toHaveTextContent("Germany · UEFA Nations League");
    expect(about.querySelector(".mk-card__outcome")).toHaveTextContent(/^Germany$/);
    expect(about.querySelector(".mk-card__context")).toHaveTextContent("· UEFA Nations League");
    // and the question follows it
    expect(about.querySelector(".mk-card__question")).toHaveTextContent("Will Germany win against Serbia?");
  });

  it("a subtitle with no context is the outcome alone", () => {
    const { container } = renderCard("PM900104", { subtitle: "Germany to win" });
    expect(container.querySelector(".mk-card__outcome")).toHaveTextContent("Germany to win");
    expect(container.querySelector(".mk-card__context")).toBeNull();
  });

  it("an odds market with no subtitle has no subline: its title is the question under the ticker, said once (it used to be said twice)", () => {
    const { container } = renderCard("PM900104", { subtitle: null });
    expect(container.querySelector(".mk-card__title--odds")).toBeNull();
    expect(container.querySelector(".mk-card__question")).toHaveTextContent("Will Germany win against Serbia?");
    expect(container.textContent?.split("Will Germany win against Serbia?").length).toBe(2);
  });

  it("a perp keeps its plain title in the id column", () => {
    const { container } = renderCard("BTC");
    const title = container.querySelector(".mk-card__id .mk-card__title") as HTMLElement;
    expect(title).toHaveTextContent("Bitcoin");
    expect(container.querySelector(".mk-card__about")).toBeNull();
  });
});
