package crowdvsmarket.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.season.Season;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Economy stage B (Р-92, docs/product/economy_implementation_plan.md B1, B2, B4, B6) on the ledger alone: the TREASURY and HERD accounts, the
 * cutover, the pack top-up and the packs that cannot be withdrawn, the retirement of a bot, the sweep of HERD, the circuit breaker.
 */
class LedgerBookEconomyTest {

    private static final Instant APRIL = Instant.parse("2026-04-10T10:00:00Z");
    private static final Instant MAY = Instant.parse("2026-05-10T10:00:00Z");
    private static final PlayAmount OPENING = PlayAmount.ofWholePlay(1_000_000);

    private static PlayAmount play(long whole) {
        return PlayAmount.ofWholePlay(whole);
    }

    private static LedgerBook activeLedger() {
        LedgerBook ledger = new LedgerBook();
        assertThat(ledger.activateEconomy(OPENING, APRIL)).isTrue();
        return ledger;
    }

    // --- B1: the cutover ---

    @Test
    void beforeTheCutoverPacksAreMintedAndNoHouseAccountMoves() {
        LedgerBook ledger = new LedgerBook();

        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(500), APRIL);
        ledger.settleRound("alice", "r1", play(500), play(900), Optional.of(true), APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(play(10_400));
        assertThat(ledger.treasury()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.herd()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.economyActive()).isFalse();
        assertThat(ledger.withdrawable("alice")).as("before the cutover the whole balance").isEqualTo(play(10_400));
    }

    @Test
    void theCutoverOpensTreasuryWithTheOpeningTotalMinusBalancesAndOpenStakes() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("bob", APRIL);
        ledger.reserveStake("alice", "r1", play(700), APRIL); // alice 9300 + 700 open, bob 10000

        assertThat(ledger.activateEconomy(OPENING, APRIL)).isTrue();

        assertThat(ledger.treasury()).isEqualTo(OPENING.minus(play(9_300 + 700 + 10_000)));
        assertThat(ledger.herd()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.openStakes()).isEqualTo(play(700));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void theCutoverHappensOnce() {
        LedgerBook ledger = activeLedger();
        PlayAmount treasury = ledger.treasury();

        assertThat(ledger.activateEconomy(play(5), MAY)).as("a second cutover event is ignored").isFalse();

        assertThat(ledger.treasury()).isEqualTo(treasury);
        assertThat(ledger.openingTotal()).isEqualTo(OPENING);
    }

    @Test
    void aCutoverBelowWhatThePlayersHoldIsRefused() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);

        assertThatThrownBy(() -> ledger.activateEconomy(play(9_999), APRIL)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.economyActive()).isFalse();
    }

    @Test
    void anOpenBetAcrossTheCutoverIsSettledOutOfTreasury() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(1_000), APRIL); // placed under the old rules
        ledger.activateEconomy(OPENING, APRIL);
        PlayAmount treasuryAtCutover = ledger.treasury();

        ledger.settleRound("alice", "r1", play(1_000), play(1_800), Optional.of(true), APRIL); // wins 800

        assertThat(ledger.treasury()).isEqualTo(treasuryAtCutover.minus(play(800)));
        assertThat(ledger.balance("alice")).isEqualTo(play(9_000 + 1_800));
        assertThat(ledger.openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aLostStakeStaysInTreasuryAndFeesGoToHerd() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        PlayAmount treasuryAfterPack = ledger.treasury();
        Fees fees = new Fees(new PlayAmount(5_000), new PlayAmount(100)); // 5 + 0.1 PLAY

        ledger.reserveStake("alice", "r1", play(1_000), fees, APRIL);
        assertThat(ledger.herd()).isEqualTo(new PlayAmount(5_100));
        assertThat(ledger.openStakes()).isEqualTo(play(1_000));
        assertThat(ledger.treasury()).as("the open stake is in no account yet").isEqualTo(treasuryAfterPack);

        ledger.settleRound("alice", "r1", play(1_000), PlayAmount.ZERO, Optional.of(false), APRIL);

        assertThat(ledger.treasury()).isEqualTo(treasuryAfterPack.plus(play(1_000)));
        assertThat(ledger.openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.balance("alice")).isEqualTo(play(9_000).minus(new PlayAmount(5_100)));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aRefundedBetGivesBothFeesBackFromHerd() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        Fees fees = new Fees(new PlayAmount(5_000), new PlayAmount(100));
        ledger.reserveStake("alice", "r1", play(1_000), fees, APRIL);

        ledger.refundStake("alice", "r1", play(1_000), APRIL);
        ledger.refundFees("alice", "r1", fees, APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(play(10_000));
        assertThat(ledger.herd()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void feesHerdNoLongerHoldsAreRefundedFromTreasury() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        Fees fees = new Fees(new PlayAmount(5_000), new PlayAmount(100));
        ledger.reserveStake("alice", "r1", play(1_000), fees, APRIL);
        ledger.sweepHerdToTreasury(new PlayAmount(5_100), APRIL); // swept away before the refund
        PlayAmount treasury = ledger.treasury();

        ledger.refundFees("alice", "r1", fees, APRIL);

        assertThat(ledger.herd()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.treasury()).isEqualTo(treasury.minus(new PlayAmount(5_100)));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void theIdentityHoldsWithDepositsAndWithdrawals() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.creditOnChainDeposit("alice", play(5_000), APRIL);
        WithdrawalRequest w = ledger.requestWithdrawal("alice", play(2_000), APRIL);

        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.externalFlowSinceCutover()).isEqualTo(play(3_000));
        assertThat(ledger.withdrawalsPending()).isEqualTo(play(2_000));
        ledger.confirmWithdrawal(w.requestId());
        assertThat(ledger.withdrawnConfirmed()).isEqualTo(play(2_000));
        assertThat(ledger.withdrawalsPending()).isEqualTo(PlayAmount.ZERO);

        WithdrawalRequest failing = ledger.requestWithdrawal("alice", play(1_000), APRIL);
        ledger.failWithdrawal(failing.requestId(), APRIL);

        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.externalFlowSinceCutover()).isEqualTo(play(3_000));
        assertThat(ledger.depositedSinceCutover()).isEqualTo(play(5_000));
    }

    // --- B2: the pack tops up, and cannot be withdrawn ---

    @Test
    void aNewPlayersPackComesFromTreasury() {
        LedgerBook ledger = activeLedger();

        ledger.ensureSeasonPack("alice", APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(play(10_000));
        assertThat(ledger.treasury()).isEqualTo(OPENING.minus(play(10_000)));
        assertThat(ledger.packCredited("alice")).isEqualTo(play(10_000));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.entries()).extracting(LedgerEntry::type).containsExactly(LedgerEntryType.STARTER_PACK);
    }

    @Test
    void theNextMonthsPackTopsUpToTenThousand() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(6_000), APRIL);
        ledger.settleRound("alice", "r1", play(6_000), play(1_000), Optional.of(false), APRIL); // 5000 left
        PlayAmount treasury = ledger.treasury();
        assertThat(ledger.seasonPackAmount("alice", MAY)).isEqualTo(play(5_000));

        ledger.ensureSeasonPack("alice", MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play(10_000));
        assertThat(ledger.treasury()).isEqualTo(treasury.minus(play(5_000)));
        assertThat(ledger.packCredited("alice")).isEqualTo(play(15_000));
        assertThat(ledger.seasonRecord("alice", Season.of(MAY)).orElseThrow().starterPack()).isEqualTo(play(5_000));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aPlayerAboveTenThousandGetsNoPackAndNoEntry() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(1_000), APRIL);
        ledger.settleRound("alice", "r1", play(1_000), play(3_000), Optional.of(true), APRIL); // 12000
        PlayAmount treasury = ledger.treasury();
        int entries = ledger.entries().size();

        assertThat(ledger.seasonPackAmount("alice", MAY)).isEqualTo(PlayAmount.ZERO);
        ledger.ensureSeasonPack("alice", MAY);

        assertThat(ledger.balance("alice")).isEqualTo(play(12_000));
        assertThat(ledger.treasury()).isEqualTo(treasury);
        assertThat(ledger.entries()).hasSize(entries);
        assertThat(ledger.packCredited("alice")).isEqualTo(play(10_000));
        assertThat(ledger.seasonRecord("alice", Season.of(MAY))).isPresent();
    }

    @Test
    void packsBeforeTheCutoverCountAsPackCredited() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("alice", MAY); // minted flat: 20000
        ledger.activateEconomy(OPENING, MAY);

        assertThat(ledger.packCredited("alice")).isEqualTo(play(20_000));
        assertThat(ledger.withdrawable("alice")).as("20000 balance, 20000 of packs").isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void onlyWhatIsAboveThePacksIsWithdrawable() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThat(ledger.withdrawable("alice")).isEqualTo(PlayAmount.ZERO);

        ledger.reserveStake("alice", "r1", play(1_000), APRIL);
        ledger.settleRound("alice", "r1", play(1_000), play(3_500), Optional.of(true), APRIL); // balance 12500
        assertThat(ledger.withdrawable("alice")).isEqualTo(play(2_500));

        ledger.creditOnChainDeposit("alice", play(1_000), APRIL);
        assertThat(ledger.withdrawable("alice")).as("a deposit is withdrawable too").isEqualTo(play(3_500));

        ledger.reserveStake("alice", "r2", play(5_000), APRIL);
        assertThat(ledger.withdrawable("alice")).as("never negative").isEqualTo(PlayAmount.ZERO);
    }

    // --- B4: retire a bot, sweep HERD ---

    @Test
    void retiringABotSweepsItsBalanceToTreasuryAndOnlyThen() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("bot", APRIL);
        ledger.reserveStake("bot", "r1", play(9_950), APRIL);
        ledger.settleRound("bot", "r1", play(9_950), PlayAmount.ZERO, Optional.of(false), APRIL); // 50 left
        PlayAmount treasury = ledger.treasury();

        PlayAmount swept = ledger.retire("bot", APRIL);

        assertThat(swept).isEqualTo(play(50));
        assertThat(ledger.balance("bot")).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.treasury()).isEqualTo(treasury.plus(play(50)));
        assertThat(ledger.isRetired("bot")).isTrue();
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void theSweepMovesHerdToTreasury() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("bot", APRIL);
        ledger.reserveStake("bot", "r1", play(1_000), new Fees(play(10), play(1)), APRIL);
        assertThat(ledger.herd()).isEqualTo(play(11));
        PlayAmount treasury = ledger.treasury();

        ledger.sweepHerdToTreasury(play(4), MAY);

        assertThat(ledger.herd()).isEqualTo(play(7));
        assertThat(ledger.treasury()).isEqualTo(treasury.plus(play(4)));
        assertThat(ledger.botFeesSwept()).isEqualTo(play(4));
        assertThat(ledger.lastSweepAtMs()).isEqualTo(MAY.toEpochMilli());
        assertThat(ledger.feesPaid("bot")).isEqualTo(play(11));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThatThrownBy(() -> ledger.sweepHerdToTreasury(play(8), MAY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRetiredBotIsInNoRatingList() {
        LedgerBook ledger = activeLedger();
        for (String p : new String[] {"bot", "human"}) {
            ledger.ensureSeasonPack(p, APRIL);
            ledger.reserveStake(p, "r-" + p, play(100), APRIL);
            ledger.settleRound(p, "r-" + p, play(100), play(150), Optional.of(true), APRIL);
        }
        RatingBook ratings = new RatingBook(ledger, 1);
        assertThat(ratings.richList(Season.of(APRIL), Map.of())).hasSize(2);
        assertThat(ratings.prophetList(Season.of(APRIL), Map.of())).hasSize(2);

        ledger.retire("bot", APRIL);

        assertThat(ratings.richList(Season.of(APRIL), Map.of())).extracting(RatingBook.RichEntry::playerId).containsExactly("human");
        assertThat(ratings.prophetList(Season.of(APRIL), Map.of())).extracting(RatingBook.ProphetEntry::playerId).containsExactly("human");
    }

    @Test
    void theRatingSubtractsThePackThatWasActuallyCredited() {
        LedgerBook ledger = activeLedger();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", play(4_000), APRIL);
        ledger.settleRound("alice", "r1", play(4_000), play(1_000), Optional.of(false), APRIL); // 7000
        ledger.ensureSeasonPack("alice", MAY); // topped up by 3000 to 10000
        ledger.reserveStake("alice", "r2", play(1_000), MAY);
        ledger.settleRound("alice", "r2", play(1_000), play(1_600), Optional.of(true), MAY);

        // May: baseline 7000 + pack 3000, then +600: growth 600, not 600 - 7000 (a flat 10000 pack would have been subtracted)
        assertThat(new RatingBook(ledger, 1).richList(Season.of(MAY), Map.of()).get(0).growth()).isEqualTo(play(600));
    }

    // --- B6: the circuit breaker ---

    @Test
    void theBreakerOpensBelowNinetyPercentOfTheThirtyDayMaximumAndClosesOnRecovery() {
        LedgerBook ledger = new LedgerBook();
        ledger.activateEconomy(play(1_000_000), APRIL);
        assertThat(ledger.circuitBreakerOpen(APRIL)).isFalse();
        assertThat(ledger.treasuryMax30d(APRIL)).isEqualTo(play(1_000_000));

        // the treasury pays out 100001 more than it takes in: 899999 < 900000
        ledger.ensureSeasonPack("w", APRIL); // pack 10000 -> treasury 990000
        ledger.reserveStake("w", "r1", play(5_000), APRIL);
        ledger.settleRound("w", "r1", play(5_000), play(20_000).plus(play(80_000)), Optional.of(true), APRIL);
        assertThat(ledger.treasury()).isEqualTo(play(990_000).minus(play(95_000)));
        assertThat(ledger.circuitBreakerOpen(APRIL)).as("895000 is below 900000").isTrue();

        ledger.reserveStake("w", "r2", play(50_000), APRIL);
        ledger.settleRound("w", "r2", play(50_000), PlayAmount.ZERO, Optional.of(false), APRIL);
        assertThat(ledger.treasury()).isEqualTo(play(945_000));
        assertThat(ledger.circuitBreakerOpen(APRIL)).isFalse();
    }

    @Test
    void theMaximumForgetsDaysOlderThanThirty() {
        LedgerBook ledger = new LedgerBook();
        ledger.activateEconomy(play(1_000_000), APRIL);
        ledger.ensureSeasonPack("w", APRIL);
        ledger.reserveStake("w", "r1", play(5_000), APRIL);
        ledger.settleRound("w", "r1", play(5_000), play(100_000), Optional.of(true), APRIL); // treasury 990000 - 95000
        Instant later = APRIL.plusSeconds(40L * 86_400L);

        assertThat(ledger.circuitBreakerOpen(APRIL)).isTrue();
        assertThat(ledger.circuitBreakerOpen(later)).as("the old peak is out of the 30 days").isFalse();
        assertThat(ledger.treasuryMax30d(later)).isEqualTo(ledger.treasury());
    }

    @Test
    void theBreakerIsNeverOpenBeforeTheCutover() {
        assertThat(new LedgerBook().circuitBreakerOpen(APRIL)).isFalse();
    }
}
