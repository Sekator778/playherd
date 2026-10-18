import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { EconomyRulesDto, MarketDto } from "../api/contract";
import { market, marketsOf, seriesDto } from "../components/testFixtures";
import { FeesPage } from "./FeesPage";

function renderPage(markets: MarketDto[], economy: boolean | EconomyRulesDto = true) {
  const Wrapper = apiWrapper(
    fakeApi({
      markets: async () => {
        const dto = marketsOf(markets);
        if (economy === false) delete dto.economy;
        else if (economy !== true) dto.economy = economy;
        return dto;
      },
    }),
    null,
  );
  return render(
    <MemoryRouter>
      <Wrapper>
        <FeesPage />
      </Wrapper>
    </MemoryRouter>,
  );
}

const btc = market("BTC", {
  symbol: "BTC",
  series: [
    seriesDto({ maxLeverage: 40, herdFeeBp: "0.92", feeFactor: "1" }),
    seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 20, herdFeeBp: "0.92", feeFactor: "2.2" }),
  ],
});
const ger = market("GER", {
  kind: "ODDS",
  symbol: "GER",
  series: [seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 10, herdFeeBp: "2.5", feeFactor: "3" })],
});

describe("FeesPage — public, computed from the markets the server sends", () => {
  it("prints the table: the herd fee as a share of the stake for each market, round length and boost", async () => {
    renderPage([btc, ger]);
    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row");
    const cells = (i: number) => within(rows[i]).getAllByRole("cell").map((c) => c.textContent);
    expect(within(table).getAllByRole("columnheader").map((c) => c.textContent)).toEqual(["Market · round", "x3", "x10", "x25", "Highest"]);
    expect(rows[1]).toHaveTextContent("BTC · 3 MIN");
    expect(cells(1)).toEqual(["0.028 %", "0.092 %", "0.23 %", "0.368 % (x40)"]);
    expect(rows[2]).toHaveTextContent("BTC · 15 MIN");
    expect(cells(2)).toEqual(["0.061 %", "0.202 %", "—", "0.405 % (x20)"]);
    expect(rows[3]).toHaveTextContent("GER");
    expect(rows[3]).toHaveTextContent("ODDS");
    expect(cells(3)).toEqual(["0.225 %", "0.75 %", "—", "0.75 % (x10)"]);
  });

  it("says the formula, the three values of k from the server, the flat network fee and the stake tiers", async () => {
    renderPage([btc, ger]);
    await screen.findByRole("table");
    expect(screen.getByText("stake · min(20 %, k · r · boost)")).toBeInTheDocument();
    expect(screen.getByText(/k is 1 in 3 MIN rounds, 2\.2 in 15 MIN rounds, and 3 in 15 MIN rounds of odds markets\./)).toBeInTheDocument();
    expect(screen.getByText(/a flat network fee of 0\.1 PLAY/)).toBeInTheDocument();
    expect(screen.getByText(/up to 250 PLAY the market's highest boost, up to 500 1\/2 of it, up to 1200 1\/5 of it, above that 1\/10 of it/)).toBeInTheDocument();
    expect(screen.getByText("Both fees are paid to the HERD account.")).toBeInTheDocument();
  });

  it("the network fee, the guard and the tiers are the server's: other numbers on the wire are the numbers on the page", async () => {
    renderPage([btc, ger], {
      networkFeePlay: "0.25",
      herdFeeGuardPercent: "15",
      stakeTiers: [
        { upToPlay: 100, divisor: 1 },
        { upToPlay: 300, divisor: 2 },
        { upToPlay: 900, divisor: 4 },
        { upToPlay: null, divisor: 8 },
      ],
    });
    await screen.findByRole("table");
    expect(screen.getByText("stake · min(15 %, k · r · boost)")).toBeInTheDocument();
    expect(screen.getByText(/a flat network fee of 0\.25 PLAY/)).toBeInTheDocument();
    expect(screen.getByText(/up to 100 PLAY the market's highest boost, up to 300 1\/2 of it, up to 900 1\/4 of it, above that 1\/8 of it/)).toBeInTheDocument();
  });

  it("a server that sends the rates but no economy rules gets no table (no fallback constants)", async () => {
    renderPage([btc, ger], false);
    await waitFor(() => expect(screen.getByText("The fee table is not available from this server yet.")).toBeInTheDocument());
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText(/network fee/)).not.toBeInTheDocument();
  });

  it("a server that does not send the rates yet gets no invented table", async () => {
    renderPage([market("OLD", { series: [seriesDto({ maxLeverage: 10 })] })]);
    await waitFor(() => expect(screen.getByText("The fee table is not available from this server yet.")).toBeInTheDocument());
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
