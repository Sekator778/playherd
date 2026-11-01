import { describe, expect, it } from "vitest";
import { DICTIONARIES } from "./translate";
import type { Locale } from "./locale";

/** The texts added for the API bots and the developers' page (D-106). */
function botTexts(locale: Locale): Array<[string, string]> {
  const dict = DICTIONARIES[locale] as unknown as { bots: Record<string, string>; developers: Record<string, string>; ratings: Record<string, string>; layout: Record<string, string> };
  return [
    ...Object.entries(dict.bots).map(([k, v]): [string, string] => [`bots.${k}`, v]),
    ...Object.entries(dict.developers).map(([k, v]): [string, string] => [`developers.${k}`, v]),
    ["ratings.botsTab", dict.ratings.botsTab],
    ["ratings.botsNote", dict.ratings.botsNote],
    ["layout.developers", dict.layout.developers],
  ];
}

/** The game's money is PLAY and is never described as a plaything, in any language (the stems of the words for "toy"). */
const PLAYTHING = /\btoy|игруш|іграш|juguet|玩具|खिलौन/i;

describe("the API bots' texts, all six languages", () => {
  it.each(Object.keys(DICTIONARIES) as Locale[])("%s: never calls PLAY a plaything, and keeps PLAY and the call names as they are", (locale) => {
    for (const [key, text] of botTexts(locale)) {
      expect(text, `${locale}:${key}`).not.toMatch(PLAYTHING);
    }
    const developers = DICTIONARIES[locale].developers as Record<string, string>;
    expect(developers.callsBody).toContain("API_CALL_NOT_ALLOWED");
    expect(developers.authBody).toContain("API_KEY_INVALID");
    expect(developers.payoutBody).toContain("\"mode\"");
    expect(developers.payoutBody).toContain("BAD_REQUEST");
  });

  it("the payout-modes section names both modes in every language", () => {
    for (const locale of Object.keys(DICTIONARIES) as Locale[]) {
      const developers = DICTIONARIES[locale].developers as Record<string, string>;
      expect(developers.payoutTitle.length, locale).toBeGreaterThan(3);
      expect(developers.payoutBody, locale).toContain("PERCENTAGE");
      expect(developers.payoutBody, locale).toContain("FIXED");
    }
  });
});
