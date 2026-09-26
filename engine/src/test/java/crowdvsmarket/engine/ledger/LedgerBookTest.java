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
    void creditOnChainDepositCreditsImmediatelyAndCountsTowardSeasonDeposited() {
        // Р-53: unlike requestDeposit/confirmDeposit's two-step (above), a real on-chain deposit
        // is already confirmed by the time it reaches the ledger — one call, immediate credit.
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);

        ledger.creditOnChainDeposit("alice", PlayAmount.ofWholePlay(400), APRIL);

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK.plus(PlayAmount.ofWholePlay(400)));
        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.deposited()).isEqualTo(PlayAmount.ofWholePlay(400));
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

    // --- wave-2 security fix: non-positive amounts at every money entry point ---
    //
    // Note: LedgerBook.requestWithdrawal/requestDeposit deliberately do NOT reject a non-positive
    // amount themselves (see their own comments) — that guard lives in GameService, before a new
    // command is ever turned into an event, precisely so replaying an already-persisted historical
    // event (like the one this fix corrects, see the "operator correction" tests below) never
    // breaks. reserveStake has no such historical-compatibility concern (no non-positive stake was
    // ever accepted, even before this fix), so it guards directly.

    @Test
    void reserveStakeRejectsZeroAndNegativeStake() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThatThrownBy(() -> ledger.reserveStake("alice", "r1", PlayAmount.ZERO, APRIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.reserveStake("alice", "r1", PlayAmount.ofWholePlay(-500), APRIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK); // untouched
    }

    // --- wave-2 security fix: public, replayable operator correction ---

    @Test
    void reverseWithdrawalEffectUndoesABogusConfirmedWithdrawalExactly() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL); // balance 10000

        // The exact wave-2 exploit shape: LedgerBook.requestWithdrawal itself never rejected a
        // negative amount (that guard lives in GameService, see above) — debit(amount) negates it,
        // so amount=-5000 becomes a +5000 credit once auto-confirmed (this server's
        // OnChainWalletPort stub confirms every withdrawal immediately).
        PlayAmount bogusAmount = PlayAmount.fromMoney(new BigDecimal("-5000.000"));
        WithdrawalRequest bogus = ledger.requestWithdrawal("alice", bogusAmount, APRIL);
        ledger.confirmWithdrawal(bogus.requestId());
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(15_000)); // the bug, reproduced

        LedgerCorrection correction = ledger.reverseWithdrawalEffect("alice", bogus.amount(), Season.of(APRIL),
                "wave-2 test exploit: requestWithdrawal(-5000.000) minted PLAY", bogus.requestId(), APRIL.plusSeconds(60));

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK); // back to honest 10000
        assertThat(correction.playerId()).isEqualTo("alice");
        assertThat(correction.delta()).isEqualTo(bogusAmount);
        assertThat(correction.relatedRequestId()).isEqualTo(bogus.requestId());
        assertThat(correction.reason()).contains("wave-2");
        assertThat(ledger.corrections()).containsExactly(correction); // public, listed

        // the original bogus request is untouched — no altering/deleting history
        WithdrawalRequest stillOnRecord = ledger.withdrawal(bogus.requestId()).orElseThrow();
        assertThat(stillOnRecord.state()).isEqualTo(WithdrawalState.CONFIRMED);
        assertThat(stillOnRecord.amount().toMoney()).isEqualByComparingTo("-5000.000");

        // season bookkeeping (withdrawn) is also restored to honest zero, not left at -5000
        SeasonRecord record = ledger.seasonRecord("alice", Season.of(APRIL)).orElseThrow();
        assertThat(record.withdrawn()).isEqualTo(PlayAmount.ZERO);

        // replay of entries() still reproduces the corrected balance (event-sourcing invariant)
        PlayAmount replayed = PlayAmount.ZERO;
        for (LedgerEntry entry : ledger.entries()) {
            if (entry.playerId().equals("alice")) {
                replayed = replayed.plus(entry.delta());
            }
        }
        assertThat(replayed).isEqualTo(ledger.balance("alice"));
    }

    @Test
    void reverseWithdrawalEffectWorksFromConfirmedStateUnlikeFailWithdrawal() {
        // failWithdrawal (the pre-existing on-chain-failure path) requires REQUESTED and would
        // reject this; reverseWithdrawalEffect must not, since this server auto-confirms every
        // withdrawal immediately (docs/research/server_design.md §3) — REQUESTED never lingers.
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        WithdrawalRequest w = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(500), APRIL);
        ledger.confirmWithdrawal(w.requestId());
        assertThatThrownBy(() -> ledger.failWithdrawal(w.requestId(), APRIL))
                .isInstanceOf(IllegalStateException.class); // sanity: confirms the precondition above

        ledger.reverseWithdrawalEffect("alice", w.amount(), Season.of(APRIL), "operator test correction",
                w.requestId(), APRIL.plusSeconds(1));

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK); // 500 restored
        // the request itself is still CONFIRMED — reverseWithdrawalEffect never transitions it
        assertThat(ledger.withdrawal(w.requestId())).map(WithdrawalRequest::state).contains(WithdrawalState.CONFIRMED);
    }

    @Test
    void reverseWithdrawalEffectRequiresAReason() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        assertThatThrownBy(() -> ledger.reverseWithdrawalEffect("alice", PlayAmount.ofWholePlay(500), Season.of(APRIL),
                " ", "w-1", APRIL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.reverseWithdrawalEffect("alice", PlayAmount.ofWholePlay(500), Season.of(APRIL),
                null, "w-1", APRIL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reverseWithdrawalEffectCannotBeAppliedTwiceForTheSameRequestId() {
        LedgerBook ledger = new LedgerBook();
        ledger.ensureSeasonPack("alice", APRIL);
        WithdrawalRequest w = ledger.requestWithdrawal("alice", PlayAmount.ofWholePlay(500), APRIL);
        ledger.confirmWithdrawal(w.requestId());
        ledger.reverseWithdrawalEffect("alice", w.amount(), Season.of(APRIL), "first correction", w.requestId(), APRIL);

        assertThatThrownBy(() -> ledger.reverseWithdrawalEffect("alice", w.amount(), Season.of(APRIL),
                "second correction", w.requestId(), APRIL))
                .isInstanceOf(IllegalStateException.class);
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
