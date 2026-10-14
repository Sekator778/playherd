import { describe, expect, it } from "vitest";
import { categoryName } from "../api/marketMeta";
import { describeAnchor } from "../components/anchorModel";
import { formatAgo, formatTimeLeft } from "../components/marketFormat";
import { originPhrase, sourceName } from "../components/marketModel";
import { formatDuration, formatUpdatedAt } from "../format";
import { createT, tEn } from "./translate";

const uk = createT("uk");
const ru = createT("ru");

describe("display models speak the chosen language", () => {
  it("round lengths take the plural of the unit", () => {
    expect(formatDuration(3_600, tEn)).toBe("1 HOUR");
    expect(formatDuration(7_200, tEn)).toBe("2 HOURS");
    expect(formatDuration(3_600, uk)).toBe("1 ГОДИНА");
    expect(formatDuration(7_200, uk)).toBe("2 ГОДИНИ");
    expect(formatDuration(18_000, uk)).toBe("5 ГОДИН");
    expect(formatDuration(180, uk)).toBe("3 ХВ");
    expect(formatDuration(86_400 * 2, ru)).toBe("2 ДНЯ");
    expect(formatDuration(86_400 * 5, ru)).toBe("5 ДНЕЙ");
  });

  it("ages and time left use the language's units", () => {
    expect(formatAgo(5_000, uk)).toBe("щойно");
    expect(formatAgo(30_000, ru)).toBe("30 с назад");
    expect(formatAgo(5 * 60_000, uk)).toBe("5 хв тому");
    expect(formatTimeLeft(90 * 60_000, ru)).toBe("1ч 30м");
    expect(formatTimeLeft(90 * 60_000, tEn)).toBe("1h 30m");
  });

  it("the English defaults are unchanged", () => {
    expect(formatUpdatedAt(Date.UTC(2026, 9, 14, 6, 35, 38))).toMatch(/^Updated 06:35:38/);
    expect(originPhrase("coinbase")).toBe("Price from Coinbase");
    expect(sourceName("synthetic")).toBe("Simulated");
  });

  it("source phrases and the anchor wording are translated, names of exchanges are not", () => {
    expect(originPhrase("kraken", uk)).toBe("Ціна з Kraken");
    expect(originPhrase("polymarket", ru)).toBe("Шансы с Polymarket");
    expect(sourceName("synthetic", ru)).toBe("Симуляция");
    const anchor = describeAnchor({ seq: 7, state: "queued", height: null } as never, ru);
    expect(anchor.chip).toBe("В ОЧЕРЕДИ");
    expect(anchor.detail).toContain("№7");
  });

  it("category names follow the language, an unknown category keeps the server's title", () => {
    expect(categoryName({ id: "sports", title: "Sports" }, uk)).toBe("Спорт");
    expect(categoryName({ id: "commodities", title: "Commodities" }, ru)).toBe("Сырьё");
    expect(categoryName({ id: "weather" as never, title: "Weather" }, uk)).toBe("Weather");
  });
});
