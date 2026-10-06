// A stand in a few hundred lines, for the offline tests of the end-to-end runs (`tools/e2e/*.test.mjs`): the RPC
// methods and the public documents the runs use, answering in the shapes the real server sends (server/.../dto/
// Markets.java, Rounds.java; util/ResultsHasher.java; api/OracleRecordsResource.java; api/MarketRegistryResource.java),
// with rounds that last a second or less. No network beyond localhost, no wallet (any signature is accepted), no
// price source: every round of every market opens and closes at prices of its own, a function of the market and the time.
//
// What it is not: the engine. A bet pays twice the stake when its side wins, nothing when it loses (liquidated) and the
// stake back when the price did not move; leverage only has to stay within the market's ceiling. What the tests are
// about — what a run does with a stand's answers — does not depend on more.
//
// `faults` (each a Set of market ids, or a boolean for everything) make the stand misbehave in the ways a run must catch
// or survive: `corruptResultsHash` (the round names another hash than its document has), `noJournalRow` (the oracle
// journal lacks the round's record), `wrongAsset` (the record carries another market's index), `cancelRounds` (every round
// of the market is cancelled with a refund — the market left its band), `flatRounds` (the price does not move), `noOpenPrice`
// (a running round has no opening price yet), `closeOnRounds`/`closeOnBet` (the market closes the moment its rounds, or a
// bet on it, are asked for), `hashDelayMs`/`oracleQueueDelayMs` (how long until the hash is on the round, until the record
// is handed to the chain service).
import { createHash } from "node:crypto";
import http from "node:http";

const MOJOS = 1000;

/** A wallet for `Player` that signs nothing: the fake stand accepts any signature. Same shape as testWallet.mjs. */
export function fakeWallet(label) {
  return {
    label,
    address: `txch1${label.replace(/[^a-zA-Z0-9]/g, "")}`,
    publicKey: `0x${"00".repeat(48)}`,
    sign: () => `0x${"00".repeat(96)}`,
  };
}

/** `<marketId>-1h-<startMs>`, and `1h-<startMs>` for the market `BTC` (web/src/api/clock.ts `buildRoundId`). */
export function roundIdOf(marketId, startMs) {
  return marketId === "BTC" ? `1h-${startMs}` : `${marketId}-1h-${startMs}`;
}

function parseRoundId(id) {
  const m = /^(?:([A-Za-z0-9_]+)-)?1h-(\d+)$/.exec(id);
  return m ? { marketId: m[1] ?? "BTC", startMs: Number(m[2]) } : null;
}

const play = (mojos) => (mojos / MOJOS).toFixed(3);
const hashOf = (text) => `0x${createHash("sha256").update(text).digest("hex")}`;

/** JSON with every object's keys sorted and no whitespace: the canonical form of results.json. */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** A market as the tests write it down; the rest is filled in. */
function completeMarket(spec, index) {
  const kind = spec.kind ?? "PERP";
  return {
    kind,
    category: kind === "ODDS" ? "politics" : "crypto",
    symbol: spec.id,
    title: spec.id,
    maxLeverage: kind === "ODDS" ? 10 : 40,
    priceDecimals: kind === "ODDS" ? 6 : 2,
    status: "ACTIVE",
    source: kind === "ODDS" ? "polymarket" : "coinbase",
    backupSource: null,
    oracleIndex: index + 1,
    endsAtMs: null,
    closedAtMs: null,
    closeReason: null,
    basePrice: kind === "ODDS" ? 0.52 : 100,
    price: { stale: false },
    ...spec,
  };
}

/**
 * @param {object} config
 * @param {number} [config.roundMs]   length of a round, and of the betting window before it
 * @param {object[]} config.markets   `{ id, kind?, category?, source?, maxLeverage?, status?, price?: null | {stale}, endsAtMs?, … }`
 * @param {object} [config.faults]
 * @returns {Promise<{ url: string, close: () => Promise<void>, calls: object[], state: object }>}
 */
export async function startFakeStand({ roundMs = 800, markets, faults = {} } = {}) {
  const fault = (name, marketId) => {
    const f = faults[name];
    return f === true || (f instanceof Set && f.has(marketId));
  };
  const queueDelayMs = faults.oracleQueueDelayMs ?? 250;
  const hashDelayMs = faults.hashDelayMs ?? 150;

  const marketList = markets.map(completeMarket);
  const byId = new Map(marketList.map((m) => [m.id, m]));
  const players = new Map(); // playerId -> { playerId, address, nickname, balance }
  const sessions = new Map(); // cookie value -> playerId
  const challenges = new Map(); // nonce -> address
  const bets = new Map(); // roundId -> Map(playerId -> bet)
  const finished = new Map(); // roundId -> what became of it
  const journal = [];
  const calls = []; // { method, atMs, args } of every RPC
  let nextSeq = 1000;

  // --- prices: a deterministic path per market ---
  const rawPrice = (market, ms) => {
    if (fault("flatRounds", market.id)) return market.basePrice.toFixed(market.priceDecimals);
    const wobble = (parseInt(hashOf(`${market.id}:${ms}`).slice(2, 10), 16) % 2000) / 10_000 - 0.1; // -10% .. +10%
    const value = market.basePrice * (1 + wobble);
    return (market.kind === "ODDS" ? Math.min(0.95, Math.max(0.05, value)) : value).toFixed(market.priceDecimals);
  };
  const centsOf = (raw) => BigInt(raw.replace(".", ""));

  // --- the clock of the rounds: the round that starts at S is bet on until S, runs until S + roundMs ---
  const startOfRunning = (now) => Math.floor(now / roundMs) * roundMs;
  const phaseOf = (startMs, now) => (now < startMs ? "BETTING" : now < startMs + roundMs ? "RUNNING" : "ENDED");

  /** The catalog closes a market: it leaves the lobby's active list, its series stop, it takes no bets. */
  function close(market, reason) {
    market.status = "CLOSED";
    market.closedAtMs = Date.now();
    market.closeReason = reason;
  }

  const summary = (playerId) => {
    const p = players.get(playerId);
    return { playerId, nickname: p.nickname, walletAddress: p.address, displayName: p.nickname, isBot: false };
  };

  function betsOf(roundId) {
    if (!bets.has(roundId)) bets.set(roundId, new Map());
    return bets.get(roundId);
  }

  // A round that is over is settled (or cancelled) once: balances move, the results document and the oracle record are made.
  function finish(market, startMs) {
    const roundId = roundIdOf(market.id, startMs);
    if (finished.has(roundId)) return finished.get(roundId);
    const taken = [...betsOf(roundId).values()];
    const now = Date.now();
    const cancelled = fault("cancelRounds", market.id);
    const open = rawPrice(market, startMs);
    const close = rawPrice(market, startMs + roundMs);
    const positions = taken.map((bet) => {
      let outcome;
      let payout;
      if (cancelled) {
        [outcome, payout] = ["CANCELLED", bet.stake];
      } else if (close === open) {
        [outcome, payout] = ["SETTLED", bet.stake]; // flat: the stake comes back
      } else {
        const up = Number(close) > Number(open);
        const won = (bet.direction === "UP") === up;
        [outcome, payout] = won ? ["SETTLED", bet.stake * 2] : ["LIQUIDATED", 0];
      }
      players.get(bet.playerId).balance += payout;
      return { ...bet, outcome, payout };
    });
    const document = canonical({
      ...(market.id === "BTC" ? {} : { market: market.id }),
      ...(market.backupSource ? { venue: market.source } : {}),
      roundId,
      series: "1h",
      status: cancelled ? "CANCELLED" : "SETTLED",
      openPriceCents: cancelled ? null : Number(centsOf(open)),
      closePriceCents: cancelled ? null : Number(centsOf(close)),
      positions: positions
        .map((p) => ({ playerId: p.playerId, direction: p.direction, leverage: p.leverage, stakeMojos: p.stake, outcome: p.outcome, payoutMojos: p.payout }))
        .sort((a, b) => (a.playerId < b.playerId ? -1 : 1)),
      crowd: null,
    });
    const result = {
      roundId, market, startMs, cancelled, open, close, positions, document, hash: hashOf(document),
      madeAtMs: now, seq: nextSeq++,
    };
    finished.set(roundId, result);
    if (!fault("noJournalRow", market.id)) {
      journal.push({
        seq: result.seq,
        roundId,
        marketId: market.id,
        asset: fault("wrongAsset", market.id) ? market.oracleIndex + 7 : market.oracleIndex,
        ts: Math.floor(now / 1000),
        priceCents: cancelled ? 0 : Number(centsOf(close)),
        resultsHash: result.hash,
        madeAtMs: now,
        venue: market.backupSource ? market.source : null,
      });
    }
    return result;
  }

  const journalState = (row) => (Date.now() - row.madeAtMs >= queueDelayMs ? "queued" : "unknown");
  const journalView = (row) => ({ ...row, madeAtMs: undefined, state: journalState(row) });

  // --- DTOs ---
  const positionDto = (p, result, running) => ({
    playerId: p.playerId,
    roundId: result.roundId,
    direction: p.direction,
    leverage: p.leverage,
    size: "1.00000000",
    entryPrice: result.open,
    liquidationPrice: "0",
    pnl: running ? "0.00" : play(p.payout - p.stake),
    stake: play(p.stake),
    player: summary(p.playerId),
    liquidated: !running && p.outcome === "LIQUIDATED",
    payout: running ? null : play(p.payout),
    directionCorrect: running || p.payout === p.stake ? null : p.payout > p.stake,
  });

  function roundDto(roundId, viewer) {
    const ref = parseRoundId(roundId);
    const market = ref && byId.get(ref.marketId);
    if (!market) throw new ApiError(404, "NOT_FOUND", `no round ${roundId}`);
    const { startMs } = ref;
    const now = Date.now();
    const base = {
      roundId,
      window: { interval: "HOURLY", startMs, endMs: startMs + roundMs },
      marketId: market.id,
      asset: market.symbol,
      kind: market.kind,
      priceDecimals: market.priceDecimals,
      source: market.source,
    };
    const phase = phaseOf(startMs, now);
    if (phase === "BETTING") {
      const taken = [...betsOf(roundId).values()];
      const stakeOf = (dir) => taken.filter((b) => b.direction === dir).reduce((sum, b) => sum + b.stake, 0);
      const mine = viewer && betsOf(roundId).get(viewer);
      return {
        ...base,
        phase,
        participantCount: taken.length,
        myBet: mine ? { playerId: viewer, roundId, direction: mine.direction, leverage: mine.leverage, stake: play(mine.stake) } : null,
        upStake: play(stakeOf("UP")),
        downStake: play(stakeOf("DOWN")),
        upCount: taken.filter((b) => b.direction === "UP").length,
        downCount: taken.filter((b) => b.direction === "DOWN").length,
        majorityDirection: null,
        majorityLeverage: null,
        recentBets: taken.slice(-14).reverse().map((b) => ({ player: summary(b.playerId), direction: b.direction, leverage: b.leverage, stake: play(b.stake), atMs: b.atMs })),
      };
    }
    const result = phase === "RUNNING" ? null : finish(market, startMs);
    if (phase === "RUNNING") {
      const known = !fault("noOpenPrice", market.id);
      const open = rawPrice(market, startMs);
      const taken = [...betsOf(roundId).values()];
      const view = { roundId, open };
      return {
        ...base,
        phase,
        openPrice: known ? open : null,
        positions: known ? taken.map((b) => positionDto({ ...b, payout: 0 }, view, true)) : [],
        crowd: null,
      };
    }
    const hashAttached = now - result.madeAtMs >= hashDelayMs;
    const shownHash = fault("corruptResultsHash", market.id) ? hashOf(`${result.document} (not this)`) : result.hash;
    const row = journal.find((r) => r.roundId === roundId);
    const anchor = row ? { seq: row.seq, state: journalState(row), height: null } : null;
    if (result.cancelled) {
      return {
        ...base,
        phase: "CANCELLED",
        cancellation: {
          roundId, marketId: market.id, asset: market.symbol, reason: "market closed: the price left the 3%–97% range",
          cancelledAtMs: result.madeAtMs, refundedBetCount: result.positions.length,
        },
        ...(hashAttached ? { resultsHash: shownHash } : {}),
        oracleAnchor: null,
      };
    }
    return {
      ...base,
      phase: "SETTLED",
      openPrice: result.open,
      closePrice: result.close,
      positions: result.positions.map((p) => positionDto(p, result, false)),
      crowd: null,
      ...(hashAttached ? { resultsHash: shownHash } : {}),
      oracleAnchor: anchor,
    };
  }

  const priceDto = (market) =>
    market.price === null
      ? null
      : { price: rawPrice(market, Date.now()), asOfMs: Date.now(), change24h: 0.01, stale: Boolean(market.price.stale), venue: null };

  function marketDto(market) {
    const now = Date.now();
    const running = startOfRunning(now);
    const active = market.status === "ACTIVE";
    return {
      id: market.id, kind: market.kind, category: market.category, symbol: market.symbol, title: market.title, subtitle: null, iconUrl: null,
      maxLeverage: market.maxLeverage, priceDecimals: market.priceDecimals, status: market.status, endsAtMs: market.endsAtMs,
      eventStartsAtMs: null, closedAtMs: market.closedAtMs, closeReason: market.closeReason, oracleIndex: market.oracleIndex,
      source: market.source, backupSource: market.backupSource, price: active ? priceDto(market) : null,
      series: active
        ? [{
            interval: "HOURLY", durationSec: roundMs / 1000,
            bettingRoundId: roundIdOf(market.id, running + roundMs), bettingClosesAtMs: running + roundMs,
            runningRoundId: roundIdOf(market.id, running), runningEndsAtMs: running + roundMs,
            participantCount: 0, upStake: "0.000", downStake: "0.000", majorityDirection: null,
          }]
        : [],
      crowd: { capital: "100000.00", returnPct: 0, roundsTraded: 0, roundsWon: 0, lastOutcome: null },
    };
  }

  // --- the RPC surface ---
  const rpc = {
    authChallenge: ([{ address }]) => {
      const nonce = `nonce-${challenges.size + 1}`;
      challenges.set(nonce, address);
      return { message: `Herd sign-in\nnonce: ${nonce}`, nonce };
    },
    authVerify: ([{ nonce, address }], ctx) => {
      if (challenges.get(nonce) !== address) throw new ApiError(401, "CHALLENGE_EXPIRED_OR_UNKNOWN", "unknown challenge");
      let player = [...players.values()].find((p) => p.address === address);
      const isNewPlayer = !player;
      if (!player) {
        player = { playerId: `player-${players.size + 1}`, address, nickname: address.slice(0, 8), balance: 10_000 * MOJOS };
        players.set(player.playerId, player);
      }
      const cookie = `session-${sessions.size + 1}`;
      sessions.set(cookie, player.playerId);
      ctx.setCookie = `herd_session=${cookie}; Path=/; HttpOnly`;
      return { player: summary(player.playerId), isNewPlayer };
    },
    setNickname: ([name], ctx) => {
      ctx.requirePlayer().nickname = name;
      return summary(ctx.playerId);
    },
    markets: () => ({ serverTimeMs: Date.now(), categories: [], markets: marketList.map(marketDto) }),
    rounds: ([interval, marketId], ctx) => {
      const market = byId.get(marketId);
      if (!market) throw new ApiError(404, "MARKET_NOT_FOUND", `no market ${marketId}`);
      if (fault("closeOnRounds", marketId)) close(market, "The source market ended");
      if (market.status !== "ACTIVE") throw new ApiError(409, "MARKET_CLOSED", `market ${marketId} is closed`);
      const running = startOfRunning(Date.now());
      return {
        interval,
        running: roundDto(roundIdOf(marketId, running), ctx.playerId),
        betting: roundDto(roundIdOf(marketId, running + roundMs), ctx.playerId),
      };
    },
    round: ([roundId], ctx) => roundDto(roundId, ctx.playerId),
    placeBet: ([bet], ctx) => {
      const player = ctx.requirePlayer();
      const ref = parseRoundId(bet.roundId);
      const market = ref && byId.get(ref.marketId);
      if (!market) throw new ApiError(404, "NOT_FOUND", `no round ${bet.roundId}`);
      if (fault("closeOnBet", market.id)) close(market, "Price left the 3%–97% range");
      if (market.status !== "ACTIVE") throw new ApiError(409, "MARKET_CLOSED", `market ${market.id} is closed`);
      if (phaseOf(ref.startMs, Date.now()) !== "BETTING") throw new ApiError(409, "ROUND_NOT_OPEN_FOR_BETTING", "betting is closed");
      if (!(bet.leverage >= 2 && bet.leverage <= market.maxLeverage)) throw new ApiError(400, "INVALID_LEVERAGE", `x${bet.leverage} is not allowed`);
      const stake = Math.round(parseFloat(bet.stake) * MOJOS);
      if (stake > player.balance) throw new ApiError(409, "INSUFFICIENT_BALANCE", "not enough PLAY");
      const previous = betsOf(bet.roundId).get(player.playerId);
      player.balance += previous ? previous.stake : 0;
      player.balance -= stake;
      betsOf(bet.roundId).set(player.playerId, { playerId: player.playerId, direction: bet.direction, leverage: bet.leverage, stake, atMs: Date.now() });
      return { playerId: player.playerId, roundId: bet.roundId, direction: bet.direction, leverage: bet.leverage, stake: play(stake) };
    },
    myAccount: (_, ctx) => ({ playerId: ctx.playerId, balance: play(ctx.requirePlayer().balance) }),
    publicProfile: ([playerId]) => ({
      playerId,
      history: [...finished.values()].filter((r) => betsOf(r.roundId).has(playerId)).map((r) => ({ roundId: r.roundId })),
    }),
    serverInfo: () => ({
      oracle: { launcherId: "0xlauncher", operatorBalanceMojos: "1000000", lastConfirmedSeq: null, lastConfirmedHeight: null, pendingRecords: journal.length },
    }),
  };

  // --- HTTP ---
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, "http://localhost");
    const send = (status, body, headers = {}) => {
      const text = typeof body === "string" ? body : JSON.stringify(body);
      res.writeHead(status, { "Content-Type": "application/json", ...headers });
      res.end(text);
    };
    try {
      if (req.method === "POST" && url.pathname.startsWith("/api/rpc/")) {
        const method = url.pathname.slice("/api/rpc/".length);
        let raw = "";
        for await (const chunk of req) raw += chunk;
        const args = raw ? JSON.parse(raw).args : [];
        const cookie = /(?:^|;\s*)herd_session=([^;]+)/.exec(req.headers.cookie ?? "")?.[1];
        const ctx = {
          playerId: cookie ? sessions.get(cookie) : undefined,
          setCookie: null,
          requirePlayer() {
            if (!this.playerId) throw new ApiError(401, "NOT_SIGNED_IN", "sign in first");
            return players.get(this.playerId);
          },
        };
        calls.push({ method, atMs: Date.now(), args });
        const handler = rpc[method];
        if (!handler) throw new ApiError(404, "NOT_FOUND", `no method ${method}`);
        const result = handler(args, ctx);
        return send(200, result ?? {}, ctx.setCookie ? { "Set-Cookie": ctx.setCookie } : {});
      }
      const results = /^\/api\/rounds\/([^/]+)\/results\.json$/.exec(url.pathname);
      if (req.method === "GET" && results) {
        const ref = parseRoundId(decodeURIComponent(results[1]));
        const done = ref && finished.get(roundIdOf(ref.marketId, ref.startMs));
        if (!done) return send(404, { error: { code: "NOT_FOUND", message: "no results yet" } });
        return send(200, done.document);
      }
      if (req.method === "GET" && url.pathname === "/api/oracle/records") {
        const from = Number(url.searchParams.get("fromSeq") ?? 0);
        const limit = Math.min(Number(url.searchParams.get("limit") ?? 200), 1000);
        const rows = journal.filter((r) => r.seq >= from).slice(0, limit).map(journalView);
        return send(200, { firstSeq: journal[0]?.seq ?? null, records: rows });
      }
      if (req.method === "GET" && url.pathname === "/api/markets/registry.json") {
        return send(200, marketList.map((m) => ({
          index: m.oracleIndex, id: m.id, kind: m.kind, symbol: m.symbol, priceDecimals: m.priceDecimals, source: m.source,
          sourceRef: m.id, listedAtMs: 0, backup: m.backupSource ? { source: m.backupSource, ref: m.id } : null,
        })));
      }
      return send(404, { error: { code: "NOT_FOUND", message: `${req.method} ${url.pathname}` } });
    } catch (e) {
      if (e instanceof ApiError) return send(e.status, { error: { code: e.code, message: e.message } });
      return send(500, { error: { code: "INTERNAL", message: String(e?.stack ?? e) } });
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  // A round that is over is settled when it ends, not when somebody first asks for it.
  const sweeper = setInterval(() => {
    for (const roundId of bets.keys()) {
      const ref = parseRoundId(roundId);
      if (ref && phaseOf(ref.startMs, Date.now()) === "ENDED") finish(byId.get(ref.marketId), ref.startMs);
    }
  }, 40);
  return {
    url: `http://127.0.0.1:${port}`,
    calls,
    state: { players, finished, journal, bets, markets: byId },
    close: () =>
      new Promise((resolve) => {
        clearInterval(sweeper);
        server.closeAllConnections?.();
        server.close(() => resolve());
      }),
  };
}
