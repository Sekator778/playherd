import { describe, expect, it } from "vitest";
import { checkNickname, NICKNAME_HINT } from "./nickname";

describe("checkNickname — the server's rule, said in words before the question is asked", () => {
  it.each(["abc", "moonWalker", "a b", "A_b.c-d", "x".repeat(24), "  trimmed  ", "007"])("%j is fine", (raw) => {
    expect(checkNickname(raw)).toEqual({ ok: true, value: raw.trim() });
  });

  it("an empty (or blank) field has nothing to complain about yet — the button is just off", () => {
    expect(checkNickname("")).toEqual({ ok: false, reason: null });
    expect(checkNickname("   ")).toEqual({ ok: false, reason: null });
  });

  it("too short and too long say so, by the trimmed length", () => {
    expect(checkNickname("ab")).toEqual({ ok: false, reason: "Too short — at least 3 characters." });
    expect(checkNickname("  ab  ")).toEqual({ ok: false, reason: "Too short — at least 3 characters." });
    expect(checkNickname("x".repeat(25))).toEqual({ ok: false, reason: "Too long — at most 24 characters." });
  });

  it("a character outside the allow-list is named — Cyrillic, an emoji (one character, not two) and punctuation", () => {
    expect(checkNickname("Саша")).toEqual({ ok: false, reason: "Only letters A–Z, digits, space, _ . - are allowed — not: С а ш" });
    expect(checkNickname("moon🚀")).toEqual({ ok: false, reason: "Only letters A–Z, digits, space, _ . - are allowed — not: 🚀" });
    expect(checkNickname("a@b!")).toMatchObject({ ok: false, reason: expect.stringContaining("@ !") });
    expect(checkNickname("привет-мир")).toMatchObject({ ok: false, reason: expect.stringMatching(/ …$/) });
  });

  it("the hint states the same rule", () => {
    expect(NICKNAME_HINT).toBe("3–24 characters: letters A–Z, digits, space, _ . -");
  });
});
