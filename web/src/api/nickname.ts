/**
 * The nickname rule, as the server states it (`ApiErrorCode.INVALID_NICKNAME`, `GameService.setNickname`): trimmed,
 * 3–24 characters, each one a letter A–Z, a digit, a space, `_`, `.` or `-`. The sign-in sheet checks it BEFORE asking —
 * with the reason in words — so a player never types "Саша" or "moon🚀" into a field that then does nothing; the server
 * stays the judge, and if it refuses anyway its own sentence is shown (`api/apiErrors.ts`).
 */
import type { PlayerSummaryDto } from "./contract";
import { shortenAddress } from "../format";

export const NICKNAME_MIN = 3;
export const NICKNAME_MAX = 24;

/** What the hint under the field says, always. */
export const NICKNAME_HINT = "3–24 characters: letters A–Z, digits, space, _ . -";

const ALLOWED = /^[A-Za-z0-9 _.-]$/;

/**
 * A player who has not picked a nickname yet: the server then names them by their wallet address, shortened the
 * way `shortenAddress` does ("txch1z…hge7" — `PlayerDirectory.Row.shortWallet`); a build with no server at all (the
 * mock) sends an empty one. A real nickname can never look like this — the rule above has no "…" in it — so the
 * check cannot mistake a chosen name for the placeholder. Used to ask a returning player who left the first
 * sign-in at the nickname step for it again, and to keep the placeholder (whose own "…" the header's ellipsis
 * then doubled) out of the header.
 */
export function hasPlaceholderNickname(player: Pick<PlayerSummaryDto, "nickname" | "walletAddress">): boolean {
  const nickname = player.nickname.trim();
  return nickname === "" || nickname === shortenAddress(player.walletAddress);
}

/** What the header calls the player: the nickname — or "Player" until one is picked, so the shortened address
 *  (whose own "…" the header's ellipsis doubled, "txch1z……") is not what stands on the button. Short on purpose:
 *  the button is capped at 92 px (68 px at 320), and "New player" came out as "New pla…". */
export function headerName(player: Pick<PlayerSummaryDto, "nickname" | "walletAddress">): string {
  return hasPlaceholderNickname(player) ? "Player" : player.nickname;
}

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
