package crowdvsmarket.server.config;

import crowdvsmarket.engine.round.RoundDurations;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Central read of the {@code herd.*} config keys (application.properties) used across the
 *  server; small enough to keep as one bean rather than a {@code @ConfigMapping} per feature. */
@ApplicationScoped
public class GameConfig {

    /** PLAY's asset id (the CAT's TAIL hash) as issued on testnet11 on 2026-09-25 — docs/research/
     *  week1_testnet_results.md §3, the same number web/src/components/playAsset.ts falls back to. */
    public static final String TESTNET11_PLAY_ASSET_ID =
            "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

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

    /**
     * Economy stage B (plan B1): the total the house accounts add up to at the cutover, in whole PLAY - the treasury coin on chain,
     * 99 998 100 PLAY (checked on chain 2026-10-15, docs/product/economy_implementation_plan.md). TREASURY opens with it minus what the
     * players hold. {@code 0} switches the economy off: no cutover event is written (a stand that must keep the old rules).
     */
    @ConfigProperty(name = "herd.economy.treasury-opening-play", defaultValue = "99998100")
    long economyTreasuryOpeningPlay;

    /** Plan B3: how many new players one IP address may register in 24 hours. */
    @ConfigProperty(name = "herd.registration.max-per-ip-per-day", defaultValue = "3")
    int registrationMaxPerIpPerDay;

    /** Plan B4: a bot whose balance is below this many PLAY retires. */
    @ConfigProperty(name = "herd.bots.retire-below-play", defaultValue = "100")
    long botsRetireBelowPlay;

    /** Plan B4: how long after a bot retired its replacement is created (milliseconds). */
    @ConfigProperty(name = "herd.bots.respawn-delay-ms", defaultValue = "3600000")
    long botsRespawnDelayMs;

    /**
     * Which brain plays the bots: {@code catalog} (default, the {@code bots} module: 21 bots of the catalog, rules on candles and a
     * persona each) or {@code legacy} (the five {@code BotStrategy} kinds as before). The operator's roll-back without a redeploy.
     */
    @ConfigProperty(name = "herd.bots.engine", defaultValue = "catalog")
    String botsEngine;

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    /** The domain a player signs at sign-in ("Domain: …" in the challenge text, {@code AuthService}): the
     *  public domain of this deployment (compose passes {@code HERD_PUBLIC_DOMAIN}). It used to be the
     *  literal {@code herd.local} in the code. */
    @ConfigProperty(name = "herd.public-domain", defaultValue = "herd.local")
    String publicDomain;

    /** The PLAY CAT's asset id on the oracle network, for {@code serverInfo().playAssetId} (what a wallet
     *  needs to show PLAY and to send a deposit). The default is the testnet11 issuance. */
    @ConfigProperty(name = "herd.play.asset-id", defaultValue = TESTNET11_PLAY_ASSET_ID)
    String playAssetId;

    /** Whether the browser test wallet (Р-45) is offered on the sign-in sheet ({@code
     *  serverInfo().testWallet}); a public deployment with real wallets only turns it off. */
    @ConfigProperty(name = "herd.test-wallet.enabled", defaultValue = "true")
    boolean testWalletEnabled;

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

    /** Р-106: the token bucket of ONE API key (a bot), separate from the per-address one above: capacity is the burst a bot may make,
     *  the refill is what flows back every second. Every call a key makes takes one token, reads included. */
    @ConfigProperty(name = "herd.ratelimit.bot.capacity", defaultValue = "60")
    int botRateLimitCapacity;

    @ConfigProperty(name = "herd.ratelimit.bot.refill-per-sec", defaultValue = "5")
    double botRateLimitRefillPerSecond;

    /** Р-81: the per-IP token bucket of {@code submitFeedback} — its own budget, far stricter than the shared one above
     *  (a feedback message is written to the database and read by a person). Capacity: how many messages one address
     *  may send in a burst. */
    @ConfigProperty(name = "herd.feedback.rate-limit.capacity", defaultValue = "5")
    int feedbackRateLimitCapacity;

    /** Р-81: tokens that flow back into a feedback bucket per HOUR (not per second, unlike the shared bucket: the
     *  numbers are small and an hour is the unit an operator thinks in). */
    @ConfigProperty(name = "herd.feedback.rate-limit.refill-per-hour", defaultValue = "6")
    double feedbackRateLimitRefillPerHour;

    /** Р-81: how many feedback messages the server takes in any 24 hours, from everyone together; with that many in the
     *  last 24 hours {@code submitFeedback} answers {@code 429 RATE_LIMITED} until older ones drop out of the count. */
    @ConfigProperty(name = "herd.feedback.max-per-day", defaultValue = "1000")
    int feedbackMaxPerDay;

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

    /** {@code true} for the catalog brain; anything but {@code catalog} (case aside) is the legacy bots. */
    public boolean botsCatalogEngine() {
        return "catalog".equalsIgnoreCase(botsEngine == null ? "catalog" : botsEngine.strip());
    }

    public long tickMs() {
        return tickMs;
    }

    public long economyTreasuryOpeningPlay() {
        return economyTreasuryOpeningPlay;
    }

    public int registrationMaxPerIpPerDay() {
        return registrationMaxPerIpPerDay;
    }

    public long botsRetireBelowPlay() {
        return botsRetireBelowPlay;
    }

    public long botsRespawnDelayMs() {
        return botsRespawnDelayMs;
    }

    public String operatorToken() {
        return operatorToken;
    }

    public String publicDomain() {
        return publicDomain;
    }

    public String playAssetId() {
        return playAssetId;
    }

    public boolean testWalletEnabled() {
        return testWalletEnabled;
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

    public int botRateLimitCapacity() {
        return botRateLimitCapacity;
    }

    public double botRateLimitRefillPerSecond() {
        return botRateLimitRefillPerSecond;
    }

    public int feedbackRateLimitCapacity() {
        return feedbackRateLimitCapacity;
    }

    public double feedbackRateLimitRefillPerHour() {
        return feedbackRateLimitRefillPerHour;
    }

    public int feedbackMaxPerDay() {
        return feedbackMaxPerDay;
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
