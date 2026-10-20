/**
 * The client side of `/api/ws` (docs/research/lmax_roadmap.md step 5): the server tells a screen THAT something changed (a topic
 * and a version, never data); the screen then reads the RPC it already reads, so the RPC answers stay the one source of the data
 * shape. Polling stays: it is the fallback when the socket is down or unsupported, and a slow safety net while it is healthy.
 *
 * Topics (server `PushTopics`): `markets` (the lobby), `market:<id>` (a market's price, round windows and crowd), `round:<roundId>`,
 * `feed:<roundId>`, and `me` (the signed-in player's own positions and balance; needs the session cookie, so a change of player
 * reconnects the socket - `setIdentity`).
 *
 * Wire: client `{"op":"sub"|"unsub","topics":[...]}`; server `{"t":"hello"}`, `{"t":"subscribed","topics":[...]}` (answered with a
 * refresh of those topics, so a change between a screen's first read and the subscription is not lost), `{"t":"n","topic":"..."}`,
 * `{"t":"resync"}` (notices were dropped for this client: refresh everything).
 *
 * The socket is closed while the tab is hidden (a backgrounded tab neither polls nor listens) and reopened when it is visible again.
 */
import { API_MODE } from "./apiMode";

/** While the socket is healthy a screen polls this often, as a safety net (it polls every 2 s otherwise). */
export const PUSH_FALLBACK_POLL_MS = 15_000;
/** A screen reads again after a notice at most this often (a burst of notices is one read, at its end). */
export const PUSH_REFRESH_MIN_GAP_MS = 500;

export interface PushClient {
  /** Calls `onNotice` whenever one of `topics` changed (and on every (re)subscription, to catch up); returns the unsubscribe. */
  subscribe(topics: readonly string[], onNotice: () => void): () => void;
  /** The socket is open and the server has greeted it. */
  isHealthy(): boolean;
  /** Calls `listener` when `isHealthy()` changes; returns the unsubscribe. */
  onHealthChange(listener: () => void): () => void;
  /** Who the session cookie belongs to (null: signed out); a change reopens the socket so the server sees the new session. */
  setIdentity(playerId: string | null): void;
}

export interface PushClientOptions {
  url: string;
  /** For tests. */
  WebSocketImpl?: typeof WebSocket;
  /** Reconnect delays: first, ceiling (ms). */
  backoffMs?: [number, number];
}

const OPEN = 1;

/** Reads again at most every `gapMs`: the first call goes at once, the ones that follow inside the gap become one call at its end. */
export function throttled(fn: () => void, gapMs: number = PUSH_REFRESH_MIN_GAP_MS): { call: () => void; cancel: () => void } {
  let last = Number.NEGATIVE_INFINITY;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const run = () => {
    timer = undefined;
    last = Date.now();
    fn();
  };
  return {
    call: () => {
      if (timer !== undefined) return;
      const wait = last + gapMs - Date.now();
      if (wait <= 0) run();
      else timer = setTimeout(run, wait);
    },
    cancel: () => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    },
  };
}

export function createPushClient(options: PushClientOptions): PushClient {
  const Impl = options.WebSocketImpl ?? (typeof WebSocket === "undefined" ? undefined : WebSocket);
  const [firstBackoff, maxBackoff] = options.backoffMs ?? [1000, 30_000];
  /** topic -> its listeners. */
  const listeners = new Map<string, Set<() => void>>();
  const healthListeners = new Set<() => void>();
  let socket: WebSocket | null = null;
  let healthy = false;
  let identity: string | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let backoff = firstBackoff;
  /** Connection attempts in a row that never got a greeting; many of them mean the server has no socket for us (a proxy without it). */
  let failures = 0;

  const setHealthy = (next: boolean) => {
    if (healthy === next) return;
    healthy = next;
    healthListeners.forEach((l) => l());
  };

  const visible = () => typeof document === "undefined" || document.visibilityState === "visible";
  const wanted = () => Impl !== undefined && listeners.size > 0 && visible();

  const send = (message: object) => {
    if (socket && socket.readyState === OPEN) socket.send(JSON.stringify(message));
  };

  const fire = (topic: string) => {
    listeners.get(topic)?.forEach((l) => l());
  };
  const fireAll = () => {
    const seen = new Set<() => void>();
    listeners.forEach((set) => set.forEach((l) => seen.add(l)));
    seen.forEach((l) => l());
  };

  const clearReconnect = () => {
    if (reconnectTimer !== undefined) clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  };

  const disconnect = () => {
    clearReconnect();
    const s = socket;
    socket = null;
    setHealthy(false);
    if (s) {
      s.onopen = s.onmessage = s.onclose = s.onerror = null;
      try {
        s.close();
      } catch {
        // already closed
      }
    }
  };

  const scheduleReconnect = () => {
    clearReconnect();
    if (!wanted()) return;
    failures += 1;
    // After many greetless attempts the socket is evidently not offered here: look again rarely, polling carries on.
    const delay = failures > 5 ? 5 * 60_000 : Math.min(maxBackoff, backoff) * (0.75 + Math.random() * 0.5);
    backoff = Math.min(maxBackoff, backoff * 2);
    reconnectTimer = setTimeout(connect, delay);
  };

  function connect() {
    clearReconnect();
    if (socket || !wanted() || !Impl) return;
    let s: WebSocket;
    try {
      s = new Impl(options.url);
    } catch {
      scheduleReconnect();
      return;
    }
    socket = s;
    s.onmessage = (event: MessageEvent) => {
      if (socket !== s || typeof event.data !== "string") return;
      let message: { t?: string; topic?: string; topics?: string[] };
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      switch (message.t) {
        case "hello":
          failures = 0;
          backoff = firstBackoff;
          setHealthy(true);
          send({ op: "sub", topics: [...listeners.keys()] });
          break;
        case "subscribed":
          // Whatever changed between a screen's first read and this subscription: read again.
          for (const topic of message.topics ?? []) fire(topic);
          break;
        case "n":
          if (message.topic) fire(message.topic);
          break;
        case "resync":
          fireAll();
          break;
        default:
          break;
      }
    };
    s.onclose = () => {
      if (socket !== s) return;
      socket = null;
      setHealthy(false);
      scheduleReconnect();
    };
    s.onerror = () => {
      // `onclose` follows; nothing to do here
    };
  }

  const onVisibility = () => {
    if (visible()) connect();
    else disconnect();
  };
  // Listened to only while somebody is subscribed.
  const watchVisibility = (on: boolean) => {
    if (typeof document === "undefined") return;
    if (on) document.addEventListener?.("visibilitychange", onVisibility);
    else document.removeEventListener?.("visibilitychange", onVisibility);
  };

  return {
    subscribe(topics, onNotice) {
      if (listeners.size === 0) watchVisibility(true);
      const fresh: string[] = [];
      for (const topic of topics) {
        let set = listeners.get(topic);
        if (!set) {
          set = new Set();
          listeners.set(topic, set);
          fresh.push(topic);
        }
        set.add(onNotice);
      }
      if (socket && healthy) {
        if (fresh.length > 0) send({ op: "sub", topics: fresh });
      } else {
        connect(); // a socket still connecting sends every held topic when the server greets it
      }
      return () => {
        const gone: string[] = [];
        for (const topic of topics) {
          const set = listeners.get(topic);
          if (!set) continue;
          set.delete(onNotice);
          if (set.size === 0) {
            listeners.delete(topic);
            gone.push(topic);
          }
        }
        if (listeners.size === 0) {
          watchVisibility(false);
          disconnect();
        }
        else if (gone.length > 0) send({ op: "unsub", topics: gone });
      };
    },
    isHealthy: () => healthy,
    onHealthChange(listener) {
      healthListeners.add(listener);
      return () => {
        healthListeners.delete(listener);
      };
    },
    setIdentity(playerId) {
      if (playerId === identity) return;
      identity = playerId;
      if (socket) {
        disconnect();
        failures = 0;
        backoff = firstBackoff;
        connect();
      }
    },
  };
}

let singleton: PushClient | null | undefined;
let override: PushClient | null | undefined;

function defaultUrl(): string {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/api/ws`;
}

/**
 * The app's push client: made on first use, and only against the real server (`VITE_API_MODE=real`, same origin as the RPC: the
 * proxy passes `/api/ws`) and in a browser that has WebSocket. `null` otherwise - the mock app polls as it always did.
 */
export function getPush(): PushClient | null {
  if (override !== undefined) return override;
  if (singleton === undefined) {
    singleton =
      API_MODE === "real" && typeof window !== "undefined" && typeof WebSocket !== "undefined"
        ? createPushClient({ url: defaultUrl() })
        : null;
  }
  return singleton;
}

/** For tests: use `client` (or none, with `null`) as the app's push client; `undefined` goes back to the default. */
export function setPushForTests(client: PushClient | null | undefined): void {
  override = client;
}
