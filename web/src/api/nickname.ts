/**
 * The nickname rule, as the server states it (`ApiErrorCode.INVALID_NICKNAME`, `GameService.setNickname`): trimmed,
 * 3–24 characters, each one a letter A–Z, a digit, a space, `_`, `.` or `-`. The sign-in sheet checks it BEFORE asking —
 * with the reason in words — so a player never types "Саша" or "moon🚀" into a field that then does nothing; the server
 * stays the judge, and if it refuses anyway its own sentence is shown (`api/apiErrors.ts`).
 */
export const NICKNAME_MIN = 3;
export const NICKNAME_MAX = 24;

/** What the hint under the field says, always. */
export const NICKNAME_HINT = "3–24 characters: letters A–Z, digits, space, _ . -";

const ALLOWED = /^[A-Za-z0-9 _.-]$/;

export type NicknameCheck =
  | { ok: true; value: string }
  /** `reason` is `null` for an empty field — nothing to complain about yet, the button just stays off. */
  | { ok: false; reason: string | null };

export function checkNickname(raw: string): NicknameCheck {
  const value = raw.trim();
  if (value === "") return { ok: false, reason: null };
  // Code points, not UTF-16 units: an emoji is one character the player can see, and one to name.
  const chars = Array.from(value);
  const refused = Array.from(new Set(chars.filter((c) => !ALLOWED.test(c))));
  if (refused.length > 0) {
    const shown = refused.slice(0, 4).join(" ");
    return { ok: false, reason: `Only letters A–Z, digits, space, _ . - are allowed — not: ${shown}${refused.length > 4 ? " …" : ""}` };
  }
  if (chars.length < NICKNAME_MIN) return { ok: false, reason: `Too short — at least ${NICKNAME_MIN} characters.` };
  if (chars.length > NICKNAME_MAX) return { ok: false, reason: `Too long — at most ${NICKNAME_MAX} characters.` };
  return { ok: true, value };
}
