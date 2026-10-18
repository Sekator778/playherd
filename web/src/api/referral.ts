/**
 * The invite link `/?ref=<code>` and the channel tag `/?src=<channel>`: captured from the landing URL, kept in the
 * browser until the player registers (the first wallet sign-in that creates the player), then handed to `authVerify`.
 * The server records them once, at registration (`InviteRepository`); a returning player's sign-in never changes them.
 *
 * First touch wins: while a stored value is younger than {@link REFERRAL_TTL_MS}, a later visit through another link does
 * not replace it (the server's own rule is the same: the first record stays). Storage may be unavailable (private window,
 * blocked site data): then nothing is kept and registration simply goes without a source.
 */
export const REFERRAL_STORAGE_KEY = "herd.referral";
export const REFERRAL_TTL_MS = 30 * 24 * 3_600_000;

export interface PendingReferral {
  referralCode?: string;
  source?: string;
}

interface Stored extends PendingReferral {
  atMs: number;
}

const CODE = /^[a-z0-9]{4,16}$/;
const SOURCE = /^[a-z0-9_-]{1,32}$/;

function clean(raw: string | null, re: RegExp): string | undefined {
  if (raw === null) return undefined;
  const s = raw.trim().toLowerCase();
  return re.test(s) ? s : undefined;
}

function read(now: number): Stored | null {
  try {
    const raw = localStorage.getItem(REFERRAL_STORAGE_KEY);
    if (!raw) return null;
    const v = JSON.parse(raw) as Stored;
    if (typeof v.atMs !== "number" || now - v.atMs > REFERRAL_TTL_MS) return null;
    return v;
  } catch {
    return null;
  }
}

/** Reads `ref` / `src` from a URL query string and keeps them, unless a fresh value is already kept. Returns what is kept. */
export function captureReferral(search: string, now: number = Date.now()): PendingReferral | null {
  const params = new URLSearchParams(search);
  const referralCode = clean(params.get("ref"), CODE);
  const source = clean(params.get("src"), SOURCE);
  const existing = read(now);
  if (existing) return pendingOf(existing);
  if (!referralCode && !source) return null;
  const stored: Stored = { atMs: now, ...(referralCode ? { referralCode } : {}), ...(source ? { source } : {}) };
  try {
    localStorage.setItem(REFERRAL_STORAGE_KEY, JSON.stringify(stored));
  } catch {
    // Nothing kept: the sign-in goes on without a source.
  }
  return pendingOf(stored);
}

function pendingOf(s: Stored): PendingReferral {
  return { ...(s.referralCode ? { referralCode: s.referralCode } : {}), ...(s.source ? { source: s.source } : {}) };
}

/** What `authVerify` should carry: the kept value, or `{}`. */
export function pendingReferral(now: number = Date.now()): PendingReferral {
  const v = read(now);
  return v ? pendingOf(v) : {};
}

/** Forget the kept value - after a sign-in went through (the server has used it or ignored it). */
export function clearReferral(): void {
  try {
    localStorage.removeItem(REFERRAL_STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** The link a player shares: this site's origin plus their code. */
export function inviteLink(code: string, origin: string = window.location.origin): string {
  return `${origin}/?ref=${encodeURIComponent(code)}`;
}
