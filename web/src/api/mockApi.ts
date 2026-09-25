/**
 * `Api` implementation backed entirely by seeded/deterministic local data (Р-35) — no network
 * calls. A single instance (`mockApi`, exported at the bottom) holds the only real mutable state
 * in the whole mock: the signed-in demo player's identity, balance, ledger and own bets. Every
 * NPC/crowd/round number is otherwise a pure function of the simulated clock (`clock.ts`) and a
 * seed (`mockPlayers.ts`, `mockRounds.ts`, `mockCrowdHistory.ts`).
 *
 * Sign-in is mocked behind the exact interface the real WalletConnect flow will use
 * (`docs/research/week1_wallet_auth.md` §4): `authChallenge`/`authVerify` fabricate an address
 * and accept any "signature" instead of round-tripping through Sage — swapping this file for a
 * real HTTP client later changes no call site (contract.ts's whole reason to exist).
 *
 * Every connect is treated as a brand-new player (`isNewPlayer: true`) so the first-time flow
 * (`ux_spec.md` §10) is always the one being demoed; `signOut` resets all mutable state.
 */
import {
  ApiRequestError,
  LEVERAGE_MAX,
  LEVERAGE_MIN,
  MIN_STAKE_PLAY,
  RATING_ROUNDS_THRESHOLD,
  STARTER_PACK_PLAY,
  type Api,
  type AuthChallengeDto,
  type AuthChallengeRequest,
  type AuthSessionDto,
  type AuthVerifyRequest,
  type BetDto,
  type CancellationDto,
  type DepositRequestDto,
  type DepositState,
  type HomeSummaryDto,
  type LedgerEntryDto,
  type LedgerEntryType,
  type LiquidationPreviewDto,
  type LiquidationPreviewRequest,
  type MarketReferenceDto,
  type Page,
  type PageParams,
  type PlaceBetRequest,
  type PlayerAccountDto,
  type PlayerSummaryDto,
  type ProfileRoundEntryDto,
  type PublicProfileDto,
  type RatingsDto,
  type RoundDto,
  type RoundsPairDto,
  type SeasonId,
  type SeasonRecordDto,
  type WithdrawalRequestDto,
  type WithdrawalState,
} from "./contract";
import { btcPriceStrAt, nextWindow, parseRoundId, roundIdFor, simNowMs, windowFor } from "./clock";
import { crowdAccount as buildCrowdAccount } from "./mockCrowdHistory";
import {
  CURRENT_SEASON,
  computeSeasonRecord,
  fakeAddress,
  findNpcIndexByPlayerId,
  npcPublicProfile,
  prophetRatings,
  richRatings,
} from "./mockPlayers";
import {
  buildBettingRound,
  buildCancelledRound,
  buildRunningRound,
  buildSettledRound,
  settleOwnBet,
  type OwnBetInfo,
} from "./mockRounds";
import { hashSeed, mulberry32 } from "./rng";
import {
  LONG,
  SHORT,
  liquidationPrice,
  percentToLiquidation,
  sizeForMargin,
  type Side,
} from "./liquidation";

const MOCK_PLAYER_ID = "me";
const CHALLENGE_TTL_MS = 5 * 60 * 1000;

/** One fixed, always-reachable cancelled round (Р-23/Р-32) — a real past hourly window, a day
 *  before the demo anchor, so it is always in the past regardless of how long the session runs. */
const CANCELLED_WINDOW = windowFor("HOURLY", Date.UTC(2026, 8, 24, 9, 0, 0));
const CANCELLED_ROUND_ID = roundIdFor(CANCELLED_WINDOW);
const CANCELLED_REASON =
  "Automatic cancellation: no reliable Hyperliquid price could be read within 1 hour of this round's close, so it could not be settled (Р-23). All bets were refunded.";
const CANCELLED_AT_MS = CANCELLED_WINDOW.endMs + 55 * 60 * 1000;
const CANCELLED_REFUNDED_COUNT = 19;

type WithdrawalRecord = WithdrawalRequestDto;
type DepositRecord = DepositRequestDto;

class MockApi implements Api {
  private player: PlayerSummaryDto | null = null;
  private balance = 0;
  private ledger: LedgerEntryDto[] = [];
  private ledgerSeq = 0;
  private myBets = new Map<string, BetDto>(); // roundId -> bet, kept even after settlement
  private settledRoundIds = new Set<string>();
  private myHistory: ProfileRoundEntryDto[] = [];
  private challenges = new Map<string, { message: string; expiresAtMs: number; address: string }>();
  private withdrawals = new Map<string, WithdrawalRecord>();
  private deposits = new Map<string, DepositRecord>();

  // -------------------------------------------------------------------------
  // Settlement sweep — applied lazily whenever any method reads state, since the simulated
  // clock advances between calls without any push/subscription mechanism.
  // -------------------------------------------------------------------------

  private processSettlements(): void {
    const now = simNowMs();
    for (const [roundId, bet] of this.myBets) {
      if (this.settledRoundIds.has(roundId)) continue;
      const parsed = parseRoundId(roundId);
      if (!parsed) continue;
      const window = windowFor(parsed.interval, parsed.startMs);
      if (now < window.endMs) continue;
      const outcome = settleOwnBet(bet, window);
      this.settledRoundIds.add(roundId);
      const payoutNum = Number(outcome.payout);
      this.appendLedger("SETTLEMENT", payoutNum, roundId);
      this.balance += payoutNum;
      this.myHistory.unshift({
        roundId,
        interval: parsed.interval,
        settledAtMs: window.endMs - 1,
        direction: bet.direction,
        leverage: bet.leverage,
        stake: bet.stake,
        liquidated: outcome.liquidated,
        directionCorrect: outcome.directionCorrect,
        payout: outcome.payout,
      });
    }
  }

  private appendLedger(type: LedgerEntryType, delta: number, roundId: string | null): void {
    this.ledgerSeq += 1;
    this.ledger.unshift({
      sequence: this.ledgerSeq,
      type,
      delta: delta.toFixed(3),
      roundId,
      atMs: simNowMs(),
    });
  }

  private requireSignedIn(): PlayerSummaryDto {
    if (!this.player) throw new ApiRequestError("NOT_SIGNED_IN", "Connect your wallet first.");
    return this.player;
  }

  private ownBetInfo(roundId: string): OwnBetInfo | null {
    const bet = this.myBets.get(roundId);
    if (!bet || !this.player) return null;
    return { bet, player: this.player };
  }

  // -------------------------------------------------------------------------
  // Sign-in (Р-4; week1_wallet_auth.md §4)
  // -------------------------------------------------------------------------

  async authChallenge(req: AuthChallengeRequest): Promise<AuthChallengeDto> {
    const now = simNowMs();
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
    const address = req.address ?? fakeAddress(mulberry32(hashSeed(nonce) ^ Date.now()));
    const expiresAtMs = now + CHALLENGE_TTL_MS;
    const message = [
      "Herd — sign in",
      "Domain: herd.demo",
      `Address: ${address}`,
      "Network: testnet11",
      `Nonce: ${nonce}`,
      `Issued: ${new Date(now).toISOString()}`,
      `Expires: ${new Date(expiresAtMs).toISOString()}`,
    ].join("\n");
    this.challenges.set(nonce, { message, expiresAtMs, address });
    return { nonce, message, expiresAtMs };
  }

  async authVerify(req: AuthVerifyRequest): Promise<AuthSessionDto> {
    const challenge = this.challenges.get(req.nonce);
    if (!challenge || simNowMs() > challenge.expiresAtMs) {
      throw new ApiRequestError("CHALLENGE_EXPIRED_OR_UNKNOWN", "This sign-in request has expired — try again.");
    }
    this.challenges.delete(req.nonce);
    // Mock trust boundary: a real backend verifies `signature` against `publicKey`/`address`
    // (week1_wallet_auth.md §4, steps c/d) — the mock accepts whatever the mock wallet sent.
    this.player = {
      playerId: MOCK_PLAYER_ID,
      nickname: "",
      walletAddress: req.address,
      displayName: req.address,
    };
    this.balance = 0;
    this.ledger = [];
    this.ledgerSeq = 0;
    this.myBets.clear();
    this.settledRoundIds.clear();
    this.myHistory = [];
    return { player: this.player, isNewPlayer: true };
  }

  async currentPlayer(): Promise<PlayerSummaryDto | null> {
    return this.player;
  }

  async setNickname(nickname: string): Promise<PlayerSummaryDto> {
    const player = this.requireSignedIn();
    const displayName = `${nickname} (${player.walletAddress.slice(0, 6)}…${player.walletAddress.slice(-4)})`;
    this.player = { ...player, nickname, displayName };
    // ensureSeasonPack (round_engine_design.md §3): lazily granted on first activity.
    if (this.ledger.length === 0) {
      this.appendLedger("STARTER_PACK", Number(STARTER_PACK_PLAY), null);
      this.balance += Number(STARTER_PACK_PLAY);
    }
    return this.player;
  }

  async signOut(): Promise<void> {
    this.player = null;
    this.balance = 0;
    this.ledger = [];
    this.ledgerSeq = 0;
    this.myBets.clear();
    this.settledRoundIds.clear();
    this.myHistory = [];
    this.withdrawals.clear();
    this.deposits.clear();
  }

  // -------------------------------------------------------------------------
  // Rounds (Р-8, Р-13, Р-19, Р-22, Р-23, Р-32)
  // -------------------------------------------------------------------------

  async rounds(interval: "HOURLY" | "DAILY"): Promise<RoundsPairDto> {
    this.processSettlements();
    const now = simNowMs();
    const runningWindow = windowFor(interval, now);
    const bettingWindow = nextWindow(runningWindow);
    const capitalBefore = Number(buildCrowdAccount(now).capital);
    const running = buildRunningRound(runningWindow, capitalBefore, this.ownBetInfo(roundIdFor(runningWindow)));
    const betting = buildBettingRound(bettingWindow, this.ownBetInfo(roundIdFor(bettingWindow)));
    return { interval, running, betting };
  }

  async round(roundId: string): Promise<RoundDto> {
    this.processSettlements();
    if (roundId === CANCELLED_ROUND_ID) {
      return buildCancelledRound(CANCELLED_WINDOW, CANCELLED_REASON, CANCELLED_REFUNDED_COUNT, CANCELLED_AT_MS);
    }
    const parsed = parseRoundId(roundId);
    if (!parsed) throw new Error("Round not found.");
    const window = windowFor(parsed.interval, parsed.startMs);
    const now = simNowMs();
    const capitalBefore = Number(buildCrowdAccount(Math.min(now, window.startMs)).capital);
    const own = this.ownBetInfo(roundId);
    if (now < window.startMs) return buildBettingRound(window, own);
    if (now < window.endMs) return buildRunningRound(window, capitalBefore, own);
    return buildSettledRound(window, capitalBefore, own);
  }

  async btcReferencePrice(): Promise<MarketReferenceDto> {
    const now = simNowMs();
    return { price: btcPriceStrAt(now), asOfMs: now };
  }

  async previewLiquidation(req: LiquidationPreviewRequest): Promise<LiquidationPreviewDto> {
    if (req.leverage < LEVERAGE_MIN || req.leverage > LEVERAGE_MAX) {
      throw new ApiRequestError("INVALID_LEVERAGE", `Leverage must be between ${LEVERAGE_MIN} and ${LEVERAGE_MAX}.`);
    }
    const entryPriceUsed = req.referencePrice ?? btcPriceStrAt(simNowMs());
    const side: Side = req.direction === "UP" ? LONG : SHORT;
    const size = sizeForMargin(req.stake, req.leverage, entryPriceUsed);
    const liqPrice = liquidationPrice(size, entryPriceUsed, req.leverage, side);
    return {
      entryPriceUsed,
      liquidationPrice: liqPrice.toFixed(2),
      percentToLiquidation: percentToLiquidation(entryPriceUsed, liqPrice),
    };
  }

  async placeBet(req: PlaceBetRequest): Promise<BetDto> {
    this.processSettlements();
    const player = this.requireSignedIn();
    const parsed = parseRoundId(req.roundId);
    const now = simNowMs();
    const currentBettingId = parsed ? roundIdFor(nextWindow(windowFor(parsed.interval, now))) : null;
    if (!parsed || currentBettingId !== req.roundId) {
      throw new ApiRequestError("ROUND_NOT_OPEN_FOR_BETTING", "Betting just closed for this round.");
    }
    if (req.leverage < LEVERAGE_MIN || req.leverage > LEVERAGE_MAX) {
      throw new ApiRequestError("INVALID_LEVERAGE", `Leverage must be between ${LEVERAGE_MIN} and ${LEVERAGE_MAX}.`);
    }
    const stakeNum = Number(req.stake);
    if (stakeNum < Number(MIN_STAKE_PLAY)) {
      throw new ApiRequestError("STAKE_BELOW_MINIMUM", "Minimum stake is 100 PLAY.");
    }
    // GameEngine.placeBet always refunds any prior bet on this round before reserving the new
    // one (round_engine_design.md §4.9) — so an increased stake against the freed balance isn't
    // wrongly rejected.
    const old = this.myBets.get(req.roundId);
    const balanceAfterRefund = this.balance + (old ? Number(old.stake) : 0);
    if (stakeNum > balanceAfterRefund) {
      throw new ApiRequestError(
        "INSUFFICIENT_BALANCE",
        `Not enough PLAY — you have ${balanceAfterRefund.toFixed(3)} PLAY, this bet needs ${stakeNum.toFixed(3)} PLAY.`,
      );
    }
    if (old) {
      this.appendLedger("STAKE_REFUND", Number(old.stake), req.roundId);
      this.balance += Number(old.stake);
    }
    this.appendLedger("STAKE_DEBIT", -stakeNum, req.roundId);
    this.balance -= stakeNum;
    const bet: BetDto = {
      playerId: player.playerId,
      roundId: req.roundId,
      direction: req.direction,
      leverage: req.leverage,
      stake: stakeNum.toFixed(3),
    };
    this.myBets.set(req.roundId, bet);
    return bet;
  }

  async cancelBet(roundId: string): Promise<void> {
    this.processSettlements();
    this.requireSignedIn();
    const bet = this.myBets.get(roundId);
    if (!bet) return;
    const parsed = parseRoundId(roundId);
    const now = simNowMs();
    const stillBetting = parsed ? roundIdFor(nextWindow(windowFor(parsed.interval, now))) === roundId : false;
    if (!stillBetting) {
      throw new ApiRequestError("ROUND_NOT_OPEN_FOR_BETTING", "Betting already closed for this round.");
    }
    this.appendLedger("STAKE_REFUND", Number(bet.stake), roundId);
    this.balance += Number(bet.stake);
    this.myBets.delete(roundId);
  }

  // -------------------------------------------------------------------------
  // Player account / ledger (Р-2, Р-3, Р-6, Р-12, Р-16)
  // -------------------------------------------------------------------------

  async myAccount(): Promise<PlayerAccountDto> {
    this.processSettlements();
    const player = this.requireSignedIn();
    return { playerId: player.playerId, balance: this.balance.toFixed(3), currentSeason: CURRENT_SEASON };
  }

  async mySeasonRecord(season?: SeasonId): Promise<SeasonRecordDto> {
    this.processSettlements();
    this.requireSignedIn();
    return computeSeasonRecord(this.myHistory, season ?? CURRENT_SEASON, {
      baselineBalance: "0.000",
      deposited: "0.000",
      withdrawn: "0.000",
    });
  }

  async myLedger(params?: PageParams): Promise<Page<LedgerEntryDto>> {
    this.processSettlements();
    this.requireSignedIn();
    return paginate(this.ledger, params, (e) => e.atMs);
  }

  async requestWithdrawal(amount: string): Promise<WithdrawalRequestDto> {
    this.requireSignedIn();
    const amountNum = Number(amount);
    if (amountNum > this.balance) {
      throw new ApiRequestError("WITHDRAWAL_EXCEEDS_BALANCE", `You only have ${this.balance.toFixed(3)} PLAY.`);
    }
    this.balance -= amountNum; // debited at REQUESTED, escrowed (round_engine_design.md §4.8)
    this.appendLedger("WITHDRAWAL", -amountNum, null);
    const requestId = `wd-${this.withdrawals.size + 1}-${Date.now()}`;
    const record: WithdrawalRecord = {
      requestId,
      playerId: this.player!.playerId,
      amount: amountNum.toFixed(3),
      state: "REQUESTED",
      requestedAtMs: simNowMs(),
    };
    this.withdrawals.set(requestId, record);
    this.scheduleWithdrawalOutcome(requestId, amountNum);
    return record;
  }

  private scheduleWithdrawalOutcome(requestId: string, amount: number): void {
    const fails = mulberry32(hashSeed(requestId))() < 0.12;
    setTimeout(() => {
      const record = this.withdrawals.get(requestId);
      if (!record || record.state !== "REQUESTED") return;
      if (fails) {
        record.state = "FAILED" satisfies WithdrawalState;
        this.balance += amount;
        this.appendLedger("WITHDRAWAL_REFUND", amount, null);
      } else {
        record.state = "CONFIRMED" satisfies WithdrawalState;
      }
    }, 4000);
  }

  async withdrawal(requestId: string): Promise<WithdrawalRequestDto> {
    this.requireSignedIn();
    const record = this.withdrawals.get(requestId);
    if (!record) throw new Error("Withdrawal request not found.");
    return record;
  }

  async requestDeposit(amount: string): Promise<DepositRequestDto> {
    this.requireSignedIn();
    const amountNum = Number(amount);
    const requestId = `dep-${this.deposits.size + 1}-${Date.now()}`;
    const record: DepositRecord = {
      requestId,
      playerId: this.player!.playerId,
      amount: amountNum.toFixed(3),
      state: "REQUESTED",
      requestedAtMs: simNowMs(),
    };
    this.deposits.set(requestId, record);
    this.scheduleDepositOutcome(requestId, amountNum);
    return record;
  }

  private scheduleDepositOutcome(requestId: string, amount: number): void {
    const fails = mulberry32(hashSeed(requestId))() < 0.12;
    setTimeout(() => {
      const record = this.deposits.get(requestId);
      if (!record || record.state !== "REQUESTED") return;
      if (fails) {
        record.state = "FAILED" satisfies DepositState;
      } else {
        record.state = "CONFIRMED" satisfies DepositState;
        this.balance += amount;
        this.appendLedger("DEPOSIT", amount, null);
      }
    }, 4000);
  }

  async deposit(requestId: string): Promise<DepositRequestDto> {
    this.requireSignedIn();
    const record = this.deposits.get(requestId);
    if (!record) throw new Error("Deposit request not found.");
    return record;
  }

  // -------------------------------------------------------------------------
  // Crowd account (Р-5, Р-14, Р-17, Р-25, Р-26)
  // -------------------------------------------------------------------------

  async crowdAccount() {
    return buildCrowdAccount(simNowMs());
  }

  // -------------------------------------------------------------------------
  // Ratings (Р-9, Р-18, Р-28, Р-30)
  // -------------------------------------------------------------------------

  async ratings(season?: SeasonId): Promise<RatingsDto> {
    const s = season ?? CURRENT_SEASON;
    return {
      season: s,
      roundsThreshold: RATING_ROUNDS_THRESHOLD,
      rich: richRatings(s),
      prophets: prophetRatings(s),
    };
  }

  // -------------------------------------------------------------------------
  // Profiles (Р-15, Р-29)
  // -------------------------------------------------------------------------

  async publicProfile(playerId: string): Promise<PublicProfileDto> {
    this.processSettlements();
    const npcIndex = findNpcIndexByPlayerId(playerId);
    if (npcIndex !== null) return npcPublicProfile(npcIndex);
    if (this.player && this.player.playerId === playerId) return this.ownProfile();
    throw new Error("Player not found.");
  }

  async myProfile(): Promise<PublicProfileDto> {
    this.processSettlements();
    this.requireSignedIn();
    return this.ownProfile();
  }

  private ownProfile(): PublicProfileDto {
    const player = this.player!;
    const seasonRecord =
      this.myHistory.length > 0
        ? computeSeasonRecord(this.myHistory, CURRENT_SEASON, {
            baselineBalance: "0.000",
            deposited: "0.000",
            withdrawn: "0.000",
          })
        : null;
    return { player, seasonRecord, history: this.myHistory };
  }

  // -------------------------------------------------------------------------
  // Cancellations log (Р-23, Р-32)
  // -------------------------------------------------------------------------

  async cancellations(params?: PageParams): Promise<Page<CancellationDto>> {
    const all: CancellationDto[] = [
      { roundId: CANCELLED_ROUND_ID, reason: CANCELLED_REASON, cancelledAtMs: CANCELLED_AT_MS, refundedBetCount: CANCELLED_REFUNDED_COUNT },
    ];
    return paginate(all, params, (c) => c.cancelledAtMs);
  }

  // -------------------------------------------------------------------------
  // Home aggregate (Р-34)
  // -------------------------------------------------------------------------

  async home(): Promise<HomeSummaryDto> {
    const [hourly, daily, crowd, ratingsDto] = await Promise.all([
      this.rounds("HOURLY"),
      this.rounds("DAILY"),
      this.crowdAccount(),
      this.ratings(),
    ]);
    return {
      hourly,
      daily,
      crowd,
      topRich: ratingsDto.rich.slice(0, 5),
      topProphets: ratingsDto.prophets.slice(0, 5),
    };
  }
}

function paginate<T>(items: T[], params: PageParams | undefined, timeKey: (item: T) => number): Page<T> {
  const limit = params?.limit ?? 20;
  const before = params?.before;
  const filtered = before !== undefined ? items.filter((item) => timeKey(item) < before) : items;
  const page = filtered.slice(0, limit);
  return { items: page, hasMore: filtered.length > limit };
}

export const mockApi: Api = new MockApi();
