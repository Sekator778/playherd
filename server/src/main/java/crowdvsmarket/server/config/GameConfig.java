package crowdvsmarket.server.config;

import crowdvsmarket.engine.round.RoundDurations;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Central read of the {@code herd.*} config keys (application.properties) used across the
 *  server; small enough to keep as one bean rather than a {@code @ConfigMapping} per feature. */
@ApplicationScoped
public class GameConfig {

    @ConfigProperty(name = "herd.mode")
    String modeRaw;

    @ConfigProperty(name = "herd.round-durations.hourly-sec")
    long hourlySec;

    @ConfigProperty(name = "herd.round-durations.daily-sec")
    long dailySec;

    @ConfigProperty(name = "herd.replay.speedup")
    double replaySpeedup;

    @ConfigProperty(name = "herd.bots.count")
    int botsCount;

    /** Т-8 (docs/research/markets_architecture.md §2, §10): how many distinct random active markets
     *  each bot bets on in every round of a series; capped by the number of active markets. */
    @ConfigProperty(name = "herd.bots.markets-per-round", defaultValue = "3")
    int botsMarketsPerRound;

    @ConfigProperty(name = "herd.scheduler.tick-ms")
    long tickMs;

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @ConfigProperty(name = "herd.session.cookie-name")
    String sessionCookieName;

    @ConfigProperty(name = "herd.session.ttl-hours")
    long sessionTtlHours;

    @ConfigProperty(name = "herd.auth.challenge-ttl-sec")
    long challengeTtlSec;

    @ConfigProperty(name = "herd.oracle.launcher-id")
    String oracleLauncherId;

    @ConfigProperty(name = "herd.oracle.network")
    String oracleNetwork;

    @ConfigProperty(name = "herd.chainsvc.token")
    String chainsvcToken;

    @ConfigProperty(name = "herd.ratelimit.capacity")
    int rateLimitCapacity;

    @ConfigProperty(name = "herd.ratelimit.refill-per-sec")
    double rateLimitRefillPerSecond;

    /** Р-53 update: now gates the REAL on-chain deposit path — {@code myDepositAddress()} and
     *  {@code DepositPollerService} — set {@code true} once chainsvc is deployed with the FIXED
     *  INTERFACE's {@code /v1/play/*} endpoints. Does NOT gate {@code requestDeposit} any more:
     *  that self-report RPC (task item 1, BLOCKER, docs/reviews/2026-09-26-independent-critique.md
     *  — it used to credit any self-reported amount with zero on-chain verification) is now
     *  PERMANENTLY retired regardless of this flag, see {@code GameService#requestDeposit}'s
     *  javadoc for why turning real deposits on must never also reopen that fraud vector. Default
     *  {@code false} until chainsvc is configured — see docs/research/server_design.md "Real PLAY
     *  withdraw/deposit". */
    @ConfigProperty(name = "herd.deposit.enabled", defaultValue = "false")
    boolean depositEnabled;

    /** Р-53 update: now gates the REAL on-chain withdrawal path — once {@code true}, {@code
     *  requestWithdrawal} escrows the amount and actually submits it to chainsvc ({@code
     *  POST /v1/play/withdrawals}), polled to completion by {@code WithdrawalPollerService}, rather
     *  than the pre-Р-53 stub this flag originally guarded (task item 1: {@code requestWithdrawal}
     *  used to auto-confirm with no chain call at all, falsely claiming a CAT left the game).
     *  Default {@code false} until chainsvc is configured. */
    @ConfigProperty(name = "herd.withdrawal.enabled", defaultValue = "false")
    boolean withdrawalEnabled;

    /** Task item 3 (security minor): the {@code herd_session} cookie's {@code Secure} attribute —
     *  {@code false} for the local http-only Docker stand (Р-44), must be {@code true} once this
     *  is ever deployed behind TLS (Р-38 VPS) — see docs/research/server_design.md "Session cookie". */
    @ConfigProperty(name = "herd.cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    /** Task item 7 (PLAN.md §3, README's Hyperliquid-adapter claim): {@code
     *  crowdvsmarket.server.execution.HyperliquidAdapter} refuses to build or send a real order
     *  unless this is {@code true} AND a wallet address is configured — default {@code false},
     *  same "opt-in, never touched by the local stand/demo" posture as Р-38's own execution
     *  scope note. See docs/research/server_design.md "Execution adapter". */
    @ConfigProperty(name = "herd.execution.hyperliquid.enabled", defaultValue = "false")
    boolean hyperliquidExecutionEnabled;

    /** The account HyperliquidAdapter would trade/query as — unset by default (no such account is
     *  configured anywhere in this repo or its .env; the local stand never sets this). {@link
     *  Optional} rather than a {@code ""}-default {@code String}: SmallRye Config's built-in String
     *  converter treats an empty-string config value as "absent" and refuses to inject it into a
     *  non-Optional String property (fails Quarkus startup validation) — see
     *  {@link #hyperliquidWalletAddress()}. */
    @ConfigProperty(name = "herd.execution.hyperliquid.wallet-address")
    java.util.Optional<String> hyperliquidWalletAddress;

    /** предложение: Hyperliquid assigns integer asset indices via its own {@code /info}
     *  {@code "meta"} request — this codebase does not look that up dynamically in this pass (see
     *  {@code HyperliquidAdapter}'s javadoc), so it is a plain config value instead. */
    @ConfigProperty(name = "herd.execution.hyperliquid.asset-index", defaultValue = "0")
    int hyperliquidAssetIndex;

    public GameMode mode() {
        return GameMode.parse(modeRaw);
    }

    public RoundDurations roundDurations() {
        return new RoundDurations(hourlySec * 1000L, dailySec * 1000L);
    }

    public double replaySpeedup() {
        return replaySpeedup;
    }

    public int botsCount() {
        return botsCount;
    }

    public int botsMarketsPerRound() {
        return botsMarketsPerRound;
    }

    public long tickMs() {
        return tickMs;
    }

    public String operatorToken() {
        return operatorToken;
    }

    public String sessionCookieName() {
        return sessionCookieName;
    }

    public long sessionTtlMs() {
        return sessionTtlHours * 3_600_000L;
    }

    public long challengeTtlMs() {
        return challengeTtlSec * 1000L;
    }

    public String oracleLauncherId() {
        return oracleLauncherId;
    }

    public String oracleNetwork() {
        return oracleNetwork;
    }

    /** FIXED INTERFACE (a): the bearer token this server sends to chainsvc on every {@code
     *  /v1/*} call (chainsvc's own {@code C2_SVC_TOKEN} must hold the same value — {@code
     *  tools/stand.sh} generates both into {@code .env} together). */
    public String chainsvcToken() {
        return chainsvcToken;
    }

    public int rateLimitCapacity() {
        return rateLimitCapacity;
    }

    public double rateLimitRefillPerSecond() {
        return rateLimitRefillPerSecond;
    }

    public boolean depositEnabled() {
        return depositEnabled;
    }

    public boolean withdrawalEnabled() {
        return withdrawalEnabled;
    }

    public boolean cookieSecure() {
        return cookieSecure;
    }

    public boolean hyperliquidExecutionEnabled() {
        return hyperliquidExecutionEnabled;
    }

    /** {@code ""} when unset, never {@code null} — every caller below already only checks
     *  {@code isBlank()}, matching every other optional-string convention in this class. */
    public String hyperliquidWalletAddress() {
        return hyperliquidWalletAddress.orElse("");
    }

    public int hyperliquidAssetIndex() {
        return hyperliquidAssetIndex;
    }
}
