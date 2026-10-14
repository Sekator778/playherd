/**
 * `Api` implementation over the task's FIXED INTERFACE (1): every method `m` of {@link Api} is
 * served as `POST /api/rpc/m` with JSON body `{"args":[...positional args in declaration
 * order, omitted optionals as null]}`; success is HTTP 200 with the DTO JSON exactly as typed in
 * `contract.ts` (`void` → `{}`); failure is HTTP 4xx/5xx with
 * `{"error":{"code":"SNAKE_CASE_CODE","message":"human text"}}`. Session is the httpOnly cookie
 * `herd_session` the browser already attaches/receives automatically for same-origin requests —
 * `credentials: "include"` only matters when the API isn't same-origin (kept on regardless, it's
 * a no-op for same-origin requests and correct for a dev setup that proxies elsewhere).
 *
 * Selected via `VITE_API_MODE=real` (see `apiMode.ts`) — swapping this in for `mockApi` changes
 * no call site, by `contract.ts`'s own design.
 *
 * Errors: a server error whose code is in `ApiErrorCode` becomes an `ApiRequestError` with that
 * code; ANY other code becomes `ApiRequestError("OTHER", <the server's own message>)`, so the UI
 * can show the server's text instead of a generic "Something went wrong" (the old behaviour lost
 * `RATE_LIMITED`, `BAD_REQUEST`, … that way). Only failures with no `{"error":…}` envelope at all —
 * the network, a proxy's HTML error page — stay a plain `Error`; the one exception is HTTP 429, which
 * is `RATE_LIMITED` whoever sent it (a proxy's own limit has no envelope either).
 *
 * The engine's state (`engineState.ts`) is fed from here, because every call passes through: a 503
 * `ENGINE_NOT_READY` marks it not ready; a success of a read that needs the engine, or `serverInfo()` saying
 * `engineReady !== false`, marks it ready again.
 */
import { tNow } from "../i18n";
import { reportEngineNotReady, reportEngineReady } from "./engineState";
import {
  ApiRequestError,
  type Api,
  type ApiErrorCode,
  type AuthChallengeDto,
  type AuthChallengeRequest,
  type AuthSessionDto,
  type AuthVerifyRequest,
  type BetDto,
  type BetFeedDto,
  type CancellationDto,
  type CrowdAccountDto,
  type CrowdOverviewDto,
  type DepositAddressDto,
  type EpochMs,
  type FeedbackReceiptDto,
  type FeedbackRequest,
  type HomeSummaryDto,
  type Interval,
  type LedgerEntryDto,
  type LiquidationPreviewDto,
  type LiquidationPreviewRequest,
  type MarketId,
  type MarketReferenceDto,
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
  type RoundDto,
  type RoundId,
  type RoundsPairDto,
  type SeasonId,
  type SeasonRecordDto,
  type ServerInfoDto,
  type WithdrawalRequestDto,
} from "./contract";

/** The codes `ApiErrorCode` names. `"OTHER"` is deliberately not in the set: it is what everything
 *  outside it turns into (a server that literally sent `"OTHER"` gets the same treatment). */
const KNOWN_ERROR_CODES: ReadonlySet<string> = new Set<Exclude<ApiErrorCode, "OTHER">>([
  "NOT_SIGNED_IN",
  "ROUND_NOT_OPEN_FOR_BETTING",
  "STAKE_BELOW_MINIMUM",
  "INSUFFICIENT_BALANCE",
  "INVALID_LEVERAGE",
  "WITHDRAWAL_EXCEEDS_BALANCE",
  "CHALLENGE_EXPIRED_OR_UNKNOWN",
  "SIGNATURE_INVALID",
  "MARKET_NOT_FOUND",
  "MARKET_CLOSED",
  "PRICE_UNAVAILABLE",
  "RATE_LIMITED",
  "ENGINE_NOT_READY",
  "NOT_FOUND",
  "INVALID_NICKNAME",
]);

/** The methods the server answers from the engine (`GameService` takes its read lock for them; `markets()` checks it
 *  even for a cached answer): while the engine rebuilds they answer 503, so a success proves it is back. Everything
 *  else — `serverInfo`, the cancellations log, a reference price, a settled round — is answered either way. */
const ENGINE_READS: ReadonlySet<string> = new Set([
  "markets",
  "rounds",
  "myPositions",
  "myAccount",
  "mySeasonRecord",
  "myLedger",
  "crowdAccount",
  "crowdOverview",
  "ratings",
  "publicProfile",
  "myProfile",
  "home",
  "betFeed",
]);

interface RpcErrorBody {
  error?: { code?: string; message?: string };
}

async function rpc<T>(method: string, args: unknown[]): Promise<T> {
  const startedAtMs = Date.now();
  let res: Response;
  try {
    res = await fetch(`/api/rpc/${method}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ args }),
    });
  } catch {
    throw new Error(tNow("common.networkError"));
  }

  let body: unknown = null;
  const text = await res.text();
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }

  if (!res.ok) {
    const err = (body as RpcErrorBody | null)?.error;
    const message = err?.message || `Request failed (${res.status}).`;
    if (!err) {
      // No server envelope: a transport/proxy failure — except a rate limit, which is one whoever applies it.
      if (res.status === 429) throw new ApiRequestError("RATE_LIMITED", tNow("errors.rateLimited"));
      throw new Error(message);
    }
    const code: ApiErrorCode = err.code && KNOWN_ERROR_CODES.has(err.code) ? (err.code as ApiErrorCode) : "OTHER";
    if (code === "ENGINE_NOT_READY") reportEngineNotReady();
    throw new ApiRequestError(code, message);
  }

  if (method === "serverInfo") {
    // The one call that answers during a rebuild and says whether it is still going on (`engineReady`, absent → true).
    if ((body as ServerInfoDto | null)?.engineReady === false) reportEngineNotReady();
    else reportEngineReady(startedAtMs);
  } else if (ENGINE_READS.has(method)) {
    reportEngineReady(startedAtMs);
  }

  // An empty or JSON-null body means null (e.g. currentPlayer with no session);
  // void methods get an explicit {} from the server.
  return body as T;
}

class RealApi implements Api {
  // --- sign-in (Р-4/Р-45) ---
  authChallenge(req: AuthChallengeRequest): Promise<AuthChallengeDto> {
    return rpc("authChallenge", [req]);
  }
  authVerify(req: AuthVerifyRequest): Promise<AuthSessionDto> {
    return rpc("authVerify", [req]);
  }
  currentPlayer(): Promise<PlayerSummaryDto | null> {
    return rpc("currentPlayer", []);
  }
  setNickname(nickname: string): Promise<PlayerSummaryDto> {
    return rpc("setNickname", [nickname]);
  }
  signOut(): Promise<void> {
    return rpc("signOut", []);
  }

  // --- markets (Р-69, Р-70) ---
  markets(): Promise<MarketsDto> {
    return rpc("markets", []);
  }
  priceSeries(marketId: MarketId, sinceMs?: EpochMs): Promise<PriceSeriesDto> {
    return rpc("priceSeries", [marketId, sinceMs ?? null]);
  }
  referencePrice(marketId: MarketId): Promise<MarketReferenceDto> {
    return rpc("referencePrice", [marketId]);
  }
  myPositions(): Promise<MyPositionDto[]> {
    return rpc("myPositions", []);
  }

  // --- rounds ---
  rounds(interval: Interval, marketId?: MarketId): Promise<RoundsPairDto> {
    return rpc("rounds", [interval, marketId ?? null]);
  }
  round(roundId: RoundId): Promise<RoundDto> {
    return rpc("round", [roundId]);
  }
  betFeed(roundId: RoundId, sinceSeq?: number): Promise<BetFeedDto> {
    return rpc("betFeed", [roundId, sinceSeq ?? null]);
  }
  btcReferencePrice(): Promise<MarketReferenceDto> {
    return rpc("btcReferencePrice", []);
  }
  previewLiquidation(req: LiquidationPreviewRequest): Promise<LiquidationPreviewDto> {
    return rpc("previewLiquidation", [req]);
  }
  placeBet(req: PlaceBetRequest): Promise<BetDto> {
    return rpc("placeBet", [req]);
  }
  cancelBet(roundId: RoundId): Promise<void> {
    return rpc("cancelBet", [roundId]);
  }

  // --- player account / ledger ---
  myAccount(): Promise<PlayerAccountDto> {
    return rpc("myAccount", []);
  }
  mySeasonRecord(season?: SeasonId): Promise<SeasonRecordDto> {
    return rpc("mySeasonRecord", [season ?? null]);
  }
  myLedger(params?: PageParams): Promise<Page<LedgerEntryDto>> {
    return rpc("myLedger", [params ?? null]);
  }
  requestWithdrawal(amount: string): Promise<WithdrawalRequestDto> {
    return rpc("requestWithdrawal", [amount]);
  }
  withdrawal(requestId: string): Promise<WithdrawalRequestDto> {
    return rpc("withdrawal", [requestId]);
  }
  myDepositAddress(): Promise<DepositAddressDto> {
    return rpc("myDepositAddress", []);
  }

  // --- crowd account (one per market, Т-6) ---
  crowdAccount(marketId?: MarketId): Promise<CrowdAccountDto> {
    return rpc("crowdAccount", [marketId ?? null]);
  }
  crowdOverview(): Promise<CrowdOverviewDto> {
    return rpc("crowdOverview", []);
  }

  // --- ratings ---
  ratings(season?: SeasonId): Promise<RatingsDto> {
    return rpc("ratings", [season ?? null]);
  }

  // --- profiles ---
  publicProfile(playerId: string): Promise<PublicProfileDto> {
    return rpc("publicProfile", [playerId]);
  }
  myProfile(): Promise<PublicProfileDto> {
    return rpc("myProfile", []);
  }

  // --- cancellations log ---
  cancellations(params?: PageParams): Promise<Page<CancellationDto>> {
    return rpc("cancellations", [params ?? null]);
  }

  // --- home aggregate (describes the BTC pair: the server resolves it to the open market whose symbol is BTC) ---
  home(): Promise<HomeSummaryDto> {
    return rpc("home", []);
  }

  // --- server info (additive, task's FIXED INTERFACE (1)) ---
  serverInfo(): Promise<ServerInfoDto> {
    return rpc("serverInfo", []);
  }

  // --- feedback (Р-81): open to everyone, no session needed; a 429 is RATE_LIMITED like any other call's ---
  submitFeedback(req: FeedbackRequest): Promise<FeedbackReceiptDto> {
    return rpc("submitFeedback", [req]);
  }

  // --- "while you were away" (additive proposal — contract.ts's own doc comment) ---
  unseenResults(): Promise<ProfileRoundEntryDto[]> {
    return rpc("unseenResults", []);
  }
  acknowledgeResults(roundIds: RoundId[]): Promise<void> {
    return rpc("acknowledgeResults", [roundIds]);
  }
}

export const realApi: Api = new RealApi();
