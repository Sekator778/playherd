package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link LiveMarketsProfile} with the production setting of the journal (Р-107): an append does NOT wait for the copy in {@code game_events}.
 * The server tests run with {@code herd.journal.project-synchronously=true} so that a read of the table after an answer sees the row; the
 * tests of the projector, the lag and the crashes between the journal and the table need the real thing.
 */
public class JournalAsyncProfile extends LiveMarketsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.journal.project-synchronously", "false");
        return overrides;
    }
}
