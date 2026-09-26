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
 */
import {
  ApiRequestError,
  type Api,
  type ApiErrorCode,
  type AuthChallengeDto,
  type AuthChallengeRequest,
  type AuthSessionDto,
  type AuthVerifyRequest,
  type BetDto,
  type CancellationDto,
  type CrowdAccountDto,
  type DepositAddressDto,
  type HomeSummaryDto,
  type Interval,
  type LedgerEntryDto,
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
  type RoundId,
  type RoundsPairDto,
  type SeasonId,
  type SeasonRecordDto,
  type ServerInfoDto,
  type WithdrawalRequestDto,
} from "./contract";

/** The subset of `ApiErrorCode` this build knows about — a code outside this set (a future
 *  server addition this frontend predates) surfaces as a plain `Error` instead of a typed
 *  `ApiRequestError`, per `contract.ts`'s own doc comment ("a plain `Error` on transport/
 *  unexpected failure"), rather than lying about its type. */
const KNOWN_ERROR_CODES: ReadonlySet<string> = new Set<ApiErrorCode>([
  "NOT_SIGNED_IN",
  "ROUND_NOT_OPEN_FOR_BETTING",
  "STAKE_BELOW_MINIMUM",
  "INSUFFICIENT_BALANCE",
  "INVALID_LEVERAGE",
  "WITHDRAWAL_EXCEEDS_BALANCE",
  "CHALLENGE_EXPIRED_OR_UNKNOWN",
  "SIGNATURE_INVALID",
]);

interface RpcErrorBody {
  error?: { code?: string; message?: string };
}

async function rpc<T>(method: string, args: unknown[]): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/rpc/${method}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ args }),
    });
  } catch {
    throw new Error("Network error — check your connection and try again.");
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
    const code = err?.code ?? "";
    const message = err?.message ?? `Request failed (${res.status}).`;
    if (KNOWN_ERROR_CODES.has(code)) throw new ApiRequestError(code as ApiErrorCode, message);
    throw new Error(message);
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

  // --- rounds ---
  rounds(interval: Interval): Promise<RoundsPairDto> {
    return rpc("rounds", [interval]);
  }
  round(roundId: string): Promise<RoundDto> {
    return rpc("round", [roundId]);
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
  cancelBet(roundId: string): Promise<void> {
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

  // --- crowd account ---
  crowdAccount(): Promise<CrowdAccountDto> {
    return rpc("crowdAccount", []);
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

  // --- home aggregate ---
  home(): Promise<HomeSummaryDto> {
    return rpc("home", []);
  }

  // --- server info (additive, task's FIXED INTERFACE (1)) ---
  serverInfo(): Promise<ServerInfoDto> {
    return rpc("serverInfo", []);
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
