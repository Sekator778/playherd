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

/**
 * The rich-list archive of a past season (D-10, D-18) must not move when a player rolls into the
 * next season: {@code RatingBook.richList} used to take the CURRENT balance for every season, so
 * the next season's starter pack, results, deposits and withdrawals all leaked into last
 * season's growth for exactly the players who had already moved on
 * (an internal status review, section 3.2, point 2).
 *
 * <p>A movement that is booked after the player rolled on but dated in the past season — a late
 * settlement of the round that ended on the last day of the month, an operator reversal of a
 * request of that month — has to land in that season's archive and in no later season's growth;
 * {@code LedgerBook} restates the later baselines for it, and the tests below pin the resulting
 * growth numbers, not just the counters.
 */
class RatingBookSeasonArchiveTest {

    private static final Instant APRIL = Instant.parse("2026-04-05T10:00:00Z");
    private static final Instant MAY = Instant.parse("2026-05-02T10:00:00Z");
    private static final Instant JUNE = Instant.parse("2026-06-03T10:00:00Z");
    private static final Season APRIL_SEASON = Season.of(APRIL);
    private static final Season MAY_SEASON = Season.of(MAY);
    private static final Season JUNE_SEASON = Season.of(JUNE);
    private static final Map<String, PlayerIdentity> NO_IDENTITIES = Map.of();
    private static final int THRESHOLD = 10;

    private static PlayAmount play(String amount) {
        return PlayAmount.fromMoney(new BigDecimal(amount));
    }

    /** {@code count} rounds at {@code now}, each netting {@code netPerRound} on a minimum stake. */
    private static void playRounds(LedgerBook ledger, String player, String idPrefix, int count, PlayAmount netPerRound, Instant now) {
        for (int i = 0; i < count; i++) {
            String roundId = idPrefix + i;
            ledger.reserveStake(player, roundId, LedgerBook.MIN_STAKE, now);
            ledger.settleRound(player, roundId, LedgerBook.MIN_STAKE.plus(netPerRound), Optional.of(true), now);
        }
    }

    private static PlayAmount growthOf(List<RatingBook.RichEntry> richList, String playerId) {
        return richList.stream().filter(e -> e.playerId().equals(playerId)).findFirst().orElseThrow().growth();
    }

    @Test
    void aPastSeasonsRichListDoesNotChangeWhenThePlayersMovesOnToTheNextSeason() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        // April: alice nets +50 x 10 = +500 (balance 10500); bob nets -20 x 10 = -200 and never comes back.
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("bob", APRIL);
        playRounds(ledger, "alice", "a-apr-", 10, play("50.00"), APRIL);
        playRounds(ledger, "bob", "b-apr-", 10, play("-20.00"), APRIL);

        List<RatingBook.RichEntry> lastInstantOfApril = ratings.richList(APRIL_SEASON, NO_IDENTITIES);
        assertThat(growthOf(lastInstantOfApril, "alice")).isEqualTo(play("500.00")); // (10500 + 0 - 0) - (0 + 10000)
        assertThat(growthOf(lastInstantOfApril, "bob")).isEqualTo(play("-200.00"));

        // May: alice's pack arrives, then a win, a deposit and a withdrawal.
        playRounds(ledger, "alice", "a-may-", 10, play("30.00"), MAY); // first call rolls her over: balance 20500, then +300
        ledger.creditOnChainDeposit("alice", PlayAmount.ofWholePlay(2_000), MAY);
        ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(500), MAY);
        assertThat(ledger.balance("alice")).isEqualTo(play("22300.00")); // 10500 + 10000 + 300 + 2000 - 500

        // April's archive is exactly what it was at the last instant of April ...
        List<RatingBook.RichEntry> archivedApril = ratings.richList(APRIL_SEASON, NO_IDENTITIES);
        assertThat(archivedApril).isEqualTo(lastInstantOfApril);
        assertThat(growthOf(archivedApril, "alice")).isEqualTo(play("500.00"));
        assertThat(archivedApril).extracting(RatingBook.RichEntry::playerId).containsExactly("alice", "bob");

        // ... while May, her current season, is computed from her current balance:
        // growth = (22300 + 500 withdrawn - 2000 deposited) - (10500 baseline + 10000 pack) = 300
        List<RatingBook.RichEntry> may = ratings.richList(MAY_SEASON, NO_IDENTITIES);
        assertThat(may).extracting(RatingBook.RichEntry::playerId).containsExactly("alice"); // bob has no May record
        assertThat(growthOf(may, "alice")).isEqualTo(play("300.00"));
        assertThat(may.get(0).roundsPlayed()).isEqualTo(10);
    }

    @Test
    void theSeasonThePlayerIsStillInIsComputedFromTheLiveBalanceAsBefore() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-", 10, play("50.00"), APRIL);
        ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(200), APRIL);

        // same numbers as RatingBookTest.richListMatchesTheD18GrowthFormula: (10300 + 200 - 0) - (0 + 10000)
        assertThat(growthOf(ratings.richList(APRIL_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("500.00"));
    }

    @Test
    void anArchivedSeasonOfAPlayerWhoSkippedAMonthStillUsesTheBalanceWhereTheyLeftOff() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-", 10, play("50.00"), APRIL); // 10500
        // she skips May entirely and is back in June:
        ledger.reserveStake("alice", "a-jun", LedgerBook.MIN_STAKE, JUNE); // roll: baseline(Jun) = 10500, pack -> 20500

        assertThat(growthOf(ratings.richList(APRIL_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("500.00"));
    }

    @Test
    void aLateSettledRoundCountsTowardThePastSeasonsThresholdAndNotTheNewOnes() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-apr-", THRESHOLD - 1, play("10.00"), APRIL); // 9 rounds: one short
        Instant lastRoundBet = Instant.parse("2026-04-30T22:59:00Z");
        Instant lastRoundEnd = Instant.parse("2026-04-30T23:59:59.999Z");
        ledger.reserveStake("alice", "1h-last", LedgerBook.MIN_STAKE, lastRoundBet);
        assertThat(ratings.richList(APRIL_SEASON, NO_IDENTITIES)).isEmpty();

        // She signs in on May 1st before the last April round is settled; the settlement is timed April 30.
        ledger.ensureSeasonPack("alice", Instant.parse("2026-05-01T00:00:05Z"));
        ledger.settleRound("alice", "1h-last", LedgerBook.MIN_STAKE.plus(PlayAmount.ofWholePlay(10)), Optional.of(true), lastRoundEnd);

        List<RatingBook.RichEntry> april = ratings.richList(APRIL_SEASON, NO_IDENTITIES);
        assertThat(april).extracting(RatingBook.RichEntry::playerId).containsExactly("alice");
        assertThat(april.get(0).roundsPlayed()).isEqualTo(THRESHOLD);
        assertThat(ratings.prophetList(APRIL_SEASON, NO_IDENTITIES)).extracting(RatingBook.ProphetEntry::playerId).containsExactly("alice");
        assertThat(ratings.richList(MAY_SEASON, NO_IDENTITIES)).isEmpty(); // May has no completed round yet

        // The late round's payout is April's money, not May's: ten rounds of +10 are +100 for April ...
        assertThat(growthOf(april, "alice")).isEqualTo(play("100.00"));
        // ... and May, once it has played its own ten break-even rounds, has grown by exactly nothing.
        playRounds(ledger, "alice", "a-may-", THRESHOLD, play("0.00"), MAY);
        assertThat(growthOf(ratings.richList(MAY_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("0.00"));
        assertThat(growthOf(ratings.richList(APRIL_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("100.00"));
    }

    @Test
    void aLateSettlementGivesTheSameRatingsWhetherItArrivesBeforeOrAfterThePlayerRollsOn() {
        Instant lastRoundBet = Instant.parse("2026-04-30T22:59:00Z");
        Instant lastRoundEnd = Instant.parse("2026-04-30T23:59:59.999Z");
        Instant mayFirst = Instant.parse("2026-05-01T00:00:05Z");
        PlayAmount payout = LedgerBook.MIN_STAKE.plus(PlayAmount.ofWholePlay(10));

        LedgerBook onTime = new LedgerBook();
        onTime.ensureSeasonPack("alice", APRIL);
        playRounds(onTime, "alice", "a-apr-", THRESHOLD - 1, play("10.00"), APRIL);
        onTime.reserveStake("alice", "1h-last", LedgerBook.MIN_STAKE, lastRoundBet);
        onTime.settleRound("alice", "1h-last", payout, Optional.of(true), lastRoundEnd); // before she rolls on
        onTime.ensureSeasonPack("alice", mayFirst);
        playRounds(onTime, "alice", "a-may-", THRESHOLD, play("30.00"), MAY);

        LedgerBook late = new LedgerBook();
        late.ensureSeasonPack("alice", APRIL);
        playRounds(late, "alice", "a-apr-", THRESHOLD - 1, play("10.00"), APRIL);
        late.reserveStake("alice", "1h-last", LedgerBook.MIN_STAKE, lastRoundBet);
        late.ensureSeasonPack("alice", mayFirst);
        late.settleRound("alice", "1h-last", payout, Optional.of(true), lastRoundEnd); // after she rolled on
        playRounds(late, "alice", "a-may-", THRESHOLD, play("30.00"), MAY);

        for (Season season : List.of(APRIL_SEASON, MAY_SEASON)) {
            assertThat(new RatingBook(late, THRESHOLD).richList(season, NO_IDENTITIES))
                    .as(season.id())
                    .isEqualTo(new RatingBook(onTime, THRESHOLD).richList(season, NO_IDENTITIES));
        }
        assertThat(growthOf(new RatingBook(late, THRESHOLD).richList(MAY_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("300.00"));
    }

    @Test
    void aLateSettlementDoesNotLeakIntoEveryLaterSeasonEither() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        Instant lastRoundBet = Instant.parse("2026-04-30T22:59:00Z");
        Instant lastRoundEnd = Instant.parse("2026-04-30T23:59:59.999Z");
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-apr-", THRESHOLD - 1, play("10.00"), APRIL);
        ledger.reserveStake("alice", "1h-last", LedgerBook.MIN_STAKE, lastRoundBet);
        playRounds(ledger, "alice", "a-may-", THRESHOLD, play("5.00"), MAY);                     // rolls into May
        playRounds(ledger, "alice", "a-jun-", THRESHOLD, play("-2.00"), JUNE);                    // and June

        ledger.settleRound("alice", "1h-last", LedgerBook.MIN_STAKE.plus(PlayAmount.ofWholePlay(10)), Optional.of(true), lastRoundEnd);

        assertThat(growthOf(ratings.richList(APRIL_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("100.00"));
        assertThat(growthOf(ratings.richList(MAY_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("50.00"));
        assertThat(growthOf(ratings.richList(JUNE_SEASON, NO_IDENTITIES), "alice")).isEqualTo(play("-20.00"));
    }

    @Test
    void anOperatorReversalOfAPastSeasonsWithdrawalMovesNoArchive() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-apr-", THRESHOLD, play("50.00"), APRIL);               // 10500
        var withdrawal = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(500), APRIL); // 10000
        ledger.confirmWithdrawal(withdrawal.requestId());
        playRounds(ledger, "alice", "a-may-", THRESHOLD, play("30.00"), MAY);                 // rolls on: 20300
        List<RatingBook.RichEntry> aprilBefore = ratings.richList(APRIL_SEASON, NO_IDENTITIES);
        List<RatingBook.RichEntry> mayBefore = ratings.richList(MAY_SEASON, NO_IDENTITIES);
        assertThat(growthOf(aprilBefore, "alice")).isEqualTo(play("500.00")); // (10000 + 500) - 10000: the withdrawal nets out
        assertThat(growthOf(mayBefore, "alice")).isEqualTo(play("300.00"));

        // The operator reverses the April withdrawal while May is running: +500 lands in May's balance,
        // the bookkeeping goes to April. A withdrawal that never happened changes nobody's growth.
        ledger.reverseWithdrawalEffect("alice", withdrawal.amount(), APRIL_SEASON, "bogus withdrawal", withdrawal.requestId(), MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play("20800.00"));
        assertThat(ratings.richList(APRIL_SEASON, NO_IDENTITIES)).isEqualTo(aprilBefore);
        assertThat(ratings.richList(MAY_SEASON, NO_IDENTITIES)).isEqualTo(mayBefore);
    }

    @Test
    void anOperatorReversalOfAPastSeasonsDepositMovesNoArchive() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-apr-", THRESHOLD, play("50.00"), APRIL);               // 10500
        ledger.creditOnChainDeposit("alice", PlayAmount.ofWholePlay(800), APRIL);             // 11300
        playRounds(ledger, "alice", "a-may-", THRESHOLD, play("30.00"), MAY);                 // rolls on: 21600
        List<RatingBook.RichEntry> aprilBefore = ratings.richList(APRIL_SEASON, NO_IDENTITIES);
        List<RatingBook.RichEntry> mayBefore = ratings.richList(MAY_SEASON, NO_IDENTITIES);
        assertThat(growthOf(aprilBefore, "alice")).isEqualTo(play("500.00")); // (11300 + 0 - 800) - 10000: the deposit nets out
        assertThat(growthOf(mayBefore, "alice")).isEqualTo(play("300.00"));

        ledger.reverseDepositEffect("alice", PlayAmount.ofWholePlay(800), APRIL_SEASON, "unverified deposit", "d-1", MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play("20800.00"));
        assertThat(ratings.richList(APRIL_SEASON, NO_IDENTITIES)).isEqualTo(aprilBefore);
        assertThat(ratings.richList(MAY_SEASON, NO_IDENTITIES)).isEqualTo(mayBefore);
    }

    @Test
    void theProphetListOfAPastSeasonIsUnaffectedByTheRollover() {
        LedgerBook ledger = new LedgerBook();
        RatingBook ratings = new RatingBook(ledger, THRESHOLD);
        ledger.ensureSeasonPack("alice", APRIL);
        playRounds(ledger, "alice", "a-apr-", 10, play("10.00"), APRIL);
        List<RatingBook.ProphetEntry> before = ratings.prophetList(APRIL_SEASON, NO_IDENTITIES);

        playRounds(ledger, "alice", "a-may-", 10, play("10.00"), MAY);

        assertThat(ratings.prophetList(APRIL_SEASON, NO_IDENTITIES)).isEqualTo(before);
        assertThat(before).singleElement().satisfies(e -> assertThat(e.accuracy()).isEqualByComparingTo("1"));
    }
}
