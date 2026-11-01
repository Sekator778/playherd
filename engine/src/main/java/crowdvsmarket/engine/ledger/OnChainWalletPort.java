package crowdvsmarket.engine.ledger;

/**
 * The seam between this pure off-chain ledger and the actual testnet11 CAT transfer (D-6). A real
 * implementation (in the future Quarkus service, D-20) submits the chain transaction for a
 * {@link WithdrawalRequest}/{@link DepositRequest} and, once it resolves, calls back into
 * {@code LedgerBook.confirmWithdrawal}/{@code failWithdrawal} (or the deposit equivalents) to
 * drive the request's state machine. No such implementation exists in this module — deliberately
 * out of scope (see docs/research/round_engine_design.md's open questions).
 */
public interface OnChainWalletPort {

    void submitWithdrawal(WithdrawalRequest request);

    void submitDeposit(DepositRequest request);
}
