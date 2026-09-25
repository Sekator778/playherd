package crowdvsmarket.engine.ledger;

/** State machine of a withdrawal request (Р-6): requested → confirmed | failed. */
public enum WithdrawalState {
    REQUESTED,
    CONFIRMED,
    FAILED
}
