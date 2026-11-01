package crowdvsmarket.engine.ledger;

/** State machine of a deposit request (D-6): requested → confirmed | failed. */
public enum DepositState {
    REQUESTED,
    CONFIRMED,
    FAILED
}
