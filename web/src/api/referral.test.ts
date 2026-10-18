import { beforeEach, describe, expect, it } from "vitest";
import { captureReferral, clearReferral, inviteLink, pendingReferral, REFERRAL_STORAGE_KEY, REFERRAL_TTL_MS } from "./referral";

beforeEach(() => localStorage.clear());

describe("invite link capture", () => {
  it("keeps ?ref and ?src from the landing URL until registration reads them", () => {
    captureReferral("?ref=AB12cd34&src=Discord", 1_000);
    expect(pendingReferral(2_000)).toEqual({ referralCode: "ab12cd34", source: "discord" });
    // it survives later page loads (nothing is consumed by reading)
    expect(pendingReferral(3_000)).toEqual({ referralCode: "ab12cd34", source: "discord" });
  });

  it("keeps a source alone", () => {
    captureReferral("?src=reddit", 1_000);
    expect(pendingReferral(1_001)).toEqual({ source: "reddit" });
  });

  it("first touch wins: a later visit through another link does not overwrite", () => {
    captureReferral("?ref=aaaa1111&src=discord", 1_000);
    captureReferral("?ref=bbbb2222&src=reddit", 2_000);
    expect(pendingReferral(3_000)).toEqual({ referralCode: "aaaa1111", source: "discord" });
  });

  it("a value older than the window is dropped and can be replaced", () => {
    captureReferral("?ref=aaaa1111", 1_000);
    const later = 1_000 + REFERRAL_TTL_MS + 1;
    expect(pendingReferral(later)).toEqual({});
    captureReferral("?ref=bbbb2222", later);
    expect(pendingReferral(later + 1)).toEqual({ referralCode: "bbbb2222" });
  });

  it("ignores malformed values and URLs without the parameters", () => {
    captureReferral("?ref=<script>&src=a b", 1_000);
    captureReferral("?foo=bar", 1_000);
    expect(localStorage.getItem(REFERRAL_STORAGE_KEY)).toBeNull();
    expect(pendingReferral(1_001)).toEqual({});
  });

  it("clearReferral forgets it", () => {
    captureReferral("?ref=aaaa1111", 1_000);
    clearReferral();
    expect(pendingReferral(1_001)).toEqual({});
  });

  it("builds the shared link from the origin and the code", () => {
    expect(inviteLink("ab12cd34", "https://example.test")).toBe("https://example.test/?ref=ab12cd34");
  });
});
