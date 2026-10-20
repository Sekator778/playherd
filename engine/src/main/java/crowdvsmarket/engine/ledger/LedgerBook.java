package crowdvsmarket.engine.ledger;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.season.Season;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.function.UnaryOperator;

/**
 * The in-game PLAY ledger: balances, starter packs, stakes, settlements and withdraw/deposit
 * requests (Р-2, Р-3, Р-6, Р-7, Р-12, Р-16). Single-threaded, functional-core-mutable-shell: every
 * {@link PlayerAccount}/{@link SeasonRecord} is an immutable snapshot, replaced wholesale on each
 * mutation; every mutation also produces one {@link LedgerEntry}, handed to the {@link LedgerListener}s
 * ({@link #addListener}) and, when the book retains them ({@link #LedgerBook()}), kept in an append-only audit log
 * ({@link #entries()}). {@code MonthLongHourlySimulationTest} replays the entries to check the
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
    /** The audit log; {@code null} when the book does not retain its entries ({@link #LedgerBook(boolean)}). */
    private final List<LedgerEntry> entries;
    private final List<LedgerListener> listeners = new ArrayList<>();
    private final Map<String, WithdrawalRequest> withdrawals = new LinkedHashMap<>();
    private final Map<String, DepositRequest> deposits = new LinkedHashMap<>();
    private final List<LedgerCorrection> corrections = new ArrayList<>();
    private long sequence = 0;
    private long requestCounter = 0;
    private long correctionCounter = 0;

    // --- economy stage B (Р-92, docs/product/economy_implementation_plan.md B1-B6) ---
    // The house accounts. Before the cutover ({@link #activateEconomy}) none of them moves except the always-tracked
    // open stakes and the per-player counters: packs and wins are minted and lost stakes are burned, exactly as before.
    private boolean economyActive;
    private PlayAmount treasury = PlayAmount.ZERO;
    private PlayAmount herd = PlayAmount.ZERO;
    /** Stakes reserved for bets that are neither settled nor refunded yet: out of the players' balances, in no account. */
    private PlayAmount openStakes = PlayAmount.ZERO;
    /** The sum of all the players' balances, kept by {@link #applyDelta} — the only place a balance changes (architecture plan F2). */
    private PlayAmount playersTotal = PlayAmount.ZERO;
    /** What the opening of the TREASURY account made the total of all accounts: the on-chain treasury coin at the cutover. */
    private PlayAmount openingTotal = PlayAmount.ZERO;
    /** Money that crossed the boundary of the game (deposits in, withdrawals out, operator corrections) - ever, and at the cutover. */
    private PlayAmount externalFlow = PlayAmount.ZERO;
    private PlayAmount externalFlowAtCutover = PlayAmount.ZERO;
    private PlayAmount depositedTotal = PlayAmount.ZERO;
    private PlayAmount depositedAtCutover = PlayAmount.ZERO;
    private PlayAmount withdrawnConfirmed = PlayAmount.ZERO;
    private long cutoverAtMs = -1;
    private final Map<String, PlayAmount> packCredited = new LinkedHashMap<>();
    /** Fees a player paid (herd + network, net of the refunds) - what the daily sweep of the bots' fees is computed from. */
    private final Map<String, PlayAmount> feesPaid = new LinkedHashMap<>();
    private PlayAmount botFeesSwept = PlayAmount.ZERO;
    private long lastSweepAtMs = -1;
    private final Set<String> retired = new HashSet<>();
    /** The highest TREASURY balance seen on each UTC day (epoch day), for the 30-day maximum of the circuit breaker (B6). */
    private final TreeMap<Long, PlayAmount> treasuryDayMax = new TreeMap<>();
    private static final long MILLIS_PER_DAY = 86_400_000L;
    /** The window of the circuit breaker's maximum, in days (B6). */
    public static final int BREAKER_WINDOW_DAYS = 30;

    /** A book that keeps every entry in memory ({@link #entries()}): for simulations and tests, whose runs are bounded. */
    public LedgerBook() {
        this(true);
    }

    /**
     * @param retainEntries {@code false}: the entries are only handed to the {@link LedgerListener}s and not kept, so the
     *        memory of the book is O(players), not O(movements ever made) (architecture plan F3); {@link #entries()} then
     *        throws. The server runs its book this way - the history lives in the {@code ledger_entries} table.
     */
    public LedgerBook(boolean retainEntries) {
        this.entries = retainEntries ? new ArrayList<>() : null;
    }

    /** Hands every entry the book produces from now on to {@code listener}, in order, right after the balance changed. */
    public void addListener(LedgerListener listener) {
        listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    /** The sequence number of the last entry produced (0: none yet) - the next one is this plus one. */
    public long lastSequence() {
        return sequence;
    }

    // --- state export / restore (architecture plan F5: snapshots) ---

    /** Everything the book holds except its listeners and its optional audit log: what a snapshot of the book is made of
     *  ({@link #exportState}, {@link #restoreState}). Maps keep the order the book keeps them in. */
    public record State(List<PlayerAccount> accounts, List<WithdrawalRequest> withdrawals, List<DepositRequest> deposits,
                        List<LedgerCorrection> corrections, long sequence, long requestCounter, long correctionCounter,
                        boolean economyActive, PlayAmount treasury, PlayAmount herd, PlayAmount openStakes,
                        PlayAmount playersTotal, PlayAmount openingTotal, PlayAmount externalFlow,
                        PlayAmount externalFlowAtCutover, PlayAmount depositedTotal, PlayAmount depositedAtCutover,
                        PlayAmount withdrawnConfirmed, long cutoverAtMs, Map<String, PlayAmount> packCredited,
                        Map<String, PlayAmount> feesPaid, PlayAmount botFeesSwept, long lastSweepAtMs, List<String> retired,
                        Map<Long, PlayAmount> treasuryDayMax) {
    }

    /** A copy of the book's state; the retired players are in name order, the rest in the order the book keeps it. */
    public State exportState() {
        List<String> retiredIds = new ArrayList<>(retired);
        java.util.Collections.sort(retiredIds);
        return new State(List.copyOf(accounts.values()), List.copyOf(withdrawals.values()), List.copyOf(deposits.values()),
                List.copyOf(corrections), sequence, requestCounter, correctionCounter, economyActive, treasury, herd,
                openStakes, playersTotal, openingTotal, externalFlow, externalFlowAtCutover, depositedTotal,
                depositedAtCutover, withdrawnConfirmed, cutoverAtMs, new LinkedHashMap<>(packCredited),
                new LinkedHashMap<>(feesPaid), botFeesSwept, lastSweepAtMs, retiredIds, new TreeMap<>(treasuryDayMax));
    }

    /** Puts {@code state} into this book, which must be fresh (nothing booked yet). Nothing is handed to the listeners:
     *  the movements the state stands for were handed to them when they happened. */
    public void restoreState(State state) {
        if (sequence != 0 || !accounts.isEmpty()) {
            throw new IllegalStateException("a ledger state is restored into a fresh book only");
        }
        for (PlayerAccount account : state.accounts()) {
            accounts.put(account.playerId(), account);
        }
        for (WithdrawalRequest w : state.withdrawals()) {
            withdrawals.put(w.requestId(), w);
        }
        for (DepositRequest d : state.deposits()) {
            deposits.put(d.requestId(), d);
        }
        corrections.addAll(state.corrections());
        sequence = state.sequence();
        requestCounter = state.requestCounter();
        correctionCounter = state.correctionCounter();
        economyActive = state.economyActive();
        treasury = state.treasury();
        herd = state.herd();
        openStakes = state.openStakes();
        playersTotal = state.playersTotal();
        openingTotal = state.openingTotal();
        externalFlow = state.externalFlow();
        externalFlowAtCutover = state.externalFlowAtCutover();
        depositedTotal = state.depositedTotal();
        depositedAtCutover = state.depositedAtCutover();
        withdrawnConfirmed = state.withdrawnConfirmed();
        cutoverAtMs = state.cutoverAtMs();
        packCredited.putAll(state.packCredited());
        feesPaid.putAll(state.feesPaid());
        botFeesSwept = state.botFeesSwept();
        lastSweepAtMs = state.lastSweepAtMs();
        retired.addAll(state.retired());
        treasuryDayMax.putAll(state.treasuryDayMax());
    }

    // --- reads ---

    public PlayAmount balance(String playerId) {
        return account(playerId).balance();
    }

    public Optional<SeasonRecord> seasonRecord(String playerId, Season season) {
        PlayerAccount acc = accounts.get(playerId);
        return acc == null ? Optional.empty() : Optional.ofNullable(acc.seasons().get(season));
    }

    /**
     * The player's balance at the end of {@code season} — what the "богачи" archive of a past
     * season (Р-10, Р-18) must be computed from, instead of the balance now. A {@link SeasonRecord}
     * does not store its own end balance, but the next one does: {@link #ensureSeasonPack} records
     * "the balance just before the new season's pack" as the new record's
     * {@code baselineBalance}. So this is the {@code baselineBalance} of the player's earliest
     * record of a season after {@code season} (months the player sat out have no record and are
     * skipped over), or the current balance when there is no later record — the player has not
     * rolled past {@code season} yet, so "now" is still inside it.
     *
     * <p>The answer does not depend on when a movement arrived: one that is dated in {@code season}
     * but was only booked after the player rolled on (a late {@link #settleRound}, an operator
     * {@link #reverseWithdrawalEffect}/{@link #reverseDepositEffect}) was restated into the later
     * baselines when it was booked ({@link #restateLaterBaselines}), so it is already part of the
     * value read here — as if it had been booked on time.
     *
     * @throws NoSuchElementException for an unknown player, like {@link #balance}
     */
    public PlayAmount seasonEndBalance(String playerId, Season season) {
        PlayerAccount acc = account(playerId);
        Season next = null;
        for (Season candidate : acc.seasons().keySet()) {
            if (candidate.compareTo(season) > 0 && (next == null || candidate.compareTo(next) < 0)) {
                next = candidate;
            }
        }
        return next == null ? acc.balance() : acc.seasons().get(next).baselineBalance();
    }

    public Collection<String> playerIds() {
        return List.copyOf(accounts.keySet());
    }

    /**
     * The full append-only movement log, in order — see the class-level conservation note.
     *
     * @throws IllegalStateException when the book was built not to retain its entries ({@link #LedgerBook(boolean)});
     *         listen with {@link #addListener} instead
     */
    public List<LedgerEntry> entries() {
        if (entries == null) {
            throw new IllegalStateException("this ledger does not retain its entries; use addListener");
        }
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
        if (!packDue(acc, season)) {
            return acc;
        }
        if (acc == null) {
            accounts.put(playerId, new PlayerAccount(playerId, PlayAmount.ZERO, season,
                    new LinkedHashMap<>(Map.of(season, SeasonRecord.starting(season, PlayAmount.ZERO, packFor(PlayAmount.ZERO))))));
            grantPack(playerId, packFor(PlayAmount.ZERO), now);
            return accounts.get(playerId);
        }
        PlayAmount baseline = acc.balance();
        PlayAmount pack = packFor(baseline);
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.put(season, SeasonRecord.starting(season, baseline, pack));
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), season, seasons));
        grantPack(playerId, pack, now);
        return accounts.get(playerId);
    }

    /**
     * The starter pack a player with {@code balance} gets for a new season (economy stage B, plan B2). Before the cutover the pack
     * is the flat {@link #STARTER_PACK} minted on top of the balance; from the cutover on it is a top-up to
     * {@link #STARTER_PACK}: the difference when the balance is below it, nothing when it is not, paid from TREASURY.
     */
    public PlayAmount packFor(PlayAmount balance) {
        if (!economyActive) {
            return STARTER_PACK;
        }
        PlayAmount missing = STARTER_PACK.minus(balance);
        return missing.isPositive() ? missing : PlayAmount.ZERO;
    }

    /** Credits a pack (nothing at all when it is zero), counts it in {@link #packCredited}, and - once the economy is active - pays it from TREASURY. */
    private void grantPack(String playerId, PlayAmount pack, Instant now) {
        if (!pack.isPositive()) {
            return;
        }
        packCredited.merge(playerId, pack, PlayAmount::plus);
        if (economyActive) {
            moveTreasury(pack.negate(), now);
        }
        credit(playerId, pack, LedgerEntryType.STARTER_PACK, null, now);
    }

    /**
     * Whether {@link #ensureSeasonPack} would credit a starter pack to this player at {@code now}: the player has no account yet, or
     * {@code now} lies in a later season than the one their account is in. For anyone else (the same season, an earlier one) it is a
     * no-op, and so is asking: this is a pure read. It is the one place that says when a pack is due — {@code ensureSeasonPack}
     * asks it too — so a caller that has to tell the player "a pack was credited" (the sign-in, {@code AuthSessionDto.starterPackGranted})
     * asks it just BEFORE the call that credits, and cannot disagree with the credit.
     */
    public boolean seasonPackDue(String playerId, Instant now) {
        return packDue(accounts.get(playerId), Season.of(now));
    }

    private static boolean packDue(PlayerAccount acc, Season season) {
        return acc == null || season.compareTo(acc.currentSeason()) > 0;
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
        openStakes = openStakes.plus(stake);
    }

    /**
     * Economy stage A (plan A4): reserves {@code stake} <b>and</b> {@code fees} for a bet — the balance must cover the sum.
     * Records the stake as {@link LedgerEntryType#STAKE_DEBIT}, then the herd fee as {@link LedgerEntryType#FEE_HERD} and the
     * network fee as {@link LedgerEntryType#FEE_NETWORK} (each with the round id, none when it is zero), so stage B can route
     * the fees to their accounts.
     */
    public void reserveStake(String playerId, String roundId, PlayAmount stake, Fees fees, Instant now) {
        stake.requirePositive("stake");
        ensureSeasonPack(playerId, now);
        if (stake.compareTo(MIN_STAKE) < 0) {
            throw new IllegalArgumentException("stake below minimum " + MIN_STAKE + ": " + stake);
        }
        PlayAmount total = stake.plus(fees.total());
        PlayAmount balance = balance(playerId);
        if (total.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                    "stake " + stake + " plus fees " + fees.total() + " exceeds balance " + balance + " for player " + playerId);
        }
        debit(playerId, stake, LedgerEntryType.STAKE_DEBIT, roundId, now);
        openStakes = openStakes.plus(stake);
        if (fees.herd().isPositive()) {
            debit(playerId, fees.herd(), LedgerEntryType.FEE_HERD, roundId, now);
        }
        if (fees.network().isPositive()) {
            debit(playerId, fees.network(), LedgerEntryType.FEE_NETWORK, roundId, now);
        }
        // stage B: both fees go to HERD (before the cutover they are simply spent, as in stage A)
        feesPaid.merge(playerId, fees.total(), PlayAmount::plus);
        if (economyActive) {
            herd = herd.plus(fees.total());
        }
    }

    /**
     * Returns the fees of a bet that was cancelled, replaced or whose round was cancelled (plan A4: a cancellation refunds the
     * stake and both fees; a tie refunds the stake only, through the settlement, and never calls this).
     */
    public void refundFees(String playerId, String roundId, Fees fees, Instant now) {
        if (fees.herd().isPositive()) {
            credit(playerId, fees.herd(), LedgerEntryType.FEE_HERD_REFUND, roundId, now);
        }
        if (fees.network().isPositive()) {
            credit(playerId, fees.network(), LedgerEntryType.FEE_NETWORK_REFUND, roundId, now);
        }
        feesPaid.merge(playerId, fees.total().negate(), PlayAmount::plus);
        if (economyActive) {
            // HERD gives the fees back; what it no longer holds (already swept to TREASURY) TREASURY gives back instead
            PlayAmount fromHerd = herd.compareTo(fees.total()) >= 0 ? fees.total() : herd;
            herd = herd.minus(fromHerd);
            moveTreasury(fees.total().minus(fromHerd).negate(), now);
        }
    }

    /** Returns a reserved stake because the bet was cancelled or replaced before round start (Р-13). */
    public void refundStake(String playerId, String roundId, PlayAmount stake, Instant now) {
        credit(playerId, stake, LedgerEntryType.STAKE_REFUND, roundId, now);
        openStakes = openStakes.minus(stake);
    }

    /**
     * Settles a resolved (non-cancelled) position: credits {@code payout} — 0 on liquidation, per
     * Р-7 — and counts the round toward the Р-9/Р-18 rounds-played threshold and, when
     * {@code directionCorrect} is present, toward the "пророки" ratio (Р-18); {@code
     * Optional.empty()} for a flat round (close == open price), excluded from that ratio's
     * denominator.
     *
     * <p>The round belongs to the season {@code Season.of(now)} ({@code now} is the round's own last
     * millisecond, see {@code GameEngine#runRound}) — when the player has a record for it. Its
     * counters go there, and so does its payout. A late settlement of a round that ended on the last
     * day of a month, for a player who already signed in on the 1st, therefore still counts toward
     * the month it was played in. The balance is credited as of the call, like every other
     * movement, so for such a round the payout is also restated into the baseline of every later
     * season ({@link #restateLaterBaselines}): the stake left the balance before the player rolled
     * on and the payout arrives after, and without the restatement the old season's archive would
     * show the loss and the new season's growth the win. With no record for that season the round
     * counts toward the current one — counters and payout alike — as it always did.
     */
    public void settleRound(String playerId, String roundId, PlayAmount payout, Optional<Boolean> directionCorrect, Instant now) {
        settleRound(playerId, roundId, PlayAmount.ZERO, payout, directionCorrect, now);
    }

    /**
     * {@link #settleRound(String, String, PlayAmount, Optional, Instant)} for a bet whose reserved {@code stake} is known: the
     * stake leaves the open stakes, and once the economy is active (stage B, plan B1) TREASURY keeps the stake and pays the
     * payout - a lost stake stays in TREASURY, a win is paid out of it. Before the cutover the payout is minted, as ever.
     */
    public void settleRound(String playerId, String roundId, PlayAmount stake, PlayAmount payout,
            Optional<Boolean> directionCorrect, Instant now) {
        ensureSeasonPack(playerId, now);
        openStakes = openStakes.minus(stake);
        if (economyActive) {
            moveTreasury(stake.minus(payout), now);
        }
        credit(playerId, payout, LedgerEntryType.SETTLEMENT, roundId, now);
        PlayerAccount acc = account(playerId);
        Season roundSeason = Season.of(now);
        Season counted = acc.seasons().containsKey(roundSeason) ? roundSeason : acc.currentSeason();
        updateSeasonRecord(playerId, counted, sr -> sr.withRoundPlayed(directionCorrect.orElse(null)));
        restateLaterBaselines(playerId, counted, payout);
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
        externalFlow = externalFlow.minus(amount);
        updateCurrentSeasonRecord(playerId, sr -> sr.withWithdrawalDelta(amount));
        String id = "w-" + (++requestCounter);
        WithdrawalRequest request = new WithdrawalRequest(id, playerId, amount, WithdrawalState.REQUESTED, now.toEpochMilli());
        withdrawals.put(id, request);
        return request;
    }

    public WithdrawalRequest confirmWithdrawal(String requestId) {
        WithdrawalRequest confirmed = transitionWithdrawal(requestId, WithdrawalState.CONFIRMED);
        withdrawnConfirmed = withdrawnConfirmed.plus(confirmed.amount());
        return confirmed;
    }

    /** The on-chain leg failed: returns the escrowed amount to the balance and un-counts it from
     * the season's withdrawn total. */
    public WithdrawalRequest failWithdrawal(String requestId, Instant now) {
        WithdrawalRequest updated = transitionWithdrawal(requestId, WithdrawalState.FAILED);
        credit(updated.playerId(), updated.amount(), LedgerEntryType.WITHDRAWAL_REFUND, null, now);
        externalFlow = externalFlow.plus(updated.amount());
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
     * <p>The correction is dated in {@code originalSeason}, not in the season it is issued in: when
     * that is an earlier season than the player's current one, the {@code originalAmount} credit
     * (which can only land in the live balance) is also restated into every later season's baseline
     * ({@link #restateLaterBaselines}). So the reversal moves no season's growth, the past season's
     * "богачи" archive included — the withdrawal's debit sat in {@code originalSeason}'s flow, and
     * its reversal now does too, together with the {@code withdrawn} bookkeeping above.
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
        externalFlow = externalFlow.plus(originalAmount);
        adjustSeasonWithdrawn(playerId, originalSeason, originalAmount.negate());
        restateLaterBaselines(playerId, originalSeason, originalAmount);

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
     * <p>Like {@link #reverseWithdrawalEffect}, the correction is dated in {@code originalSeason}:
     * when that is earlier than the player's current season, the debit is also restated into every
     * later season's baseline ({@link #restateLaterBaselines}), so the reversal moves no season's
     * growth.
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
        externalFlow = externalFlow.plus(debit);
        adjustSeasonDeposited(playerId, originalSeason, debit);
        restateLaterBaselines(playerId, originalSeason, debit);

        String id = "c-" + (++correctionCounter);
        LedgerCorrection correction = new LedgerCorrection(id, playerId, debit, reason,
                relatedDepositRequestId, now.toEpochMilli());
        corrections.add(correction);
        return correction;
    }

    /**
     * Restates a movement that was applied to the balance as of the call but is dated in
     * {@code season}, an earlier season than the player's current one: {@code delta} (the amount
     * the balance moved by) is added to the {@code baselineBalance} of every record after
     * {@code season}. A record's baseline is the previous season's end balance
     * ({@link #seasonEndBalance}), so this puts the movement into the end balance of the season it
     * belongs to and — because it is then in both the live balance and the baseline — out of the
     * growth of every later season (Р-18: balance at season end + withdrawn - deposited - baseline -
     * starter pack), however late it was booked. Booking such a movement before or after the player
     * rolls on therefore leaves the same records behind.
     *
     * <p>A no-op when there is nothing earlier to attribute it to: {@code season} is the current one
     * (the movement is simply part of the live balance and no record follows it) or the player has no
     * record of it (no archive entry to keep right — the case in which {@link #adjustSeasonWithdrawn}
     * and {@link #adjustSeasonDeposited} do nothing either).
     */
    private void restateLaterBaselines(String playerId, Season season, PlayAmount delta) {
        PlayerAccount acc = account(playerId);
        if (delta.mojos() == 0 || season.compareTo(acc.currentSeason()) >= 0 || !acc.seasons().containsKey(season)) {
            return;
        }
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.replaceAll((later, record) -> later.compareTo(season) > 0 ? record.withBaselineDelta(delta) : record);
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), acc.currentSeason(), seasons));
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

    /**
     * Credits PLAY that a real, chain-confirmed CAT deposit already delivered (Р-53): unlike
     * {@link #requestDeposit}/{@link #confirmDeposit} (a two-step player-initiated
     * request-then-confirm, kept only so already-persisted historical self-report rows keep
     * replaying — see {@code GameService}'s javadoc on why the self-report RPC is permanently
     * retired), a coin the deposit poller reports from {@code GET /v1/play/deposits} is already
     * confirmed on chain by the time this is called — there is no separate "request" to
     * synthesize first, and (unlike a withdrawal) it can never subsequently fail: the PLAY already
     * arrived. Exactly-once crediting per on-chain coin is the CALLER's job — this method does not
     * take or track a {@code coinId} itself (see {@code DepositConfirmedOnChain}'s javadoc /
     * the deposit poller's own coin-id de-dup table for how replay- and restart-safety are
     * actually enforced one layer up).
     */
    public void creditOnChainDeposit(String playerId, PlayAmount amount, Instant now) {
        ensureSeasonPack(playerId, now);
        credit(playerId, amount, LedgerEntryType.DEPOSIT, null, now);
        externalFlow = externalFlow.plus(amount);
        depositedTotal = depositedTotal.plus(amount);
        updateCurrentSeasonRecord(playerId, sr -> sr.withDeposit(amount));
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
        externalFlow = externalFlow.plus(updated.amount());
        depositedTotal = depositedTotal.plus(updated.amount());
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

    // --- economy stage B: the house accounts (Р-92, plan B1-B6) ---

    /**
     * The cutover (plan B1): from here on starter packs, refunds of fees and payouts come from TREASURY, lost stakes go to it,
     * fees go to HERD. TREASURY opens with {@code openingTotal} (the treasury coin on chain) minus everything the players hold at
     * this moment - their balances and their open stakes - so that {@code TREASURY + HERD + players + open stakes} equals
     * {@code openingTotal} from now on (plus what crosses the boundary of the game, {@link #externalFlowSinceCutover}).
     * Packs credited before the cutover count as {@link #packCredited}; nothing is changed retroactively.
     *
     * @return {@code false} (and nothing happens) when the economy is active already: the event that calls this is applied once
     * @throws IllegalStateException when the players already hold more than {@code openingTotal}
     */
    public boolean activateEconomy(PlayAmount openingTotal, Instant now) {
        if (economyActive) {
            return false;
        }
        PlayAmount held = playersTotal().plus(openStakes);
        PlayAmount opening = openingTotal.minus(held);
        if (opening.isNegative()) {
            throw new IllegalStateException("the players hold " + held + ", more than the opening total " + openingTotal);
        }
        economyActive = true;
        this.openingTotal = openingTotal;
        externalFlowAtCutover = externalFlow;
        depositedAtCutover = depositedTotal;
        cutoverAtMs = now.toEpochMilli();
        treasury = opening;
        herd = PlayAmount.ZERO;
        treasuryDayMax.clear();
        recordTreasuryDayMax(now);
        return true;
    }

    /** Moves PLAY into (positive) or out of (negative) TREASURY; it never goes negative. */
    private void moveTreasury(PlayAmount delta, Instant now) {
        PlayAmount updated = treasury.plus(delta);
        if (updated.isNegative()) {
            throw new IllegalStateException("TREASURY would go negative: " + updated + " (" + treasury + " " + delta + ")");
        }
        treasury = updated;
        recordTreasuryDayMax(now);
    }

    private void recordTreasuryDayMax(Instant now) {
        long day = Math.floorDiv(now.toEpochMilli(), MILLIS_PER_DAY);
        treasuryDayMax.merge(day, treasury, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        treasuryDayMax.headMap(day - 2L * BREAKER_WINDOW_DAYS).clear();
    }

    /**
     * Daily sweep of HERD to TREASURY (plan B4): moves {@code amount} - the fees the bots paid since the last sweep - from HERD to
     * TREASURY. Nothing else of HERD (the fees of the people) is touched.
     *
     * @throws IllegalStateException when the economy is not active or HERD holds less than {@code amount}
     */
    public void sweepHerdToTreasury(PlayAmount amount, Instant now) {
        if (!economyActive) {
            throw new IllegalStateException("the economy is not active: nothing to sweep");
        }
        if (amount.isNegative() || amount.compareTo(herd) > 0) {
            throw new IllegalStateException("cannot sweep " + amount + " from HERD holding " + herd);
        }
        herd = herd.minus(amount);
        botFeesSwept = botFeesSwept.plus(amount);
        lastSweepAtMs = now.toEpochMilli();
        moveTreasury(amount, now);
    }

    /**
     * Retires a bot (plan B4): its balance goes to TREASURY, it can bet no more and is left out of the ratings. The account stays
     * (its history is part of the log). Before the cutover only the flag is set.
     *
     * @return the balance swept to TREASURY
     */
    public PlayAmount retire(String playerId, Instant now) {
        PlayerAccount acc = account(playerId);
        retired.add(playerId);
        PlayAmount swept = acc.balance();
        if (economyActive && swept.isPositive()) {
            applyDelta(playerId, swept.negate(), LedgerEntryType.OPERATOR_CORRECTION, null, now);
            moveTreasury(swept, now);
            return swept;
        }
        return PlayAmount.ZERO;
    }

    public boolean isRetired(String playerId) {
        return retired.contains(playerId);
    }

    public boolean economyActive() {
        return economyActive;
    }

    public PlayAmount treasury() {
        return treasury;
    }

    public PlayAmount herd() {
        return herd;
    }

    public PlayAmount openStakes() {
        return openStakes;
    }

    /** The sum of all the players' balances (retired bots included, open stakes not). */
    public PlayAmount playersTotal() {
        return playersTotal;
    }

    public PlayAmount openingTotal() {
        return openingTotal;
    }

    /** Deposits minus withdrawals (and operator corrections) since the cutover. */
    public PlayAmount externalFlowSinceCutover() {
        return externalFlow.minus(externalFlowAtCutover);
    }

    public PlayAmount depositedTotal() {
        return depositedTotal;
    }

    /** Deposits credited since the cutover. */
    public PlayAmount depositedSinceCutover() {
        return depositedTotal.minus(depositedAtCutover);
    }

    public PlayAmount withdrawnConfirmed() {
        return withdrawnConfirmed;
    }

    /** Withdrawals reserved and not yet confirmed or failed. */
    public PlayAmount withdrawalsPending() {
        PlayAmount total = PlayAmount.ZERO;
        for (WithdrawalRequest w : withdrawals.values()) {
            if (w.state() == WithdrawalState.REQUESTED) {
                total = total.plus(w.amount());
            }
        }
        return total;
    }

    public long cutoverAtMs() {
        return cutoverAtMs;
    }

    /**
     * {@code TREASURY + HERD + players + open stakes - (opening total + external flow since the cutover)}: zero when the books
     * balance (plan B1). Meaningless (zero) before the cutover.
     */
    public PlayAmount identityDelta() {
        if (!economyActive) {
            return PlayAmount.ZERO;
        }
        return treasury.plus(herd).plus(playersTotal()).plus(openStakes)
                .minus(openingTotal.plus(externalFlowSinceCutover()));
    }

    /** The sum of the starter packs credited to the player, ever (plan B2). */
    public PlayAmount packCredited(String playerId) {
        return packCredited.getOrDefault(playerId, PlayAmount.ZERO);
    }

    /**
     * What the player may withdraw (plan B2): once the economy is active, {@code max(0, balance - packCredited)} - the packs are
     * not withdrawable; before it, the whole balance.
     */
    public PlayAmount withdrawable(String playerId) {
        PlayAmount balance = balance(playerId);
        if (!economyActive) {
            return balance;
        }
        PlayAmount free = balance.minus(packCredited(playerId));
        return free.isPositive() ? free : PlayAmount.ZERO;
    }

    /** What the player has paid in fees, herd and network together, net of the refunds. */
    public PlayAmount feesPaid(String playerId) {
        return feesPaid.getOrDefault(playerId, PlayAmount.ZERO);
    }

    /** The fees of bots already moved from HERD to TREASURY by {@link #sweepHerdToTreasury}. */
    public PlayAmount botFeesSwept() {
        return botFeesSwept;
    }

    /** When HERD was last swept (epoch ms), -1 if never. */
    public long lastSweepAtMs() {
        return lastSweepAtMs;
    }

    /** The treasury the payout cap of a bet is taken from (plan A5): TREASURY once the economy is active, the on-chain fallback before. */
    public PlayAmount payoutCapTreasury() {
        return economyActive ? treasury : EconomyRules.TREASURY_FALLBACK;
    }

    /** The highest TREASURY balance of the last 30 days up to {@code now}, the current one included (plan B6). */
    public PlayAmount treasuryMax30d(Instant now) {
        long day = Math.floorDiv(now.toEpochMilli(), MILLIS_PER_DAY);
        PlayAmount max = treasury;
        for (PlayAmount value : treasuryDayMax.tailMap(day - (BREAKER_WINDOW_DAYS - 1), true).values()) {
            if (value.compareTo(max) > 0) {
                max = value;
            }
        }
        return max;
    }

    /** Whether TREASURY is below 90% of its maximum of the last 30 days (plan B6). Always {@code false} before the cutover. */
    public boolean circuitBreakerOpen(Instant now) {
        if (!economyActive) {
            return false;
        }
        long lhs = Math.multiplyExact(treasury.mojos(), 10L);
        long rhs = Math.multiplyExact(treasuryMax30d(now).mojos(), 9L);
        return lhs < rhs;
    }

    /**
     * Whether a pack is due and how big it would be if it were granted at {@code now} (zero when none is due or the top-up has
     * nothing to add): what the sign-in tells the player.
     */
    public PlayAmount seasonPackAmount(String playerId, Instant now) {
        PlayerAccount acc = accounts.get(playerId);
        if (!packDue(acc, Season.of(now))) {
            return PlayAmount.ZERO;
        }
        return packFor(acc == null ? PlayAmount.ZERO : acc.balance());
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
        playersTotal = playersTotal.plus(delta);
        LedgerEntry entry = new LedgerEntry(++sequence, playerId, type, delta, roundId, now.toEpochMilli());
        if (entries != null) {
            entries.add(entry);
        }
        for (LedgerListener listener : listeners) {
            listener.onEntry(entry);
        }
    }

    private void updateCurrentSeasonRecord(String playerId, UnaryOperator<SeasonRecord> fn) {
        updateSeasonRecord(playerId, account(playerId).currentSeason(), fn);
    }

    /** Replaces {@code season}'s record with {@code fn} applied to it; the record must exist. */
    private void updateSeasonRecord(String playerId, Season season, UnaryOperator<SeasonRecord> fn) {
        PlayerAccount acc = account(playerId);
        Map<Season, SeasonRecord> seasons = new LinkedHashMap<>(acc.seasons());
        seasons.put(season, fn.apply(seasons.get(season)));
        accounts.put(playerId, new PlayerAccount(playerId, acc.balance(), acc.currentSeason(), seasons));
    }
}
