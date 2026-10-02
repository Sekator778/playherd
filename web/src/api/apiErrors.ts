/**
 * How a failed call is told to the player — pure functions over `ApiRequestError` (`contract.ts`), so every
 * screen words the same failure the same way and every sentence has a test.
 *
 * Two codes are not errors in the usual sense:
 * - `ENGINE_NOT_READY` (HTTP 503): the server is rebuilding its state after a failure and has changed nothing.
 *   A normal, transient state — a polled read keeps what is on screen and tries again, a button says "the server is
 *   restarting — try again in a moment" instead of a raw error (`contract.ts`, `ApiErrorCode`).
 * - `RATE_LIMITED` (HTTP 429): the server's own message is a developer's sentence; the player gets a plain one.
 */
import { ApiRequestError, type ApiErrorCode } from "./contract";

export const ENGINE_RESTARTING_TEXT = "The server is restarting — try again in a moment.";
export const RATE_LIMITED_TEXT = "Too many requests just now — wait a few seconds and try again.";

/** The code of a failure that came with one (`ApiRequestError`), else `null` (a transport failure, or not an API error). */
export function apiErrorCode(err: unknown): ApiErrorCode | null {
  return err instanceof ApiRequestError ? err.code : null;
}

/** The server is rebuilding its state (503 `ENGINE_NOT_READY`): transient, nothing was changed by the call. */
export function isEngineNotReady(err: unknown): boolean {
  return apiErrorCode(err) === "ENGINE_NOT_READY";
}

/** No such player, round or request (404 `NOT_FOUND`) — decided by the code, never by the message text. */
export function isNotFound(err: unknown): boolean {
  return apiErrorCode(err) === "NOT_FOUND";
}

/**
 * The sentence a failed button press (bet, cancel, nickname, withdrawal, sign-in) is shown as: the restart and the
 * rate limit in the app's own plain words, any other server code in the server's own text (`realApi.ts`: an unknown
 * code arrives as `OTHER` with its message), a transport failure as its own message ("Network error — check your
 * connection…"), and `fallback` for anything else.
 */
export function mutationErrorText(err: unknown, fallback: string): string {
  const code = apiErrorCode(err);
  if (code === "ENGINE_NOT_READY") return ENGINE_RESTARTING_TEXT;
  if (code === "RATE_LIMITED") return RATE_LIMITED_TEXT;
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
