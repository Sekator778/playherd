/**
 * TypeScript mirror of the Quarkus HTTP API (Р-20) that the React frontend is built against,
 * itself a thin shell over the pure domain classes in
 * `engine/src/main/java/crowdvsmarket/engine/**` (Java). No runtime code lives here — only
 * types and the `Api` interface — so the UI can be built and tested against a mock
 * implementation before the real backend exists (Р-35). Swapping the mock for a real HTTP
 * client later changes zero call sites, by design.
 *
 * Naming follows the Java classes/fields 1:1 wherever a direct counterpart exists; every type
 * below carries a comment pointing at the Java class or the product decision (Р-N,
 * `docs/product/decisions.md`) that fixes its shape. Shapes that exist only because the UI
 * needs them (pagination, HTTP error codes, the BTC/anti-crowd comparison series, the
 * challenge/verify sign-in flow) are marked "предложение" and cite the research file they come
 * from, per this project's rule that non-sourced technical choices must be flagged.
 *
 * Sources: docs/product/decisions.md (Р-1…Р-35), docs/research/round_engine_design.md,
 * docs/research/week1_wallet_auth.md, docs/product/ux_walkthrough.md.
 */

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/** Interval.java: the two round timeframes ("BTC up/down", hourly and daily — Р-19). */
export type Interval = "HOURLY" | "DAILY";

/** Direction.java: which way a position bets the round closes. */
export type Direction = "UP" | "DOWN";

/** Leverage.java: integer 2..40 (Leverage.MIN/MAX, BTC tier0 — Р-33's slider range). */
export type Leverage = number;

/**
 * PlayAmount.java, serialized as a decimal string with exactly 3 fractional digits
 * (`PlayAmount.toMoney()`, scale 3 — mojos never lose precision; 1 PLAY = 1000 mojos,
 * `PlayAmount.MOJOS_PER_PLAY`). Non-negative everywhere except a signed ledger `delta`
 * (`LedgerEntryDto`) or a rating's `growth` (`RichEntryDto`), which are explicitly documented
 * as signed. Example: `"10000.000"` (starter pack, Р-12), `"100.000"` (minimum stake, Р-12).
 */
export type PlayAmountStr = string;

/**
 * A USD amount as a decimal string, scale 2 cents (`LiquidationCalculator`'s "money" scale).
 * Used for prices and for the crowd account's paper capital (Р-17) — the crowd account trades
 * paper USD, not PLAY, and never touches a player's PLAY balance (Р-5).
 */
export type UsdStr = string;

/** Epoch milliseconds, UTC (`RoundWindow`/`RoundClock` — no timezone/DST handling, Р-19). */
export type EpochMs = number;

/** `Season.id()`: `"YYYY-MM"`, UTC calendar month (Season.java, Р-10/Р-16). */
export type SeasonId = string;

/**
 * `RoundWindow.roundId()`: `${interval.code()}-${startMs}`, e.g. `"1h-1735689600000"` —
 * `interval.code()` is `"1h"`/`"1d"` (Interval.java), not the `Interval` string above. Treat as
 * opaque; never parse it client-side.
 */
export type RoundId = string;

// ---------------------------------------------------------------------------
// Engine constants the UI validates against before round-tripping to the server
// (client-side validation only — the server, via LedgerBook/Leverage, is authoritative)
// ---------------------------------------------------------------------------

/** LedgerBook.STARTER_PACK (Р-2, Р-12). */
export const STARTER_PACK_PLAY: PlayAmountStr = "10000.000";
/** LedgerBook.MIN_STAKE (Р-12) — the bet slip's minimum-stake validation. */
export const MIN_STAKE_PLAY: PlayAmountStr = "100.000";
/** Leverage.MIN — the bet slip's leverage slider lower bound (Р-33). */
export const LEVERAGE_MIN: Leverage = 2;
/** Leverage.MAX — the bet slip's leverage slider upper bound (Р-33). */
export const LEVERAGE_MAX: Leverage = 40;
/** RatingBook.DEFAULT_ROUNDS_THRESHOLD, fixed at 10 by Р-28 (common to both series, Р-30). */
export const RATING_ROUNDS_THRESHOLD = 10;
/** CrowdAccount.STARTING_CAPITAL (Р-17) — paper USD, not PLAY. */
export const CROWD_STARTING_CAPITAL_USD: UsdStr = "100000.00";
/** CrowdAccount.MARGIN_SHARE_OF_CAPITAL (Р-17). */
export const CROWD_MARGIN_SHARE_OF_CAPITAL = 0.10;

// ---------------------------------------------------------------------------
// Identity / profile (PlayerIdentity.java, Р-15, Р-29)
// ---------------------------------------------------------------------------

/** PlayerIdentity.java, minus the raw walletAddress split logic — the server sends the already
 *  shortened `displayName` (PlayerIdentity.displayName(), §4.7: "nickname (first6…last4)"). */
export interface PlayerSummaryDto {
  playerId: string;
  nickname: string;
  walletAddress: string;
  /** `"nickname (xch1qp…mqqqxw)"` — precomputed server-side, never re-derived in the UI. */
  displayName: string;
  /** Additive (task's FIXED INTERFACE (1)): set on the 20 labelled test-mode bots (Р-46) so the
   *  UI can badge them everywhere a `PlayerSummaryDto` is shown. Absent/false for a real player. */
  isBot?: boolean;
}

/** Public profile (Р-29): past rounds only (direction, leverage, outcome) — the player's
 *  currently pending bet, if any, is never included here (hidden until round start, Р-22),
 *  even when the viewer IS that player (use `myAccount`/`RoundBetting.myBet` for that instead). */
export interface PublicProfileDto {
  player: PlayerSummaryDto;
  seasonRecord: SeasonRecordDto | null; // null if the player has no record in the current season yet
  history: ProfileRoundEntryDto[]; // newest first
}

export interface ProfileRoundEntryDto {
  roundId: RoundId;
  interval: Interval;
  settledAtMs: EpochMs;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  liquidated: boolean;
  /** sign(close-open) match, independent of liquidation (Р-27); null for a flat round
   *  (close == open), excluded from the "пророки" ratio's denominator (§4.6). */
  directionCorrect: boolean | null;
  payout: PlayAmountStr; // 0 on liquidation (Р-7)
}

// ---------------------------------------------------------------------------
// Sign-in (Р-4; docs/research/week1_wallet_auth.md §4's challenge/verify flow).
// Until Alex registers a Reown/WalletConnect projectId, the shipped `Api` implementation is a
// mock that fabricates a testnet-looking address/signature locally instead of calling Sage —
// these types are still the real contract the mock and the eventual real backend both honor,
// so no UI code changes when the mock is swapped for the real thing.
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
}

export interface AuthSessionDto {
  player: PlayerSummaryDto;
  /** True on a brand-new player — the UI must immediately continue into the nickname-pick step
   *  (Р-15) before anything else, per the first-time flow. */
  isNewPlayer: boolean;
}

// ---------------------------------------------------------------------------
// Rounds (round_engine_design.md §2 — RoundWindow/RoundClock/GameEngine/BetBook; Р-8, Р-13,
// Р-19, Р-22, Р-23, Р-32)
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
 *  only exist once the round opens a `Position` at the fixed round-start price (Р-8). Never
 *  shown for another player before round start (Р-22) — only via `RoundBetting.myBet`. */
export interface BetDto {
  playerId: string;
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
}

/** Position.java — a bet turned into an actual position once the round starts, at its fixed
 *  entry price (Р-8). `pnl` is null until settled/liquidated (`Position.isSettled()`). */
export interface PositionDto {
  playerId: string; // Position.userId
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  size: string; // BTC size, 8dp (PositionSizing.SIZE_SCALE)
  entryPrice: UsdStr;
  liquidationPrice: UsdStr;
  pnl: UsdStr | null;
  /**
   * Additive (2026-09-25 UI bug fix — see `docs/product/ux_spec.md` §4/§7, "net result"): the
   * original PLAY stake this position was opened with (`Bet.stake` before `GameEngine` turns it
   * into `Position.size` at round start, round_engine_design.md §4.9). Without this, the only way
   * to show a position's net PLAY change (`payout - stake`) is `initialMargin(size, entryPrice,
   * leverage)` (`liquidation.ts`) — the exact inverse of `sizeForMargin`, but lossy across the two
   * roundings (5dp size, then 2dp margin): e.g. stake 100 PLAY at x10 leverage/entry 83451.00
   * round-trips to 99.97, not 100.00. Optional so a `RealApi` build that predates this field still
   * works (falls back to that derivation, `api/outcome.ts`'s `positionStakeStr`) — but the server
   * should start sending it since it already has the exact number (`Bet.stake`) with no need to
   * derive anything. */
  stake?: PlayAmountStr;
}

/** A position as shown to OTHER players once revealed at round start (Р-22): identity attached,
 *  plus the resolved outcome once known. */
export interface PublicPositionDto extends PositionDto {
  player: PlayerSummaryDto;
  liquidated: boolean;
  /** Set once the round settles; PLAY, not USD (`PlayerRoundOutcome.payout` via `PlayAmount`).
   *  0 PLAY on liquidation (Р-7). Null while the round is still RUNNING. */
  payout: PlayAmountStr | null;
  /** Derived: sign(close-open) match, independent of liquidation (Р-27, §4.6). Null while
   *  RUNNING, or for a flat round once settled. */
  directionCorrect: boolean | null;
}

/** CrowdAccount.RoundResult, for a round still RUNNING (opened, not yet resolved) — distinct
 *  from `CrowdRoundResultDto` because the live version is open for the round's whole real-time
 *  duration, unlike the historical-replay path where open+resolve happen in the same call. */
export interface CrowdRoundPositionDto {
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage; // stake-weighted average of the majority side, HALF_UP, clamped 2..40 (Р-14)
  margin: UsdStr; // 10% of capitalBefore (Р-17)
  entryPrice: UsdStr;
  liquidationPrice: UsdStr;
}

/** CrowdAccount.RoundResult, once the round has settled. `traded=false` (all fields but
 *  `capitalBefore`/`capitalAfter` null) for a tie or zero bets on both sides (Р-5). */
export interface CrowdRoundResultDto {
  roundId: RoundId;
  traded: boolean;
  direction: Direction | null;
  leverage: Leverage | null;
  margin: UsdStr | null;
  entryPrice: UsdStr | null;
  liquidated: boolean;
  capitalBefore: UsdStr;
  capitalAfter: UsdStr;
}

/** Р-32/Р-23: an operator cancellation (bad oracle read) or an automatic one (no price could be
 *  read within 1 hour) — every cancellation is publicly visible with its reason on the round
 *  page, regardless of cause. */
export interface CancellationDto {
  roundId: RoundId;
  reason: string;
  cancelledAtMs: EpochMs;
  /** Count of bets refunded, including any that would already have been liquidated (Р-23:
   *  "все ставки возвращаются, включая ликвидированные"). */
  refundedBetCount: number;
}

interface RoundBase {
  roundId: RoundId;
  window: RoundWindowDto;
  asset: "BTC";
}

export interface RoundBetting extends RoundBase {
  phase: "BETTING";
  /** Only the headcount is public before round start (Р-22) — no directions, no amounts. */
  participantCount: number;
  /** The signed-in viewer's own pending bet on this round, if any — private to them, and the
   *  basis for the "already bet this round" edit/cancel state (Р-13). Null if signed out or has
   *  no bet yet. */
  myBet: BetDto | null;
}

export interface RoundRunning extends RoundBase {
  phase: "RUNNING";
  openPrice: UsdStr;
  /** Revealed at round start, together with the crowd position (Р-22). */
  positions: PublicPositionDto[];
  /** Null only for a tie / zero bets on both sides (Р-5). */
  crowd: CrowdRoundPositionDto | null;
}

export interface RoundSettled extends RoundBase {
  phase: "SETTLED";
  openPrice: UsdStr;
  closePrice: UsdStr;
  positions: PublicPositionDto[];
  crowd: CrowdRoundResultDto | null;
  /** Additive (task's FIXED INTERFACE (1), Р-41): "0x"+sha256 hex of the canonical results JSON
   *  published in the same oracle record as this round's close. Optional — absent until the
   *  oracle spend confirms on testnet11 (`ServerInfoDto.oracle.pendingRecords`). Verify at
   *  `GET /api/rounds/{roundId}/results.json`. */
  resultsHash?: string;
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

/** Р-19: "всегда один идущий и один открытый для ставок раунд" per series. `betting` is
 *  expected to be `RoundBetting` — it can be `RoundCancelled` only if the operator cancelled it
 *  early (Р-32), before it ever started running. */
export interface RoundsPairDto {
  interval: Interval;
  running: RoundDto;
  betting: RoundBetting | RoundCancelled;
}

// ---------------------------------------------------------------------------
// Liquidation preview (Р-7, Р-33: shown BEFORE the bet is placed)
// ---------------------------------------------------------------------------

/** The current BTC reference price — used ONLY to preview a liquidation price before a bet is
 *  placed. The position's REAL entry price is fixed at round start (Р-8) and can differ from
 *  this preview price once the round actually opens; the UI must say so (see
 *  docs/product/ux_spec.md, "Round"/"Bet slip"). */
export interface MarketReferenceDto {
  price: UsdStr;
  asOfMs: EpochMs;
}

export interface LiquidationPreviewRequest {
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
  /** Omit to let the server use its own current reference price. */
  referencePrice?: UsdStr;
}

/** Mirrors `LiquidationCalculator.liquidationPrice` computed against a reference price, not a
 *  real entry price yet — see `MarketReferenceDto`'s caveat. */
export interface LiquidationPreviewDto {
  entryPriceUsed: UsdStr;
  liquidationPrice: UsdStr;
  /** e.g. `0.025` = 2.5% adverse move from `entryPriceUsed` to `liquidationPrice` — the "%
   *  move to liquidation" figure Р-33 requires shown before the bet. */
  percentToLiquidation: number;
}

export interface PlaceBetRequest {
  roundId: RoundId;
  direction: Direction;
  leverage: Leverage;
  stake: PlayAmountStr;
}

// ---------------------------------------------------------------------------
// Player account / ledger (LedgerBook.java, PlayAmount.java, SeasonRecord.java — Р-2, Р-3,
// Р-6, Р-12, Р-16)
// ---------------------------------------------------------------------------

export interface PlayerAccountDto {
  playerId: string;
  balance: PlayAmountStr;
  currentSeason: SeasonId;
  /** Additive (2026-09-25 bug fix, backend): total stake currently sitting in bets that have not
   *  settled yet (the BETTING and RUNNING rounds of both series) — already debited from `balance`
   *  but not yet won or lost. Optional so a `RealApi` build that predates this field still works
   *  (falls back to not showing it) — but the server should start sending it. */
  inPlay?: PlayAmountStr;
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
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "WITHDRAWAL_REFUND";

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

/** WithdrawalState.java: requested → confirmed | failed (Р-6). */
export type WithdrawalState = "REQUESTED" | "CONFIRMED" | "FAILED";
/** DepositState.java: requested → confirmed | failed (Р-6). */
export type DepositState = "REQUESTED" | "CONFIRMED" | "FAILED";

/** WithdrawalRequest.java — balance is already debited (escrowed) at REQUESTED (§4.8). */
export interface WithdrawalRequestDto {
  requestId: string;
  playerId: string;
  amount: PlayAmountStr;
  state: WithdrawalState;
  requestedAtMs: EpochMs;
}

/** DepositRequest.java — nothing is credited until CONFIRMED. */
export interface DepositRequestDto {
  requestId: string;
  playerId: string;
  amount: PlayAmountStr;
  state: DepositState;
  requestedAtMs: EpochMs;
}

// ---------------------------------------------------------------------------
// Crowd account (CrowdAccount.java — Р-5, Р-14, Р-17, Р-25, Р-26)
// ---------------------------------------------------------------------------

/**
 * One round's point on the crowd chart. `btcHoldCapital`/`antiCrowdCapital` — предложение: no
 * `BtcHoldAccount`/`AntiCrowdAccount` class exists in `engine/` yet, only `CrowdAccount`
 * itself. Р-26 fixes that these two comparison series must exist and their starting capital
 * ($100 000, same as the crowd account) and mechanics ("тот же счёт, всегда против
 * большинства" for anti-crowd; a flat buy-and-hold for the BTC line) — the per-round DTO shape
 * here is this contract's proposal for how the backend will compute and serve them, modelled
 * as a small sibling engine class per round rather than re-deriving them from candle data on
 * every request. To confirm before backend work starts on this endpoint.
 */
export interface CrowdHistoryPointDto {
  roundId: RoundId;
  atMs: EpochMs; // matches GameEngine.runRound's settledAt (window.endMs() - 1)
  /** Marks a season boundary on the chart (Р-25); null on every other point. */
  seasonBoundary: SeasonId | null;
  crowdCapital: UsdStr;
  btcHoldCapital: UsdStr;
  antiCrowdCapital: UsdStr;
}

export interface SeasonReturnDto {
  season: SeasonId;
  /** e.g. `0.034` = +3.4% (Р-25: "рядом доходность за каждый сезон"). */
  returnPct: number;
}

export interface CrowdAccountDto {
  capital: UsdStr;
  /** One continuous series since day one — seasons never reset it (Р-25). Oldest first. */
  history: CrowdHistoryPointDto[];
  seasonReturns: SeasonReturnDto[];
}

// ---------------------------------------------------------------------------
// Ratings (RatingBook.java — Р-9, Р-18, Р-28, Р-30)
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
 *  together (Р-30). */
export interface RatingsDto {
  season: SeasonId;
  roundsThreshold: number; // RatingBook.DEFAULT_ROUNDS_THRESHOLD, fixed at 10 by Р-28
  rich: RichEntryDto[];
  prophets: ProphetEntryDto[];
}

// ---------------------------------------------------------------------------
// Home aggregate (Р-34's fixed layout: open round + bet slip, then the crowd chart, then top
// ratings — предложение: one call to avoid three round-trips for a screen whose composition is
// itself a product decision, not something the UI assembles freely)
// ---------------------------------------------------------------------------

export interface HomeSummaryDto {
  hourly: RoundsPairDto;
  daily: RoundsPairDto;
  crowd: CrowdAccountDto;
  topRich: RichEntryDto[];
  topProphets: ProphetEntryDto[];
}

// ---------------------------------------------------------------------------
// Server info (additive per the task's FIXED INTERFACE (1) — Р-40, Р-41, Р-46, Р-47, Р-43).
// Lets the UI show, honestly, which mode it's talking to: a real backend always answers this
// (`mode: "live"` outside test mode, `"replay"` inside it, Р-46), a `MockApi` build never does
// (see `mockApi.ts` — the method simply doesn't exist there, so the UI must treat a thrown/
// missing `serverInfo()` as "no server info to show", not as an error state).
// ---------------------------------------------------------------------------

export interface ServerInfoDto {
  mode: "live" | "replay";
  /** Set only when `mode === "replay"` (Р-46: accelerated replay of real historical BTC prices
   *  in test mode). */
  replay?: {
    sourceFromIso: string;
    sourceToIso: string;
    speedup: number;
  };
  roundDurationsSec: { HOURLY: number; DAILY: number };
  /** Count of the labelled test-mode bots playing alongside real players (Р-46: 20). */
  bots: number;
  oracle: {
    network: "testnet11";
    launcherId: string;
    lastConfirmedSeq: number | null;
    lastConfirmedHeight: number | null;
    pendingRecords: number;
    operatorBalanceMojos: string | null;
  };
  serverTimeMs: EpochMs;
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
 *   late bet is the Quarkus service's job (Р-8); предложение for how that surfaces to the
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
  | "SIGNATURE_INVALID";

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
  // --- sign-in (Р-4; mock-backed until a Reown projectId exists, see file header) ---
  authChallenge(req: AuthChallengeRequest): Promise<AuthChallengeDto>;
  authVerify(req: AuthVerifyRequest): Promise<AuthSessionDto>;
  currentPlayer(): Promise<PlayerSummaryDto | null>;
  setNickname(nickname: string): Promise<PlayerSummaryDto>;
  signOut(): Promise<void>;

  // --- rounds (Р-8, Р-13, Р-19, Р-22, Р-23, Р-32) ---
  rounds(interval: Interval): Promise<RoundsPairDto>;
  round(roundId: RoundId): Promise<RoundDto>;
  btcReferencePrice(): Promise<MarketReferenceDto>;
  previewLiquidation(req: LiquidationPreviewRequest): Promise<LiquidationPreviewDto>;
  /** Also used to MODIFY an existing bet on the same round (Р-13) — mirrors
   *  `GameEngine.placeBet`, which always refunds any prior bet by the same player before
   *  reserving the new one. */
  placeBet(req: PlaceBetRequest): Promise<BetDto>;
  cancelBet(roundId: RoundId): Promise<void>;

  // --- player account / ledger (Р-2, Р-3, Р-6, Р-12, Р-16) ---
  myAccount(): Promise<PlayerAccountDto>;
  mySeasonRecord(season?: SeasonId): Promise<SeasonRecordDto>;
  myLedger(params?: PageParams): Promise<Page<LedgerEntryDto>>;
  requestWithdrawal(amount: PlayAmountStr): Promise<WithdrawalRequestDto>;
  withdrawal(requestId: string): Promise<WithdrawalRequestDto>;
  requestDeposit(amount: PlayAmountStr): Promise<DepositRequestDto>;
  deposit(requestId: string): Promise<DepositRequestDto>;

  // --- crowd account (Р-5, Р-14, Р-17, Р-25, Р-26) ---
  crowdAccount(): Promise<CrowdAccountDto>;

  // --- ratings (Р-9, Р-18, Р-28, Р-30) ---
  ratings(season?: SeasonId): Promise<RatingsDto>;

  // --- profiles (Р-15, Р-29) ---
  publicProfile(playerId: string): Promise<PublicProfileDto>;
  myProfile(): Promise<PublicProfileDto>;

  // --- cancellations log (Р-23, Р-32) ---
  cancellations(params?: PageParams): Promise<Page<CancellationDto>>;

  // --- home aggregate (Р-34) ---
  home(): Promise<HomeSummaryDto>;

  /** Additive (task's FIXED INTERFACE (1)) — optional on the `Api` type itself: `MockApi`
   *  intentionally does not implement it (there is no "server" to report on in the mock era),
   *  so every call site must handle `api.serverInfo?.()` being `undefined` as well as the
   *  promise it returns being absent from a real backend that predates this field. */
  serverInfo?(): Promise<ServerInfoDto>;
}
