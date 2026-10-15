/**
 * How a failed call is told to the player — pure functions over `ApiRequestError` (`contract.ts`), so every
 * screen words the same failure the same way and every sentence has a test.
 *
 * Two codes are not errors in the usual sense:
 * - `ENGINE_NOT_READY` (HTTP 503): the server is rebuilding its state after a failure and has changed nothing.
 *   A normal, transient state — a polled read keeps what is on screen and tries again, a button says "the server is
 *   restarting — try again in a moment" instead of a raw error (`contract.ts`, `ApiErrorCode`).
 * - `RATE_LIMITED` (HTTP 429): the server's own message is a developer's sentence; the player gets a plain one.
 * - `REGISTRATION_LIMIT` (429) and `EXPOSURE_LIMIT_EXCEEDED` (422), economy stage B: the same, each with its own plain sentence.
 */
import { ApiRequestError, type ApiErrorCode } from "./contract";
import { tEn, type TFn } from "../i18n";

export const ENGINE_RESTARTING_TEXT = tEn("errors.engineRestarting");
export const RATE_LIMITED_TEXT = tEn("errors.rateLimited");

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
export function mutationErrorText(err: unknown, fallback: string, t: TFn = tEn): string {
  const code = apiErrorCode(err);
  if (code === "ENGINE_NOT_READY") return t("errors.engineRestarting");
  if (code === "RATE_LIMITED") return t("errors.rateLimited");
  // Economy stage B codes: the server's sentence is a developer's (amounts, addresses); the player gets a plain one.
  if (code === "REGISTRATION_LIMIT") return t("errors.registrationLimit");
  if (code === "EXPOSURE_LIMIT_EXCEEDED") return t("errors.exposureLimit");
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
