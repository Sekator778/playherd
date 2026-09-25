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
    private long sequence = 0;
    private long requestCounter = 0;

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

    /** Registers a pending on-chain-to-ledger deposit; no balance change until confirmed. */
    public DepositRequest requestDeposit(String playerId, PlayAmount amount, Instant now) {
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
