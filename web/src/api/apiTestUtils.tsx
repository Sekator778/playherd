/**
 * Test-only helpers for the hooks in this folder: a wrapper that provides a fake `Api` (and a
 * session) the same way `ApiProvider` does for the real app, and canned DTO builders. Nothing in
 * the app imports this file.
 */
import type { ReactNode } from "react";
import { ApiInstanceContext, SessionContext, type SessionState } from "./ApiContext";
import type { Api, MarketDto, MarketsDto, PlayerSummaryDto, ServerInfoDto } from "./contract";

export const TEST_PLAYER: PlayerSummaryDto = {
  playerId: "player-1",
  nickname: "p1",
  walletAddress: "txch1p1",
  displayName: "p1",
};

/** The methods `Api` declares optional: absent on a fake unless the test gives them, as on a server that predates them. */
const OPTIONAL_METHODS = new Set<string>(["betFeed", "unseenResults", "acknowledgeResults", "serverInfo"]);

/** A fake `Api` with only the methods a test implements; calling any other required one throws. */
export function fakeApi(methods: Partial<Api>): Api {
  return new Proxy(methods as Api, {
    get(target, prop, receiver) {
      if (prop in target) return Reflect.get(target, prop, receiver);
      if (typeof prop === "symbol" || prop === "then" || OPTIONAL_METHODS.has(prop)) return undefined;
      return () => {
        throw new Error(`fake Api: ${String(prop)} is not implemented`);
      };
    },
  });
}

export function apiWrapper(api: Api, player: PlayerSummaryDto | null = null) {
  const session: SessionState = {
    player,
    loading: false,
    refresh: async () => {},
    signOut: async () => {},
    openConnectModal: () => {},
  };
  return function ApiWrapper({ children }: { children: ReactNode }) {
    return (
      <ApiInstanceContext.Provider value={api}>
        <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
      </ApiInstanceContext.Provider>
    );
  };
}

export function marketDto(id: string, overrides: Partial<MarketDto> = {}): MarketDto {
  return {
    id,
    kind: "PERP",
    category: "crypto",
    symbol: id.replace(/^[a-z]+_/, ""),
    title: id,
    subtitle: null,
    iconUrl: null,
    maxLeverage: 25,
    priceDecimals: 2,
    status: "ACTIVE",
    endsAtMs: null,
    source: "hyperliquid",
    price: { price: "100.00", asOfMs: 1_000, change24h: 0.01, stale: false },
    series: [],
    crowd: { capital: "100000.00", returnPct: 0, roundsTraded: 0, roundsWon: 0, lastOutcome: null },
    ...overrides,
  };
}

export function marketsDto(markets: MarketDto[], serverTimeMs = 1_000): MarketsDto {
  return {
    serverTimeMs,
    categories: [{ id: "crypto", title: "Crypto", marketCount: markets.length }],
    markets,
  };
}

/** What a live server answers for `serverInfo()`: the stand's own shape (3 and 15 minute rounds, 20 bots, an oracle
 *  with a confirmed record and two waiting). */
export function serverInfoDto(overrides: Partial<ServerInfoDto> = {}): ServerInfoDto {
  return {
    mode: "live",
    priceSource: "live",
    markets: 19,
    roundDurationsSec: { HOURLY: 180, DAILY: 900 },
    bots: 20,
    oracle: {
      network: "testnet11",
      launcherId: "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20",
      lastConfirmedSeq: 412,
      lastConfirmedHeight: 4_738_927,
      pendingRecords: 2,
      operatorBalanceMojos: "500000000000",
      firstSeq: 1146,
    },
    serverTimeMs: 1_000,
    ...overrides,
  };
}
