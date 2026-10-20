import { describe, expect, it, vi } from "vitest";
import { createT } from "../i18n/translate";
import {
  CARD_HEIGHT,
  CARD_WIDTH,
  buildShareCard,
  drawShareCard,
  performShare,
  shareLink,
  type Card2D,
  type ShareEnv,
  type ShareResultInput,
} from "./shareCard";

const t = createT("en");

function input(over: Partial<ShareResultInput> = {}): ShareResultInput {
  return { roundId: "BTC-1h-1000", symbol: "BTC", seriesLabel: "3 MIN", direction: "UP", mode: null, leverage: 10, stake: 100, net: 25, liquidated: false, ...over };
}

describe("share card content", () => {
  it("a won percentage bet: market, side, mode, +PLAY, the return on the stake, the chain line, the address", () => {
    const card = buildShareCard(input(), t);
    expect(card.title).toBe("BTC · 3 MIN");
    expect(card.direction).toBe("UP");
    expect(card.modeLabel).toBe("Percent · x10");
    expect(card.outcome).toBe("won");
    expect(card.resultText).toBe("+25 PLAY");
    expect(card.percentText).toBe("+25.0%");
    expect(card.chainLine).toBe("Recorded on Chia testnet11");
    expect(card.address).toBe("playherd.app");
    expect(card.shareText).toBe("My Herd round: BTC UP, +25 PLAY (+25.0%).");
  });

  it("a lost percentage bet is worded as plainly as a win, with a real minus sign (Р-51)", () => {
    const card = buildShareCard(input({ direction: "DOWN", net: -40 }), t);
    expect(card.outcome).toBe("lost");
    expect(card.resultText).toBe("−40 PLAY");
    expect(card.percentText).toBe("−40.0%");
    expect(card.shareText).toContain("−40 PLAY");
  });

  it("a liquidated percentage bet carries the LIQ tag", () => {
    const card = buildShareCard(input({ net: -100, liquidated: true }), t);
    expect(card.resultText).toBe("−100 PLAY");
    expect(card.liquidatedTag).toBe("LIQ");
  });

  it("Win or lose: the mode says so, a win has no percentage", () => {
    const card = buildShareCard(input({ mode: "FIXED", net: 94.6, stake: 100 }), t);
    expect(card.modeLabel).toBe("Win or lose");
    expect(card.percentText).toBeNull();
    expect(card.resultText).toBe("+94.60 PLAY");
  });

  it("Win or lose, lost: minus the stake, no percentage, no LIQ tag", () => {
    const card = buildShareCard(input({ mode: "FIXED", net: -100, liquidated: false }), t);
    expect(card.outcome).toBe("lost");
    expect(card.resultText).toBe("−100 PLAY");
    expect(card.percentText).toBeNull();
    expect(card.liquidatedTag).toBeNull();
  });

  it("a tie says the stake is back, in both modes, with no percentage", () => {
    for (const mode of ["FIXED", null] as const) {
      const card = buildShareCard(input({ mode, net: 0 }), t);
      expect(card.outcome).toBe("even");
      expect(card.resultText).toBe("Tie — stake back");
      expect(card.percentText).toBeNull();
    }
  });

  it("says nothing about prizes, income or prices, and never the adjective for a plaything, in any language", () => {
    for (const locale of ["en", "uk", "ru", "es", "zh", "hi"] as const) {
      const tt = createT(locale);
      for (const net of [25, -25, 0]) {
        const card = buildShareCard(input({ net }), tt);
        const all = [card.title, card.modeLabel, card.resultText, card.chainLine, card.shareText, card.alt].join(" ");
        expect(all, locale).not.toMatch(/prize|income|earn|profit|price|toy|игруш|іграш|juguete|玩具|खिलौन/i);
      }
    }
  });
});

describe("share link", () => {
  it("is the round page with the player's own code and src=share", () => {
    expect(shareLink("BTC-1h-1000", "ab12cd34", "https://playherd.app")).toBe("https://playherd.app/round/BTC-1h-1000?ref=ab12cd34&src=share");
  });
  it("without a code it still says where it came from", () => {
    expect(shareLink("BTC-1h-1000", null, "https://playherd.app")).toBe("https://playherd.app/round/BTC-1h-1000?src=share");
  });
});

describe("drawing", () => {
  it("writes the card's texts on a canvas of the card's size", () => {
    const texts: string[] = [];
    const rects: number[][] = [];
    const ctx: Card2D = {
      fillStyle: "",
      strokeStyle: "",
      lineWidth: 1,
      font: "",
      textAlign: "left",
      textBaseline: "alphabetic",
      fillRect: (x, y, w, h) => void rects.push([x, y, w, h]),
      strokeRect: () => {},
      fillText: (text) => void texts.push(text),
      measureText: (text) => ({ width: text.length * 20 }),
    };
    const card = buildShareCard(input({ net: -40 }), t);
    drawShareCard(ctx, card);
    expect(rects[0]).toEqual([0, 0, CARD_WIDTH, CARD_HEIGHT]);
    expect(texts).toEqual(expect.arrayContaining(["BTC · 3 MIN", "UP", "· Percent · x10", "−40 PLAY", "−40.0%", "Recorded on Chia testnet11", "playherd.app"]));
  });
});

function env(nav: Record<string, unknown>, download = vi.fn(() => true)): ShareEnv & { download: ReturnType<typeof vi.fn> } {
  return { nav, download };
}
const parts = { image: new Blob(["png"], { type: "image/png" }), link: "https://playherd.app/round/R?src=share", text: "My Herd round", title: "Herd" };

describe("sharing", () => {
  it("uses the Web Share API with the picture when the browser takes files", async () => {
    const share = vi.fn(async () => {});
    const e = env({ share, canShare: () => true });
    expect(await performShare(parts, e)).toBe("shared");
    const data = (share.mock.calls as unknown as Array<[ShareData]>)[0][0];
    expect(data.files?.[0].name).toBe("herd-result.png");
    expect(data.url).toBe(parts.link);
    expect(e.download).not.toHaveBeenCalled();
  });

  it("without Web Share: the picture is downloaded and the link copied", async () => {
    const writeText = vi.fn(async () => {});
    const e = env({ clipboard: { writeText } });
    expect(await performShare(parts, e)).toBe("savedAndCopied");
    expect(e.download).toHaveBeenCalledWith(parts.image, "herd-result.png");
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(parts.link));
  });

  it("a browser that shares links but not files: the picture is saved, the link goes to the share sheet", async () => {
    const share = vi.fn(async () => {});
    const e = env({ share, canShare: () => false });
    expect(await performShare(parts, e)).toBe("shared");
    expect(e.download).toHaveBeenCalled();
    expect((share.mock.calls as unknown as Array<[ShareData]>)[0][0].files).toBeUndefined();
  });

  it("a player who closes the share sheet is not told anything failed", async () => {
    const share = vi.fn(async () => {
      throw new DOMException("closed", "AbortError");
    });
    expect(await performShare(parts, env({ share, canShare: () => true }))).toBe("cancelled");
  });

  it("without a picture (no canvas) only the link is copied; with nothing at all it says it failed", async () => {
    const writeText = vi.fn(async () => {});
    expect(await performShare({ ...parts, image: null }, env({ clipboard: { writeText } }))).toBe("copied");
    expect(await performShare({ ...parts, image: null }, env({}))).toBe("failed");
  });
});
