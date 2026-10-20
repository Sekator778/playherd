package crowdvsmarket.server.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import java.util.List;

/**
 * Everything persisted to {@code game_events} (append-only) in order to deterministically rebuild
 * the in-memory {@code GameEngine}/{@code LedgerBook}/{@code CrowdAccount} state on startup — see
 * docs/research/server_design.md "Persistence". Each variant carries exactly what its matching
 * {@code EventApplier} method needs to replay the original engine call byte-for-byte, including
 * the {@code atMs} timestamp the live call used (never re-derived from wall-clock at replay time).
 *
 * <p>Read-model tables ({@code round_results}, {@code oracle_records}, …) are written beside the event, once, when first
 * produced, and read as-is. <b>Architecture plan F4 (schema version 2):</b> the journal is self-contained for the players and the
 * markets: {@link PlayerRegistered} carries the row of a new player ({@link NicknameChanged}, {@link BotStrategyAssigned} and
 * {@link BotRetired} its later changes), {@link MarketListed} / {@link MarketClosed} the market registry, and {@link RoundResolved}
 * the oracle sequence number the engine assigned. The {@code players} and {@code markets} tables are projections of these events,
 * written in the event's own transaction and repaired from the journal at every rebuild ({@code EngineStateBuilder}). What stays
 * outside the journal on purpose: the API keys of API bots, the referral and source columns of a player, the withdrawal and deposit
 * bookkeeping tables. An event of schema version 1 (everything written before F4) has none of the new fields and replays as it always did.
 *
 * <p><b>Markets (Р-69, docs/research/markets_architecture.md §6, Т-13):</b> the four round events
 * ({@link BetPlaced}, {@link BetCancelled}, {@link RoundResolved}, {@link RoundCancelled}) carry an
 * optional trailing {@code market} (a {@code MarketSpec} id). A row written before markets existed
 * has no such key, Jackson hands the component {@code null}, and the compact constructor turns it
 * into {@code "BTC"} — so every pre-markets row replays exactly as before, with no schema bump
 * ({@code EventStore.CURRENT_SCHEMA_VERSION} stays 1). Each of the four also keeps its pre-markets
 * constructor (BTC) for callers and fixtures that predate the field; production code names the
 * market explicitly.
 *
 * <p><b>Venues (Р-76):</b> {@link RoundResolved} has one more optional trailing field, {@code venue}, the exchange a
 * round of a market with a backup exchange ran on and was settled from. It is written only when there is one
 * ({@code @JsonInclude(NON_NULL)}): the event of any other round is the same bytes it always was, and a row without the
 * key reads as {@code null}. The engine does not use it — it is the record of where the prices of the event came from, next
 * to the prices themselves. Still no schema bump.
 */
public sealed interface GameEvent {

    /** A missing market means BTC (Т-13) — the one place that rule lives. */
    private static String marketOrBtc(String market) {
        return market == null ? MarketSpec.BTC_ID : market;
    }

    /** Grants a first-activity-in-season starter pack immediately at sign-in (Р-2), rather than
     *  waiting for the player's first bet — {@code LedgerBook.ensureSeasonPack} is idempotent
     *  per season, so this is safe to also happen implicitly again later via a bet/withdrawal, and
     *  tolerates being applied twice for the same season without granting a second starter pack
     *  (relied on by replay of any already-persisted duplicate row, see
     *  {@code GameService#registerOrTouchPlayer}'s task item 1 fix javadoc). Since that fix,
     *  {@code GameService} only actually appends one of these for a brand-new player or a genuine
     *  season rollover — not on every plain re-login — but a historical duplicate from before the
     *  fix must still replay safely, which is exactly what this idempotency already gives for free. */
    record PlayerRegistered(String playerId, long atMs,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String walletAddress,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Integer playerIndex,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String displayName,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String kind,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String botStrategy,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String ownerPlayerId,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String replacesPlayerId) implements GameEvent {

        /** The shape before F4 and the event of a returning player (a new season's pack): the player exists already. */
        public PlayerRegistered(String playerId, long atMs) {
            this(playerId, atMs, null, null, null, null, null, null, null);
        }

        /** A registration: the event carries the player's row, so the {@code players} table is its projection (architecture plan F4).
         *  {@code displayName} is the name chosen at registration ({@code null}: a person who has none yet); the key of an API bot is
         *  never in the journal. */
        public static PlayerRegistered registration(String playerId, long atMs, String walletAddress, int playerIndex,
                                                    String displayName, String kind, String botStrategy, String ownerPlayerId,
                                                    String replacesPlayerId) {
            return new PlayerRegistered(playerId, atMs, java.util.Objects.requireNonNull(walletAddress, "walletAddress"),
                    playerIndex, displayName, java.util.Objects.requireNonNull(kind, "kind"), botStrategy, ownerPlayerId,
                    replacesPlayerId);
        }

        /** Whether this event registers the player (it carries the row) and not only grants the pack of a new season. */
        public boolean isRegistration() {
            return walletAddress != null;
        }
    }

    /** F4: a player's nickname was set (a person's own, or a bot relabelled). The {@code players} row is its projection. */
    record NicknameChanged(String playerId, String nickname, long atMs) implements GameEvent {
    }

    /** F4: a house bot was assigned the strategy of a catalog slot. The {@code players} row is its projection. */
    record BotStrategyAssigned(String playerId, String botStrategy, long atMs) implements GameEvent {
    }

    /**
     * F4: a market was listed. Carries the whole row, the append-only {@code oracleIndex} and the economy parameters the registry
     * gave it, so the {@code markets} table is a projection of the journal and a replay never recomputes a default. BTC is not listed
     * by an event: it is the genesis row ({@code V7__markets.sql}).
     */
    record MarketListed(String marketId, String kind, String category, String symbol, String title, String subtitle,
                        String iconUrl, String source, String sourceRef, String sourceMeta, int maxLeverage, int lotExp,
                        int oracleIndex, long listedAtMs, Long endsAtMs, int capHourly, int capDaily, String kappa,
                        String herdFeeBp, String stressMove) implements GameEvent {
    }

    /** F4: a market was closed (an operator, an ended question, a market that took over its symbol). */
    record MarketClosed(String marketId, String reason, long atMs) implements GameEvent {
    }

    /**
     * {@code feeHerdMojos} / {@code feeNetMojos} (economy stage A, Р-92, plan A4) are the fees charged with the stake, written
     * only for a bet placed under the new rules ({@code @JsonInclude(NON_NULL)}: an event of the old shape is the same bytes
     * it always was). A row written before the change has neither key, Jackson hands the components {@code null}, and
     * {@code EventApplier} then places a <b>legacy</b> bet: no fees, the original settlement rules — so an old log replays to
     * the balances it always did. Still no schema bump ({@code EventStore.CURRENT_SCHEMA_VERSION} stays 1); the fees are
     * stored, never recomputed, so a later change of a rate cannot move a replayed balance.
     *
     * <p>{@code liqCap} / {@code liqKappa} (economy stage B) are the liquidation parameters of the bet - the leverage cap of its market
     * and round length and the maintenance constant kappa, {@code l = kappa / cap} - stored for the same reason: replay settles the
     * bet with them and never with the market's row of the day (the weekly recalculation of stage D changes caps). They are written
     * only for a bet with fees, and a row of stage A without them is settled with the market's current ones (stage A was never
     * on a public stand, so no such row exists in production).
     */
    record BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                       int leverage, long stakeMojos, long atMs, String market,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Long feeHerdMojos,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Long feeNetMojos,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer liqCap,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String liqKappa,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String mode) implements GameEvent {

        public BetPlaced {
            market = marketOrBtc(market);
            if (mode != null) {
                crowdvsmarket.engine.round.BetMode.fromWire(mode); // refuses a name that is not a mode
                if (!"FIXED".equals(mode)) {
                    throw new IllegalArgumentException("mode is written only for the fixed-payout mode, the percentage mode is an absent field");
                }
                if (feeHerdMojos == null || liqCap != null) {
                    throw new IllegalArgumentException("a fixed-payout bet has fees and no liquidation parameters");
                }
            }
            if ((feeHerdMojos == null) != (feeNetMojos == null)) {
                throw new IllegalArgumentException("feeHerdMojos and feeNetMojos are both set or both absent");
            }
            if ((feeHerdMojos != null && feeHerdMojos < 0) || (feeNetMojos != null && feeNetMojos < 0)) {
                throw new IllegalArgumentException("fees must not be negative");
            }
            if ((liqCap == null) != (liqKappa == null)) {
                throw new IllegalArgumentException("liqCap and liqKappa are both set or both absent");
            }
            if (liqCap != null && feeHerdMojos == null) {
                throw new IllegalArgumentException("liquidation parameters belong to a bet placed under the economy rules (with fees)");
            }
        }

        /** The shape before the fixed-payout mode (Р-106): a percentage-mode bet, the {@code mode} key absent. */
        public BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                           int leverage, long stakeMojos, long atMs, String market, Long feeHerdMojos, Long feeNetMojos,
                           Integer liqCap, String liqKappa) {
            this(playerId, interval, windowStart, windowEnd, direction, leverage, stakeMojos, atMs, market, feeHerdMojos,
                    feeNetMojos, liqCap, liqKappa, null);
        }

        /**
         * A fixed-payout bet (Р-106, docs/product/fixed_payout_rules.md): no leverage (the lowest value is written as a placeholder),
         * fees, no liquidation parameters, {@code "mode":"FIXED"}.
         */
        public static BetPlaced fixed(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                                      long stakeMojos, long atMs, String market, long feeHerdMojos, long feeNetMojos) {
            return new BetPlaced(playerId, interval, windowStart, windowEnd, direction,
                    crowdvsmarket.engine.round.Bet.FIXED_LEVERAGE.value(), stakeMojos, atMs, market, feeHerdMojos, feeNetMojos,
                    null, null, "FIXED");
        }

        /** The mode of the bet: an absent field is the percentage mode. */
        public crowdvsmarket.engine.round.BetMode betMode() {
            return crowdvsmarket.engine.round.BetMode.fromWire(mode);
        }

        /** Stage-A shape: fees, but no stored liquidation parameters (replayed with the market's current cap and kappa). */
        public BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                           int leverage, long stakeMojos, long atMs, String market, Long feeHerdMojos, Long feeNetMojos) {
            this(playerId, interval, windowStart, windowEnd, direction, leverage, stakeMojos, atMs, market, feeHerdMojos,
                    feeNetMojos, null, null);
        }

        /** Pre-economy shape with a market: a legacy bet (no fees). */
        public BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                           int leverage, long stakeMojos, long atMs, String market) {
            this(playerId, interval, windowStart, windowEnd, direction, leverage, stakeMojos, atMs, market, null, null, null, null);
        }

        /** Pre-markets shape: a bet on BTC. */
        public BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                           int leverage, long stakeMojos, long atMs) {
            this(playerId, interval, windowStart, windowEnd, direction, leverage, stakeMojos, atMs, null, null, null, null, null);
        }

        /** Whether the bet was placed under the economy-stage-A rules (it carries its fees). */
        public boolean hasFees() {
            return feeHerdMojos != null;
        }

        /** The cap and kappa the bet was placed with, or {@code null} when the event did not store them. */
        public crowdvsmarket.engine.round.LiquidationParams liquidation() {
            return liqCap == null ? null
                    : new crowdvsmarket.engine.round.LiquidationParams(liqCap, new java.math.BigDecimal(liqKappa));
        }
    }

    record BetCancelled(String playerId, Interval interval, long windowStart, long windowEnd, long atMs,
                          String market) implements GameEvent {

        public BetCancelled {
            market = marketOrBtc(market);
        }

        /** Pre-markets shape: a bet on BTC. */
        public BetCancelled(String playerId, Interval interval, long windowStart, long windowEnd, long atMs) {
            this(playerId, interval, windowStart, windowEnd, atMs, null);
        }
    }

    /** One OHLCV candle, decimal fields as exact strings (no float round-trip) — mirrors
     *  {@code crowdvsmarket.engine.market.Candle}'s constructor arguments. */
    record CandleData(long openTimeMs, long closeTimeMs, String open, String high, String low, String close,
                        String volume, long trades) {
    }

    /** The exact price path {@code GameEngine.runRound} was called with, captured so replay is
     *  independent of the (possibly wall-clock-driven, e.g. live-mode) price source that produced
     *  it originally. */
    record RoundResolved(Interval interval, long windowStart, long windowEnd, String openPrice, String closePrice,
                           List<CandleData> path, long atMs, String market,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String venue,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long oracleSeq) implements GameEvent {

        public RoundResolved {
            market = marketOrBtc(market);
        }

        /** Before F4: no oracle sequence number in the event (it was taken from {@code server_meta} when the rows were written). */
        public RoundResolved(Interval interval, long windowStart, long windowEnd, String openPrice, String closePrice,
                               List<CandleData> path, long atMs, String market, String venue) {
            this(interval, windowStart, windowEnd, openPrice, closePrice, path, atMs, market, venue, null);
        }

        /** Pre-venues shape: a round of a market with one exchange (the event is then written without a {@code venue} key, as ever). */
        public RoundResolved(Interval interval, long windowStart, long windowEnd, String openPrice,
                               String closePrice, List<CandleData> path, long atMs, String market) {
            this(interval, windowStart, windowEnd, openPrice, closePrice, path, atMs, market, null);
        }

        /** Pre-markets shape: a BTC round. */
        public RoundResolved(Interval interval, long windowStart, long windowEnd, String openPrice,
                               String closePrice, List<CandleData> path, long atMs) {
            this(interval, windowStart, windowEnd, openPrice, closePrice, path, atMs, null, null, null);
        }
    }

    /** Operator/automatic cancellation (Р-23, Р-32): refunds every bet still pending for the
     *  round. */
    record RoundCancelled(Interval interval, long windowStart, long windowEnd, String reason, long atMs,
                            String market) implements GameEvent {

        public RoundCancelled {
            market = marketOrBtc(market);
        }

        /** Pre-markets shape: a BTC round. */
        public RoundCancelled(Interval interval, long windowStart, long windowEnd, String reason, long atMs) {
            this(interval, windowStart, windowEnd, reason, atMs, null);
        }
    }

    /**
     * Economy stage B (plan B1): the cutover to the house accounts, written once. {@code openingTotalMojos} is the total the accounts
     * must add up to - the treasury coin on chain, configured at the time ({@code herd.economy.treasury-opening-play}); it is
     * stored in the event, so a replay opens TREASURY with the same number whatever the configuration is by then. TREASURY opens with it
     * minus what the players hold when the event is applied (balances and open stakes), HERD with 0. Events before it replay as they
     * always did (packs and wins minted, lost stakes burned).
     */
    record EconomyCutover(long openingTotalMojos, long atMs) implements GameEvent {
    }

    /**
     * Economy stage B (plan B4): a bot retires - its balance goes to TREASURY, it bets no more and is in no list. Internal: no screen
     * and no public endpoint shows it. The bot that replaces it (an ordinary {@link PlayerRegistered}) comes at least an hour later.
     */
    record BotRetired(String playerId, long atMs) implements GameEvent {
    }

    /**
     * Economy stage B (plan B4): the daily move of the fees the bots paid from HERD to TREASURY. The amount is decided when the event
     * is written and stored, so a replay moves the same amount.
     */
    record HerdSweptToTreasury(long amountMojos, long atMs) implements GameEvent {
    }

    /** No {@code requestId} field on purpose: {@code LedgerBook.requestWithdrawal} assigns its own
     *  ({@code "w-N"}, a counter shared with deposits' {@code "d-N"}) — replaying this same event
     *  in the same position in the log reproduces the identical id deterministically, so the id is
     *  an output of applying the event, not an input to it (see {@code GameService.requestWithdrawal}). */
    record WithdrawalRequested(String playerId, long amountMojos, long atMs) implements GameEvent {
    }

    record WithdrawalConfirmed(String requestId, long atMs) implements GameEvent {
    }

    record WithdrawalFailed(String requestId, long atMs) implements GameEvent {
    }

    /** Same id-reuse note as {@link WithdrawalRequested}. */
    record DepositRequested(String playerId, long amountMojos, long atMs) implements GameEvent {
    }

    record DepositConfirmed(String requestId, long atMs) implements GameEvent {
    }

    record DepositFailed(String requestId, long atMs) implements GameEvent {
    }

    /** Wave-2 security fix (Р-32/Р-43 pattern): an operator correction that reverses the balance/
     *  season effect a withdrawal request originally applied (e.g. the {@code
     *  requestWithdrawal("-5000.000")} exploit) — {@code GameService#reverseWithdrawal}. Fully
     *  self-contained on purpose: {@code originalAmountMojos}/{@code originalSeasonId} are captured
     *  once, at command time, from the request's own already-recorded values, rather than being
     *  re-derived from {@code relatedWithdrawalRequestId} on replay (see
     *  {@code crowdvsmarket.engine.ledger.LedgerBook#reverseWithdrawalEffect}'s javadoc).
     *  {@code relatedWithdrawalRequestId} is carried only for the public audit trail. */
    record WithdrawalReversed(String playerId, long originalAmountMojos, String originalSeasonId, String reason,
                                String relatedWithdrawalRequestId, long atMs) implements GameEvent {
    }

    /** Task item 1 (wave-3 security fix, Р-6 deposit-verification gap): an operator correction
     *  that reverses the balance/season effect a deposit request originally applied — e.g. the
     *  fake self-reported deposits {@code d-2}/{@code d-4}/{@code d-7}/{@code d-10} accepted before
     *  {@code GameService#requestDeposit} started rejecting new deposits with
     *  {@code DEPOSIT_VERIFICATION_UNAVAILABLE} (no real on-chain CAT-deposit verifier exists yet,
     *  Р-6 decision pending with Alex). Exactly {@link WithdrawalReversed}'s shape/reasoning,
     *  mirrored for deposits — see {@code crowdvsmarket.engine.ledger.LedgerBook
     *  #reverseDepositEffect}'s javadoc. */
    record DepositReversed(String playerId, long originalAmountMojos, String originalSeasonId, String reason,
                             String relatedDepositRequestId, long atMs) implements GameEvent {
    }

    /** Р-53: a real, chain-confirmed PLAY CAT deposit detected by {@code DepositPollerService}
     *  ({@code GET /v1/play/deposits}) — unlike {@link DepositRequested}/{@link DepositConfirmed}
     *  (the self-report two-step, permanently retired at the RPC level but kept here only so
     *  already-persisted historical rows keep replaying), there is no separate request: the coin's
     *  existence on chain, at the player's known deposit address, IS the request and the
     *  confirmation in one, so this single event carries both. {@code coinId} is this deposit's own
     *  natural idempotency key — replay safety is enforced one layer up, by {@code
     *  DepositPollerService} only ever emitting this event the FIRST time it successfully reserves
     *  a given {@code coinId} in {@code deposit_confirmations} (a plain read-model table, not
     *  itself replayed); once emitted, ordinary event-log replay re-applies it exactly once, like
     *  every other event here. {@code playerIndex} is carried for audit/debugging only — {@code
     *  EventApplier}'s case resolves the credit by {@code playerId}, already resolved once at
     *  poll time. */
    record DepositConfirmedOnChain(String playerId, int playerIndex, long amountMojos, String coinId, long height,
                                     long atMs) implements GameEvent {
    }

    /** Р-53: the real on-chain counterpart of {@link WithdrawalConfirmed} (kept only for replaying
     *  pre-Р-53 auto-confirmed rows) — carries the CAT coin's own {@code coinId}/{@code height},
     *  which the task requires be "public in the player's ledger" once confirmed. The engine-side
     *  effect is identical to {@code WithdrawalConfirmed} (the escrow debit already happened at
     *  {@link WithdrawalRequested} time; confirming just closes out the request) — {@code coinId}/
     *  {@code height} are read-model-only, {@code LedgerBook} itself has no use for them. */
    record WithdrawalConfirmedOnChain(String requestId, String coinId, long height, long atMs)
            implements GameEvent {
    }
}
