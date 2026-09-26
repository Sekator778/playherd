/**
 * Client-side pair registry for the Lobby/Pair-room IA (task item; multi-pair readiness per
 * `docs/research/multi_pair_architecture.md` §9's proposed `/play/:asset` route). Only BTC is
 * backed by the real API today — ETH/SOL are listed here purely so the lobby can show them as
 * clearly-labelled "coming soon" tiles (task: "without numbers") without the server knowing
 * anything about them yet. This is a UI-only registry, not `Api`/`contract.ts` — nothing here is
 * a server round-trip.
 */
export type PairStatus = "live" | "coming-soon";

export interface PairMeta {
  /** Lowercase, used as the `:asset` route param (`/play/:asset`). */
  id: string;
  symbol: string;
  name: string;
  status: PairStatus;
}

export const PAIRS: PairMeta[] = [
  { id: "btc", symbol: "BTC", name: "Bitcoin", status: "live" },
  { id: "eth", symbol: "ETH", name: "Ethereum", status: "coming-soon" },
  { id: "sol", symbol: "SOL", name: "Solana", status: "coming-soon" },
];

export function pairById(id: string | undefined): PairMeta | undefined {
  if (!id) return undefined;
  return PAIRS.find((p) => p.id === id.toLowerCase());
}
