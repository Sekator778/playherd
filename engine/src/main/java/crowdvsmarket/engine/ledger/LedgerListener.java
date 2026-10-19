package crowdvsmarket.engine.ledger;

/**
 * Receives every {@link LedgerEntry} a {@link LedgerBook} produces, in order, on the thread that changed the balance
 * (the book is single-threaded). It lets a caller keep the history somewhere else than in the book's memory: the
 * server projects it into a table, a test collects it to replay the conservation check.
 */
@FunctionalInterface
public interface LedgerListener {

    void onEntry(LedgerEntry entry);
}
