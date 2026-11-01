package crowdvsmarket.engine.rating;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RatingBookTest {

    private static final Instant NOW = Instant.parse("2026-04-05T10:00:00Z");
    private static final Season SEASON = Season.of(NOW);
    private static final Map<String, PlayerIdentity> NO_IDENTITIES = Map.of();

    private static void playRounds(LedgerBook ledger, String player, int count, PlayAmount netPerRound, boolean correct) {
        for (int i = 0; i < count; i++) {
            String roundId = player + "-r" + i;
            ledger.reserveStake(player, roundId, LedgerBook.MIN_STAKE, NOW);
            PlayAmount payout = LedgerBook.MIN_STAKE.plus(netPerRound);
            ledger.settleRound(player, roundId, payout, Optional.of(correct), NOW);
        }
    }

    @Test
    void richListMatchesTheD18GrowthFormula() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW); // balance 10000, baseline 0
        playRounds(ledger, "alice", 10, PlayAmount.fromMoney(new BigDecimal("50.00")), true); // net +50/round -> balance 10500
        ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(200), NOW); // balance 10300, season withdrawn 200

        // growth = (balanceNow + withdrawn_in_season - deposited_in_season) - (baseline + pack)
        //        = (10300 + 200 - 0) - (0 + 10000) = 500
        RatingBook ratings = new RatingBook(ledger, 10);
        List<RatingBook.RichEntry> richList = ratings.richList(SEASON, NO_IDENTITIES);

        assertThat(richList).hasSize(1);
        assertThat(richList.get(0).growth()).isEqualTo(PlayAmount.fromMoney(new BigDecimal("500.00")));
        assertThat(richList.get(0).roundsPlayed()).isEqualTo(10);
    }

    @Test
    void playersBelowTheThresholdAreExcludedFromBothLists() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW);
        playRounds(ledger, "alice", 9, PlayAmount.fromMoney(new BigDecimal("50.00")), true); // one short of threshold 10

        RatingBook ratings = new RatingBook(ledger, 10);

        assertThat(ratings.richList(SEASON, NO_IDENTITIES)).isEmpty();
        assertThat(ratings.prophetList(SEASON, NO_IDENTITIES)).isEmpty();
    }

    @Test
    void prophetAccuracyExcludesFlatRoundsFromTheDenominator() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW);
        // 6 correct, 4 incorrect eligible rounds, plus flat rounds that must not affect the ratio.
        playRounds(ledger, "alice", 6, PlayAmount.fromMoney(new BigDecimal("50.00")), true);
        for (int i = 0; i < 4; i++) {
            String roundId = "alice-wrong-" + i;
            ledger.reserveStake("alice", roundId, LedgerBook.MIN_STAKE, NOW);
            ledger.settleRound("alice", roundId, PlayAmount.ZERO, Optional.of(false), NOW);
        }
        for (int i = 0; i < 5; i++) {
            String roundId = "alice-flat-" + i;
            ledger.reserveStake("alice", roundId, LedgerBook.MIN_STAKE, NOW);
            ledger.settleRound("alice", roundId, LedgerBook.MIN_STAKE, Optional.empty(), NOW);
        }

        RatingBook ratings = new RatingBook(ledger, 10);
        List<RatingBook.ProphetEntry> prophets = ratings.prophetList(SEASON, NO_IDENTITIES);

        assertThat(prophets).hasSize(1);
        RatingBook.ProphetEntry entry = prophets.get(0);
        assertThat(entry.eligibleRounds()).isEqualTo(10); // 6 + 4, not 15
        assertThat(entry.roundsPlayed()).isEqualTo(15);
        assertThat(entry.accuracy()).isEqualByComparingTo(new BigDecimal("0.6")); // 6/10
    }

    @Test
    void playerWithOnlyFlatRoundsIsExcludedFromProphetList() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW);
        for (int i = 0; i < 10; i++) {
            String roundId = "alice-flat-" + i;
            ledger.reserveStake("alice", roundId, LedgerBook.MIN_STAKE, NOW);
            ledger.settleRound("alice", roundId, LedgerBook.MIN_STAKE, Optional.empty(), NOW);
        }

        RatingBook ratings = new RatingBook(ledger, 10);

        assertThat(ratings.prophetList(SEASON, NO_IDENTITIES)).isEmpty();
    }

    @Test
    void richListIsSortedDescendingByGrowth() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW);
        ledger.ensureSeasonPack("bob", NOW);
        playRounds(ledger, "alice", 10, PlayAmount.fromMoney(new BigDecimal("10.00")), true);
        playRounds(ledger, "bob", 10, PlayAmount.fromMoney(new BigDecimal("90.00")), true);

        RatingBook ratings = new RatingBook(ledger, 10);
        List<RatingBook.RichEntry> richList = ratings.richList(SEASON, NO_IDENTITIES);

        assertThat(richList).extracting(RatingBook.RichEntry::playerId).containsExactly("bob", "alice");
    }

    @Test
    void displayNameFallsBackToPlayerIdWithoutAnIdentity() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", NOW);
        playRounds(ledger, "alice", 10, PlayAmount.fromMoney(new BigDecimal("10.00")), true);

        RatingBook ratings = new RatingBook(ledger, 10);
        PlayerIdentity identity = new PlayerIdentity("alice", "nick", "xch1address");
        List<RatingBook.RichEntry> withIdentity = ratings.richList(SEASON, Map.of("alice", identity));
        List<RatingBook.RichEntry> withoutIdentity = ratings.richList(SEASON, NO_IDENTITIES);

        assertThat(withIdentity.get(0).displayName()).isEqualTo(identity.displayName());
        assertThat(withoutIdentity.get(0).displayName()).isEqualTo("alice");
    }
}
