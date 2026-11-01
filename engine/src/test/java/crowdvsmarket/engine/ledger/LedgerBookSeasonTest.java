package crowdvsmarket.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.season.Season;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two season-boundary rules of {@link LedgerBook}: {@link LedgerBook#seasonEndBalance} (what a
 * past season's rich-list archive is computed from, D-10/D-18) and the season a settled round's
 * counters are filed under (D-9/D-18's rounds-played threshold and the prophets ratio) — plus the
 * restatement that ties them together: a movement booked after the player rolled on, but dated in
 * an earlier season (a late settlement, an operator reversal), is added to the later seasons'
 * baselines, so the archive of its own season includes it and no later season's growth does.
 */
class LedgerBookSeasonTest {

    private static final Instant APRIL = Instant.parse("2026-04-05T10:00:00Z");
    private static final Instant MAY = Instant.parse("2026-05-02T10:00:00Z");
    private static final Instant JUNE = Instant.parse("2026-06-03T10:00:00Z");
    private static final Instant APRIL_LAST_MS = Instant.parse("2026-04-30T23:59:59.999Z");
    private static final Instant APRIL_LAST_BET = Instant.parse("2026-04-30T22:59:00Z");
    private static final Instant MAY_FIRST = Instant.parse("2026-05-01T00:00:05Z");
    private static final Instant MAY_LAST_BET = Instant.parse("2026-05-31T22:59:00Z");
    private static final Instant MAY_LAST_MS = Instant.parse("2026-05-31T23:59:59.999Z");
    private static final Instant JUNE_FIRST = Instant.parse("2026-06-01T00:00:05Z");

    private static final Season APRIL_SEASON = Season.of(APRIL);
    private static final Season MAY_SEASON = Season.of(MAY);
    private static final Season JUNE_SEASON = Season.of(JUNE);

    private static PlayAmount play(long whole) {
        return PlayAmount.ofWholePlay(whole);
    }

    /** What the player's balance moved by inside {@code season}: its end balance minus its baseline (the pack included). */
    private static PlayAmount flow(LedgerBook ledger, String playerId, Season season) {
        return ledger.seasonEndBalance(playerId, season).minus(ledger.seasonRecord(playerId, season).orElseThrow().baselineBalance());
    }

    // --- seasonPackDue: the sign-in tells the player when a pack was credited, and asks this first ---

    private static long starterPacksOf(LedgerBook ledger, String playerId) {
        return ledger.entries().stream()
                .filter(e -> e.playerId().equals(playerId) && e.type() == LedgerEntryType.STARTER_PACK)
                .count();
    }

    @Test
    void aPackIsDueToAPlayerWithNoAccountAndToOneWhoseSeasonHasRolledOverAndToNoOneElse() {
        LedgerBook ledger = new LedgerBook();

        assertThat(ledger.seasonPackDue("alice", APRIL)).as("no account: the first pack is due").isTrue();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThat(ledger.seasonPackDue("alice", APRIL)).as("same season").isFalse();
        assertThat(ledger.seasonPackDue("alice", APRIL_LAST_MS)).as("still the same season, to the last millisecond").isFalse();
        assertThat(ledger.seasonPackDue("alice", MAY_FIRST)).as("the first sign-in of the next month").isTrue();
        assertThat(ledger.seasonPackDue("alice", JUNE)).as("a month that was sat out does not matter: the season is later").isTrue();

        ledger.ensureSeasonPack("alice", MAY);
        assertThat(ledger.seasonPackDue("alice", MAY)).isFalse();
        assertThat(ledger.seasonPackDue("alice", APRIL)).as("an earlier season is never due again").isFalse();
        assertThat(ledger.seasonPackDue("bob", MAY)).as("each player is asked on their own").isTrue();
    }

    @Test
    void askingChangesNothing() {
        LedgerBook ledger = new LedgerBook();

        assertThat(ledger.seasonPackDue("alice", APRIL)).isTrue();
        assertThat(ledger.seasonPackDue("alice", APRIL)).isTrue();

        assertThat(ledger.playerIds()).as("a question does not make an account").isEmpty();
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    void thePackIsCreditedExactlyWhenItWasDue() {
        LedgerBook ledger = new LedgerBook();
        for (Instant at : List.of(APRIL, APRIL, APRIL_LAST_MS, MAY_FIRST, MAY, JUNE, APRIL, JUNE, JUNE_FIRST)) {
            boolean due = ledger.seasonPackDue("alice", at);
            long before = starterPacksOf(ledger, "alice");
            ledger.ensureSeasonPack("alice", at);
            assertThat(starterPacksOf(ledger, "alice") - before)
                    .as("at %s: a pack %s due, and %s", at, due ? "was" : "was not", due ? "one came" : "none came")
                    .isEqualTo(due ? 1 : 0);
        }
        assertThat(starterPacksOf(ledger, "alice")).as("April, May, June").isEqualTo(3);
        assertThat(ledger.balance("alice")).isEqualTo(play(30_000));
    }

    // --- seasonEndBalance ---

    @Test
    void seasonEndBalanceIsTheCurrentBalanceWhileThePlayerIsStillInTheSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(500), APRIL);
        ledger.settleRound("alice", "r1", play(800), Optional.of(true), APRIL);

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(ledger.balance("alice")).isEqualTo(play(10_300));

        // and it keeps following the balance as long as she is in that season
        ledger.requestWithdrawal("alice", play(300), APRIL);
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
    }

    @Test
    void seasonEndBalanceOfAPastSeasonIsTheNextSeasonsBaselineAndStaysFixed() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(500), APRIL);
        ledger.settleRound("alice", "r1", play(800), Optional.of(true), APRIL); // 10300
        PlayAmount lastInApril = ledger.balance("alice");

        ledger.ensureSeasonPack("alice", MAY); // pack on top: 20300, May's baseline is 10300

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(lastInApril).isEqualTo(play(10_300));
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON))
                .isEqualTo(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance());
        // May is the season she is in: its "end" is still the live balance
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(20_300));

        // nothing she does in May moves April's end balance
        ledger.reserveStake("alice", "r2", play(1_000), MAY);
        ledger.settleRound("alice", "r2", play(2_500), Optional.of(true), MAY);
        ledger.creditOnChainDeposit("alice", play(4_000), MAY);
        ledger.requestWithdrawal("alice", play(700), MAY);
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_300));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(ledger.balance("alice"));
    }

    @Test
    void seasonEndBalanceWalksAcrossSeveralSeasons() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);                         // 10000
        ledger.ensureSeasonPack("alice", MAY);                           // +10000 -> 20000, baseline(May) = 10000
        ledger.reserveStake("alice", "r1", play(2_000), MAY);            // 18000
        ledger.ensureSeasonPack("alice", JUNE);                          // +10000 -> 28000, baseline(Jun) = 18000

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(18_000));
        assertThat(ledger.seasonEndBalance("alice", JUNE_SEASON)).isEqualTo(play(28_000));
    }

    @Test
    void seasonEndBalanceSkipsSeasonsThePlayerSatOut() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);                         // 10000
        ledger.reserveStake("alice", "r1", play(400), APRIL);            // 9600
        ledger.ensureSeasonPack("alice", JUNE);                          // no May activity; baseline(Jun) = 9600

        assertThat(ledger.seasonRecord("alice", MAY_SEASON)).isEmpty();
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(9_600));
        // a season without a record has no archive entry, but asking is well defined: nothing moved in May
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(9_600));
    }

    @Test
    void seasonEndBalanceOfAnUnknownPlayerThrowsLikeBalanceDoes() {
        LedgerBook ledger = new LedgerBook();

        assertThatThrownBy(() -> ledger.seasonEndBalance("nobody", APRIL_SEASON)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void eachPlayersEndBalanceIsTheirOwn() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("bob", APRIL);
        ledger.reserveStake("bob", "r1", play(1_000), APRIL); // bob 9000
        ledger.ensureSeasonPack("alice", MAY);                // alice rolled; bob did not

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("bob", APRIL_SEASON)).isEqualTo(play(9_000));
    }

    // --- settleRound files its counters under the round's own season ---

    @Test
    void aLateSettlementCountsTowardTheSeasonTheRoundWasPlayedIn() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), Instant.parse("2026-04-30T22:59:00Z")); // 9900
        // She signs in on the 1st, before the settlement of the round that ended on April 30 is applied:
        ledger.ensureSeasonPack("alice", MAY_FIRST); // May's pack: 19900, May's baseline 9900

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS);

        SeasonRecord april = ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow();
        SeasonRecord may = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();
        assertThat(april.roundsPlayed()).isEqualTo(1);
        assertThat(april.directionEligibleRounds()).isEqualTo(1);
        assertThat(april.directionCorrectRounds()).isEqualTo(1);
        assertThat(may.roundsPlayed()).isZero();
        assertThat(may.directionEligibleRounds()).isZero();
        assertThat(may.directionCorrectRounds()).isZero();
    }

    @Test
    void aLateSettlementStillCreditsTheBalanceAsOfTheCallAndTheLogReplaysIt() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), Instant.parse("2026-04-30T22:59:00Z"));
        ledger.ensureSeasonPack("alice", MAY_FIRST);

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(false), APRIL_LAST_MS);

        // balance handling is as ever: 10000 - 100 + 10000 + 180
        assertThat(ledger.balance("alice")).isEqualTo(play(20_080));
        PlayAmount replayed = PlayAmount.ZERO;
        for (LedgerEntry entry : ledger.entries()) {
            replayed = replayed.plus(entry.delta());
        }
        assertThat(replayed).isEqualTo(ledger.balance("alice"));
        LedgerEntry settlement = ledger.entries().get(ledger.entries().size() - 1);
        assertThat(settlement.type()).isEqualTo(LedgerEntryType.SETTLEMENT);
        assertThat(settlement.roundId()).isEqualTo("1h-late");
        assertThat(settlement.atEpochMs()).isEqualTo(APRIL_LAST_MS.toEpochMilli());
        // and the player is still in May: the late round did not move her back
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().directionCorrectRounds()).isZero();
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().directionEligibleRounds()).isEqualTo(1);
    }

    @Test
    void aFlatLateRoundCountsAsPlayedButNotAsDirectionEligibleInItsOwnSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-flat", play(100), Instant.parse("2026-04-30T22:59:00Z"));
        ledger.ensureSeasonPack("alice", MAY_FIRST);

        ledger.settleRound("alice", "1h-flat", play(100), Optional.empty(), APRIL_LAST_MS);

        SeasonRecord april = ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow();
        assertThat(april.roundsPlayed()).isEqualTo(1);
        assertThat(april.directionEligibleRounds()).isZero();
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().roundsPlayed()).isZero();
    }

    @Test
    void aSettlementInTheCurrentSeasonStillCountsThere() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(100), APRIL);

        ledger.settleRound("alice", "r1", play(150), Optional.of(true), APRIL);

        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
    }

    @Test
    void aSettlementTimedInANewSeasonRollsThePlayerOverAndCountsThere() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(100), APRIL);

        ledger.settleRound("alice", "r1", play(150), Optional.of(true), MAY);

        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().roundsPlayed()).isZero();
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
    }

    @Test
    void aSettlementTimedBeforeThePlayersFirstRecordCountsInTheCurrentSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", MAY); // her first season ever: there is no April record to file under

        ledger.settleRound("alice", "r1", play(150), Optional.of(true), APRIL_LAST_MS);

        assertThat(ledger.seasonRecord("alice", APRIL_SEASON)).isEmpty();
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(PlayAmount.ZERO); // nothing earlier to restate into
    }

    @Test
    void aLateSettlementDoesNotTouchTheOtherCountersOfEitherSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET);
        ledger.ensureSeasonPack("alice", MAY_FIRST);
        ledger.creditOnChainDeposit("alice", play(500), MAY_FIRST);
        SeasonRecord mayBefore = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS);

        // everything of May's record but its baseline (restated by the payout, see below) is as it was
        SeasonRecord mayAfter = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();
        assertThat(mayAfter).isEqualTo(new SeasonRecord(MAY_SEASON, mayBefore.baselineBalance().plus(play(180)),
                mayBefore.deposited(), mayBefore.withdrawn(), mayBefore.roundsPlayed(),
                mayBefore.directionEligibleRounds(), mayBefore.directionCorrectRounds()));
        assertThat(mayAfter.deposited()).isEqualTo(play(500));
        SeasonRecord april = ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow();
        assertThat(april.deposited()).isEqualTo(PlayAmount.ZERO);
        assertThat(april.withdrawn()).isEqualTo(PlayAmount.ZERO);
        assertThat(april.baselineBalance()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aLateSettlementLeavesThePlayerInHerCurrentSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET); // 9900
        ledger.ensureSeasonPack("alice", MAY_FIRST);                        // May's pack: 19900
        ledger.creditOnChainDeposit("alice", play(500), MAY_FIRST);         // 20400, May: deposited 500

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS); // 20580

        // She is still in May: her next May operation neither rolls her over a second time (which
        // would reset May's record and mint a third starter pack) nor forgets what she did in May.
        Instant later = MAY_FIRST.plusSeconds(3_600);
        assertThat(ledger.ensureSeasonPack("alice", later).currentSeason()).isEqualTo(MAY_SEASON);
        ledger.reserveStake("alice", "1h-may", play(100), later);
        assertThat(ledger.balance("alice")).isEqualTo(play(20_480)); // moved by the stake only
        assertThat(ledger.entries()).filteredOn(e -> e.type() == LedgerEntryType.STARTER_PACK).hasSize(2); // April's and May's
        SeasonRecord may = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();
        assertThat(may.deposited()).isEqualTo(play(500));
        assertThat(may.roundsPlayed()).isZero();
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
    }

    // --- a movement booked late still belongs to the season it is dated in ---

    @Test
    void aLateSettlementIsBookedIntoTheEndOfItsOwnSeasonNotIntoTheStartOfTheNext() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET); // 9900
        ledger.ensureSeasonPack("alice", MAY_FIRST);                        // 19900, baseline(May) 9900

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS);

        // what April ended with, had the settlement been applied on time: 9900 + 180
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_080));
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(10_080));
        // the payout is in the live balance as ever, and May is still the season she is in
        assertThat(ledger.balance("alice")).isEqualTo(play(20_080));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(20_080));
        // April's own flow holds the whole round (-100 +180) plus its pack; May's holds only its pack
        assertThat(flow(ledger, "alice", APRIL_SEASON)).isEqualTo(play(10_080));
        assertThat(flow(ledger, "alice", MAY_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void settlingBeforeOrAfterTheRolloverGivesTheSameSeasonRecords() {
        LedgerBook onTime = new LedgerBook();
        onTime.ensureSeasonPack("alice", APRIL);
        onTime.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET);
        onTime.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS); // before she rolls on
        onTime.ensureSeasonPack("alice", MAY_FIRST);

        LedgerBook late = new LedgerBook();
        late.ensureSeasonPack("alice", APRIL);
        late.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET);
        late.ensureSeasonPack("alice", MAY_FIRST);
        late.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS); // after she rolled on

        assertThat(late.balance("alice")).isEqualTo(onTime.balance("alice"));
        for (Season season : List.of(APRIL_SEASON, MAY_SEASON)) {
            assertThat(late.seasonRecord("alice", season)).as(season.id()).isEqualTo(onTime.seasonRecord("alice", season));
            assertThat(late.seasonEndBalance("alice", season)).as(season.id()).isEqualTo(onTime.seasonEndBalance("alice", season));
        }
    }

    @Test
    void aLateSettlementIsRestatedIntoEveryLaterBaselineNotOnlyTheNextOne() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET); // 9900
        ledger.ensureSeasonPack("alice", MAY_FIRST);                        // 19900, baseline(May) 9900
        ledger.ensureSeasonPack("alice", JUNE);                             // 29900, baseline(Jun) 19900

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS);

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_080));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(20_080));
        assertThat(ledger.seasonEndBalance("alice", JUNE_SEASON)).isEqualTo(play(30_080));
        // the April payout is nobody else's: May and June each moved by their starter pack and nothing more
        assertThat(flow(ledger, "alice", MAY_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(flow(ledger, "alice", JUNE_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void aLateSettlementOfAMayRoundRestatesOnlyJunesBaseline() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);                              // 10000
        ledger.reserveStake("alice", "1h-may-last", play(100), MAY_LAST_BET); // rolls to May: baseline(May) 10000, 19900
        ledger.ensureSeasonPack("alice", JUNE_FIRST);                         // 29900, baseline(Jun) 19900

        ledger.settleRound("alice", "1h-may-last", play(180), Optional.of(true), MAY_LAST_MS);

        SeasonRecord may = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();
        assertThat(may.roundsPlayed()).isEqualTo(1);
        assertThat(may.baselineBalance()).isEqualTo(play(10_000));                       // April's end is untouched
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(20_080)); // 19900 + 180
        assertThat(ledger.seasonRecord("alice", JUNE_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(20_080));
        assertThat(flow(ledger, "alice", JUNE_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void aSettlementDatedInASeasonThePlayerHasNoRecordOfMovesNoBaseline() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-may-first", play(100), APRIL_LAST_BET); // 9900: a stake for the round of May 1st
        ledger.ensureSeasonPack("alice", JUNE);                                  // no May activity: April and June records only

        ledger.settleRound("alice", "1h-may-first", play(180), Optional.of(true), Instant.parse("2026-05-01T00:59:59.999Z"));

        // there is no May record to attribute it to: counters and payout are June's, as they always were
        assertThat(ledger.seasonRecord("alice", MAY_SEASON)).isEmpty();
        assertThat(ledger.seasonRecord("alice", JUNE_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
        assertThat(ledger.seasonRecord("alice", JUNE_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(9_900));
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(9_900));
        assertThat(ledger.balance("alice")).isEqualTo(play(20_080));
    }

    @Test
    void aRoundSettledInItsOwnSeasonRestatesNothing() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("alice", MAY);
        ledger.reserveStake("alice", "r1", play(100), MAY);
        SeasonRecord mayBefore = ledger.seasonRecord("alice", MAY_SEASON).orElseThrow();

        ledger.settleRound("alice", "r1", play(180), Optional.of(true), MAY);

        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(mayBefore.baselineBalance());
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
    }

    @Test
    void aLiquidatedLateRoundRestatesNothing() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET); // 9900
        ledger.ensureSeasonPack("alice", MAY_FIRST);

        ledger.settleRound("alice", "1h-late", PlayAmount.ZERO, Optional.of(false), APRIL_LAST_MS);

        // the stake already left April's balance when it was reserved: a zero payout adds nothing to restate
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(9_900));
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().roundsPlayed()).isEqualTo(1);
    }

    @Test
    void anOperatorWithdrawalReversalDatedInAnEarlierSeasonIsRestatedIntoTheLaterBaselines() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        WithdrawalRequest w = ledger.requestWithdrawal("alice", play(500), APRIL); // 9500, April: withdrawn 500
        ledger.confirmWithdrawal(w.requestId());
        ledger.ensureSeasonPack("alice", MAY);                                     // 19500, baseline(May) 9500

        ledger.reverseWithdrawalEffect("alice", w.amount(), APRIL_SEASON, "bogus withdrawal", w.requestId(), MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play(20_000));
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().withdrawn()).isEqualTo(PlayAmount.ZERO);
        // April's books as if the withdrawal had never happened: it ends at 10000 and May starts from there
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(10_000));
        assertThat(flow(ledger, "alice", APRIL_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(flow(ledger, "alice", MAY_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void anOperatorDepositReversalDatedInAnEarlierSeasonIsRestatedIntoTheLaterBaselines() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.creditOnChainDeposit("alice", play(800), APRIL); // 10800, April: deposited 800
        ledger.ensureSeasonPack("alice", MAY);                  // 20800, baseline(May) 10800

        ledger.reverseDepositEffect("alice", play(800), APRIL_SEASON, "unverified deposit", "d-1", MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play(20_000));
        assertThat(ledger.seasonRecord("alice", APRIL_SEASON).orElseThrow().deposited()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(10_000));
        assertThat(flow(ledger, "alice", APRIL_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(flow(ledger, "alice", MAY_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void anOperatorReversalIsRestatedIntoEveryLaterBaseline() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.creditOnChainDeposit("alice", play(800), APRIL); // 10800
        ledger.ensureSeasonPack("alice", MAY);                  // 20800, baseline(May) 10800
        ledger.ensureSeasonPack("alice", JUNE);                 // 30800, baseline(Jun) 20800

        ledger.reverseDepositEffect("alice", play(800), APRIL_SEASON, "unverified deposit", "d-1", JUNE);

        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("alice", MAY_SEASON)).isEqualTo(play(20_000));
        assertThat(ledger.seasonEndBalance("alice", JUNE_SEASON)).isEqualTo(play(30_000));
        assertThat(flow(ledger, "alice", MAY_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(flow(ledger, "alice", JUNE_SEASON)).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void anOperatorReversalInTheCurrentSeasonRestatesNothing() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("alice", MAY);                  // baseline(May) 10000
        ledger.creditOnChainDeposit("alice", play(800), MAY);   // 20800

        ledger.reverseDepositEffect("alice", play(800), MAY_SEASON, "unverified deposit", "d-1", MAY);

        assertThat(ledger.seasonRecord("alice", MAY_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
        assertThat(ledger.balance("alice")).isEqualTo(play(20_000));
    }

    @Test
    void anOperatorReversalDatedInASeasonWithoutARecordRestatesNothing() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("alice", JUNE); // no May record; baseline(Jun) 10000

        // May has no record to hold the bookkeeping (a no-op there, as before), so nothing moves in the baselines either
        ledger.reverseWithdrawalEffect("alice", play(500), MAY_SEASON, "bogus withdrawal", "w-1", JUNE);

        assertThat(ledger.balance("alice")).isEqualTo(play(20_500));
        assertThat(ledger.seasonRecord("alice", JUNE_SEASON).orElseThrow().baselineBalance()).isEqualTo(play(10_000));
        assertThat(ledger.seasonEndBalance("alice", APRIL_SEASON)).isEqualTo(play(10_000));
    }

    @Test
    void theRestatementLeavesTheAppendOnlyLogAsItIs() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "1h-late", play(100), APRIL_LAST_BET);
        ledger.ensureSeasonPack("alice", MAY_FIRST);
        int entriesBefore = ledger.entries().size();

        ledger.settleRound("alice", "1h-late", play(180), Optional.of(true), APRIL_LAST_MS);

        assertThat(ledger.entries()).hasSize(entriesBefore + 1); // one SETTLEMENT entry, nothing for the restatement
        PlayAmount replayed = PlayAmount.ZERO;
        for (LedgerEntry entry : ledger.entries()) {
            replayed = replayed.plus(entry.delta());
        }
        assertThat(replayed).isEqualTo(ledger.balance("alice"));
    }
}
