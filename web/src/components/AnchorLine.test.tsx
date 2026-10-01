import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AnchorLine } from "./AnchorLine";

const HASH = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

describe("AnchorLine", () => {
  it("renders nothing when the server sent neither an anchor nor a hash — no 'anchored' claim without something behind it", () => {
    const { container } = render(<AnchorLine roundId="1h-1" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("confirmed: title, block, the hash and the link to the public results.json", () => {
    render(<AnchorLine roundId="BTC-1h-1735689600000" resultsHash={HASH} anchor={{ seq: 412, state: "confirmed", height: 4_738_927 }} />);
    expect(screen.getByText("Anchored on Chia")).toBeInTheDocument();
    expect(screen.getByText("CONFIRMED")).toBeInTheDocument();
    expect(screen.getByText("Oracle record #412, confirmed at block 4,738,927 on testnet11.")).toBeInTheDocument();
    expect(screen.getByText(/Results hash: 0x4b88ce97…080b70/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "verify results.json" });
    expect(link).toHaveAttribute("href", "/api/rounds/BTC-1h-1735689600000/results.json");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
  });

  it("queued or sent: waiting, and the page says so", () => {
    const { rerender } = render(<AnchorLine roundId="1h-1" anchor={{ seq: 5, state: "queued", height: null }} />);
    expect(screen.getByText("Waiting to be anchored on Chia")).toBeInTheDocument();
    expect(screen.getByText("QUEUED")).toBeInTheDocument();
    rerender(<AnchorLine roundId="1h-1" anchor={{ seq: 5, state: "in_flight", height: null }} />);
    expect(screen.getByText("Being anchored on Chia")).toBeInTheDocument();
    expect(screen.getByText("SENT")).toBeInTheDocument();
  });

  it("an anchor with no hash yet says the hash is not published — and still links the file", () => {
    render(<AnchorLine roundId="1h-1" anchor={{ seq: 5, state: "queued", height: null }} />);
    expect(screen.getByText(/Results hash not published yet/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "verify results.json" })).toHaveAttribute("href", "/api/rounds/1h-1/results.json");
  });

  it("a hash with no anchor (an older server) is still shown, without an anchoring claim", () => {
    render(<AnchorLine roundId="1h-1" resultsHash={HASH} />);
    expect(screen.getByText("Results published")).toBeInTheDocument();
    expect(screen.queryByText(/Anchored on Chia/)).not.toBeInTheDocument();
    expect(screen.getByText(/Results hash: 0x4b88ce97…080b70/)).toBeInTheDocument();
  });

  it("a conflict is as visible as a confirmation", () => {
    const { container } = render(<AnchorLine roundId="1h-1" resultsHash={HASH} anchor={{ seq: 9, state: "conflict", height: null }} />);
    expect(screen.getByText("Not anchored — conflict")).toBeInTheDocument();
    expect(screen.getByText("CONFLICT")).toBeInTheDocument();
    expect(container.firstChild).toHaveClass("anchor--bad");
  });

  it("a round id with a market in it is URL-safe", () => {
    render(<AnchorLine roundId="xyz_NVDA-1h-1" resultsHash={HASH} />);
    expect(screen.getByRole("link", { name: "verify results.json" })).toHaveAttribute("href", "/api/rounds/xyz_NVDA-1h-1/results.json");
  });
});
