package crowdvsmarket.engine.rating;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.ledger.SeasonRecord;
import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two season leaderboards (Р-9, Р-18), computed read-only from a {@link LedgerBook} snapshot —
 * this class holds no state of its own.
 */
public final class RatingBook {

    /** Предложение — Р-9/Р-18 fix that a threshold N exists, not its value. */
    public static final int DEFAULT_ROUNDS_THRESHOLD = 10;

    /** "богачи": {@code growth} is Р-18's exact season-growth formula. */
    public record RichEntry(String playerId, String displayName, PlayAmount growth, int roundsPlayed) {
    }

    /** "пророки": {@code accuracy} = correct / eligible direction calls this season. */
    public record ProphetEntry(String playerId, String displayName, BigDecimal accuracy, int eligibleRounds, int roundsPlayed) {
    }

    private final LedgerBook ledger;
    private final int roundsThreshold;

    public RatingBook(LedgerBook ledger) {
        this(ledger, DEFAULT_ROUNDS_THRESHOLD);
    }

    public RatingBook(LedgerBook ledger, int roundsThreshold) {
        this.ledger = ledger;
        this.roundsThreshold = roundsThreshold;
    }

    /**
     * "богачи" (Р-18): {@code growth = (balance_at_season_end + withdrawn_in_season -
     * deposited_in_season) - (balance_at_season_baseline + starter_pack)}, sorted descending.
     * Players below the Р-9 rounds-played threshold are excluded.
     *
     * <p>{@code balance_at_season_end} is {@link LedgerBook#seasonEndBalance}: the current balance
     * for the season the player is still in (Р-18's "balance now"), but for an archived season
     * (Р-10) the balance as it stood when the player rolled into the next one — otherwise the next
     * season's starter pack, results, deposits and withdrawals would leak into the past season's
     * growth for every player who has already moved on.
     */
    public List<RichEntry> richList(Season season, Map<String, PlayerIdentity> identities) {
        List<RichEntry> entries = new ArrayList<>();
        for (String playerId : ledger.playerIds()) {
            Optional<SeasonRecord> maybe = ledger.seasonRecord(playerId, season);
            if (maybe.isEmpty() || maybe.get().roundsPlayed() < roundsThreshold) {
                continue;
            }
            SeasonRecord sr = maybe.get();
            PlayAmount balanceAtSeasonEnd = ledger.seasonEndBalance(playerId, season);
            PlayAmount growth = balanceAtSeasonEnd.plus(sr.withdrawn()).minus(sr.deposited())
                    .minus(sr.baselineBalance().plus(LedgerBook.STARTER_PACK));
            entries.add(new RichEntry(playerId, displayName(playerId, identities), growth, sr.roundsPlayed()));
        }
        entries.sort(Comparator.comparing(RichEntry::growth).reversed());
        return entries;
    }

    /**
     * "пророки" (Р-18): share of rounds where the player's chosen direction matched
     * {@code sign(close - open)}, regardless of liquidation (предложение — Р-18 leaves the exact
     * rule open, see the design doc). Flat rounds (close == open) are excluded from both the
     * numerator and the denominator; a player whose every played round was flat has no defined
     * ratio and is excluded even past the rounds-played threshold.
     */
    public List<ProphetEntry> prophetList(Season season, Map<String, PlayerIdentity> identities) {
        List<ProphetEntry> entries = new ArrayList<>();
        for (String playerId : ledger.playerIds()) {
            Optional<SeasonRecord> maybe = ledger.seasonRecord(playerId, season);
            if (maybe.isEmpty()) {
                continue;
            }
            SeasonRecord sr = maybe.get();
            if (sr.roundsPlayed() < roundsThreshold || sr.directionEligibleRounds() == 0) {
                continue;
            }
            BigDecimal accuracy = BigDecimal.valueOf(sr.directionCorrectRounds())
                    .divide(BigDecimal.valueOf(sr.directionEligibleRounds()), MathContext.DECIMAL128);
            entries.add(new ProphetEntry(playerId, displayName(playerId, identities), accuracy, sr.directionEligibleRounds(), sr.roundsPlayed()));
        }
        entries.sort(Comparator.comparing(ProphetEntry::accuracy).reversed());
        return entries;
    }

    private static String displayName(String playerId, Map<String, PlayerIdentity> identities) {
        PlayerIdentity identity = identities.get(playerId);
        return identity == null ? playerId : identity.displayName();
    }
}
