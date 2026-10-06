import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CHART_FAILED_TEXT, ChartBoundary, ErrorBoundary } from "./ErrorBoundary";

function Bomb({ explode }: { explode: boolean }) {
  if (explode) throw new Error("could not draw it");
  return <p>the drawing</p>;
}

// React reports a caught render error to the console as well as to the boundary: kept off the run's output, and looked at below.
let consoleError: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => consoleError.mockRestore());

describe("ErrorBoundary — an error thrown while rendering stays with the part that threw it", () => {
  it("shows its fallback in the place of the part, and the rest of the page is still there", () => {
    render(
      <div>
        <header>the header</header>
        <ErrorBoundary fallback={<p>fallback</p>}>
          <Bomb explode />
        </ErrorBoundary>
        <footer>the tab bar</footer>
      </div>,
    );
    expect(screen.getByText("fallback")).toBeInTheDocument();
    expect(screen.queryByText("the drawing")).not.toBeInTheDocument();
    expect(screen.getByText("the header")).toBeInTheDocument();
    expect(screen.getByText("the tab bar")).toBeInTheDocument();
  });

  it("without it the same error takes everything down — the reason it is there", () => {
    expect(() =>
      render(
        <div>
          <header>the header</header>
          <Bomb explode />
        </div>,
      ),
    ).toThrow("could not draw it");
    expect(screen.queryByText("the header")).not.toBeInTheDocument();
  });

  it("says what failed on the console, for whoever debugs it", () => {
    render(
      <ErrorBoundary fallback={<p>fallback</p>}>
        <Bomb explode />
      </ErrorBoundary>,
    );
    expect(consoleError.mock.calls.some((call: unknown[]) => call.some((arg) => arg instanceof Error && arg.message === "could not draw it"))).toBe(true);
  });

  it("a part that does not fail is shown as it is", () => {
    render(
      <ErrorBoundary fallback={<p>fallback</p>}>
        <Bomb explode={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText("the drawing")).toBeInTheDocument();
    expect(screen.queryByText("fallback")).not.toBeInTheDocument();
  });

  it("tries its children again when its reset key changes — and not before", () => {
    function Page() {
      const [data, setData] = useState({ bad: true });
      return (
        <>
          <button type="button" onClick={() => setData((d) => ({ ...d }))}>
            same trouble, a new object
          </button>
          <button type="button" onClick={() => setData({ bad: false })}>
            good data
          </button>
          <ErrorBoundary resetKey={data.bad} fallback={<p>fallback</p>}>
            <Bomb explode={data.bad} />
          </ErrorBoundary>
        </>
      );
    }
    render(<Page />);
    expect(screen.getByText("fallback")).toBeInTheDocument();
    // the key (`bad`) has not changed: nothing is tried again, the fallback stays
    fireEvent.click(screen.getByRole("button", { name: "same trouble, a new object" }));
    expect(screen.getByText("fallback")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "good data" }));
    expect(screen.getByText("the drawing")).toBeInTheDocument();
    expect(screen.queryByText("fallback")).not.toBeInTheDocument();
  });
});

describe("ChartBoundary — the line that stands where a chart could not be drawn", () => {
  it("is short and says what happened", () => {
    render(
      <ChartBoundary>
        <Bomb explode />
      </ChartBoundary>,
    );
    expect(CHART_FAILED_TEXT).toBe("This chart could not be drawn.");
    expect(screen.getByText("This chart could not be drawn.")).toBeInTheDocument();
  });
});
