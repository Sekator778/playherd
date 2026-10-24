import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CHART_STYLE_KEY, useChartStyle } from "./useChartStyle";

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
});

describe("useChartStyle", () => {
  it("opens on the market's default when nothing is stored", () => {
    expect(renderHook(() => useChartStyle("PERP")).result.current[0]).toBe("candles");
    expect(renderHook(() => useChartStyle("ODDS")).result.current[0]).toBe("line");
  });

  it("remembers the choice, and a new chart starts on it", () => {
    const first = renderHook(() => useChartStyle("PERP"));
    act(() => first.result.current[1]("line"));
    expect(first.result.current[0]).toBe("line");
    expect(window.localStorage.getItem(CHART_STYLE_KEY)).toBe("line");
    expect(renderHook(() => useChartStyle("PERP")).result.current[0]).toBe("line");
  });

  it("a stored look the market does not allow is not applied, and is kept for the markets that do", () => {
    window.localStorage.setItem(CHART_STYLE_KEY, "candles");
    expect(renderHook(() => useChartStyle("ODDS")).result.current[0]).toBe("line");
    expect(window.localStorage.getItem(CHART_STYLE_KEY)).toBe("candles");
  });

  it("storage that throws (private window, blocked) does not break the chart: it still switches for the visit", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useChartStyle("PERP"));
    expect(result.current[0]).toBe("candles");
    act(() => result.current[1]("line"));
    expect(result.current[0]).toBe("line");
  });
});
