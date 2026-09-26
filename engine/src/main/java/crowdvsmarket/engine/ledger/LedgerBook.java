package crowdvsmarket.engine.ledger;

import crowdvsmarket.engine.season.Season;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The in-game PLAY ledger: balances, starter packs, stakes, settlements and withdraw/deposit
 * requests (Р-2, Р-3, Р-6, Р-7, Р-12, Р-16). Single-threaded, functional-core-mutable-shell: every
 * {@link PlayerAccount}/{@link SeasonRecord} is an immutable snapshot, replaced wholesale on each
 * mutation; every mutation also appends one {@link LedgerEntry} to an append-only audit log
 * ({@link #entries()}), which is what {@code MonthLongHourlySimulationTest} replays to check the
 * engine's conservation invariant: <b>the only PLAY the house mints is starter packs and
 * net settlement gains; the only PLAY it burns is net settlement losses — including a full loss of
 * the stake on liquidation (Р-7)</b>. Deposits/withdrawals move PLAY across the on/off-chain
 * boundary rather than creating or destroying it in-game.
 *
 * <p>Balance never goes negative (enforced on every debit): a stake or withdrawal above the
 * current balance is rejected before anything is recorded.
 */
public final class LedgerBook {

    /** Starter pack granted on a player's first activity in a season (Р-2, Р-12, Р-16). */
    public static final PlayAmount STARTER_PACK = PlayAmount.ofWholePlay(10_000);
    /** Minimum stake per bet (Р-12). */
    public static final PlayAmount MIN_STAKE = PlayAmount.ofWholePlay(100);

    private final Map<String, PlayerAccount> accounts = new LinkedHashMap<>();
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Map<String, WithdrawalRequest> withdrawals = new LinkedHashMap<>();
    private final Map<String, DepositRequest> deposits = new LinkedHashMap<>();
    private final List<LedgerCorrection> corrections = new ArrayList<>();
    private long sequence = 0;
    private long requestCounter = 0;
    private long correctionCounter = 0;

    // --- reads ---

    public PlayAmount balance(String playerId) {
        return account(playerId).balance();
    }

    public Optional<SeasonRecord> seasonRecord(String playerId, Season season) {
        PlayerAccount acc = accounts.get(playerId);
        return acc == null ? Optional.empty() : Optional.ofNullable(acc.seasons().get(season));
    }

    public Collection<String> playerIds() {
        return List.copyOf(accounts.keySet());
    }

    /** The full append-only movement log, in order — see the class-level conservation note. */
    public List<LedgerEntry> entries() {
        return List.copyOf(entries);
    }

    public Optional<WithdrawalRequest> withdrawal(String requestId) {
        return Optional.ofNullable(withdrawals.get(requestId));
    }

    public Optional<DepositRequest> deposit(String requestId) {
        return Optional.ofNullable(deposits.get(requestId));
    }

    /** Every operator ledger correction ever issued, in order — the public audit trail wave-2's
     *  {@link #reverseWithdrawalEffect} writes to (see {@link LedgerCorrection}). */
    public List<LedgerCorrection> corrections() {
        return List.copyOf(corrections);
    }

    private PlayerAccount account(String playerId) {
        PlayerAccount acc = accounts.get(playerId);
        if (acc == null) {
            throw new NoSuchElementException("unknown player: " + playerId);
        }
        return acc;
    }

    // --- onboarding / seasons (Р-16) ---

    /**
     * Lazily grants a starter pack on the player's first activity in {@code now}'s season (Р-2,
     * Р-12): a brand-new player, or an existing one whose current season has rolled over. Records
     * the pre-pack balance as the new season's baseline (Р-16, used by the "богачи" rating, Р-18).
     * A no-op if the player is already on {@code now}'s season. Every mutating operation below
     * calls this first, so callers rarely need to call it directly.
     */
    public PlayerAccount ensureSeasonPack(String playerId, Instant now) {
        Season season = Season.of(now);
        PlayerAccount acc = accounts.get(playerId);
        if (acc == null) {
            accounts.put(playerId, new PlayerAccount(playerId, PlayAmount.ZERO, season,
                    new LinkedHashMap<>(Map.of(season, SeasonRecord.starting(season, PlayAmount.ZERO)))));
            credit(playerId, STARTER_PACK, LedgerEntryType.STARTER_PACK, null, now);
            return accounts.get(playerId);
        }
        if (season.compareTo(acc.currentSeason()) > 0) {
            PlayAmount baseline = acc.balance();
            Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
            seasons.put(season, SeasonRecord.starting(season, baseline));
            accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), season, seasons));
            credit(playerId, STARTER_PACK, LedgerEntryType.STARTER_PACK, null, now);
            return accounts.get(playerId);
        }
        return acc;
    }

    // --- stakes (Р-8, Р-13) ---

    /** Reserves {@code stake} for a bet: validates the Р-12 minimum and that it does not exceed
     * the current balance, then debits it. */
    public void reserveStake(String playerId, String roundId, PlayAmount stake, Instant now) {
        stake.requirePositive("stake"); // wave-2 fix: below-minimum already implies positive (MIN_STAKE
        // > 0), but this makes the domain's own refusal of non-positive stakes explicit rather than
        // an accident of MIN_STAKE's value — see PlayAmount#requirePositive's javadoc.
        ensureSeasonPack(playerId, now);
        if (stake.compareTo(MIN_STAKE) < 0) {
            throw new IllegalArgumentException("stake below minimum " + MIN_STAKE + ": " + stake);
        }
        PlayAmount balance = balance(playerId);
        if (stake.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                    "stake " + stake + " exceeds balance " + balance + " for player " + playerId);
        }
        debit(playerId, stake, LedgerEntryType.STAKE_DEBIT, roundId, now);
    }

    /** Returns a reserved stake because the bet was cancelled or replaced before round start (Р-13). */
    public void refundStake(String playerId, String roundId, PlayAmount stake, Instant now) {
        credit(playerId, stake, LedgerEntryType.STAKE_REFUND, roundId, now);
    }

    /**
     * Settles a resolved (non-cancelled) position: credits {@code payout} — 0 on liquidation, per
     * Р-7 — and counts the round toward the Р-9/Р-18 rounds-played threshold and, when
     * {@code directionCorrect} is present, toward the "пророки" ratio (Р-18); {@code
     * Optional.empty()} for a flat round (close == open price), excluded from that ratio's
     * denominator.
     */
    public void settleRound(String playerId, String roundId, PlayAmount payout, Optional<Boolean> directionCorrect, Instant now) {
        ensureSeasonPack(playerId, now);
        credit(playerId, payout, LedgerEntryType.SETTLEMENT, roundId, now);
        updateCurrentSeasonRecord(playerId, sr -> sr.withRoundPlayed(directionCorrect.orElse(null)));
    }

    // --- withdraw / deposit (Р-6), behind OnChainWalletPort ---

    /** Reserves {@code amount} pending the on-chain withdrawal (see {@link OnChainWalletPort}). */
    public WithdrawalRequest requestWithdrawal(String playerId, PlayAmount amount, Instant now) {
        // Wave-2 security fix, deliberately NOT enforced here (see PlayAmount#requirePositive):
        // this exact method is the one shared code path GameService uses to apply a brand-new,
        // live withdrawal command AND EventApplier/EngineRebuildService use to REPLAY an already-
        // persisted historical WithdrawalRequested event on every restart. A non-positive amount
        // here used to mint PLAY (debit(amount) below negates amount before applying it, so
        // amount=-5000 became a +5000 credit — the requestWithdrawal("-5000.000") exploit), and
        // that bug is already an immutable past event this task forbids altering or deleting.
        // Guarding it HERE would make every future restart crash while replaying that one
        // historical event (IllegalArgumentException, uncaught, in EngineRebuildService's replay
        // loop) — i.e. deploying this very fix to a stand that already has the bad event in its
        // log would break startup, before the operator correction below ever gets a chance to run.
        // The domain-level guard instead lives at the one place a genuinely NEW withdrawal command
        // is decided — GameService.requestWithdrawal, BEFORE it ever persists/applies an event —
        // which is the only caller an external attacker can actually reach; replay only ever
        // re-applies commands GameService already accepted (or, for this one historical row,
        // accepted before this fix existed). See #reverseWithdrawalEffect for how the one already-
        // corrupted balance gets corrected instead: a new, explicit, public event, not a retroactive
        // edit of this one.
        ensureSeasonPack(playerId, now);
        PlayAmount balance = balance(playerId);
        if (amount.compareTo(balance) > 0) {
            throw new IllegalArgumentException("withdrawal " + amount + " exceeds balance " + balance);
        }
        debit(playerId, amount, LedgerEntryType.WITHDRAWAL, null, now);
        updateCurrentSeasonRecord(playerId, sr -> sr.withWithdrawalDelta(amount));
        String id = "w-" + (++requestCounter);
        WithdrawalRequest request = new WithdrawalRequest(id, playerId, amount, WithdrawalState.REQUESTED, now.toEpochMilli());
        withdrawals.put(id, request);
        return request;
    }

    public WithdrawalRequest confirmWithdrawal(String requestId) {
        return transitionWithdrawal(requestId, WithdrawalState.CONFIRMED);
    }

    /** The on-chain leg failed: returns the escrowed amount to the balance and un-counts it from
     * the season's withdrawn total. */
    public WithdrawalRequest failWithdrawal(String requestId, Instant now) {
        WithdrawalRequest updated = transitionWithdrawal(requestId, WithdrawalState.FAILED);
        credit(updated.playerId(), updated.amount(), LedgerEntryType.WITHDRAWAL_REFUND, null, now);
        updateCurrentSeasonRecord(updated.playerId(), sr -> sr.withWithdrawalDelta(updated.amount().negate()));
        return updated;
    }

    private WithdrawalRequest transitionWithdrawal(String requestId, WithdrawalState newState) {
        WithdrawalRequest current = withdrawals.get(requestId);
        if (current == null) {
            throw new NoSuchElementException("unknown withdrawal request: " + requestId);
        }
        if (current.state() != WithdrawalState.REQUESTED) {
            throw new IllegalStateException("withdrawal " + requestId + " already " + current.state());
        }
        WithdrawalRequest updated = current.withState(newState);
        withdrawals.put(requestId, updated);
        return updated;
    }

    /**
     * Wave-2 security fix (Р-32/Р-43 pattern): an operator correction that reverses the balance/
     * season effect a withdrawal request originally applied. Deliberately does <b>not</b> look up
     * {@code relatedWithdrawalRequestId} in {@link #withdrawal} — the caller (see
     * {@code GameService#reverseWithdrawal}) reads the request's own {@code originalAmount}/
     * implied season from live state ONCE, at command time, and this method is given those values
     * directly. This makes the correction's own replay fully self-contained: it never needs to
     * re-resolve {@code relatedWithdrawalRequestId} against {@link #withdrawals} at replay time, so
     * it stays correct even if a future change makes that lookup unavailable or renamed — a
     * deliberately loose coupling for a correction whose whole point is to outlive the bug it fixes.
     *
     * <p>Unlike {@link #failWithdrawal}, this does not require {@code REQUESTED} state and does not
     * transition the original request's own state at all — the historical record, including
     * whatever bug produced it, is left exactly as it happened. The correction is its own new,
     * append-only, publicly-reasoned entry ({@link LedgerEntryType#OPERATOR_CORRECTION} + a
     * {@link LedgerCorrection}), not a retroactive edit. {@code originalAmount} is applied to the
     * balance as-is (the exact inverse of what {@code requestWithdrawal}'s {@code debit(amount)}
     * applied — negating a negated amount reproduces the original sign), and {@code
     * originalAmount.negate()} is applied to {@code originalSeason}'s {@code withdrawn}
     * bookkeeping (undoing that same original call's {@code withWithdrawalDelta(amount)}). Can only
     * be applied once per {@code relatedWithdrawalRequestId} (idempotency guard against
     * double-correction — tracked via {@link #corrections}, itself replay-safe for the same reason
     * as above). Never allows the balance to go negative (same guard as every other primitive, via
     * {@link #applyDelta}).
     *
     * @throws IllegalStateException    if {@code relatedWithdrawalRequestId} was already reversed
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public LedgerCorrection reverseWithdrawalEffect(String playerId, PlayAmount originalAmount, Season originalSeason,
            String reason, String relatedWithdrawalRequestId, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("correction reason must not be blank");
        }
        boolean alreadyReversed = corrections.stream()
                .anyMatch(c -> relatedWithdrawalRequestId.equals(c.relatedRequestId()));
        if (alreadyReversed) {
            throw new IllegalStateException("withdrawal " + relatedWithdrawalRequestId + " was already reversed");
        }

        ensureSeasonPack(playerId, now);
        applyDelta(playerId, originalAmount, LedgerEntryType.OPERATOR_CORRECTION, null, now);
        adjustSeasonWithdrawn(playerId, originalSeason, originalAmount.negate());

        String id = "c-" + (++correctionCounter);
        LedgerCorrection correction = new LedgerCorrection(id, playerId, originalAmount, reason,
                relatedWithdrawalRequestId, now.toEpochMilli());
        corrections.add(correction);
        return correction;
    }

    /**
     * Wave-3 security fix (task item 1, Р-6 deposit-verification gap): an operator correction that
     * reverses the balance/season effect a deposit request originally applied — e.g. a
     * self-reported "I sent it" deposit that was auto-confirmed before any real on-chain
     * CAT-deposit verifier existed (see {@code GameService#requestDeposit}'s
     * {@code DEPOSIT_VERIFICATION_UNAVAILABLE} gate, added the same task). Exactly the same
     * shape/reasoning as {@link #reverseWithdrawalEffect} — see that method's javadoc for why the
     * caller reads {@code originalAmount}/{@code originalSeason} from live state once, at command
     * time, rather than this method re-resolving {@code relatedDepositRequestId} itself, and why
     * the original {@link DepositRequest}'s own state is left completely untouched.
     *
     * <p>{@code originalAmount} is applied to the balance as a debit — the exact inverse of
     * {@link #confirmDeposit}'s credit — and {@code originalAmount.negate()} is applied to
     * {@code originalSeason}'s {@code deposited} bookkeeping (undoing that same original call's
     * {@code withDeposit(amount)}). Shares {@link #corrections}' idempotency list with
     * {@link #reverseWithdrawalEffect} — a request id is either a {@code "w-N"} or a {@code "d-N"}
     * ({@link #requestCounter} is shared between the two, see {@link #requestWithdrawal}'s javadoc
     * on why replay depends on that), so they can never collide there. Never allows the balance to
     * go negative (same guard as every other primitive, via {@link #applyDelta}) — if the
     * (wrongly) credited PLAY was already spent or withdrawn, this throws
     * {@link IllegalStateException} and the operator must resolve the account by hand.
     *
     * @throws IllegalStateException    if {@code relatedDepositRequestId} was already reversed, or
     *                                   reversing it would take the balance negative
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public LedgerCorrection reverseDepositEffect(String playerId, PlayAmount originalAmount, Season originalSeason,
            String reason, String relatedDepositRequestId, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("correction reason must not be blank");
        }
        boolean alreadyReversed = corrections.stream()
                .anyMatch(c -> relatedDepositRequestId.equals(c.relatedRequestId()));
        if (alreadyReversed) {
            throw new IllegalStateException("deposit " + relatedDepositRequestId + " was already reversed");
        }

        ensureSeasonPack(playerId, now);
        PlayAmount debit = originalAmount.negate();
        applyDelta(playerId, debit, LedgerEntryType.OPERATOR_CORRECTION, null, now);
        adjustSeasonDeposited(playerId, originalSeason, debit);

        String id = "c-" + (++correctionCounter);
        LedgerCorrection correction = new LedgerCorrection(id, playerId, debit, reason,
                relatedDepositRequestId, now.toEpochMilli());
        corrections.add(correction);
        return correction;
    }

    /** Adjusts {@code season}'s {@code withdrawn} bookkeeping directly (unlike
     *  {@link #updateCurrentSeasonRecord}, which always targets the player's *current* season) — a
     *  no-op if that season's record does not exist (defensive; should not happen for a season a
     *  real withdrawal was recorded against). */
    private void adjustSeasonWithdrawn(String playerId, Season season, PlayAmount delta) {
        PlayerAccount acc = account(playerId);
        SeasonRecord existing = acc.seasons().get(season);
        if (existing == null) {
            return;
        }
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.put(season, existing.withWithdrawalDelta(delta));
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), acc.currentSeason(), seasons));
    }

    /** Same shape as {@link #adjustSeasonWithdrawn}, for {@code deposited} bookkeeping
     *  ({@link #reverseDepositEffect}) — a no-op if that season's record does not exist. */
    private void adjustSeasonDeposited(String playerId, Season season, PlayAmount delta) {
        PlayerAccount acc = account(playerId);
        SeasonRecord existing = acc.seasons().get(season);
        if (existing == null) {
            return;
        }
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.put(season, existing.withDeposit(delta));
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), acc.currentSeason(), seasons));
    }

    /** Registers a pending on-chain-to-ledger deposit; no balance change until confirmed. */
    public DepositRequest requestDeposit(String playerId, PlayAmount amount, Instant now) {
        // Wave-2 security fix — same replay-fidelity reasoning as requestWithdrawal above (this
        // method is likewise shared between live-apply and startup replay): the guard lives in
        // GameService.requestDeposit, before a new event is ever persisted, not here.
        ensureSeasonPack(playerId, now);
        String id = "d-" + (++requestCounter);
        DepositRequest request = new DepositRequest(id, playerId, amount, DepositState.REQUESTED, now.toEpochMilli());
        deposits.put(id, request);
        return request;
    }

    public DepositRequest confirmDeposit(String requestId, Instant now) {
        DepositRequest updated = transitionDeposit(requestId, DepositState.CONFIRMED);
        credit(updated.playerId(), updated.amount(), LedgerEntryType.DEPOSIT, null, now);
        updateCurrentSeasonRecord(updated.playerId(), sr -> sr.withDeposit(updated.amount()));
        return updated;
    }

    public DepositRequest failDeposit(String requestId) {
        return transitionDeposit(requestId, DepositState.FAILED);
    }

    private DepositRequest transitionDeposit(String requestId, DepositState newState) {
        DepositRequest current = deposits.get(requestId);
        if (current == null) {
            throw new NoSuchElementException("unknown deposit request: " + requestId);
        }
        if (current.state() != DepositState.REQUESTED) {
            throw new IllegalStateException("deposit " + requestId + " already " + current.state());
        }
        DepositRequest updated = current.withState(newState);
        deposits.put(requestId, updated);
        return updated;
    }

    // --- primitives ---

    private void credit(String playerId, PlayAmount amount, LedgerEntryType type, String roundId, Instant now) {
        applyDelta(playerId, amount, type, roundId, now);
    }

    private void debit(String playerId, PlayAmount amount, LedgerEntryType type, String roundId, Instant now) {
        applyDelta(playerId, amount.negate(), type, roundId, now);
    }

    private void applyDelta(String playerId, PlayAmount delta, LedgerEntryType type, String roundId, Instant now) {
        PlayerAccount acc = account(playerId);
        PlayAmount newBalance = acc.balance().plus(delta);
        if (newBalance.isNegative()) {
            throw new IllegalStateException("balance would go negative for player " + playerId + ": " + newBalance);
        }
        accounts.put(playerId, new PlayerAccount(playerId, newBalance, acc.currentSeason(), acc.seasons()));
        entries.add(new LedgerEntry(++sequence, playerId, type, delta, roundId, now.toEpochMilli()));
    }

    private void updateCurrentSeasonRecord(String playerId, UnaryOperator<SeasonRecord> fn) {
        PlayerAccount acc = account(playerId);
        Season season = acc.currentSeason();
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.put(season, fn.apply(seasons.get(season)));
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), season, seasons));
    }
}
