/**
 * TypeScript mirror of the Quarkus HTTP API (D-20) that the React frontend is built against,
 * itself a thin shell over the pure domain classes in
 * `engine/src/main/java/crowdvsmarket/engine/**` (Java). No runtime code lives here — only
 * types and the `Api` interface — so the UI can be built and tested against a mock
 * implementation before the real backend exists (D-35). Swapping the mock for a real HTTP
 * client later changes zero call sites, by design.
 *
 * Naming follows the Java classes/fields 1:1 wherever a direct counterpart exists; every type
 * below carries a comment pointing at the Java class or the product decision (Р-N,
 * `the decision log`) that fixes its shape. Shapes that exist only because the UI
 * needs them (pagination, HTTP error codes, the BTC/anti-crowd comparison series, the
 * challenge/verify sign-in flow) are marked "предложение" and cite the research file they come
 * from, per this project's rule that non-sourced technical choices must be flagged.
 *
 * On the wire: an optional field (`name?:`) may be absent OR JSON `null` — the server writes its
 * absent values as `null`; treat the two alike. Decimal strings are parsed as numbers, never
 * compared as text.
 *
 * Sources: the decision log (D-1…D-57), docs/research/round_engine_design.md,
 * docs/research/week1_wallet_auth.md (§4 for the challenge/verify flow, "Реализация" section for
 * the real WalletConnect/Sage wiring — D-55), docs/product/ux_walkthrough.md.
 */

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/**
 * Interval.java: the two round series every market has (D-19), the shorter and the longer. The
 * names are historical — the real length of a series is `MarketSeriesDto.durationSec` (3 and 15
 * minutes on the stand, 1 hour and 1 day live, Т-11), so label a series by that
 * (`formatDuration`), never by "Hourly"/"Daily".
 */
export type Interval = "HOURLY" | "DAILY";

/** Direction.java: which way a position bets the round closes. */
export type Direction = "UP" | "DOWN";

/** Leverage.java: an integer in 2..40 (Leverage.MIN/MAX — D-33's slider range); a market's own cap
 *  (`MarketDto.maxLeverage`, Т-4) can be lower. */
export type Leverage = number;

/**
 * PlayAmount.java, serialized as a decimal string with exactly 3 fractional digits
 * (`PlayAmount.toMoney()`, scale 3 — mojos never lose precision; 1 PLAY = 1000 mojos,
 * `PlayAmount.MOJOS_PER_PLAY`). Non-negative everywhere except a signed ledger `delta`
 * (`LedgerEntryDto`) or a rating's `growth` (`RichEntryDto`), which are explicitly documented
 * as signed. Example: `"10000.000"` (starter pack, D-12), `"100.000"` (minimum stake, D-12).
 */
export type PlayAmountStr = string;

/**
 * A USD amount as a decimal string, scale 2 cents (`LiquidationCalculator`'s "money" scale).
 * Used for money: a position's P&L, the crowd account's paper capital and margin (D-17) — the
 * crowd account trades paper USD, not PLAY, and never touches a player's PLAY balance (D-5). A
 * market PRICE is a {@link PriceStr} instead (its own number of decimals).
 */
export type UsdStr = string;

/** Epoch milliseconds, UTC (`RoundWindow`/`RoundClock` — no timezone/DST handling, D-19). */
export type EpochMs = number;

/** `Season.id()`: `"YYYY-MM"`, UTC calendar month (Season.java, D-10/D-16). */
export type SeasonId = string;

/**
 * `RoundWindow.roundId()`: `${interval.code()}-${startMs}` for BTC (`"1h-1735689600000"`, the
 * legacy form — published history depends on it) and `${marketId}-${interval.code()}-${startMs}`
 * for every other market (`"ETH-1h-1735689600000"`). `interval.code()` is `"1h"`/`"1d"`
 * (Interval.java), not the `Interval` string above. Treat as opaque; never parse it client-side —
 * every DTO that carries a `roundId` also carries `marketId`.
 */
export type RoundId = string;

/**
 * Server-assigned market id, `[A-Za-z0-9_]{1,32}` — `"BTC"`, `"ETH"`, `"xyz_NVDA"`, `"PM540816"`
 * (docs/research/markets_architecture.md §3.1). Opaque; also the `:marketId` route param.
 */
export type MarketId = string;

/** `PERP`: the USD price of an asset, whatever the venue — a Hyperliquid perpetual's mark price, the mid of a
 *  Coinbase or Hyperliquid spot market (the market's `source` says which). `ODDS`: the probability (0..1) of one
 *  outcome of a Polymarket market (Т-2). Same round mechanics for both. */
export type MarketKind = "PERP" | "ODDS";

/** Lobby panels (D-69, Т-1), in display order. */
export type CategoryId = "crypto" | "politics" | "sports" | "stocks" | "indices" | "commodities";

/**
 * A market price as a decimal string with exactly `MarketDto.priceDecimals` fractional digits:
 * USD for a `PERP` market, a probability in 0..1 for an `ODDS` market (show it as a percentage).
 * Every price in every DTO of a market uses that market's `priceDecimals`.
 */
export type PriceStr = string;

// ---------------------------------------------------------------------------
// Engine constants the UI validates against before round-tripping to the server
// (client-side validation only — the server, via LedgerBook/Leverage, is authoritative)
// ---------------------------------------------------------------------------

/** LedgerBook.STARTER_PACK (D-2, D-12). */
export const STARTER_PACK_PLAY: PlayAmountStr = "10000.000";
/** LedgerBook.MIN_STAKE (D-12) — the bet slip's minimum-stake validation. */
export const MIN_STAKE_PLAY: PlayAmountStr = "100.000";
/** `GameService.MIN_WITHDRAWAL` (D-53, `server/`'s own "предложение" — not in decisions.md;
 *  verified against its source 2026-09-26) — below this, chainsvc's batch-spend fee could eat a
 *  disproportionate share of the withdrawn amount. Same number as `MIN_STAKE_PLAY` today by
 *  coincidence, not by rule — kept as its own constant since the two have no reason to always
 *  match. Client-side-only check (`WalletPage.tsx`'s withdraw form); the server enforces it for
 *  real (`ApiErrorCode.WITHDRAWAL_BELOW_MINIMUM`, additive — not in this file's `ApiErrorCode`
 *  union, same situation as every other server-only code documented there). */
export const MIN_WITHDRAWAL_PLAY: PlayAmountStr = "100.000";
/** Leverage.MIN — the bet slip's leverage slider lower bound (D-33). */
export const LEVERAGE_MIN: Leverage = 2;
/** Leverage.MAX — the platform-wide cap. The bet slip's slider upper bound is the market's own
 *  `MarketDto.maxLeverage` (Т-4), never above this. */
export const LEVERAGE_MAX: Leverage = 40;
/** RatingBook.DEFAULT_ROUNDS_THRESHOLD, fixed at 10 by D-28 (common to both series, D-30). */
export const RATING_ROUNDS_THRESHOLD = 10;
/** CrowdAccount.STARTING_CAPITAL (D-17) — paper USD, not PLAY. */
export const CROWD_STARTING_CAPITAL_USD: UsdStr = "100000.00";
/** CrowdAccount.MARGIN_SHARE_OF_CAPITAL (D-17). */
export const CROWD_MARGIN_SHARE_OF_CAPITAL = 0.10;

// ---------------------------------------------------------------------------
// Identity / profile (PlayerIdentity.java, D-15, D-29)
// ---------------------------------------------------------------------------

/** PlayerIdentity.java, minus the raw walletAddress split logic — the server sends the already
 *  shortened `displayName` (PlayerIdentity.displayName(), §4.7: "nickname (first6…last4)"). */
export interface PlayerSummaryDto {
  playerId: string;
  nickname: string;
  walletAddress: string;
  /** `"nickname (xch1qp…mqqqxw)"` — precomputed server-side, never re-derived in the UI. */
  displayName: string;
  /** Additive (task's FIXED INTERFACE (1)): set on the 20 labelled test-mode bots (D-46) so the
   *  UI can badge them everywhere a `PlayerSummaryDto` is shown. Absent/false for a real player. */
  isBot?: boolean;
}

/** Public profile (D-29): past rounds only (direction, leverage, outcome) — the player's
 *  currently pending bet, if any, is never included here (hidden until round start, D-22),
 *  even when the viewer IS that player (use `myAccount`/`RoundBetting.myBet` for that instead). */
export interface PublicProfileDto {
  player: PlayerSummaryDto;
  seasonRecord: SeasonRecordDto | null; // null if the player has no record in the current season yet
  history: ProfileRoundEntryDto[]; // newest first
  /** Win streak over settled rounds, newest first (D-71): a round counts as a win when
   *  `payout > stake`; a flat round (`payout == stake`) neither extends nor breaks the streak. */
  streak?: { current: number; best: number };
  /** UTC days in a row with a settled round, up to today (or yesterday while today is not played yet), and the longest such run.
   *  Only told: no bonus, no countdown. Absent from a server that predates it. */
  playDays?: { current: number; best: number };
}

export interface ProfileRoundEntryDto extends WithBetMode {
  roundId: RoundId;
  interval: Interval;
  marketId: MarketId;
  /** The market's `symbol` at settlement time ("BTC", "NVDA", "GER"). */
  asset: string;
  /** Real length of the round in seconds — label the row by it ("3 MIN", "1 HOUR"), so the label
   *  survives the market leaving `markets()`. Optional: absent from a server that predates it. */
  durationSec?: number;
  /** The market's kind, for the same reason (an `ODDS` row is worded as odds). */
  kind?: MarketKind;
  settledAtMs: EpochMs;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  liquidated: boolean;
  /** sign(close-open) match, independent of liquidation (D-27); null for a flat round
   *  (close == open), excluded from the "пророки" ratio's denominator (§4.6). */
  directionCorrect: boolean | null;
  payout: PlayAmountStr; // 0 on liquidation (D-7)
  /** The bet's total fees (herd + network) as charged when it was placed and kept at settlement (a tie keeps them too), PLAY. Absent or null for a
   *  round settled before the server recorded them: the tiles then show no fees line. */
  fees?: PlayAmountStr | null;
}

// ---------------------------------------------------------------------------
// Sign-in (D-4; docs/research/week1_wallet_auth.md §4's challenge/verify flow). Same shape for
// both real routes an app build can offer at once (`ConnectWalletModal.tsx`): the browser test
// wallet (D-45, `wallet/testWallet.ts`, always available on this local stand) and real Sage over
// WalletConnect (D-55, `wallet/walletConnect.ts`, gated on a Reown `projectId` existing —
// build-time `VITE_WALLETCONNECT_PROJECT_ID` or `ServerInfoDto.walletConnectProjectId` at
// runtime; "Coming soon" in the UI until one does). `MockApi` fabricates a testnet-looking
// address/signature locally instead of verifying a real one — these types are still the real
// contract every implementation honors, so no UI code changes when one `Api` is swapped for
// another.
// ---------------------------------------------------------------------------

export interface AuthChallengeRequest {
  /** Optional: the UI may already know the address (`chia_getAddress`) before asking for a
   *  challenge — week1_wallet_auth.md §4 step 1. */
  address?: string;
}

export interface AuthChallengeDto {
  nonce: string;
  /** The exact text Sage will show the player to sign — the UI must display this BEFORE
   *  prompting for a signature, since Sage's own approval dialog does not decode it
   *  (docs/research/prediction_market_research.md §6, cited in week1_wallet_auth.md §0). */
  message: string;
  expiresAtMs: EpochMs;
}

export interface AuthVerifyRequest {
  nonce: string;
  address: string;
  publicKey: string;
  signature: string;
  /** Another player's invite code and a channel tag, as kept from the landing URL (`/?ref=<code>&src=<channel>`, `api/referral.ts`).
   *  Optional; the server reads them only when this sign-in registers a NEW player, records them once, and ignores an unknown code,
   *  the player's own code, or a malformed tag. */
  referralCode?: string;
  source?: string;
}

/** The signed-in player's own invite link data: `code` goes into `/?ref=<code>`; `referredCount` is the number of players
 *  who registered through it (bots excluded). No rewards attach to either. */
export interface InviteDto {
  code: string;
  referredCount: number;
}

/** D-106: a bot of an outside developer, as its owner sees it. `keyHint` is the key's prefix and last four characters (`herd_...ab12`),
 *  `null` once the key is revoked; `keyActive` says whether a key opens the bot now. The key itself is never in this object. */
export interface ApiBotDto {
  playerId: string;
  nickname: string;
  keyHint: string | null;
  keyActive: boolean;
  createdAtMs: EpochMs;
}

/** What creating a bot and regenerating a key answer: the bot and its key in full. The key is sent this once and is not stored in
 *  readable form anywhere, on the server or here - show it, let the owner copy it, then drop it. */
export interface ApiBotKeyDto {
  bot: ApiBotDto;
  apiKey: string;
}

/** The most API bots one person can own (`GameService.MAX_API_BOTS_PER_OWNER`, D-106). */
export const MAX_API_BOTS = 3;

export interface AuthSessionDto {
  player: PlayerSummaryDto;
  /** True on a brand-new player — the UI must immediately continue into the nickname-pick step
   *  (D-15) before anything else, per the first-time flow. */
  isNewPlayer: boolean;
  /** True when THIS sign-in credited a starter pack (`STARTER_PACK` in the ledger): a brand-new player's, and a returning
   *  player's first sign-in of a new season (calendar month) — the pack comes on top of the balance they have. The UI tells the
   *  player about a pack when it was credited (first tester pass, D1: it used to tell a returning player "You've got 10,000 PLAY"
   *  over a balance that was not 10,000 — and, once it stopped, said nothing to the one whose month had rolled over). Optional:
   *  the mock and an older server do not send it, and `isNewPlayer` then stands for it. The real server always does. */
  starterPackGranted?: boolean;
}

// ---------------------------------------------------------------------------
// Rounds (round_engine_design.md §2 — RoundWindow/RoundClock/GameEngine/BetBook; D-8, D-13,
// D-19, D-22, D-23, D-32)
// ---------------------------------------------------------------------------

export interface RoundWindowDto {
  interval: Interval;
  startMs: EpochMs;
  endMs: EpochMs;
}

/**
 * Round-level display phase. Not a literal field on any Java class: `GameEngine.runRound`
 * resolves a round (open → monitor → settle) in one atomic call once its whole price path is
 * known (round_engine_design.md §2, "почему одним вызовом"), so the domain model itself has no
 * separately observable RUNNING state. The live backend adds this phase on top, driven by wall
 * clock time against `RoundWindow`, so the UI has something to render for the whole
 * `[startMs, endMs)` duration before the resolve happens — предложение (round_engine_design.md
 * does not name a live/real-time API surface, only the historical-replay one).
 */
export type RoundPhase = "BETTING" | "RUNNING" | "SETTLED" | "CANCELLED";

/** Bet.java — a pending stake before the round starts. No entry/liquidation price yet: those
 *  only exist once the round opens a `Position` at the fixed round-start price (D-8). Never
 *  shown for another player before round start (D-22) — only via `RoundBetting.myBet`. */
export type BetMode = "PERCENTAGE" | "FIXED";

/** How a bet is paid (D-106, `docs/product/fixed_payout_rules.md`). `FIXED`: a right direction pays 2 x stake, a wrong one 0, a tie gives the
 *  stake back; no leverage (the `leverage` field is a placeholder), no liquidation. Absent or null = `PERCENTAGE`, the original mode. */
export interface WithBetMode {
  mode?: BetMode | null;
}

export interface BetDto extends WithBetMode {
  playerId: string;
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  /** Economy stage A (D-92): the fees charged on top of `stake` when the bet was placed, and the highest leverage the market
   *  allows for this stake in this round length. Absent/null for a bet placed before the economy change. */
  feeHerd?: PlayAmountStr | null;
  feeNetwork?: PlayAmountStr | null;
  maxLeverageForStake?: number | null;
}

/** Position.java — a bet turned into an actual position once the round starts, at its fixed
 *  entry price (D-8). `pnl` is null until settled/liquidated (`Position.isSettled()`). */
export interface PositionDto {
  playerId: string; // Position.userId
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  size: string; // position size in lots, 8dp (PositionSizing.SIZE_SCALE; one lot = 10^(priceDecimals-2) units, Т-3)
  entryPrice: PriceStr;
  /** Null for a fixed-payout position (`mode: "FIXED"`): it cannot be liquidated. */
  liquidationPrice: PriceStr | null;
  /** Signed, in PLAY (1 PLAY of stake is 1 USD of margin). RUNNING: the unrealised P&L at the
   *  latest price, 2 decimals — null once the position is liquidated on the path so far. SETTLED:
   *  `payout - stake` exactly, 3 decimals. */
  pnl: UsdStr | null;
  /**
   * Additive (2026-09-25 UI bug fix — see `docs/product/ux_spec.md` §4/§7, "net result"): the
   * original PLAY stake this position was opened with (`Bet.stake` before `GameEngine` turns it
   * into `Position.size` at round start, round_engine_design.md §4.9). Without this, the only way
   * to show a position's net PLAY change (`payout - stake`) is `initialMargin(size, entryPrice,
   * leverage)` (`liquidation.ts`) — the inverse of `sizeForMargin`, but only as good as its two
   * roundings (size to 8dp, margin to cents) and blind to the stake's third PLAY decimal (a size
   * handed over with fewer digits drifts further: 0.01198 BTC at x10/83451.00 recovers 99.97, not
   * 100.00). Optional so a `RealApi` build that predates this field still works (falls back to
   * that derivation, `api/outcome.ts`'s `positionStakeStr`) — but the server should start sending
   * it since it already has the exact number (`Bet.stake`) with no need to derive anything. */
  stake?: PlayAmountStr;
}

/** A position as shown to OTHER players once revealed at round start (D-22): identity attached,
 *  plus the resolved outcome once known. */
export interface PublicPositionDto extends PositionDto, WithBetMode {
  player: PlayerSummaryDto;
  liquidated: boolean;
  /** Set once the round settles; PLAY, not USD (`PlayerRoundOutcome.payout` via `PlayAmount`).
   *  0 PLAY on liquidation (D-7). Null while the round is still RUNNING. */
  payout: PlayAmountStr | null;
  /** Derived: sign(close-open) match, independent of liquidation (D-27, §4.6). Null while
   *  RUNNING, or for a flat round once settled. */
  directionCorrect: boolean | null;
}

/** CrowdAccount.RoundResult, for a round still RUNNING (opened, not yet resolved) — distinct
 *  from `CrowdRoundResultDto` because the live version is open for the round's whole real-time
 *  duration, unlike the historical-replay path where open+resolve happen in the same call. */
export interface CrowdRoundPositionDto {
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage; // stake-weighted average of the majority side, HALF_UP, clamped 2..the market's cap (D-14)
  margin: UsdStr; // 10% of capitalBefore (D-17)
  entryPrice: PriceStr;
  liquidationPrice: PriceStr;
}

/** CrowdAccount.RoundResult, once the round has settled. `traded=false` (all fields but
 *  `capitalBefore`/`capitalAfter` null) for a tie or zero bets on both sides (D-5). */
export interface CrowdRoundResultDto {
  roundId: RoundId;
  traded: boolean;
  direction: Direction | null;
  leverage: Leverage | null;
  margin: UsdStr | null;
  entryPrice: PriceStr | null;
  liquidated: boolean;
  capitalBefore: UsdStr;
  capitalAfter: UsdStr;
}

/** D-32/D-23: an operator cancellation (bad oracle read) or an automatic one (no price could be
 *  read — after five minutes, fifteen for Polymarket, two when the market's other exchange still
 *  sends prices: D-79, D-77; at the latest within 1 hour, as for any other fault: D-23) — every
 *  cancellation is publicly visible with its reason on the round page, regardless of cause. */
export interface CancellationDto {
  roundId: RoundId;
  /** The round's market and its `symbol` — the cancellations log names the pair, not only the id. */
  marketId?: MarketId;
  asset?: string;
  reason: string;
  cancelledAtMs: EpochMs;
  /** Count of bets refunded, including any that would already have been liquidated (D-23:
   *  "все ставки возвращаются, включая ликвидированные"). */
  refundedBetCount: number;
}

interface RoundBase {
  roundId: RoundId;
  window: RoundWindowDto;
  marketId: MarketId;
  /** The market's `symbol` ("BTC", "NVDA", "GER"). */
  asset: string;
  /** What a round page needs to print this round's prices and name their source without
   *  `markets()` — a market leaves that list ten minutes after it closes, its rounds stay
   *  readable for good. Same values as the market's `MarketDto` fields. */
  kind: MarketKind;
  priceDecimals: number;
  /** The exchange the round runs on — its prices come from there alone. For a market that has a backup exchange
   *  it is chosen when the round opens (the primary if that has a live price at the start, else the backup) and
   *  never changes; until then, the primary. A round that moves to the backup opens at the backup's price, which
   *  may differ from the previous round's close by the two exchanges' basis (a few basis points). */
  source: "hyperliquid" | "coinbase" | "kraken" | "polymarket" | "synthetic";
}

export interface RoundBetting extends RoundBase {
  phase: "BETTING";
  /** Headcount — public even before D-62 and still meaningful standalone (e.g. for a screen that
   *  only wants "N playing", like a lobby card — `MarketSeriesDto.participantCount` carries the
   *  same number for every market in one `markets()` call). */
  participantCount: number;
  /** The signed-in viewer's own pending bet on this round, if any — private to them, and the
   *  basis for the "already bet this round" edit/cancel state (D-13). Null if signed out or has
   *  no bet yet. */
  myBet: BetDto | null;
  /**
   * Additive (D-62, 2026-09-26 — "Расклад толпы (UP/DOWN в %, число игроков)... видны вживую во
   * время окна ставок, как в Twitch Predictions", **replaces D-22**'s "count only" during
   * BETTING): the live stake split by direction, updating throughout the betting window — the
   * arena's UP/DOWN half-widths (`docs/product/design/concepts.md` concept A) read straight from
   * these two numbers. Optional so a `RealApi` build that predates D-62 still degrades to the old
   * D-22 behavior (participant count only, no split) rather than crashing on a missing field.
   */
  upStake?: PlayAmountStr;
  downStake?: PlayAmountStr;
  /**
   * Additive (server-side extension of the same D-62 live split above, `docs/research/
   * server_design.md`): headcount by side — `upCount + downCount === participantCount`. Optional
   * for the same reason as `upStake`/`downStake`.
   */
  upCount?: number;
  downCount?: number;
  /**
   * Additive (server-side extension of D-62): the crowd's majority side previewed one phase early
   * — same majority-by-stake rule and stake-weighted-average leverage calc (D-14) as
   * `CrowdRoundPositionDto`/`CrowdRoundResultDto` use once the round actually opens a position,
   * just computed straight from the still-pending bets. Both `null` only for a tie (including 0
   * vs 0 — same tie rule as `RoundRunning.crowd`/`RoundSettled.crowd`). Optional for the same
   * reason as `upStake`/`downStake`.
   */
  majorityDirection?: Direction | null;
  majorityLeverage?: Leverage | null;
  /**
   * Additive (D-62's "лента ставок видна вживую") — recent bets placed during this betting
   * window, newest first, capped server-side (mock: 14). Never reveals anything before it's
   * placed — this is bets that have already happened becoming visible live, not a retroactive
   * lift of D-22 on unplaced bets. Optional, same fallback reasoning as `upStake`/`downStake`
   * above: absent → the room simply shows no live feed. For the FULL history of this same feed,
   * with cursor-based pagination instead of a small fixed cap, see `betFeed()` below.
   */
  recentBets?: LiveBetDto[];
}

/** One row of D-62's live bet feed — same identity/direction/leverage/stake shape as a settled
 *  position already exposes (`PublicPositionDto`), just for a bet that hasn't reached round start
 *  yet. */
export interface LiveBetDto extends WithBetMode {
  player: PlayerSummaryDto;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  atMs: EpochMs;
}

/**
 * Additive (server-side, D-62): one row of the FULL live bet feed (`betFeed()` below) — the same
 * underlying activity `RoundBetting.recentBets`/`LiveBetDto` above already gives a small,
 * newest-first, uncapped-history preview of, just paginated with a `seq` cursor instead of a fixed
 * small cap, and with `player` flattened to just `playerDisplayName`/`isBot` (nicknames are already
 * public per D-15/D-29; a feed row has no other use for the rest of `PlayerSummaryDto`). A new
 * entry appears every time a player places OR edits a bet (D-13's "modify before start" shows as a
 * new row too, same as a Twitch Predictions viewer changing their vote never erases the earlier
 * one).
 */
export interface BetFeedEntryDto extends WithBetMode {
  seq: number;
  atMs: EpochMs;
  playerDisplayName: string;
  isBot: boolean;
  direction: Direction;
  stake: PlayAmountStr;
  leverage: Leverage;
}

/**
 * `entries`: oldest-first among those with `seq > sinceSeq` passed to `betFeed()`, capped per call
 * (server's own page size). `hasMore=true` means more already-placed entries exist beyond this
 * page — call `betFeed()` again immediately with this page's last entry's `seq` rather than
 * waiting for the next poll tick.
 */
export interface BetFeedDto {
  roundId: RoundId;
  marketId: MarketId;
  asset: string;
  entries: BetFeedEntryDto[];
  hasMore: boolean;
}

export interface RoundRunning extends RoundBase {
  phase: "RUNNING";
  /** The price at round start (D-8). Null while the server does not know it yet — a market listed
   *  moments ago, or a source outage across the boundary: show "waiting for the opening price",
   *  never a zero. `positions` is empty and `crowd` is null for as long as it is null. */
  openPrice: PriceStr | null;
  /** Revealed at round start, together with the crowd position (D-22). */
  positions: PublicPositionDto[];
  /** Null only for a tie / zero bets on both sides (D-5). */
  crowd: CrowdRoundPositionDto | null;
}

export interface RoundSettled extends RoundBase {
  phase: "SETTLED";
  openPrice: PriceStr;
  closePrice: PriceStr;
  positions: PublicPositionDto[];
  crowd: CrowdRoundResultDto | null;
  /** Additive (task's FIXED INTERFACE (1), D-41): "0x"+sha256 hex of the canonical results JSON
   *  published in the same oracle record as this round's close. Optional — absent until the
   *  oracle spend confirms on testnet11 (`ServerInfoDto.oracle.pendingRecords`). Verify at
   *  `GET /api/rounds/{roundId}/results.json`. */
  resultsHash?: string;
  /** Where this round's oracle record is on Chia (D-40, D-41): `seq` in the oracle singleton's
   *  stream, `state` as chainsvc reports it (`unknown` until queued, then `queued`, `in_flight`,
   *  `confirmed`, or `conflict`), `height` once confirmed. Absent for a round with no record. */
  oracleAnchor?: OracleAnchorDto;
}

/** Server `Rounds.OracleAnchorDto`. */
export interface OracleAnchorDto {
  seq: number;
  state: string;
  height: number | null;
}

export interface RoundCancelled extends RoundBase {
  phase: "CANCELLED";
  cancellation: CancellationDto;
  /** Additive (task's FIXED INTERFACE (1)) — same meaning as `RoundSettled.resultsHash`; a
   *  cancelled round's canonical results JSON has `status: "CANCELLED"` and an empty/void
   *  `positions`/`crowd` per the FIXED INTERFACE (2) shape. */
  resultsHash?: string;
}

export type RoundDto = RoundBetting | RoundRunning | RoundSettled | RoundCancelled;

/** D-19: "всегда один идущий и один открытый для ставок раунд" per series. `betting` is
 *  expected to be `RoundBetting` — it can be `RoundCancelled` only if the operator cancelled it
 *  early (D-32), before it ever started running. */
export interface RoundsPairDto {
  interval: Interval;
  running: RoundDto;
  betting: RoundBetting | RoundCancelled;
}

// ---------------------------------------------------------------------------
// Liquidation preview (D-7, D-33: shown BEFORE the bet is placed)
// ---------------------------------------------------------------------------

/** A market's current reference price — the starting point of the liquidation preview before a bet
 *  is placed, and the live price a RUNNING round's P&L is read against (`useReferencePrice`). The
 *  position's REAL entry price is fixed at round start (D-8) and can differ from this preview
 *  price once the round actually opens; the UI must say so (see docs/product/ux_spec.md,
 *  "Round"/"Bet slip"). */
export interface MarketReferenceDto {
  price: PriceStr;
  asOfMs: EpochMs;
}

export interface LiquidationPreviewRequest {
  /** Omitted → the BTC pair: the open market whose symbol is BTC (`spot_BTC` once the legacy `BTC` perpetual was replaced by it
   *  and closed), which the server resolves (`GameService.defaultMarket`). A screen that has to name a market itself takes
   *  `defaultMarket(markets)` (`components/marketModel.ts`), never the id `"BTC"`. */
  marketId?: MarketId;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  /** Omit to let the server use its own current reference price. */
  referencePrice?: PriceStr;
  /** Economy stage A (D-92): the round length the bet is for. With it the answer uses that length's maintenance rate and
   *  carries `feeHerd`/`feeNetwork`/`totalDebit`/`maxLeverageForStake`, and a leverage above what the stake allows is
   *  `INVALID_LEVERAGE` (the message names the maximum). Omitted → the answer of old. */
  interval?: Interval;
}

/** Mirrors `LiquidationCalculator.liquidationPrice` computed against a reference price, not a
 *  real entry price yet — see `MarketReferenceDto`'s caveat. */
export interface LiquidationPreviewDto {
  entryPriceUsed: PriceStr;
  liquidationPrice: PriceStr;
  /** e.g. `0.025` = 2.5% adverse move from `entryPriceUsed` to `liquidationPrice` — the "%
   *  move to liquidation" figure D-33 requires shown before the bet. */
  percentToLiquidation: number;
  /** Economy stage A, present when the request named an `interval`: the herd fee, the flat network fee, stake plus both
   *  fees (what leaves the balance), and the highest leverage allowed for this stake (the market's cap for the round
   *  length, divided by 1 / 2 / 5 / 10 for a stake of up to 250 / 500 / 1 200 / more PLAY, at least 2). */
  feeHerd?: PlayAmountStr | null;
  feeNetwork?: PlayAmountStr | null;
  totalDebit?: PlayAmountStr | null;
  maxLeverageForStake?: number | null;
}

export interface PlaceBetRequest extends WithBetMode {
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  /** Optional idempotency key of one press of the button (a UUID; 1-64 characters of `A-Z a-z 0-9 - _`). The same id from the same
   *  player within ten minutes places no second bet and is answered with the first answer, so a retry after a lost answer is safe;
   *  the same id with a different bet is refused (`BAD_REQUEST`). Absent: the call is as it always was. `placeBetWithRetryId`
   *  (`betAttempt.ts`) makes and reuses it. */
  clientRequestId?: string;
}

// ---------------------------------------------------------------------------
// Player account / ledger (LedgerBook.java, PlayAmount.java, SeasonRecord.java — D-2, D-3,
// D-6, D-12, D-16)
// ---------------------------------------------------------------------------

export interface PlayerAccountDto {
  playerId: string;
  balance: PlayAmountStr;
  currentSeason: SeasonId;
  /** Additive (2026-09-25 bug fix, backend): total stake currently sitting in bets that have not
   *  settled yet (the BETTING and RUNNING rounds of both series of EVERY market) — already debited from `balance`
   *  but not yet won or lost. Optional so a `RealApi` build that predates this field still works
   *  (falls back to not showing it) — but the server should start sending it. */
  inPlay?: PlayAmountStr;
  /** Economy stage C (D-92, C6): what the player may withdraw, `max(0, balance - packCredited)` once the economy is
   *  active — the starter packs stay in the game, winnings and deposits above them can leave. Optional so a server that
   *  predates it still works (the wallet then falls back to the whole balance). */
  withdrawable?: PlayAmountStr;
}

export interface SeasonRecordDto {
  season: SeasonId;
  baselineBalance: PlayAmountStr;
  deposited: PlayAmountStr;
  withdrawn: PlayAmountStr;
  roundsPlayed: number;
  directionEligibleRounds: number;
  directionCorrectRounds: number;
}

/** LedgerEntryType.java. */
export type LedgerEntryType =
  | "STARTER_PACK"
  | "STAKE_DEBIT"
  | "STAKE_REFUND"
  | "SETTLEMENT"
  /** Economy stage A: the herd fee and the flat network fee charged with a bet (debits), and their refunds
   *  when the bet is cancelled/replaced before the round or its round is cancelled (credits). */
  | "FEE_HERD"
  | "FEE_NETWORK"
  | "FEE_HERD_REFUND"
  | "FEE_NETWORK_REFUND"
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "WITHDRAWAL_REFUND"
  /** A signed correction booked by the operator (reversing a wrong deposit or withdrawal). */
  | "OPERATOR_CORRECTION";

/** LedgerEntry.java — one append-only ledger movement, as shown in the wallet's transaction
 *  history. */
export interface LedgerEntryDto {
  sequence: number;
  type: LedgerEntryType;
  /** Signed: positive = credit, negative = debit (unlike every other `PlayAmountStr` in this
   *  file, this one can be negative — LedgerEntry.delta). */
  delta: PlayAmountStr;
  roundId: RoundId | null;
  atMs: EpochMs;
}

/**
 * WithdrawalState — the ledger-level outcome of a real on-chain withdrawal (D-53), unchanged in
 * shape from before D-53 (`server/`'s `Enums.WithdrawalState`, verified against its own source
 * 2026-09-26 rather than assumed): `REQUESTED` the instant the amount is escrowed (debited),
 * whether or not chainsvc has even accepted the submission yet; `CONFIRMED`/`FAILED` only once
 * the server's `WithdrawalPollerService` (task's FIXED INTERFACE, chainsvc) observes the
 * withdrawal confirmed/failed on testnet11. The finer-grained "queued"/"in_flight" progress the
 * task's UI item asks for lives in `chainState` below, not here — `state` alone stays
 * `REQUESTED` for that whole window.
 */
export type WithdrawalState = "REQUESTED" | "CONFIRMED" | "FAILED";

/**
 * chainsvc's own withdrawal status word (task's FIXED INTERFACE:
 * `GET /v1/play/withdrawals/{requestId}` → `state: "queued"|"in_flight"|"confirmed"|"failed"`),
 * mirrored into `WithdrawalRequestDto.chainState` verbatim while the coarser `WithdrawalState`
 * above is still `REQUESTED` — this is where a `QUEUED`/`IN_FLIGHT` distinction actually comes
 * from. Plus `"submitted"`: the server's own placeholder the instant its `POST
 * /v1/play/withdrawals` call is accepted but not yet polled once
 * (`WithdrawalDepositRepository.markWithdrawalSubmitted`, verified against `server/` 2026-09-26).
 * `null` before even that (the synchronous submit attempt inside `requestWithdrawal` hasn't
 * succeeded yet — chainsvc was down/timed out; `WithdrawalPollerService`'s `retrySubmissions` is
 * the retry path, task: "durable queue").
 */
export type WithdrawalChainState = "submitted" | "queued" | "in_flight" | "confirmed" | "failed";

/**
 * WithdrawalRequest.java — balance is already debited (escrowed) the instant this is created
 * (`state === "REQUESTED"`), regardless of `chainState`. `toAddress` is always the player's own
 * registered address (D-57 — never a free-form destination the player types in; the withdraw
 * form only shows it, read-only, per `ux_spec.md` §8). `coinId`/`height` are set once
 * `state === "CONFIRMED"`; `failureReason` only once `state === "FAILED"` — no public testnet11
 * block explorer is reliably up (`docs/research/week1_testnet_results.md`: spacescan.io 504/403,
 * coinset.org has no per-network human-readable page), so the UI shows `coinId`/`height` as plain
 * reference text, not a link. Verified field-for-field against `server/`'s
 * `Ledger.WithdrawalRequestDto`/`WithdrawalDepositRepository` 2026-09-26 (they were built
 * concurrently with this file by a different agent working the same task) rather than guessed.
 */
export interface WithdrawalRequestDto {
  requestId: string;
  playerId: string;
  amount: PlayAmountStr;
  state: WithdrawalState;
  requestedAtMs: EpochMs;
  toAddress: string;
  chainState: WithdrawalChainState | null;
  coinId: string | null;
  height: number | null;
  failureReason: string | null;
}

/**
 * `POST /v1/play/deposit-address` (task's FIXED INTERFACE, chainsvc), proxied by the server for
 * the signed-in player (`playerIndex` is a server-side/chain concern — never sent to or derived
 * by the UI, which treats `address` as opaque). Replaces the old self-reported
 * `requestDeposit(amount)`/`deposit(requestId)` flow entirely (D-53's blocker fix, independent
 * critique 2026-09-26: that flow credited any typed-in amount with zero on-chain check — "5,000,000
 * fake PLAY minted in ten seconds" — and is not coming back in any form). A deposit is no longer
 * something the player "requests": they send PLAY from their own wallet to this address, and the
 * server credits it automatically once chainsvc reports it confirmed
 * (`GET /v1/play/deposits?sinceHeight=N`) — the UI only has to show the address and let
 * `myAccount()`/`myLedger()` polling (`ux_spec.md` §8) reveal the resulting `DEPOSIT` ledger entry
 * once it lands; there is no intermediate "pending" state to poll for a specific deposit (chainsvc
 * only reports already-confirmed coins).
 */
export interface DepositAddressDto {
  address: string;
}

// ---------------------------------------------------------------------------
// Crowd account (CrowdAccount.java — D-5, D-14, D-17, D-25, D-26)
// ---------------------------------------------------------------------------

/**
 * One round's point on the crowd chart. `holdCapital`/`antiCrowdCapital` — предложение: no
 * `HoldAccount`/`AntiCrowdAccount` class exists in `engine/` yet, only `CrowdAccount`
 * itself. D-26 fixes that these two comparison series must exist and their starting capital
 * ($100 000, same as the crowd account) and mechanics ("тот же счёт, всегда против
 * большинства" for anti-crowd; a flat buy-and-hold of the market's asset for the hold line — it
 * was `btcHoldCapital` while BTC was the only market) — the per-round DTO shape
 * here is this contract's proposal for how the backend will compute and serve them, modelled
 * as a small sibling engine class per round rather than re-deriving them from candle data on
 * every request. To confirm before backend work starts on this endpoint.
 */
export interface CrowdHistoryPointDto {
  roundId: RoundId;
  atMs: EpochMs; // matches GameEngine.runRound's settledAt (window.endMs() - 1)
  /** Marks a season boundary on the chart (D-25); null on every other point. */
  seasonBoundary: SeasonId | null;
  crowdCapital: UsdStr;
  /** "Just hold the asset" on the same starting capital, bought at the market's first resolved
   *  round (D-26, per market — Т-6). */
  holdCapital: UsdStr;
  antiCrowdCapital: UsdStr;
}

export interface SeasonReturnDto {
  season: SeasonId;
  /** e.g. `0.034` = +3.4% (D-25: "рядом доходность за каждый сезон"). */
  returnPct: number;
}

export interface CrowdAccountDto {
  marketId: MarketId;
  symbol: string;
  capital: UsdStr;
  /** One continuous series since day one — seasons never reset it (D-25). Oldest first. */
  history: CrowdHistoryPointDto[];
  seasonReturns: SeasonReturnDto[];
}

// ---------------------------------------------------------------------------
// Markets and categories (D-69, D-70, D-71; docs/research/markets_architecture.md §3, §8)
// ---------------------------------------------------------------------------

export interface CategoryDto {
  id: CategoryId;
  title: string;
  /** Active markets in the category. A category with no market is not listed. */
  marketCount: number;
}

export interface MarketPriceDto {
  price: PriceStr;
  asOfMs: EpochMs;
  /** Relative change vs 24h ago, e.g. `0.0098` = +0.98%; null when the source does not give it.
   *  For an `ODDS` market show it in percentage points instead: the price 24h ago is
   *  `price / (1 + change24h)`, so the move is `price - price / (1 + change24h)` (0.64 → 0.65 is
   *  "+1.0 pt", not "+1.56%"). */
  change24h: number | null;
  /** True when the last tick is older than the source's staleness limit: betting stays open, the
   *  card shows the price as stale. */
  stale: boolean;
  /** Which exchange the shown price is from, when it is not the market's own (`MarketDto.source`):
   *  a crypto market whose primary exchange has no fresh price shows its backup's (`MarketDto.backupSource`)
   *  and says so here (D-76). Null/absent: the primary's price, and every market that has no backup. */
  venue?: "hyperliquid" | "coinbase" | "kraken" | null;
}

/** One round series of a market, as the lobby card and the room header need it (D-19, D-62). */
export interface MarketSeriesDto {
  interval: Interval;
  /** Real length of this series' rounds — label the series by it ("3 MIN", "1 HOUR"), never by
   *  the `Interval` name. */
  durationSec: number;
  bettingRoundId: RoundId;
  /** Betting closes when this round starts. */
  bettingClosesAtMs: EpochMs;
  runningRoundId: RoundId;
  runningEndsAtMs: EpochMs;
  /** Live split of the round open for betting. */
  participantCount: number;
  upStake: PlayAmountStr;
  downStake: PlayAmountStr;
  /** Null for a tie, including 0 vs 0. */
  majorityDirection: Direction | null;
  /** Economy stage A: the market's leverage cap for this round length (a bigger stake lowers it, see
   *  `BetDto.maxLeverageForStake`), and its herd-fee rate `r_m` in basis points (`fee = stake * min(20%, k * r_m * leverage), k = 1 in the 3-minute round, 2.2 (3.0 odds) in the 15-minute one`). */
  maxLeverage?: number | null;
  herdFeeBp?: string | null;
  /** `k` of the herd fee for this round length: "1" in the 3-minute round, "2.2" in the 15-minute one ("3" in an odds market's) — the "All fees" page computes
   *  its table from `herdFeeBp`, this and `maxLeverage`, never from numbers of its own. Absent from a server that predates it. */
  feeFactor?: string | null;
}

/** A market's own crowd account (D-5, D-17, Т-6) in card form — how the herd is doing here. */
export interface MarketCrowdBriefDto {
  capital: UsdStr;
  /** `capital / 100000 - 1`. */
  returnPct: number;
  /** Rounds in which the crowd account opened a position (a tie opens none). */
  roundsTraded: number;
  /** Traded rounds that ended with more capital than they started with. A flat round (the price
   *  closed where it opened) is traded but neither won nor lost. */
  roundsWon: number;
  /** The crowd's last traded round; null before the first one. `FLAT`: closed where it opened. */
  lastOutcome: "WIN" | "LOSS" | "LIQUIDATED" | "FLAT" | null;
}

export interface MarketDto {
  id: MarketId;
  kind: MarketKind;
  category: CategoryId;
  /** Card ticker: "BTC", "NVDA"; a short label for `ODDS` ("GER", "Lula"). */
  symbol: string;
  /** "Bitcoin"; for `ODDS` the market question. */
  title: string;
  /** `ODDS`: the outcome the price refers to plus its context ("Yes · Brazil election",
   *  "Germany · UEFA Nations League"). `PERP`: null. */
  subtitle: string | null;
  /** Source image when there is one (`ODDS`); null → draw a glyph from `symbol`. */
  iconUrl: string | null;
  /** Upper bound of the leverage slider for this market (Т-4), 2..40. */
  maxLeverage: Leverage;
  priceDecimals: number;
  status: "ACTIVE" | "CLOSED";
  /** `ODDS`: when the underlying market is scheduled to end. Null otherwise — also null for a
   *  sports game, whose source "end date" is the kick-off (see `eventStartsAtMs`). */
  endsAtMs: EpochMs | null;
  /** `ODDS` sports game: kick-off time. Before it the card says "Starts in …", after it "Live".
   *  Null/absent for everything else. */
  eventStartsAtMs?: EpochMs | null;
  /** Set once `status === "CLOSED"`: when the market left and why, in plain words ("The source
   *  market ended", "Price left the 3%–97% range") — the card shows the reason as is. */
  closedAtMs?: EpochMs | null;
  closeReason?: string | null;
  /** The market's index in the on-chain oracle record (0 = BTC; `GET /api/markets/registry.json`
   *  is the public list) — shown next to "Anchored on Chia". */
  oracleIndex?: number;
  /** The market's primary exchange (or source). Crypto is priced by the exchange of the asset itself: Coinbase
   *  for BTC, ETH, SOL, XRP and DOGE, Hyperliquid's spot market for HYPE; stocks, indices and commodities by
   *  Hyperliquid (dex `xyz`), odds by Polymarket. */
  source: "hyperliquid" | "coinbase" | "kraken" | "polymarket" | "synthetic";
  /** The exchange a round runs on when the primary cannot price its start (D-76: a round runs entirely on one
   *  exchange, chosen when it opens): Kraken behind Coinbase, Coinbase behind Hyperliquid spot. Null/absent: no
   *  backup. Which of the two a round runs on is that round's `source`. */
  backupSource?: "coinbase" | "kraken" | null;
  /** Null until the first tick arrives. */
  price: MarketPriceDto | null;
  /** One entry per series, shortest first. */
  series: MarketSeriesDto[];
  crowd: MarketCrowdBriefDto;
}

/** The lobby in one call — poll it; it is built from memory, no source round-trip. */
export interface MarketsDto {
  serverTimeMs: EpochMs;
  categories: CategoryDto[];
  /** Active markets, plus markets closed within the last 10 minutes (so a card can say why it
   *  left). Listing order. */
  markets: MarketDto[];
  /** The economy rules every market shares (D-102, `EconomyRules` on the server): the flat network fee, the guard on the herd fee and the stake
   *  tiers. The per-market part (`herdFeeBp`, `feeFactor`, `maxLeverage`) is on each series. Absent from a server that predates it: a page
   *  that needs them shows nothing rather than a copy of its own. */
  economy?: EconomyRulesDto;
}

/** One stake tier: a stake up to `upToPlay` PLAY (null: any bigger one) may take the market's highest leverage divided by `divisor`. */
export interface StakeTierDto {
  upToPlay: number | null;
  divisor: number;
}

export interface EconomyRulesDto {
  /** The flat network fee per bet, PLAY ("0.1"). */
  networkFeePlay: string;
  /** The herd fee never takes more than this percent of the stake ("20"). */
  herdFeeGuardPercent: string;
  /** Smallest stake first; the last tier has `upToPlay: null`. */
  stakeTiers: StakeTierDto[];
  /** D-106: the platform fee of a fixed-payout bet, percent of the stake ("3"), rounded up to 0.001 PLAY, charged on top of `networkFeePlay`. */
  fixedPlatformFeePercent?: string;
}

export interface PriceTickDto {
  atMs: EpochMs;
  price: PriceStr;
}

/** Ticks for the room's live chart, oldest first. */
export interface PriceSeriesDto {
  marketId: MarketId;
  priceDecimals: number;
  ticks: PriceTickDto[];
}

/** One finished 1-minute candle: `openTimeMs` is the start of the minute; prices are raw prices with the
 *  market's `priceDecimals` digits, `low <= open, close <= high`. */
export interface CandleDto {
  openTimeMs: EpochMs;
  open: PriceStr;
  high: PriceStr;
  low: PriceStr;
  close: PriceStr;
}

/** Finished 1-minute candles of a market's primary exchange, oldest first. A minute without a tick has no
 *  candle, so consecutive candles are not always one minute apart. */
export interface CandlesDto {
  marketId: MarketId;
  priceDecimals: number;
  candles: CandleDto[];
}

/** One pending bet or open position of the signed-in player, in any market — the "my positions"
 *  strip (D-71). Live P&L is computed client-side from `markets()` prices with
 *  `api/liquidation.ts`. */
export interface MyPositionDto extends WithBetMode {
  marketId: MarketId;
  symbol: string;
  roundId: RoundId;
  interval: Interval;
  /** Real length of the round in seconds (label: "3 MIN", "1 HOUR"). */
  durationSec: number;
  phase: "BETTING" | "RUNNING";
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  /** RUNNING only. Both are null while the round's opening price is not known yet
   *  (`RoundRunning.openPrice === null`). */
  entryPrice: PriceStr | null;
  liquidationPrice: PriceStr | null;
  /** RUNNING only: the position size the server opened, in lots, 8dp (`PositionDto.size`) — use it
   *  for live P&L instead of re-deriving it from the stake. Null in BETTING or while the opening
   *  price is unknown. */
  size?: string | null;
  /** RUNNING only: already liquidated on the path so far. */
  liquidated: boolean;
  /** BETTING: when the round starts. RUNNING: when it ends. */
  untilMs: EpochMs;
}

/** All markets' crowd accounts side by side (Т-6). */
export interface CrowdOverviewDto {
  totalCapital: UsdStr;
  totalStartingCapital: UsdStr;
  returnPct: number;
  markets: Array<{
    marketId: MarketId;
    symbol: string;
    /** `MarketDto.title` and `status` — a closed market keeps its row here after it has left
     *  `markets()`. */
    title: string;
    status: "ACTIVE" | "CLOSED";
    category: CategoryId;
    capital: UsdStr;
    returnPct: number;
    roundsTraded: number;
    roundsWon: number;
  }>;
}

// ---------------------------------------------------------------------------
// Ratings (RatingBook.java — D-9, D-18, D-28, D-30)
// ---------------------------------------------------------------------------

/** RatingBook.RichEntry — "богачи". `growth` is signed (can be negative). */
export interface RichEntryDto {
  rank: number;
  player: PlayerSummaryDto;
  growth: PlayAmountStr;
  roundsPlayed: number;
}

/** RatingBook.ProphetEntry — "пророки". */
export interface ProphetEntryDto {
  rank: number;
  player: PlayerSummaryDto;
  accuracy: number; // 0..1
  eligibleRounds: number;
  roundsPlayed: number;
}

/** Both ratings share one rounds-played threshold and cover both HOURLY and DAILY rounds
 *  together (D-30). */
export interface RatingsDto {
  season: SeasonId;
  roundsThreshold: number; // RatingBook.DEFAULT_ROUNDS_THRESHOLD, fixed at 10 by D-28
  rich: RichEntryDto[];
  prophets: ProphetEntryDto[];
}

/** The "This week" boards: the same two rankings as {@link RatingsDto}, over the settled rounds from Monday 00:00 UTC (`weekStartMs`) to now,
 *  with the same minimum of rounds (`roundsThreshold`, D-28). `rich` lists winners only (D-90): a player whose week is not above zero is not in it. */
export interface WeeklyRatingsDto {
  weekStartMs: EpochMs;
  roundsThreshold: number;
  rich: RichEntryDto[];
  prophets: ProphetEntryDto[];
}

// ---------------------------------------------------------------------------
// Home aggregate (D-34's fixed layout: open round + bet slip, then the crowd chart, then top
// ratings — предложение: one call to avoid three round-trips for a screen whose composition is
// itself a product decision, not something the UI assembles freely). Describes BTC only: the
// markets have `markets()`, `rounds(interval, marketId)` and `crowdAccount(marketId)`.
// ---------------------------------------------------------------------------

export interface HomeSummaryDto {
  hourly: RoundsPairDto;
  daily: RoundsPairDto;
  crowd: CrowdAccountDto;
  topRich: RichEntryDto[];
  topProphets: ProphetEntryDto[];
}

// ---------------------------------------------------------------------------
// Supply (economy stage C, C5): the public reconciliation. The platform reserve is TREASURY and HERD together —
// HERD alone is the platform's revenue and is never published (D-90).
// ---------------------------------------------------------------------------

export interface SupplyDto {
  /** False before the economy cutover: the books are not split into accounts yet. */
  economyActive: boolean;
  /** Everything ever issued: 100 000 000 PLAY. */
  issued: PlayAmountStr;
  /** The treasury coin on chain; null when the chain service cannot say. */
  onChainTreasury: PlayAmountStr | null;
  treasuryCoinId: string | null;
  /** Balances plus the stakes of bets in open rounds. */
  heldByPlayers: PlayAmountStr;
  /** The treasury and the platform's own account, together. */
  platformReserve: PlayAmountStr;
  /** Withdrawals confirmed on chain. */
  withdrawnOnChain: PlayAmountStr;
  /** The game's books add up (internal identity). */
  booksBalanced: boolean;
  /** The books agree with the chain (null: the chain is unknown). */
  chainConsistent: boolean | null;
  /** The circuit breaker is open: new bets are limited to a lower leverage. */
  circuitBreakerOpen: boolean;
  atMs: EpochMs;
}

// ---------------------------------------------------------------------------
// Server info (additive per the task's FIXED INTERFACE (1) — D-40, D-41, D-46, D-47, D-43).
// Lets the UI show, honestly, which mode it's talking to: a real backend always answers this
// (`mode: "live"` outside test mode, `"replay"` inside it, D-46), a `MockApi` build never does
// (see `mockApi.ts` — the method simply doesn't exist there, so the UI must treat a thrown/
// missing `serverInfo()` as "no server info to show", not as an error state).
// ---------------------------------------------------------------------------

export interface ServerInfoDto {
  mode: "live" | "replay";
  /** Where prices come from (Т-12): `live` — the sources' real current prices; `synthetic` — a
   *  deterministic random walk (CI, offline); `replay` — accelerated history (`mode: "replay"`). */
  priceSource?: "live" | "synthetic" | "replay";
  /** Active markets. */
  markets?: number;
  /** Set only when `mode === "replay"` (D-46: accelerated replay of real historical BTC prices
   *  in test mode). */
  replay?: {
    sourceFromIso: string;
    sourceToIso: string;
    speedup: number;
  };
  /** Length of the two series, in seconds — the same numbers `MarketSeriesDto.durationSec` carries
   *  per market; the shorter is keyed `HOURLY`, the longer `DAILY` whatever their real length. */
  roundDurationsSec: { HOURLY: number; DAILY: number };
  /** Count of the labelled test-mode bots playing alongside real players (D-46: 20). */
  bots: number;
  oracle: {
    network: "testnet11";
    launcherId: string;
    lastConfirmedSeq: number | null;
    lastConfirmedHeight: number | null;
    pendingRecords: number;
    operatorBalanceMojos: string | null;
    /** Lowest `seq` this server's database knows — records below it were published before this
     *  database existed and can only be checked on chain. Null until the first record. */
    firstSeq?: number | null;
  };
  serverTimeMs: EpochMs;
  /** PLAY CAT asset id ("0x" + 64 hex) of this deployment's issuance on `oracle.network` — what a
   *  wallet needs to show PLAY and to send a deposit. */
  playAssetId?: string;
  /** Whether the browser test wallet (D-45) is offered on the sign-in sheet. Absent → offered. */
  testWallet?: boolean;
  /** False while the server is rebuilding its state after a failure: engine-backed calls answer
   *  503 `ENGINE_NOT_READY` until it is true again. Absent → true. */
  engineReady?: boolean;
  /**
   * Additive (task item 2, D-55): a Reown/WalletConnect `projectId` set on the SERVER (env var),
   * overriding this build's own `VITE_WALLETCONNECT_PROJECT_ID` at runtime — lets Alex's Reown
   * registration reach a Docker image already built without it, no web rebuild/redeploy needed.
   * Undefined/empty means "no runtime override, fall back to the build-time env var" (
   * `wallet/walletConnect.ts`'s `getWalletConnectProjectId`), not "WalletConnect unavailable" —
   * only absence of BOTH means that. Note for whoever next touches `server/`: this field does not
   * exist there yet (no `server/` change was made by this task's web agent, out of its assigned
   * paths) — add `HERD_WALLETCONNECT_PROJECT_ID` (or similar) to `GameConfig`/`serverInfo` to wire
   * it up; until then this stays build-time-only, exactly as this field's own doc predicts.
   */
  walletConnectProjectId?: string;
}

// ---------------------------------------------------------------------------
// Feedback (D-81): one free-text message from a visitor, signed in or not
// ---------------------------------------------------------------------------

/** The most characters one feedback message may have (`FeedbackRequest.message`, counted as JavaScript counts a string:
 *  UTF-16 units — the form's `maxLength` and the server's limit are the same number). Client-side check only — the
 *  server is authoritative (D-81: the web side's own limit is 2000, the server refuses more). */
export const FEEDBACK_MAX_LENGTH = 2000;

/** Which build sent a feedback message: a browser tab, the installed web app (display-mode standalone), or the
 *  Android shell (Capacitor). Lets the operator tell the three apart; says nothing about the person. */
export type FeedbackClient = "web" | "app" | "pwa";

/**
 * `submitFeedback`'s one argument (D-81). Everything but `message` is context the browser can state by itself, and
 * every one of them may be `null` (not known, or not available in this browser): the player types a message and
 * nothing else — no email, no name. The country is NOT sent: the server works it out from the request's IP address
 * and does not keep the address. The signed-in player is not sent either: the server knows the session; the message
 * is stored with the player id when there is one. `submitFeedback` needs no session.
 *
 * Limits the web side keeps (the server may be stricter): `message` is trimmed, 1..{@link FEEDBACK_MAX_LENGTH};
 * `timeZone` an IANA name (`Intl.DateTimeFormat().resolvedOptions().timeZone`, at most 64 characters); `language`
 * `navigator.language` (at most 35); `page` the path of the screen the form was opened on — `location.pathname`,
 * never the query string or the hash (at most 200).
 */
export interface FeedbackRequest {
  message: string;
  timeZone: string | null;
  language: string | null;
  page: string | null;
  client: FeedbackClient | null;
}

/** What the server answers to a stored message: the id it gave it. Opaque (a number or a string) — the web does not use it. */
export interface FeedbackReceiptDto {
  id: string | number;
}

// ---------------------------------------------------------------------------
// Pagination (предложение — no source fixes a page size/cursor shape; kept generic and small)
// ---------------------------------------------------------------------------

export interface PageParams {
  limit?: number;
  /** Cursor: items strictly before this timestamp, newest first. */
  before?: EpochMs;
}

export interface Page<T> {
  items: T[];
  hasMore: boolean;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Codes the UI branches on to show a friendly message instead of a raw backend/Sage error
 * (docs/product/ux_walkthrough.md, вопрос 8). Each is sourced:
 * - STAKE_BELOW_MINIMUM / INSUFFICIENT_BALANCE / WITHDRAWAL_EXCEEDS_BALANCE: `LedgerBook`
 *   throws `IllegalArgumentException` for exactly these cases (`reserveStake`,
 *   `requestWithdrawal`).
 * - INVALID_LEVERAGE: the `Leverage` record's compact constructor.
 * - ROUND_NOT_OPEN_FOR_BETTING: `GameEngine.placeBet`/`cancelBet` take a `RoundWindow` but do
 *   not themselves check wall-clock time against it (round_engine_design.md §2) — rejecting a
 *   late bet is the Quarkus service's job (D-8); предложение for how that surfaces to the
 *   client.
 * - NOT_SIGNED_IN / CHALLENGE_EXPIRED_OR_UNKNOWN / SIGNATURE_INVALID: sign-in flow
 *   (week1_wallet_auth.md §4, steps a/d).
 */
export type ApiErrorCode =
  | "NOT_SIGNED_IN"
  | "ROUND_NOT_OPEN_FOR_BETTING"
  | "STAKE_BELOW_MINIMUM"
  | "INSUFFICIENT_BALANCE"
  | "INVALID_LEVERAGE"
  | "WITHDRAWAL_EXCEEDS_BALANCE"
  | "CHALLENGE_EXPIRED_OR_UNKNOWN"
  | "SIGNATURE_INVALID"
  | "MARKET_NOT_FOUND"
  | "MARKET_CLOSED"
  | "PRICE_UNAVAILABLE"
  | "RATE_LIMITED"
  /** HTTP 429 (economy stage B): more than 3 new players from one address in 24 hours. */
  | "REGISTRATION_LIMIT"
  /** HTTP 422 (economy stage B): the open stakes on this side of this round already reach the limit the treasury allows. */
  | "EXPOSURE_LIMIT_EXCEEDED"
  /** HTTP 503: the server is rebuilding its state after a failure. Transient — keep what is on
   *  screen, say "the server is restarting" and retry; never treat it as "no data". */
  | "ENGINE_NOT_READY"
  /** HTTP 404: no such player, round or request. */
  | "NOT_FOUND"
  /** HTTP 400: the nickname is not 3–24 characters of letters, digits, space, `_`, `.`, `-`. */
  | "INVALID_NICKNAME"
  /** HTTP 409 (D-106): the person already owns the most API bots allowed ({@link MAX_API_BOTS}). */
  | "BOT_LIMIT_REACHED"
  /** HTTP 401 (D-106): an `Authorization: Bearer` key that is unknown, malformed or revoked. Seen by a bot, never by the web app itself. */
  | "API_KEY_INVALID"
  /** HTTP 403 (D-106): a call an API key may not make (creating bots, withdrawals, profile changes, ...). */
  | "API_CALL_NOT_ALLOWED"
  /** HTTP 403 (D-106): an API bot's PLAY cannot be withdrawn to a wallet. */
  | "BOT_WITHDRAWAL_NOT_ALLOWED"
  /** Any other server code: the message is still the server's own text and is shown as is. */
  | "OTHER";

export class ApiRequestError extends Error {
  constructor(
    public readonly code: ApiErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

// ---------------------------------------------------------------------------
// The API itself
// ---------------------------------------------------------------------------

/**
 * Every method is async and rejects with an {@link ApiRequestError} on a known failure mode
 * (see {@link ApiErrorCode}) or a plain `Error` on transport/unexpected failure. Read methods
 * that depend on a signed-in player (`myAccount`, `myProfile`, …) reject with
 * `ApiRequestError("NOT_SIGNED_IN", …)` when there is no session, rather than returning a
 * nullable value — call `currentPlayer()` first to branch UI on sign-in state.
 */
export interface Api {
  // --- sign-in (D-4; mock-backed until a Reown projectId exists, see file header) ---
  authChallenge(req: AuthChallengeRequest): Promise<AuthChallengeDto>;
  authVerify(req: AuthVerifyRequest): Promise<AuthSessionDto>;
  currentPlayer(): Promise<PlayerSummaryDto | null>;
  setNickname(nickname: string): Promise<PlayerSummaryDto>;
  signOut(): Promise<void>;
  /** The signed-in player's invite code and how many players came through it. */
  myInvite(): Promise<InviteDto>;

  // --- bots of outside developers (D-106): for a signed-in person only; a bot itself plays through the API with its key ---
  /** The signed-in person's API bots, oldest first. */
  myBots(): Promise<ApiBotDto[]>;
  /** A new API bot (its own player with a starter pack) and its key. Rejects with `BOT_LIMIT_REACHED` past {@link MAX_API_BOTS}
   *  (message shown as is), `INVALID_NICKNAME` for a name that is not 3-24 letters, digits, space, `_`, `.`, `-`. */
  createBot(name: string): Promise<ApiBotKeyDto>;
  /** A new key for the bot; the old one stops working at once. */
  regenerateBotKey(botId: string): Promise<ApiBotKeyDto>;
  /** Revokes the bot's key: nothing opens the bot until a new key is generated. */
  revokeBotKey(botId: string): Promise<ApiBotDto>;
  /** The boards of the API bots only (same season and threshold rules as `ratings`, which leaves them out). */
  botRatings(season?: SeasonId): Promise<RatingsDto>;
  /** The "This week" boards of the API bots (Monday 00:00 UTC to now; see {@link WeeklyRatingsDto}). */
  botWeeklyRatings(): Promise<WeeklyRatingsDto>;

  // --- markets (D-69, D-70) ---
  markets(): Promise<MarketsDto>;
  /** Ticks of the last 15 minutes, or — with `sinceMs` — the ticks with `atMs > sinceMs`. At most
   *  600 points; when more exist the newest 600 are returned, thinned evenly so they still span
   *  the whole requested range. */
  priceSeries(marketId: MarketId, sinceMs?: EpochMs): Promise<PriceSeriesDto>;
  /** The finished 1-minute candles (OHLC) of the market's primary exchange, oldest first: the last `minutes`
   *  minutes (default 120, at most 179), without the minute in progress. A minute without a tick has no
   *  candle. Public, read from memory; the bots read their rules' history from it. */
  candles(marketId: MarketId, minutes?: number): Promise<CandlesDto>;
  referencePrice(marketId: MarketId): Promise<MarketReferenceDto>;
  /** Every pending bet and open position of the signed-in player, all markets. */
  myPositions(): Promise<MyPositionDto[]>;

  // --- rounds (D-8, D-13, D-19, D-22, D-23, D-32) ---
  /** `marketId` omitted → the BTC pair: the open market whose symbol is BTC (see {@link LiquidationPreviewRequest.marketId}). */
  rounds(interval: Interval, marketId?: MarketId): Promise<RoundsPairDto>;
  round(roundId: RoundId): Promise<RoundDto>;
  /**
   * Additive (server-side, D-62): the FULL live bet feed for `roundId`, public — no session
   * required, same visibility as the round itself during BETTING. Omit `sinceSeq` for the first
   * page, then pass the previous call's last `entries[].seq` to keep paging forward. Optional on
   * `Api`, same "graceful fallback" reasoning as `unseenResults`/`acknowledgeResults` below: a
   * `RealApi` build or server that predates this RPC leaves `api.betFeed` `undefined`, and a caller
   * that only needs the small always-present `RoundBetting.recentBets` preview never needs this at
   * all.
   */
  betFeed?(roundId: RoundId, sinceSeq?: number): Promise<BetFeedDto>;
  /** Same as `referencePrice` of the BTC pair — the open market whose symbol is BTC — kept for callers that predate markets. */
  btcReferencePrice(): Promise<MarketReferenceDto>;
  previewLiquidation(req: LiquidationPreviewRequest): Promise<LiquidationPreviewDto>;
  /** Also used to MODIFY an existing bet on the same round (D-13) — mirrors
   *  `GameEngine.placeBet`, which always refunds any prior bet by the same player before
   *  reserving the new one. */
  placeBet(req: PlaceBetRequest): Promise<BetDto>;
  cancelBet(roundId: RoundId): Promise<void>;

  // --- player account / ledger (D-2, D-3, D-6, D-12, D-16) ---
  myAccount(): Promise<PlayerAccountDto>;
  mySeasonRecord(season?: SeasonId): Promise<SeasonRecordDto>;
  myLedger(params?: PageParams): Promise<Page<LedgerEntryDto>>;
  /** Real on-chain withdrawal (D-53) — `amount` only; the destination is always the caller's own
   *  registered address (D-57), never a parameter. */
  requestWithdrawal(amount: PlayAmountStr): Promise<WithdrawalRequestDto>;
  /** Poll this with the `requestId` from {@link requestWithdrawal} until `state` is `CONFIRMED`
   *  or `FAILED` (`ux_spec.md` §8). */
  withdrawal(requestId: string): Promise<WithdrawalRequestDto>;
  /** The signed-in player's dedicated deposit address (D-53) — stable for as long as they're the
   *  same player (task's FIXED INTERFACE derives it from a fixed `playerIndex`), so the UI can
   *  cache it for the session instead of re-fetching on every render. */
  myDepositAddress(): Promise<DepositAddressDto>;

  // --- crowd account (D-5, D-14, D-17, D-25, D-26; one per market, Т-6) ---
  /** `marketId` omitted → the BTC pair: the open market whose symbol is BTC (see {@link LiquidationPreviewRequest.marketId}). */
  crowdAccount(marketId?: MarketId): Promise<CrowdAccountDto>;
  crowdOverview(): Promise<CrowdOverviewDto>;

  // --- ratings (D-9, D-18, D-28, D-30) ---
  ratings(season?: SeasonId): Promise<RatingsDto>;
  /** The "This week" boards of the people (and the house's bots): Monday 00:00 UTC to now, same minimum of rounds, winners only. */
  weeklyRatings(): Promise<WeeklyRatingsDto>;

  // --- profiles (D-15, D-29) ---
  publicProfile(playerId: string): Promise<PublicProfileDto>;
  myProfile(): Promise<PublicProfileDto>;

  // --- cancellations log (D-23, D-32) ---
  cancellations(params?: PageParams): Promise<Page<CancellationDto>>;

  // --- home aggregate (D-34): describes the BTC pair, the open market whose symbol is BTC (see above) ---
  home(): Promise<HomeSummaryDto>;

  // --- "while you were away" (task item, 2026-09-26 IA follow-up) ---
  /**
   * Additive proposal: settled positions the signed-in player has not yet been shown a result
   * moment for, across sessions/devices/reloads — reuses `ProfileRoundEntryDto`'s exact shape
   * (already enough to rebuild a `ResultEvent`, see `fx/resultEvent.ts`'s
   * `resultEventFromHistoryEntry`), newest first, same convention as `PublicProfileDto.history`.
   * Optional on `Api`, like `serverInfo` below: a `RealApi` build talking to a server that
   * predates this RPC simply gets `undefined` back from `api.unseenResults?.()`, and the caller
   * (`fx/useAwayReplay.ts`) falls back to showing nothing extra on open rather than guessing —
   * "fall back gracefully if absent" per the task. Unlike `serverInfo`, `MockApi` DOES implement
   * this one (see its own doc comment) so the away-replay flow has something real to demo.
   */
  unseenResults?(): Promise<ProfileRoundEntryDto[]>;
  /**
   * Marks the given rounds' results as shown — called after `unseenResults` above fires its
   * moments, AND after the live `useSettlementWatcher` fires one in real time (both paths must
   * ack, or a round shown live would show again as "unseen" on the next reload). Fire-and-forget
   * from every caller's point of view: a failed ack costs at most one repeated result moment on
   * the next reload, never a lost one, and never touches balances/ledger.
   */
  acknowledgeResults?(roundIds: RoundId[]): Promise<void>;

  // --- feedback (D-81) ---
  /**
   * Stores one message from a visitor (the feedback form, `components/FeedbackModal.tsx`). Open to everyone — no
   * session needed; with one, the server keeps the player id beside the message. Rejects with `ApiRequestError`
   * `RATE_LIMITED` (HTTP 429) when the sender writes too often; any other failure leaves the text with the sender,
   * who may try again. Not an engine read: it never reports the engine ready or not.
   */
  submitFeedback(req: FeedbackRequest): Promise<FeedbackReceiptDto>;

  /** Additive (task's FIXED INTERFACE (1)) — optional on the `Api` type itself: `MockApi`
   *  intentionally does not implement it (there is no "server" to report on in the mock era),
   *  so every call site must handle `api.serverInfo?.()` being `undefined` as well as the
   *  promise it returns being absent from a real backend that predates this field. */
  serverInfo?(): Promise<ServerInfoDto>;

  /** Economy stage C (C5, D-90): the public supply block — optional like `serverInfo` (the mock has no chain to reconcile with,
   *  and an older server has no such call). */
  supply?(): Promise<SupplyDto>;
}
