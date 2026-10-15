package crowdvsmarket.server.dto;

/** Economy stage B (Р-92): the operator's view of the house accounts. Not part of contract.ts. */
public final class Economy {
    private Economy() {
    }

    /**
     * The reconciliation of the books with the chain (plan B7), all amounts in PLAY as exact decimal strings.
     *
     * <ul>
     *   <li>{@code issued}: what was ever issued, 100 000 000 PLAY;
     *   <li>{@code onChainTreasury}: the PLAY treasury coin the chain service tracks, {@code null} when it cannot say;
     *   <li>{@code treasury}, {@code herd}, {@code players} (the balances), {@code openStakes} (reserved for open bets): the books;
     *   <li>{@code withdrawnConfirmed}, {@code withdrawalsPending}, {@code depositedTotal}: what crossed the boundary of the game;
     *   <li>{@code openingTotal}: what the accounts added up to at the cutover; {@code expectedTotal}: that plus the deposits minus the
     *       withdrawals since; {@code ledgerTotal}: {@code treasury + herd + players + openStakes}; {@code bookDelta}: the difference of
     *       the two, 0 when the books balance;
     *   <li>{@code chainDelta}: {@code ledgerTotal - onChainTreasury} ({@code null} when the chain is unknown); it is expected to lie
     *       between minus the pending withdrawals (debited here, not yet paid on chain) and the deposits since the cutover (credited
     *       here, not yet swept to the treasury on chain), {@code chainConsistent} says whether it does.
     * </ul>
     */
    public record ReconciliationDto(boolean economyActive, String issued, String onChainTreasury, String treasury, String herd,
                                    String players, String openStakes, String withdrawnConfirmed, String withdrawalsPending,
                                    String depositedTotal, String openingTotal, String expectedTotal, String ledgerTotal,
                                    String bookDelta, String chainDelta, boolean booksBalanced, Boolean chainConsistent,
                                    boolean circuitBreakerOpen, long atMs) {
    }

    /**
     * Economy stage C (C5, Р-90): the public cut of the reconciliation. It shows the supply and where it sits, never HERD (the platform's
     * revenue): {@code platformReserve} is TREASURY and HERD together. All amounts in PLAY as exact decimal strings.
     *
     * <ul>
     *   <li>{@code issued}: 100 000 000 PLAY ever issued;
     *   <li>{@code onChainTreasury}, {@code treasuryCoinId}: the treasury coin on chain, {@code null} when the chain service cannot say;
     *   <li>{@code heldByPlayers}: balances plus the stakes of open bets; {@code platformReserve}: TREASURY + HERD;
     *   <li>{@code withdrawnOnChain}: confirmed withdrawals;
     *   <li>{@code booksBalanced}, {@code chainConsistent} ({@code null} when the chain is unknown), {@code circuitBreakerOpen}.
     * </ul>
     */
    public record SupplyDto(boolean economyActive, String issued, String onChainTreasury, String treasuryCoinId, String heldByPlayers,
                            String platformReserve, String withdrawnOnChain, boolean booksBalanced, Boolean chainConsistent,
                            boolean circuitBreakerOpen, long atMs) {
    }
}
