import { ApiRequestError, type Api, type BetDto, type PlaceBetRequest } from "./contract";

/**
 * One press of the "place bet" button is one `clientRequestId` (the server's idempotency key, `PlaceBetRequest.clientRequestId`): when the
 * answer is lost on the way back (a network error, a proxy failure) the player presses again, the SAME id goes out again and the server
 * answers with the first bet's answer instead of placing a second bet. Once the server has answered at all - the bet, or a refusal - the
 * id is spent: the next press is a new bet and gets a new id.
 *
 * What counts as "the same press": the same bet (round, side, leverage, stake, mode) asked again, within `REUSE_MS` of the first try (the
 * server remembers an id for ten minutes). A changed stake or side is another bet and another id.
 */

/** Shorter than the server's ten minutes, so a reused id is never one the server has already forgotten. */
const REUSE_MS = 9 * 60_000;

interface Pending {
  readonly id: string;
  readonly at: number;
}

const pending = new Map<string, Pending>();

/** A random UUID (v4): `crypto.randomUUID` where it exists, `getRandomValues` otherwise (an older WebView), `Math.random` as a last resort. */
export function newClientRequestId(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === "function") c.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fingerprint(req: PlaceBetRequest): string {
  return JSON.stringify([req.roundId, req.direction, req.leverage, req.stake, req.mode ?? null]);
}

/**
 * `api.placeBet(req)` with a `clientRequestId`: a new one for a new press, the first try's for a retry after a failure that carries no
 * answer from the server. An id the caller put in `req` itself is left as it is.
 */
export async function placeBetWithRetryId(api: Pick<Api, "placeBet">, req: PlaceBetRequest, now: () => number = Date.now): Promise<BetDto> {
  if (req.clientRequestId !== undefined) return api.placeBet(req);
  const key = fingerprint(req);
  const earlier = pending.get(key);
  const reuse = earlier !== undefined && now() - earlier.at < REUSE_MS;
  const attempt: Pending = reuse ? earlier : { id: newClientRequestId(), at: now() };
  pending.set(key, attempt);
  try {
    const bet = await api.placeBet({ ...req, clientRequestId: attempt.id });
    pending.delete(key);
    return bet;
  } catch (err) {
    // The server answered (a refusal, "engine not ready": nothing was placed): the id is spent. Anything else - a transport failure,
    // a timeout - may have placed the bet without the page hearing of it: the retry must carry the same id.
    if (err instanceof ApiRequestError) pending.delete(key);
    throw err;
  }
}

/** Forgets every remembered id (tests; a sign-out). */
export function forgetBetAttempts(): void {
  pending.clear();
}
