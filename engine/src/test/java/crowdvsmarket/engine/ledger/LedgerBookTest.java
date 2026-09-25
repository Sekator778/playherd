package crowdvsmarket.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LedgerBookTest {

    private static final Instant APRIL = Instant.parse("2026-04-05T10:00:00Z");
    private static final Instant MAY = Instant.parse("2026-05-02T10:00:00Z");

    @Test
    void firstActivityGrantsTheStarterPackLazily() {
        LedgerBook ledger = new LedgerBook();
        assertThat(ledger.playerIds()).doesNotContain("alice");

        ledger.ensureSeasonPack("alice", APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(ledger.seasonRecord("alice", Season.of(APRIL)))
                .contains(new SeasonRecord(Season.of(APRIL), PlayAmount.ZERO, PlayAmount.ZERO, PlayAmount.ZERO, 0, 0, 0));
    }

    @Test
    void packGrantIsIdempotentWithinTheSameSeason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.ensureSeasonPack("alice", APRIL.plusSeconds(3600));

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void seasonRolloverRecordsBaselineAndAddsPackOnTop() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        // Alice wins some PLAY during April.
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);
        ledger.settleRound("alice", "r1", PlayAmount.ofWholePlay(800), Optional.of(true), APRIL);
        PlayAmount balanceBeforeMay = ledger.balance("alice"); // 10000 - 500 + 800 = 10300

        ledger.ensureSeasonPack("alice", MAY);

        assertThat(ledger.balance("alice")).isEqualTo(balanceBeforeMay.plus(LedgerBook.STARTER_PACK));
        Optional<SeasonRecord> may = ledger.seasonRecord("alice", Season.of(MAY));
        assertThat(may).isPresent();
        assertThat(may.get().baselineBalance()).isEqualTo(balanceBeforeMay);
        // April's record is kept, not discarded (Р-10: archive).
        assertThat(ledger.seasonRecord("alice", Season.of(APRIL))).isPresent();
    }

    @Test
    void reserveStakeRejectsBelowMinimum() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThatThrownBy(() -> ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(99), APRIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveStakeRejectsAboveBalance() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL); // balance 10000
        assertThatThrownBy(() -> ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(10_001), APRIL))
                .isInstanceOf(IllegalArgumentException.class);
        // The rejected attempt must not have mutated the balance.
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void reserveStakeDebitsAndRefundCreditsBack() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_500));

        ledger.refundStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(10_000));
    }

    @Test
    void settleRoundCreditsPayoutAndTracksRoundsPlayed() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);

        ledger.settleRound("alice", "r1", PlayAmount.fromMoney(new BigDecimal("650.00")), Optional.of(true), APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_500).plus(PlayAmount.fromMoney(new BigDecimal("650.00"))));
        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.roundsPlayed()).isEqualTo(1);
        assertThat(record.directionEligibleRounds()).isEqualTo(1);
        assertThat(record.directionCorrectRounds()).isEqualTo(1);
    }

    @Test
    void settleRoundWithZeroPayoutRepresentsFullLiquidationLoss() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);

        ledger.settleRound("alice", "r1", PlayAmount.ZERO, Optional.of(false), APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_500)); // stake never returns
        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.roundsPlayed()).isEqualTo(1);
        assertThat(record.directionCorrectRounds()).isZero();
    }

    @Test
    void flatRoundIsExcludedFromDirectionEligibility() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);

        ledger.settleRound("alice", "r1", PlayAmount.ofWholePlay(500), Optional.empty(), APRIL);

        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.roundsPlayed()).isEqualTo(1);
        assertThat(record.directionEligibleRounds()).isZero();
        assertThat(record.directionCorrectRounds()).isZero();
    }

    @Test
    void withdrawalConfirmedKeepsTheEarlierDebit() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);

        WithdrawalRequest requested = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(1_000), APRIL);
        assertThat(requested.state()).isEqualTo(WithdrawalState.REQUESTED);
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_000));

        WithdrawalRequest confirmed = ledger.confirmWithdrawal(requested.requestId());
        assertThat(confirmed.state()).isEqualTo(WithdrawalState.CONFIRMED);
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_000));

        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.withdrawn()).isEqualTo(PlayAmount.ofWholePlay(1_000));
    }

    @Test
    void failedWithdrawalRefundsAndUnwindsSeasonWithdrawn() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        WithdrawalRequest requested = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(1_000), APRIL);

        WithdrawalRequest failed = ledger.failWithdrawal(requested.requestId(), APRIL);

        assertThat(failed.state()).isEqualTo(WithdrawalState.FAILED);
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.withdrawn()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void depositOnlyCreditsOnceConfirmed() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);

        DepositRequest requested = ledger.requestDeposit("alice", PlayAmount.ofWholePlay(2_000), APRIL);
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK); // no change yet

        DepositRequest confirmed = ledger.confirmDeposit(requested.requestId(), APRIL);
        assertThat(confirmed.state()).isEqualTo(DepositState.CONFIRMED);
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK.plus(PlayAmount.ofWholePlay(2_000)));

        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.deposited()).isEqualTo(PlayAmount.ofWholePlay(2_000));
    }

    @Test
    void failedDepositNeverCreditsBalance() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        DepositRequest requested = ledger.requestDeposit("alice", PlayAmount.ofWholePlay(2_000), APRIL);

        DepositRequest failed = ledger.failDeposit(requested.requestId());

        assertThat(failed.state()).isEqualTo(DepositState.FAILED);
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
    }

    @Test
    void balanceNeverGoesNegative() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThatThrownBy(() -> ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(20_000), APRIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.balance("alice").isNegative()).isFalse();
    }

    @Test
    void everyMutationIsLoggedAndReplayReproducesTheBalance() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(500), APRIL);
        ledger.settleRound("alice", "r1", PlayAmount.fromMoney(new BigDecimal("650.00")), Optional.of(true), APRIL);
        WithdrawalRequest w = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(1_000), APRIL);
        ledger.confirmWithdrawal(w.requestId());

        PlayAmount replayed = PlayAmount.ZERO;
        for (LedgerEntry entry : ledger.entries()) {
            if (entry.playerId().equals("alice")) {
                replayed = replayed.plus(entry.delta());
            }
        }

        assertThat(replayed).isEqualTo(ledger.balance("alice"));
    }
}
