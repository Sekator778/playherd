package crowdvsmarket.engine.ledger;

import crowdvsmarket.engine.season.Season;

/**
 * One player's bookkeeping for one {@link Season}: the balance baseline recorded just before that
 * season's starter pack was granted (Р-16), money moved on/off chain during the season, and the
 * round counters the two ratings (Р-18) are built from. Immutable — {@code LedgerBook} replaces it
 * wholesale on every update, like every other aggregate in this engine.
 *
 * <p>{@code baselineBalance} is, in other words, the player's balance at the end of the previous
 * season she has a record of. It is that balance <i>as attributed</i>: a movement that belongs to an
 * earlier season but was only booked after the player rolled into this one (a late settlement, an
 * operator reversal) is added to it, see {@code LedgerBook#settleRound} — so it can differ from the
 * balance the account actually had at the instant of the roll by exactly such movements.
 */
public record SeasonRecord(
        Season season,
        PlayAmount baselineBalance,
        PlayAmount deposited,
        PlayAmount withdrawn,
        int roundsPlayed,
        int directionEligibleRounds,
        int directionCorrectRounds) {

    static SeasonRecord starting(Season season, PlayAmount baselineBalance) {
        return new SeasonRecord(season, baselineBalance, PlayAmount.ZERO, PlayAmount.ZERO, 0, 0, 0);
    }

    /**
     * Restates the baseline by {@code delta} (negative to take a movement out): a movement dated in
     * an earlier season, booked after this season began, belongs to the previous season's end
     * balance, not to this season's flow — see {@code LedgerBook#settleRound}.
     */
    SeasonRecord withBaselineDelta(PlayAmount delta) {
        return new SeasonRecord(
                season, baselineBalance.plus(delta), deposited, withdrawn, roundsPlayed,
                directionEligibleRounds, directionCorrectRounds);
    }

    SeasonRecord withDeposit(PlayAmount amount) {
        return new SeasonRecord(
                season, baselineBalance, deposited.plus(amount), withdrawn, roundsPlayed,
                directionEligibleRounds, directionCorrectRounds);
    }

    /** {@code delta} is negative to reverse a withdrawal whose on-chain leg failed. */
    SeasonRecord withWithdrawalDelta(PlayAmount delta) {
        return new SeasonRecord(
                season, baselineBalance, deposited, withdrawn.plus(delta), roundsPlayed,
                directionEligibleRounds, directionCorrectRounds);
    }

    /**
     * Records one more resolved round. {@code directionCorrect} is {@code null} for a flat round
     * (close == open), excluded from the "пророки" denominator (Р-18); otherwise it marks whether
     * the player's chosen direction matched the round's actual move, independent of liquidation.
     */
    SeasonRecord withRoundPlayed(Boolean directionCorrect) {
        int eligible = directionEligibleRounds + (directionCorrect != null ? 1 : 0);
        int correct = directionCorrectRounds + (Boolean.TRUE.equals(directionCorrect) ? 1 : 0);
        return new SeasonRecord(season, baselineBalance, deposited, withdrawn, roundsPlayed + 1, eligible, correct);
    }
}
