import { describe, expect, it } from "vitest";
import { apiErrorCode, ENGINE_RESTARTING_TEXT, isEngineNotReady, isNotFound, mutationErrorText, RATE_LIMITED_TEXT } from "./apiErrors";
import { ApiRequestError } from "./contract";

describe("apiErrors — a failed call, told the same way on every screen", () => {
  it("knows the code of an API error, and of nothing else", () => {
    expect(apiErrorCode(new ApiRequestError("NOT_FOUND", "x"))).toBe("NOT_FOUND");
    expect(apiErrorCode(new Error("NOT_FOUND"))).toBeNull();
    expect(apiErrorCode("ENGINE_NOT_READY")).toBeNull();
    expect(apiErrorCode(undefined)).toBeNull();
  });

  it("ENGINE_NOT_READY and NOT_FOUND are decided by the CODE, never by the words", () => {
    expect(isEngineNotReady(new ApiRequestError("ENGINE_NOT_READY", "anything"))).toBe(true);
    expect(isEngineNotReady(new Error("the game engine is recovering"))).toBe(false);
    expect(isNotFound(new ApiRequestError("NOT_FOUND", "unknown player p1"))).toBe(true);
    expect(isNotFound(new Error("Player not found."))).toBe(false);
    expect(isNotFound(new ApiRequestError("MARKET_NOT_FOUND", "unknown market X"))).toBe(false);
  });

  describe("mutationErrorText — what a failed button press says", () => {
    it("a restarting server: 'the server is restarting — try again in a moment', not the server's developer wording", () => {
      expect(ENGINE_RESTARTING_TEXT).toBe("The server is restarting — try again in a moment.");
      expect(mutationErrorText(new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering — nothing was changed"), "fallback")).toBe(ENGINE_RESTARTING_TEXT);
    });

    it("a rate limit has its own plain message", () => {
      expect(mutationErrorText(new ApiRequestError("RATE_LIMITED", "too many requests — please slow down"), "fallback")).toBe(RATE_LIMITED_TEXT);
    });

    it("any other server code keeps the server's own sentence (it is written for the player)", () => {
      expect(mutationErrorText(new ApiRequestError("INVALID_NICKNAME", "nickname must be 3-24 characters"), "fallback")).toBe("nickname must be 3-24 characters");
      expect(mutationErrorText(new ApiRequestError("OTHER", "stake must be a decimal string"), "fallback")).toBe("stake must be a decimal string");
    });

    it("a transport failure says what it says; only a failure with nothing to say gets the fallback", () => {
      expect(mutationErrorText(new Error("Network error — check your connection and try again."), "fallback")).toBe("Network error — check your connection and try again.");
      expect(mutationErrorText(new Error(""), "fallback")).toBe("fallback");
      expect(mutationErrorText("boom", "fallback")).toBe("fallback");
      expect(mutationErrorText(undefined, "fallback")).toBe("fallback");
    });
  });
});
