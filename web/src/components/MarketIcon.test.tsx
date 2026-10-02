import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MarketIcon } from "./MarketIcon";

const URL = "https://polymarket-upload.s3.us-east-2.amazonaws.com/market.png";

function img(container: HTMLElement): HTMLImageElement | null {
  return container.querySelector("img");
}

describe("MarketIcon — a source's image lives on the source's host, so it is loaded the quiet way", () => {
  it("referrerPolicy no-referrer, lazy, async-decoded, with its size set (no layout shift)", () => {
    const { container } = render(<MarketIcon market={{ symbol: "GER", iconUrl: URL }} size={40} />);
    const image = img(container)!;
    expect(image).toHaveAttribute("src", URL);
    expect(image).toHaveAttribute("referrerpolicy", "no-referrer");
    expect(image).toHaveAttribute("loading", "lazy");
    expect(image).toHaveAttribute("decoding", "async");
    expect(image).toHaveAttribute("width", "40");
    expect(image).toHaveAttribute("height", "40");
    expect(image).toHaveAttribute("alt", ""); // decorative: the symbol is printed beside it
  });

  it("a picture that fails to load falls back to the letter glyph — never a broken-image box", () => {
    const { container } = render(<MarketIcon market={{ symbol: "GER", iconUrl: URL }} />);
    fireEvent.error(img(container)!);
    expect(img(container)).toBeNull();
    expect(container.querySelector(".mk-icon__glyph")).not.toBeNull();
  });

  it("another url after a failed one is tried again", () => {
    const { container, rerender } = render(<MarketIcon market={{ symbol: "GER", iconUrl: URL }} />);
    fireEvent.error(img(container)!);
    rerender(<MarketIcon market={{ symbol: "GER", iconUrl: `${URL}?v=2` }} />);
    expect(img(container)).toHaveAttribute("src", `${URL}?v=2`);
  });

  it("no image url is the glyph from the start — and nothing is fetched", () => {
    const { container } = render(<MarketIcon market={{ symbol: "BTC", iconUrl: null }} />);
    expect(img(container)).toBeNull();
    expect(container.querySelector(".mk-icon__glyph")).not.toBeNull();
  });

  it("is decorative to assistive technology", () => {
    const { container } = render(<MarketIcon market={{ symbol: "GER", iconUrl: URL }} />);
    expect(container.querySelector(".mk-icon")).toHaveAttribute("aria-hidden", "true");
  });
});
