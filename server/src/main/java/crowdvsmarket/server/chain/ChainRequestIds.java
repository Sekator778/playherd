package crowdvsmarket.server.chain;

import crowdvsmarket.server.persistence.ServerMetaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The ids a withdrawal goes by at the chain service (E1). The engine numbers its withdrawals {@code w-1},
 * {@code w-2}, … per database, and the chain service keeps every request id it was ever given — confirmed
 * and failed ones for good. A new database against a chain service that has seen an older one would meet its
 * own {@code w-11} in the old one's record: submitted, it is "already known"; asked about, the chain service
 * answers with the OLD request's status — {@code confirmed}, with a coin — and the server would mark a payout that
 * never happened as paid.
 *
 * <p>So the id sent to the chain service for a withdrawal — to submit it, to ask about it, to retry it — is
 * {@code "<engine id>-<epoch tag>"} ({@link #forWithdrawal}): the tag is 8 lowercase hex characters drawn once per
 * database and kept in {@code server_meta} ({@link ServerMetaRepository#withdrawalNamespace}). The id players see —
 * in the DTOs, the ledger, the events — stays the engine's own, {@code w-11}. A database that already has
 * withdrawals when the tag is first needed keeps the plain ids (its requests in flight were submitted under
 * them); that decision is stored as well. Nothing is cached here: the stored value is the truth, and it is one
 * indexed read.
 */
@ApplicationScoped
public class ChainRequestIds {

    private static final int TAG_BYTES = 4; // 8 hex characters

    @Inject
    ServerMetaRepository serverMeta;

    private final SecureRandom random = new SecureRandom();

    /**
     * Decides this database's namespace if it has none yet. Called before the first withdrawal row of a
     * database is written ({@code GameService#requestWithdrawal}): decided only afterwards, that very row would
     * make the database look like one that already had withdrawals, and it would never get a tag.
     */
    public void ensureNamespace() {
        serverMeta.withdrawalNamespace(this::newTag);
    }

    /** The id the chain service knows {@code engineRequestId} (the engine's {@code "w-N"}) by. */
    public String forWithdrawal(String engineRequestId) {
        String namespace = serverMeta.withdrawalNamespace(this::newTag);
        return ServerMetaRepository.PLAIN_WITHDRAWAL_IDS.equals(namespace) ? engineRequestId
                : engineRequestId + "-" + namespace;
    }

    private String newTag() {
        byte[] bytes = new byte[TAG_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
