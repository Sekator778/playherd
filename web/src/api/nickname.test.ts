import { describe, expect, it } from "vitest";
import { checkNickname, hasPlaceholderNickname, headerName, NICKNAME_HINT } from "./nickname";

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

describe("hasPlaceholderNickname — a player who has not picked a nickname is named by a shortened address", () => {
  const address = "txch1z6x9q3w8e7r5t2y4u1i0o9p8a7s6d5f4g3h2j1k0l9m8n7b6v5c4x3zhge7";

  it("the server's stand-in (first 6 … last 4) and an empty one are placeholders", () => {
    expect(hasPlaceholderNickname({ nickname: "txch1z…hge7", walletAddress: address })).toBe(true);
    expect(hasPlaceholderNickname({ nickname: "", walletAddress: address })).toBe(true);
    expect(hasPlaceholderNickname({ nickname: "  ", walletAddress: address })).toBe(true);
  });

  it("a chosen nickname is not — and no allowed nickname can look like the stand-in", () => {
    expect(hasPlaceholderNickname({ nickname: "Tester_One", walletAddress: address })).toBe(false);
    expect(checkNickname("txch1z…hge7").ok).toBe(false); // the "…" is not in the allow-list
  });

  it("the header says 'Player' until a nickname is picked — never the address with its own ellipsis", () => {
    expect(headerName({ nickname: "txch1z…hge7", walletAddress: address })).toBe("Player");
    expect(headerName({ nickname: "", walletAddress: address })).toBe("Player");
    expect(headerName({ nickname: "Tester_One", walletAddress: address })).toBe("Tester_One");
  });
});
