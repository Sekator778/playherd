/**
 * `Api` implementation backed entirely by seeded/deterministic local data (Р-35) — no network
 * calls. A single instance (`mockApi`, exported at the bottom) holds the only real mutable state
 * in the whole mock: the signed-in demo player's identity, balance, ledger and own bets. Every
 * NPC/crowd/round/price number is otherwise a pure function of the simulated clock (`clock.ts`)
 * and a seed (`mockMarkets.ts`, `mockPrices.ts`, `mockPlayers.ts`, `mockRounds.ts`,
 * `mockCrowdHistory.ts`).
 *
 * The world is 19 markets in 6 categories (Р-69) — see `mockMarkets.ts` — each with its own price
 * path, two round series (the mock's 1 h and 1 d), bots, crowd account, and the risk parameters the
 * real server would derive (leverage cap, price scale; `liquidation.ts` does the math per market).
 * One `ODDS` market closes 90 simulated minutes into a session (`MOCK_CLOSING_AT_MS`): from then on
 * it is `CLOSED`, its running and betting rounds are cancelled and a bet on them refunded
 * ("market closed", Т-10), and `placeBet` on it answers `MARKET_CLOSED` — so a UI can be built
 * against that state. One `ODDS` market is "listed moments ago" (`MOCK_NEW_LISTING_ID`): the round window the
 * session opens in began before it had a price, so that running round has no opening price
 * (`RoundRunning.openPrice === null`) — the "waiting for the opening price" state.
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
  CROWD_STARTING_CAPITAL_USD,
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
  type BetFeedDto,
  type CancellationDto,
  type CategoryId,
  type CrowdAccountDto,
  type CrowdOverviewDto,
  type DepositAddressDto,
  type EpochMs,
  type HomeSummaryDto,
  type Interval,
  type LedgerEntryDto,
  type LedgerEntryType,
  type LiquidationPreviewDto,
  type LiquidationPreviewRequest,
  type MarketDto,
  type MarketId,
  type MarketReferenceDto,
  type MarketSeriesDto,
  type MarketsDto,
  type MyPositionDto,
  type Page,
  type PageParams,
  type PlaceBetRequest,
  type PlayerAccountDto,
  type PlayerSummaryDto,
  type PriceSeriesDto,
  type ProfileRoundEntryDto,
  type PublicProfileDto,
  type RatingsDto,
  type RoundCancelled,
  type RoundDto,
  type RoundId,
  type RoundWindowDto,
  type RoundsPairDto,
  type SeasonId,
  type SeasonRecordDto,
  type WithdrawalRequestDto,
  type WithdrawalState,
} from "./contract";
import { nextWindow, parseRoundId, roundIdForWindow, simNowMs, windowFor } from "./clock";
import { crowdAccount as buildCrowdAccount, crowdBrief, crowdCapitalAt } from "./mockCrowdHistory";
import {
  MOCK_BTC,
  MOCK_MARKETS,
  hasMockPrice,
  isMockMarketClosed,
  isMockMarketListed,
  mockMarketById,
  openingPriceKnown,
  type MockMarketSpec,
} from "./mockMarkets";
import {
  CURRENT_SEASON,
  computeSeasonRecord,
  fakeAddress,
  findNpcIndexByPlayerId,
  npcPublicProfile,
  prophetRatings,
  richRatings,
} from "./mockPlayers";
import { change24hAt, priceStrAt, ticksBetween } from "./mockPrices";
import {
  betFeedPage,
  buildBettingRound,
  buildCancelledRound,
  buildRunningRound,
  buildSettledRound,
  majorityDirection,
  ownRunningPosition,
  roundBets,
  settleOwnBet,
  type OwnBetInfo,
} from "./mockRounds";
import { CATEGORY_ORDER, categoryTitle } from "./marketMeta";
import { hashSeed, mulberry32, randInt, rngFor } from "./rng";
import {
  LONG,
  SHORT,
  liquidationPrice,
  percentToLiquidation,
  priceStr,
  sizeForMargin,
  type Side,
} from "./liquidation";

const MOCK_PLAYER_ID = "me";
const CHALLENGE_TTL_MS = 5 * 60 * 1000;

/** One fixed, always-reachable cancelled round (Р-23/Р-32) — a real past hourly window, a day
 *  before the demo anchor, so it is always in the past regardless of how long the session runs. */
const CANCELLED_WINDOW = windowFor("HOURLY", Date.UTC(2026, 8, 24, 9, 0, 0));
const CANCELLED_ROUND_ID = roundIdForWindow(MOCK_BTC.id, CANCELLED_WINDOW);
const CANCELLED_REASON =
  "Automatic cancellation: no reliable Coinbase price could be read within 1 hour of this round's close, so it could not be settled (Р-23). All bets were refunded.";
const CANCELLED_AT_MS = CANCELLED_WINDOW.endMs + 55 * 60 * 1000;
const CANCELLED_REFUNDED_COUNT = 19;

/** Why the rounds of a closed `ODDS` market are cancelled (Т-10) — shown on the round page. */
const MARKET_CLOSED_REASON =
  "Market closed: the underlying market ended or was withdrawn by its source, so this round was cancelled and every bet refunded.";

/** Why a round that never had an opening price ends cancelled (the mock's rule, in the spirit of Р-23). */
const NO_OPENING_PRICE_REASON =
  "No opening price: this market was listed after the round started, so the round could not open. Any bet in it was refunded.";

/** How far back `priceSeries` goes without a `sinceMs` ("the last 15 minutes"), and its point cap. */
const PRICE_SERIES_WINDOW_MS = 15 * 60_000;
const PRICE_SERIES_MAX_POINTS = 600;

const INTERVALS: readonly Interval[] = ["HOURLY", "DAILY"];

type WithdrawalRecord = WithdrawalRequestDto;

class MockApi implements Api {
  private player: PlayerSummaryDto | null = null;
  private balance = 0;
  private ledger: LedgerEntryDto[] = [];
  private ledgerSeq = 0;
  private myBets = new Map<string, BetDto>(); // roundId -> bet, kept even after settlement
  private settledRoundIds = new Set<string>();
  /** Rounds whose bet was refunded because the market closed — counted in the cancellation log. */
  private refundedRoundIds = new Set<string>();
  private myHistory: ProfileRoundEntryDto[] = [];
  private challenges = new Map<string, { message: string; expiresAtMs: number; address: string }>();
  private withdrawals = new Map<string, WithdrawalRecord>();
  private depositAddress: string | null = null;
  // "While you were away" (unseenResults/acknowledgeResults) — every settled `myHistory` entry
  // starts unacknowledged; a result moment (shown live via `useSettlementWatcher` or caught up via
  // `useAwayReplay`) acks its `roundId` so it never replays again this session.
  private acknowledgedRoundIds = new Set<string>();

  // -------------------------------------------------------------------------
  // Settlement sweep — applied lazily whenever any method reads state, since the simulated
  // clock advances between calls without any push/subscription mechanism.
  // -------------------------------------------------------------------------

  private processSettlements(): void {
    const now = simNowMs();
    for (const [roundId, bet] of this.myBets) {
      if (this.settledRoundIds.has(roundId)) continue;
      const parsed = parseRoundId(roundId);
      const spec = parsed ? mockMarketById(parsed.marketId) : undefined;
      if (!parsed || !spec) continue;
      const window = windowFor(parsed.interval, parsed.startMs);
      if (isCancelledByClose(spec, window, now)) {
        // The market closed while this round was still open or running: the round is cancelled
        // and the bet refunded in full (Т-10; Р-23: liquidation-proof — everything comes back).
        this.appendLedger("STAKE_REFUND", Number(bet.stake), roundId);
        this.balance += Number(bet.stake);
        this.settledRoundIds.add(roundId);
        this.refundedRoundIds.add(roundId);
        this.myBets.delete(roundId);
        continue;
      }
      if (now < window.endMs) continue;
      const outcome = settleOwnBet(spec, bet, window);
      this.settledRoundIds.add(roundId);
      const payoutNum = Number(outcome.payout);
      this.appendLedger("SETTLEMENT", payoutNum, roundId);
      this.balance += payoutNum;
      this.myHistory.unshift({
        roundId,
        interval: parsed.interval,
        marketId: spec.id,
        asset: spec.symbol,
        durationSec: (window.endMs - window.startMs) / 1000,
        kind: spec.kind,
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

  private resetPlayerState(): void {
    this.balance = 0;
    this.ledger = [];
    this.ledgerSeq = 0;
    this.myBets.clear();
    this.settledRoundIds.clear();
    this.refundedRoundIds.clear();
    this.myHistory = [];
    this.acknowledgedRoundIds.clear();
  }

  /** The round's id, window and market for an id that must be one of the mock's own; a malformed
   *  id is `NOT_FOUND`, an id of a market this mock does not list is `MARKET_NOT_FOUND`. */
  private resolveRoundId(roundId: RoundId): { spec: MockMarketSpec; window: RoundWindowDto } {
    const parsed = parseRoundId(roundId);
    if (!parsed) throw new ApiRequestError("NOT_FOUND", "Round not found.");
    const spec = requireMarket(parsed.marketId);
    const window = windowFor(parsed.interval, parsed.startMs);
    if (window.startMs !== parsed.startMs) throw new ApiRequestError("NOT_FOUND", "Round not found."); // not on the series' grid
    return { spec, window };
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
    this.resetPlayerState();
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
    this.resetPlayerState();
    this.withdrawals.clear();
    this.depositAddress = null;
  }

  // -------------------------------------------------------------------------
  // Markets (Р-69, Р-70; docs/research/markets_architecture.md §8)
  // -------------------------------------------------------------------------

  async markets(): Promise<MarketsDto> {
    this.processSettlements();
    const now = simNowMs();
    const listed = MOCK_MARKETS.filter((spec) => isMockMarketListed(spec, now));
    const activeByCategory = new Map<CategoryId, number>();
    for (const spec of listed) {
      if (!isMockMarketClosed(spec, now)) activeByCategory.set(spec.category, (activeByCategory.get(spec.category) ?? 0) + 1);
    }
    return {
      serverTimeMs: now,
      categories: CATEGORY_ORDER.filter((id) => activeByCategory.has(id)).map((id) => ({
        id,
        title: categoryTitle(id),
        marketCount: activeByCategory.get(id)!,
      })),
      markets: listed.map((spec) => this.marketDto(spec, now)),
    };
  }

  private marketDto(spec: MockMarketSpec, now: EpochMs): MarketDto {
    const closed = isMockMarketClosed(spec, now);
    const priceAtMs = closed ? spec.closesAtMs! : now;
    return {
      id: spec.id,
      kind: spec.kind,
      category: spec.category,
      symbol: spec.symbol,
      title: spec.title,
      subtitle: spec.subtitle,
      iconUrl: spec.iconUrl,
      maxLeverage: spec.maxLeverage,
      priceDecimals: spec.priceDecimals,
      status: closed ? "CLOSED" : "ACTIVE",
      endsAtMs: spec.endsAtMs,
      eventStartsAtMs: spec.eventStartsAtMs,
      closedAtMs: closed ? spec.closesAtMs : null,
      closeReason: closed ? spec.closeReason : null,
      oracleIndex: spec.oracleIndex,
      source: spec.source,
      backupSource: spec.backupSource,
      price: hasMockPrice(spec, priceAtMs)
        ? {
            price: priceStrAt(spec, priceAtMs),
            asOfMs: priceAtMs,
            change24h: change24hAt(spec, priceAtMs),
            stale: false,
          }
        : null,
      series: INTERVALS.map((interval) => this.seriesDto(spec, interval, now)),
      crowd: crowdBrief(spec, now),
    };
  }

  private seriesDto(spec: MockMarketSpec, interval: Interval, now: EpochMs): MarketSeriesDto {
    const runningWindow = windowFor(interval, now);
    const bettingWindow = nextWindow(runningWindow);
    const bettingRoundId = roundIdForWindow(spec.id, bettingWindow);
    const bets = roundBets(spec, bettingRoundId, this.ownBetInfo(bettingRoundId));
    const sumStake = (direction: "UP" | "DOWN") =>
      bets.filter((b) => b.direction === direction).reduce((sum, b) => sum + Number(b.stake), 0);
    return {
      interval,
      durationSec: (runningWindow.endMs - runningWindow.startMs) / 1000,
      bettingRoundId,
      bettingClosesAtMs: bettingWindow.startMs,
      runningRoundId: roundIdForWindow(spec.id, runningWindow),
      runningEndsAtMs: runningWindow.endMs,
      participantCount: bets.length,
      upStake: sumStake("UP").toFixed(3),
      downStake: sumStake("DOWN").toFixed(3),
      majorityDirection: majorityDirection(bets),
    };
  }

  /** The contract's words: the last 15 minutes of ticks, or those with `atMs > sinceMs`; at most 600, thinned
   *  evenly when more exist (`mockPrices.ticksBetween`). */
  async priceSeries(marketId: MarketId, sinceMs?: EpochMs): Promise<PriceSeriesDto> {
    const spec = requireMarket(marketId);
    const now = simNowMs();
    const until = isMockMarketClosed(spec, now) ? spec.closesAtMs! : now; // a closed market stops ticking
    const after = sinceMs ?? until - PRICE_SERIES_WINDOW_MS;
    return {
      marketId: spec.id,
      priceDecimals: spec.priceDecimals,
      ticks: ticksBetween(spec, after, until, PRICE_SERIES_MAX_POINTS),
    };
  }

  async referencePrice(marketId: MarketId): Promise<MarketReferenceDto> {
    const spec = requireMarket(marketId);
    const now = simNowMs();
    const asOfMs = isMockMarketClosed(spec, now) ? spec.closesAtMs! : now;
    return { price: priceStrAt(spec, asOfMs), asOfMs };
  }

  async myPositions(): Promise<MyPositionDto[]> {
    this.processSettlements();
    this.requireSignedIn();
    const now = simNowMs();
    const positions: MyPositionDto[] = [];
    for (const [roundId, bet] of this.myBets) {
      if (this.settledRoundIds.has(roundId)) continue;
      const parsed = parseRoundId(roundId);
      const spec = parsed ? mockMarketById(parsed.marketId) : undefined;
      if (!parsed || !spec) continue;
      const window = windowFor(parsed.interval, parsed.startMs);
      const base = {
        marketId: spec.id,
        symbol: spec.symbol,
        roundId,
        interval: parsed.interval,
        durationSec: (window.endMs - window.startMs) / 1000,
        direction: bet.direction,
        leverage: bet.leverage,
        stake: bet.stake,
      };
      if (now < window.startMs) {
        positions.push({
          ...base,
          phase: "BETTING",
          entryPrice: null,
          liquidationPrice: null,
          size: null,
          liquidated: false,
          untilMs: window.startMs,
        });
      } else {
        const running = ownRunningPosition(spec, bet, window, now);
        positions.push({
          ...base,
          phase: "RUNNING",
          entryPrice: running ? running.entryPrice : null,
          liquidationPrice: running ? running.liquidationPrice : null,
          size: running ? running.size : null,
          liquidated: running ? running.liquidated : false,
          untilMs: window.endMs,
        });
      }
    }
    return positions.sort((a, b) => a.untilMs - b.untilMs);
  }

  // -------------------------------------------------------------------------
  // Rounds (Р-8, Р-13, Р-19, Р-22, Р-23, Р-32)
  // -------------------------------------------------------------------------

  async rounds(interval: Interval, marketId: MarketId = MOCK_BTC.id): Promise<RoundsPairDto> {
    this.processSettlements();
    const spec = requireMarket(marketId);
    const now = simNowMs();
    const runningWindow = windowFor(interval, now);
    const bettingWindow = nextWindow(runningWindow);
    if (isMockMarketClosed(spec, now)) {
      // Both the running and the betting round of a closed market are cancelled (Т-10).
      return {
        interval,
        running: this.marketClosedRound(spec, runningWindow),
        betting: this.marketClosedRound(spec, bettingWindow),
      };
    }
    const capitalBefore = crowdCapitalAt(spec, now);
    const running = buildRunningRound(
      spec,
      runningWindow,
      capitalBefore,
      now,
      this.ownBetInfo(roundIdForWindow(spec.id, runningWindow)),
    );
    const betting = buildBettingRound(spec, bettingWindow, this.ownBetInfo(roundIdForWindow(spec.id, bettingWindow)));
    return { interval, running, betting };
  }

  async round(roundId: string): Promise<RoundDto> {
    this.processSettlements();
    if (roundId === CANCELLED_ROUND_ID) {
      return buildCancelledRound(MOCK_BTC, CANCELLED_WINDOW, CANCELLED_REASON, CANCELLED_REFUNDED_COUNT, CANCELLED_AT_MS);
    }
    const { spec, window } = this.resolveRoundId(roundId);
    const now = simNowMs();
    if (isCancelledByClose(spec, window, now)) return this.marketClosedRound(spec, window);
    if (now >= window.endMs && !openingPriceKnown(spec, window.startMs)) {
      // It started before there was a price and has now ended: it never opened.
      return buildCancelledRound(spec, window, NO_OPENING_PRICE_REASON, 0, window.endMs);
    }
    const capitalBefore = crowdCapitalAt(spec, Math.min(now, window.startMs));
    const own = this.ownBetInfo(roundId);
    if (now < window.startMs) return buildBettingRound(spec, window, own);
    if (now < window.endMs) return buildRunningRound(spec, window, capitalBefore, now, own);
    return buildSettledRound(spec, window, capitalBefore, own);
  }

  /** A round of a closed market: cancelled at the moment of closing, every bet refunded. */
  private marketClosedRound(spec: MockMarketSpec, window: RoundWindowDto): RoundCancelled {
    const roundId = roundIdForWindow(spec.id, window);
    const refunded = roundBets(spec, roundId).length + (this.refundedRoundIds.has(roundId) ? 1 : 0);
    return buildCancelledRound(spec, window, MARKET_CLOSED_REASON, refunded, spec.closesAtMs ?? simNowMs());
  }

  async betFeed(roundId: RoundId, sinceSeq?: number): Promise<BetFeedDto> {
    const { spec, window } = this.resolveRoundId(roundId);
    const own = this.ownBetInfo(roundId);
    const { entries, hasMore } = betFeedPage(roundId, roundBets(spec, roundId, own), window, simNowMs(), sinceSeq ?? 0, own);
    return { roundId, marketId: spec.id, asset: spec.symbol, entries, hasMore };
  }

  async btcReferencePrice(): Promise<MarketReferenceDto> {
    return this.referencePrice(MOCK_BTC.id);
  }

  async previewLiquidation(req: LiquidationPreviewRequest): Promise<LiquidationPreviewDto> {
    const spec = requireMarket(req.marketId ?? MOCK_BTC.id);
    requireValidLeverage(req.leverage, spec);
    const entryPriceUsed = req.referencePrice ?? priceStrAt(spec, simNowMs());
    const side: Side = req.direction === "UP" ? LONG : SHORT;
    const size = sizeForMargin(req.stake, req.leverage, entryPriceUsed, spec);
    const liqPrice = liquidationPrice(size, entryPriceUsed, req.leverage, side, spec);
    return {
      entryPriceUsed,
      liquidationPrice: priceStr(liqPrice, spec),
      percentToLiquidation: percentToLiquidation(entryPriceUsed, liqPrice),
    };
  }

  async placeBet(req: PlaceBetRequest): Promise<BetDto> {
    this.processSettlements();
    const player = this.requireSignedIn();
    const parsed = parseRoundId(req.roundId);
    const now = simNowMs();
    const spec = parsed ? requireMarket(parsed.marketId) : undefined;
    if (spec && isMockMarketClosed(spec, now)) {
      throw new ApiRequestError("MARKET_CLOSED", `${spec.symbol} is closed — this market is no longer taking bets.`);
    }
    const currentBettingId =
      parsed && spec ? roundIdForWindow(spec.id, nextWindow(windowFor(parsed.interval, now))) : null;
    if (!parsed || !spec || currentBettingId !== req.roundId) {
      throw new ApiRequestError("ROUND_NOT_OPEN_FOR_BETTING", "Betting just closed for this round.");
    }
    requireValidLeverage(req.leverage, spec);
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
    const stillBetting = parsed
      ? roundIdForWindow(parsed.marketId, nextWindow(windowFor(parsed.interval, now))) === roundId
      : false;
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
    let inPlay = 0; // every stake still in a BETTING or RUNNING round, in any market
    for (const [roundId, bet] of this.myBets) {
      if (!this.settledRoundIds.has(roundId)) inPlay += Number(bet.stake);
    }
    return {
      playerId: player.playerId,
      balance: this.balance.toFixed(3),
      currentSeason: CURRENT_SEASON,
      inPlay: inPlay.toFixed(3),
    };
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

  /** Real on-chain withdrawal (Р-53) — `toAddress` is always the signed-in player's own
   *  registered address (Р-57), never a parameter (mirrors `Api.requestWithdrawal`'s doc). Field
   *  shape (`state`+`chainState` kept separate, `failureReason` not `reason`) matches `server/`'s
   *  real `Ledger.WithdrawalRequestDto` — see `contract.ts`'s doc comment on `WithdrawalState`/
   *  `WithdrawalChainState` for why. */
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
      toAddress: this.player!.walletAddress,
      state: "REQUESTED",
      chainState: null,
      coinId: null,
      height: null,
      failureReason: null,
      requestedAtMs: simNowMs(),
    };
    this.withdrawals.set(requestId, record);
    this.scheduleWithdrawalOutcome(requestId, amountNum);
    return record;
  }

  /**
   * Simulates the real flow's two moving parts — `requestWithdrawal`'s own synchronous chainsvc
   * submit attempt, then `WithdrawalPollerService`'s polling (`server/`, task's FIXED INTERFACE)
   * — moving `chainState` null → "submitted" → "queued" → "in_flight" → "confirmed"/"failed"
   * (`state` itself only flips to `CONFIRMED`/`FAILED` at that last step). предложение: no
   * research file measures real timing for any of this (`week1_testnet_results.md` covers
   * individual spends, not a batched queue's cadence) — tuned only so every state is reachable in
   * a demo.
   */
  private scheduleWithdrawalOutcome(requestId: string, amount: number): void {
    const fails = mulberry32(hashSeed(requestId))() < 0.12;
    const advance = (chainState: NonNullable<WithdrawalRequestDto["chainState"]>, afterMs: number) => {
      setTimeout(() => {
        const record = this.withdrawals.get(requestId);
        if (!record || record.state !== "REQUESTED") return;
        record.chainState = chainState;
      }, afterMs);
    };
    advance("submitted", 500);
    advance("queued", 1500);
    advance("in_flight", 2500);
    setTimeout(() => {
      const record = this.withdrawals.get(requestId);
      if (!record || record.state !== "REQUESTED") return;
      if (fails) {
        record.state = "FAILED" satisfies WithdrawalState;
        record.chainState = "failed";
        record.failureReason = "Fee spiked past the treasury batch's retry budget — refunded automatically.";
        this.balance += amount;
        this.appendLedger("WITHDRAWAL_REFUND", amount, null);
      } else {
        record.state = "CONFIRMED" satisfies WithdrawalState;
        record.chainState = "confirmed";
        const rng = rngFor("withdrawal-confirm", requestId);
        record.coinId = `0x${Array.from({ length: 32 }, () => randInt(rng, 0, 15).toString(16)).join("")}`;
        record.height = randInt(rng, 4_700_000, 4_790_000);
      }
    }, 4000);
  }

  async withdrawal(requestId: string): Promise<WithdrawalRequestDto> {
    this.requireSignedIn();
    const record = this.withdrawals.get(requestId);
    if (!record) throw new ApiRequestError("NOT_FOUND", "Withdrawal request not found.");
    return record;
  }

  /**
   * `POST /v1/play/deposit-address` proxy (task's FIXED INTERFACE, chainsvc) — see
   * `DepositAddressDto`'s doc comment for why this replaced `requestDeposit`/`deposit` entirely.
   * Deterministic per player (`rngFor`, same pattern as every other mock identity in this file),
   * so it's stable across calls within a session, matching the real endpoint's "stable for as
   * long as they're the same player" contract.
   */
  async myDepositAddress(): Promise<DepositAddressDto> {
    this.requireSignedIn();
    if (!this.depositAddress) {
      this.depositAddress = fakeAddress(rngFor("deposit-address", this.player!.playerId));
    }
    return { address: this.depositAddress };
  }

  // -------------------------------------------------------------------------
  // Crowd account (Р-5, Р-14, Р-17, Р-25, Р-26; one per market, Т-6)
  // -------------------------------------------------------------------------

  async crowdAccount(marketId: MarketId = MOCK_BTC.id): Promise<CrowdAccountDto> {
    return buildCrowdAccount(requireMarket(marketId), simNowMs());
  }

  /** Every market keeps its row here — a closed one too, after it has left `markets()` (`CrowdOverviewDto`). */
  async crowdOverview(): Promise<CrowdOverviewDto> {
    const now = simNowMs();
    const starting = Number(CROWD_STARTING_CAPITAL_USD);
    const rows = MOCK_MARKETS.map((spec) => {
      const brief = crowdBrief(spec, now);
      return {
        marketId: spec.id,
        symbol: spec.symbol,
        title: spec.title,
        status: isMockMarketClosed(spec, now) ? ("CLOSED" as const) : ("ACTIVE" as const),
        category: spec.category,
        capital: brief.capital,
        returnPct: brief.returnPct,
        roundsTraded: brief.roundsTraded,
        roundsWon: brief.roundsWon,
      };
    });
    const totalCapital = rows.reduce((sum, row) => sum + Number(row.capital), 0);
    const totalStartingCapital = starting * rows.length;
    return {
      totalCapital: totalCapital.toFixed(2),
      totalStartingCapital: totalStartingCapital.toFixed(2),
      returnPct: totalStartingCapital > 0 ? totalCapital / totalStartingCapital - 1 : 0,
      markets: rows,
    };
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
    throw new ApiRequestError("NOT_FOUND", "Player not found.");
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
    return { player, seasonRecord, history: this.myHistory, streak: winStreak(this.myHistory) };
  }

  // -------------------------------------------------------------------------
  // Cancellations log (Р-23, Р-32)
  // -------------------------------------------------------------------------

  async cancellations(params?: PageParams): Promise<Page<CancellationDto>> {
    this.processSettlements();
    const now = simNowMs();
    const all: CancellationDto[] = [];
    for (const spec of MOCK_MARKETS) {
      if (!isMockMarketClosed(spec, now)) continue;
      // The rounds that were open or running at the moment the market closed (Т-10).
      const closedAt = spec.closesAtMs!;
      for (const interval of INTERVALS) {
        const running = windowFor(interval, closedAt);
        for (const window of [running, nextWindow(running)]) {
          all.push(this.marketClosedRound(spec, window).cancellation);
        }
      }
    }
    all.sort((a, b) => b.cancelledAtMs - a.cancelledAtMs);
    all.push({
      roundId: CANCELLED_ROUND_ID,
      marketId: MOCK_BTC.id,
      asset: MOCK_BTC.symbol,
      reason: CANCELLED_REASON,
      cancelledAtMs: CANCELLED_AT_MS,
      refundedBetCount: CANCELLED_REFUNDED_COUNT,
    });
    return paginate(all, params, (c) => c.cancelledAtMs);
  }

  // -------------------------------------------------------------------------
  // Home aggregate (Р-34) — describes BTC (markets_architecture.md §8: "`home` остаётся и описывает BTC")
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

  // -------------------------------------------------------------------------
  // "While you were away" (fx/useAwayReplay.ts)
  // -------------------------------------------------------------------------

  async unseenResults(): Promise<ProfileRoundEntryDto[]> {
    this.processSettlements();
    this.requireSignedIn();
    return this.myHistory.filter((h) => !this.acknowledgedRoundIds.has(h.roundId));
  }

  async acknowledgeResults(roundIds: string[]): Promise<void> {
    for (const id of roundIds) this.acknowledgedRoundIds.add(id);
  }
}

function requireMarket(marketId: MarketId): MockMarketSpec {
  const spec = mockMarketById(marketId);
  if (!spec) throw new ApiRequestError("MARKET_NOT_FOUND", `Unknown market "${marketId}".`);
  return spec;
}

/** `Leverage` is an integer in 2..the market's cap (Т-4) — the server rejects anything else. */
function requireValidLeverage(leverage: number, spec: MockMarketSpec): void {
  if (!Number.isInteger(leverage) || leverage < LEVERAGE_MIN || leverage > spec.maxLeverage) {
    throw new ApiRequestError(
      "INVALID_LEVERAGE",
      `Leverage must be between ${LEVERAGE_MIN} and ${spec.maxLeverage} on ${spec.symbol}.`,
    );
  }
}

/** A round of a market that has closed by `now` is cancelled when it had not ended by the closing
 *  moment (Т-10: the betting round and the running round); one that had already ended settled. */
function isCancelledByClose(spec: MockMarketSpec, window: RoundWindowDto, now: EpochMs): boolean {
  return spec.closesAtMs !== null && now >= spec.closesAtMs && window.endMs > spec.closesAtMs;
}

/** `PublicProfileDto.streak` over a newest-first history: a win is `payout > stake`, a flat round
 *  neither extends nor breaks a streak (Р-71). */
function winStreak(history: ProfileRoundEntryDto[]): { current: number; best: number } {
  let current = 0;
  let best = 0;
  let run = 0;
  let live = true; // still walking the unbroken run at the head of the list
  for (const entry of history) {
    const net = Number(entry.payout) - Number(entry.stake);
    if (net === 0) continue;
    if (net > 0) {
      run += 1;
      best = Math.max(best, run);
      if (live) current = run;
    } else {
      run = 0;
      live = false;
    }
  }
  return { current, best };
}

function paginate<T>(items: T[], params: PageParams | undefined, timeKey: (item: T) => number): Page<T> {
  const limit = params?.limit ?? 20;
  const before = params?.before;
  const filtered = before !== undefined ? items.filter((item) => timeKey(item) < before) : items;
  const page = filtered.slice(0, limit);
  return { items: page, hasMore: filtered.length > limit };
}

export const mockApi: Api = new MockApi();
