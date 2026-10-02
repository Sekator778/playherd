/**
 * Paging a newest-first list whose cursor is a timestamp (`PageParams.before`: "items strictly before this timestamp"),
 * without a hole and without a repeat — the one place the wallet history and the cancellations log both read it from.
 *
 * Two facts make a naive "before = the oldest row's time" wrong:
 * - Several rows can share one millisecond (every settlement of rounds ending at one boundary is stamped alike; a
 *   market that closes cancels several rounds at once), and a cursor of exactly that millisecond would skip the rest of
 *   the instant. So the next page is asked from ONE millisecond later, and what is already held is dropped by its key.
 *   A page that turned out to be all repeats is asked again with twice the limit rather than ending the list short.
 * - Between two refreshes more rows can arrive than a page holds: a refresh that merges only the newest page leaves a
 *   hole behind it. So a refresh whose page does not touch what is held keeps paging until it does (or until the list
 *   ends) — and when the burst is longer than it is willing to walk, it starts the list over from the newest rows and
 *   lets "older" fetch the rest, rather than showing a list with a gap in it that says nothing.
 *
 * Pure of React; the fetch is passed in, so every rule here has a test.
 */
import type { Page, PageParams } from "./contract";

/** What the helpers need to know of a row. */
export interface PageOps<T> {
  /** Unique within the list (a ledger `sequence`, a cancelled round's id). */
  keyOf: (item: T) => string | number;
  /** The timestamp the cursor works on. */
  atMsOf: (item: T) => number;
  /** Newest first. */
  compare: (a: T, b: T) => number;
}

export interface PagedList<T> {
  /** Newest first. */
  items: T[];
  /** More, older rows exist beyond the last one. */
  hasMore: boolean;
}

export type FetchPage<T> = (params: PageParams) => Promise<Page<T>>;

/** How many pages a refresh walks looking for the place where the new rows join the ones it holds. */
export const MAX_REFRESH_PAGES = 6;
/** How many times a page that was all repeats is asked again, each time with twice the limit. */
const MAX_WIDENINGS = 4;

/** The parameters of the page after `known`: one millisecond past the oldest of them (see the module header). */
export function nextPageParams<T>(known: readonly T[], limit: number, ops: PageOps<T>): PageParams {
  if (known.length === 0) return { limit };
  let oldest = Number.POSITIVE_INFINITY;
  for (const item of known) oldest = Math.min(oldest, ops.atMsOf(item));
  return { limit, before: oldest + 1 };
}

/** `known` followed by whatever of `incoming` it does not hold yet (by key), newest first. */
export function mergeNewestFirst<T>(known: readonly T[], incoming: readonly T[], ops: PageOps<T>): T[] {
  const seen = new Set(known.map(ops.keyOf));
  const fresh: T[] = [];
  for (const item of incoming) {
    const key = ops.keyOf(item);
    if (seen.has(key)) continue;
    seen.add(key);
    fresh.push(item);
  }
  return [...known, ...fresh].sort(ops.compare);
}

/**
 * The rows older than `known` that are not held yet. `null`: even a page of the widest size brought nothing new
 * although the server says there is more — the caller reports it instead of looping.
 */
async function fetchOlder<T>(
  fetchPage: FetchPage<T>,
  known: readonly T[],
  limit: number,
  ops: PageOps<T>,
): Promise<{ fresh: T[]; hasMore: boolean } | null> {
  const held = new Set(known.map(ops.keyOf));
  let size = limit;
  for (let attempt = 0; attempt <= MAX_WIDENINGS; attempt++) {
    const page = await fetchPage(nextPageParams(known, size, ops));
    const fresh = page.items.filter((item) => !held.has(ops.keyOf(item)));
    if (fresh.length > 0 || !page.hasMore) return { fresh, hasMore: page.hasMore };
    size *= 2;
  }
  return null;
}

/** "Show older" / "Load more": the held list plus the next page of older rows; `null` when no progress could be made. */
export async function loadOlder<T>(fetchPage: FetchPage<T>, held: PagedList<T>, limit: number, ops: PageOps<T>): Promise<PagedList<T> | null> {
  const older = await fetchOlder(fetchPage, held.items, limit, ops);
  if (!older) return null;
  return { items: mergeNewestFirst(held.items, older.fresh, ops), hasMore: older.hasMore };
}

/**
 * A refresh: `firstPage` is the newest page the server has now, `held` what is shown. The result keeps every row held
 * (new rows go on top) and never has a gap — see the module header for the walk.
 */
export async function refreshNewest<T>(
  fetchPage: FetchPage<T>,
  held: PagedList<T> | null,
  firstPage: Page<T>,
  limit: number,
  ops: PageOps<T>,
): Promise<PagedList<T>> {
  let newest = mergeNewestFirst([], firstPage.items, ops);
  let hasMore = firstPage.hasMore;
  if (!held || held.items.length === 0) return { items: newest, hasMore };

  const heldKeys = new Set(held.items.map(ops.keyOf));
  const touchesHeld = (rows: readonly T[]) => rows.some((item) => heldKeys.has(ops.keyOf(item)));
  for (let walked = 0; hasMore && !touchesHeld(newest) && walked < MAX_REFRESH_PAGES; walked++) {
    const older = await fetchOlder(fetchPage, newest, limit, ops);
    if (!older) break;
    newest = mergeNewestFirst(newest, older.fresh, ops);
    hasMore = older.hasMore;
  }
  // The new rows meet the held ones: one list, and "older" still starts where it did.
  if (touchesHeld(newest)) return { items: mergeNewestFirst(held.items, newest, ops), hasMore: held.hasMore };
  // The whole list was read, or the burst is longer than the walk: the list starts over from the newest rows, and the
  // rest (if there is any) comes through "older" — never a list with a gap in it that says nothing.
  return { items: newest, hasMore };
}
