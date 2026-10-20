import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPushClient, throttled } from "./push";

/** A WebSocket the test drives by hand. */
class FakeSocket {
  static instances: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  constructor(public url: string) {
    FakeSocket.instances.push(this);
  }
  send(text: string) {
    this.sent.push(text);
  }
  close() {
    this.closed = true;
    this.readyState = 3;
  }
  // test side
  serverOpens() {
    this.readyState = 1;
    this.onopen?.();
  }
  serverSays(message: object) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
  serverCloses() {
    this.readyState = 3;
    this.onclose?.();
  }
  ops(): { op: string; topics: string[] }[] {
    return this.sent.map((s) => JSON.parse(s));
  }
}

const last = () => FakeSocket.instances[FakeSocket.instances.length - 1];

function client() {
  const push = createPushClient({
    url: "ws://test/api/ws",
    WebSocketImpl: FakeSocket as unknown as typeof WebSocket,
    backoffMs: [1000, 8000],
  });
  const subscribe = push.subscribe.bind(push);
  push.subscribe = (topics, onNotice) => {
    const unsubscribe = subscribe(topics, onNotice);
    cleanups.push(unsubscribe);
    return unsubscribe;
  };
  return push;
}

const cleanups: (() => void)[] = [];

/** A connected, greeted socket subscribed to `topics`. */
function connected(topics: string[], onNotice = vi.fn()) {
  const push = client();
  const unsubscribe = push.subscribe(topics, onNotice);
  const socket = last();
  socket.serverOpens();
  socket.serverSays({ t: "hello", v: 1, auth: false });
  return { push, socket, onNotice, unsubscribe };
}

function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  document.dispatchEvent(new Event("visibilitychange"));
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.instances = [];
});
afterEach(() => {
  cleanups.splice(0).forEach((c) => c());
  setVisibility("visible");
  vi.useRealTimers();
});

describe("the push client", () => {
  it("opens the socket on the first subscription, subscribes after the greeting and is healthy from then on", () => {
    const push = client();
    const changes = vi.fn();
    push.onHealthChange(changes);
    push.subscribe(["markets", "market:BTC"], vi.fn());

    expect(FakeSocket.instances).toHaveLength(1);
    expect(last().url).toBe("ws://test/api/ws");
    expect(push.isHealthy()).toBe(false);
    last().serverOpens();
    expect(push.isHealthy()).toBe(false); // open is not enough: the server has to greet it

    last().serverSays({ t: "hello", v: 7 });

    expect(push.isHealthy()).toBe(true);
    expect(changes).toHaveBeenCalledTimes(1);
    expect(last().ops()).toEqual([{ op: "sub", topics: ["markets", "market:BTC"] }]);
  });

  it("calls the listener of a topic on its notice and not on another topic's", () => {
    const { socket, onNotice } = connected(["market:BTC"]);

    socket.serverSays({ t: "n", topic: "market:ETH", v: 2 });
    expect(onNotice).not.toHaveBeenCalled();

    socket.serverSays({ t: "n", topic: "market:BTC", v: 3 });
    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it("reads again for the topics the server confirms (a change between the first read and the subscription is not lost)", () => {
    const { socket, onNotice } = connected(["round:1h-5"]);

    socket.serverSays({ t: "subscribed", topics: ["round:1h-5"], rejected: {} });

    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it("reads everything again on a resync", () => {
    const a = vi.fn();
    const b = vi.fn();
    const { push, socket } = connected(["markets"], a);
    push.subscribe(["me"], b);

    socket.serverSays({ t: "resync" });

    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(1);
  });

  it("sends new topics on an open socket, unsubscribes the ones nobody holds any more, and closes with the last", () => {
    const { push, socket, unsubscribe } = connected(["markets"]);
    const unsubscribeMe = push.subscribe(["me"], vi.fn());
    expect(socket.ops().at(-1)).toEqual({ op: "sub", topics: ["me"] });

    unsubscribeMe();
    expect(socket.ops().at(-1)).toEqual({ op: "unsub", topics: ["me"] });
    expect(socket.closed).toBe(false);

    unsubscribe();
    expect(socket.closed).toBe(true);
    expect(push.isHealthy()).toBe(false);
  });

  it("two listeners of one topic share it: the topic is dropped only with the second one", () => {
    const { push, socket, unsubscribe } = connected(["markets"]);
    const second = push.subscribe(["markets"], vi.fn());

    unsubscribe();
    expect(socket.ops().some((o) => o.op === "unsub")).toBe(false);
    second();
    expect(socket.closed).toBe(true);
  });

  it("is unhealthy when the socket closes, reconnects with a growing delay and subscribes to everything again", () => {
    const { push, socket, onNotice } = connected(["markets", "me"]);
    const changes = vi.fn();
    push.onHealthChange(changes);

    socket.serverCloses();
    expect(push.isHealthy()).toBe(false);
    expect(changes).toHaveBeenCalledTimes(1);
    expect(FakeSocket.instances).toHaveLength(1);

    vi.advanceTimersByTime(1500); // the first delay is about a second
    expect(FakeSocket.instances).toHaveLength(2);
    last().serverCloses(); // never greeted: the next delay is longer
    vi.advanceTimersByTime(1000);
    expect(FakeSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(2500);
    expect(FakeSocket.instances).toHaveLength(3);

    last().serverOpens();
    last().serverSays({ t: "hello", v: 9 });
    expect(push.isHealthy()).toBe(true);
    expect(last().ops()).toEqual([{ op: "sub", topics: ["markets", "me"] }]);
    last().serverSays({ t: "n", topic: "me", v: 10 });
    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it("does not reconnect when nobody listens any more", () => {
    const { socket, unsubscribe } = connected(["markets"]);
    unsubscribe();
    socket.serverCloses();

    vi.advanceTimersByTime(60_000);

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it("closes the socket while the tab is hidden and opens it again when it is visible", () => {
    const { push, socket } = connected(["markets"]);

    setVisibility("hidden");
    expect(socket.closed).toBe(true);
    expect(push.isHealthy()).toBe(false);
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.instances).toHaveLength(1);

    setVisibility("visible");
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it("reopens the socket when the player changes, so the server sees the new session", () => {
    const { push, socket } = connected(["me"]);

    push.setIdentity("alice");

    expect(socket.closed).toBe(true);
    expect(FakeSocket.instances).toHaveLength(2);
    push.setIdentity("alice");
    expect(FakeSocket.instances).toHaveLength(2); // the same player: nothing to do
  });

  it("without WebSocket support it never connects and is never healthy", () => {
    const push = createPushClient({ url: "ws://test/api/ws", WebSocketImpl: undefined });
    // `undefined` falls back to the global; take it away for the test
    const original = globalThis.WebSocket;
    // @ts-expect-error removing the global on purpose
    delete globalThis.WebSocket;
    try {
      const bare = createPushClient({ url: "ws://test/api/ws" });
      const unsubscribe = bare.subscribe(["markets"], vi.fn());
      expect(bare.isHealthy()).toBe(false);
      unsubscribe();
    } finally {
      globalThis.WebSocket = original;
    }
    expect(push.isHealthy()).toBe(false);
  });

  it("pings every 25 s while healthy, and stops when the socket closes", () => {
    const { socket } = connected(["markets"]);
    socket.sent.length = 0;

    vi.advanceTimersByTime(25_000);
    expect(socket.ops()).toEqual([{ op: "ping" }]);
    vi.advanceTimersByTime(50_000);
    expect(socket.sent).toHaveLength(3);

    socket.serverCloses();
    vi.advanceTimersByTime(60_000);
    expect(socket.sent).toHaveLength(3);
  });

  it("ignores messages that are not JSON", () => {
    const { socket, onNotice } = connected(["markets"]);

    socket.onmessage?.({ data: "not json" });
    socket.onmessage?.({ data: new ArrayBuffer(2) });

    expect(onNotice).not.toHaveBeenCalled();
  });
});

describe("throttled", () => {
  it("runs the first call at once and folds a burst into one call at the end of the gap", () => {
    const fn = vi.fn();
    const t = throttled(fn, 500);

    t.call();
    expect(fn).toHaveBeenCalledTimes(1);
    for (let i = 0; i < 20; i++) t.call();
    expect(fn).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(500);
    expect(fn).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(5000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("cancel drops the pending call", () => {
    const fn = vi.fn();
    const t = throttled(fn, 500);
    t.call();
    t.call();
    t.cancel();

    vi.advanceTimersByTime(1000);

    expect(fn).toHaveBeenCalledTimes(1);
  });
});
