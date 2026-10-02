import { describe, expect, it, vi } from "vitest";
import type { Page, PageParams } from "./contract";
import { loadOlder, mergeNewestFirst, MAX_REFRESH_PAGES, nextPageParams, refreshNewest, type PageOps } from "./paging";

interface Row {
  id: string;
  at: number;
}
const row = (id: string, at: number): Row => ({ id, at });
const ops: PageOps<Row> = { keyOf: (r) => r.id, atMsOf: (r) => r.at, compare: (a, b) => b.at - a.at };

/** A server list that pages like the contract says: newest first, `before` strictly before, `limit` rows. */
function serverOf(all: Row[]) {
  return vi.fn(async (params: PageParams): Promise<Page<Row>> => {
    const limit = params.limit ?? 20;
    const rest = params.before !== undefined ? all.filter((r) => r.at < params.before!) : all;
    return { items: rest.slice(0, limit), hasMore: rest.length > limit };
  });
}

const ids = (rows: Row[]) => rows.map((r) => r.id);

describe("nextPageParams", () => {
  it("asks from ONE millisecond after the oldest held row, so rows sharing its instant are not skipped", () => {
    expect(nextPageParams([row("a", 300), row("b", 200), row("c", 200)], 10, ops)).toEqual({ limit: 10, before: 201 });
  });
  it("asks for the newest page when nothing is held", () => {
    expect(nextPageParams([], 10, ops)).toEqual({ limit: 10 });
  });
});

describe("mergeNewestFirst", () => {
  it("drops what is held (by key), keeps newest first", () => {
    const held = [row("a", 300), row("b", 200)];
    expect(ids(mergeNewestFirst(held, [row("b", 200), row("c", 200), row("d", 100)], ops))).toEqual(["a", "b", "c", "d"]);
    expect(mergeNewestFirst(held, held, ops)).toEqual(held);
  });
  it("drops a key repeated inside the incoming rows too", () => {
    expect(ids(mergeNewestFirst([], [row("a", 2), row("a", 2), row("b", 1)], ops))).toEqual(["a", "b"]);
  });
});

describe("loadOlder — rows that share a millisecond are never skipped", () => {
  it("reads past a page boundary that falls inside one instant (8 rows, 3 of them at the same time, page of 3)", async () => {
    const all = [row("1", 9), row("2", 8), row("3", 7), row("4", 7), row("5", 7), row("6", 5), row("7", 4), row("8", 3)];
    const server = serverOf(all);
    const first = await server({ limit: 3 });
    expect(ids(first.items)).toEqual(["1", "2", "3"]);
    // `before = 7` would skip 4 and 5; `before = 8` returns them again together with 3
    let held = { items: first.items, hasMore: first.hasMore };
    for (let i = 0; i < 6 && held.hasMore; i++) held = (await loadOlder(server, held, 3, ops))!;
    expect(ids(held.items).sort()).toEqual(ids(all).sort());
    expect(held.hasMore).toBe(false);
  });

  it("a page that is all repeats is asked again, wider, instead of ending the list short", async () => {
    // five rows at one instant, page of 2: the first ask after them returns nothing but repeats
    const all = [row("a", 100), row("b", 50), row("c", 50), row("d", 50), row("e", 50), row("f", 50), row("g", 10)];
    const server = serverOf(all);
    let held = { items: [row("a", 100), row("b", 50), row("c", 50)], hasMore: true };
    held = (await loadOlder(server, held, 2, ops))!;
    expect(ids(held.items)).toEqual(["a", "b", "c", "d", "e"]); // 2 rows were repeats; 4 reached two new ones
    expect(held.hasMore).toBe(true);
    expect(server.mock.calls.map(([p]) => p.limit)).toEqual([2, 4]);
  });

  it("is null when even the widest page brings nothing new though the server says there is more", async () => {
    const stuck = vi.fn(async (): Promise<Page<Row>> => ({ items: [row("a", 100)], hasMore: true }));
    expect(await loadOlder(stuck, { items: [row("a", 100)], hasMore: true }, 2, ops)).toBeNull();
    expect(stuck).toHaveBeenCalledTimes(5);
  });
});

describe("refreshNewest — a burst between two refreshes leaves no hole", () => {
  const log = (n: number, stepMs = 100) => Array.from({ length: n }, (_, i) => row(`r${n - i}`, 10_000 - i * stepMs));

  it("the common case: a few new rows on top of a list that was already loaded; nothing lost, 'older' where it was", async () => {
    const before = log(25).slice(3); // 22 rows held: r22…r1, after a Load more
    const held = { items: before, hasMore: false };
    const all = log(25);
    const server = serverOf(all);
    const first = await server({ limit: 10 });
    const next = await refreshNewest(server, held, first, 10, ops);
    expect(ids(next.items)).toEqual(ids(all));
    expect(next.hasMore).toBe(false);
    expect(server).toHaveBeenCalledTimes(1); // the first page touched the held list: no walk
  });

  it("a burst bigger than a page: keeps paging until it meets the held list (25 new rows, page of 10)", async () => {
    const all = log(45);
    const heldRows = all.slice(25, 35); // the newest 10 of before the burst
    const server = serverOf(all);
    const first = await server({ limit: 10 });
    const next = await refreshNewest(server, { items: heldRows, hasMore: true }, first, 10, ops);
    expect(ids(next.items)).toEqual(ids(all.slice(0, 35))); // 25 new + the 10 held, contiguous
    expect(next.hasMore).toBe(true); // older than the held list still exists, 'older' starts where it did
    expect(new Set(ids(next.items)).size).toBe(35);
  });

  it("a burst longer than the walk: the list starts over from the newest rows, 'older' fetches the rest — no hidden gap", async () => {
    const all = log(10 * (MAX_REFRESH_PAGES + 4));
    const heldRows = all.slice(-10);
    const server = serverOf(all);
    const first = await server({ limit: 10 });
    const next = await refreshNewest(server, { items: heldRows, hasMore: false }, first, 10, ops);
    // it never claims the held (old) rows follow directly after the walked ones
    expect(ids(next.items)).toEqual(ids(all.slice(0, next.items.length)));
    expect(next.hasMore).toBe(true);
    expect(server.mock.calls.length).toBe(1 + MAX_REFRESH_PAGES);
  });

  it("nothing held yet: just the page", async () => {
    const all = log(12);
    const server = serverOf(all);
    const first = await server({ limit: 10 });
    expect(await refreshNewest(server, null, first, 10, ops)).toEqual({ items: all.slice(0, 10), hasMore: true });
    expect(await refreshNewest(server, { items: [], hasMore: false }, first, 10, ops)).toEqual({ items: all.slice(0, 10), hasMore: true });
  });

  it("a burst that fits in the pages read, with rows sharing one instant at the page boundary", async () => {
    const all = [...Array.from({ length: 8 }, (_, i) => row(`n${i}`, 9_000 - i)), row("t1", 5_000), row("t2", 5_000), row("t3", 5_000), row("old1", 100), row("old2", 90)];
    const server = serverOf(all);
    const first = await server({ limit: 10 }); // n0…n7, t1, t2 — t3 is cut off at the page boundary
    const next = await refreshNewest(server, { items: [row("old1", 100), row("old2", 90)], hasMore: false }, first, 10, ops);
    expect(ids(next.items)).toEqual(expect.arrayContaining(["t1", "t2", "t3"]));
    expect(next.items).toHaveLength(all.length);
    expect(next.hasMore).toBe(false);
  });
});
