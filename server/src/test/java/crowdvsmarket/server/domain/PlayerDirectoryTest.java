package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.persistence.Db;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The directory's cache never runs ahead of the {@code players} table (class javadoc of {@link
 * PlayerDirectory}): a write touches the cache only when the caller says its transaction committed, and a
 * reload swaps a fully read table in — a read that fails leaves the cache as it was, instead of the empty
 * one the in-place reload left (a rebuild that then failed served players nobody knew). The database is a
 * mock; what is checked is what the cache does with it.
 */
class PlayerDirectoryTest {

    private Db db;
    private PlayerDirectory directory;

    private static PlayerDirectory.Row row(String id, String nickname, boolean bot, int index) {
        return new PlayerDirectory.Row(id, "txch1" + id, nickname, bot, bot ? "RANDOM" : null, index, 1L);
    }

    @BeforeEach
    void wire() {
        db = mock(Db.class);
        directory = new PlayerDirectory();
        directory.db = db;
    }

    @SuppressWarnings("unchecked")
    private void theTableHolds(PlayerDirectory.Row... rows) {
        when(db.read(any())).thenReturn(List.of(rows));
    }

    @Test
    void reloadFillsTheCacheFromTheTableAndReplacesWhatWasThere() {
        theTableHolds(row("p1", "Ann", false, 1), row("bot1", "Bot 1", true, 2));
        directory.reload();
        assertThat(directory.byId("p1")).get().extracting(PlayerDirectory.Row::nickname).isEqualTo("Ann");
        assertThat(directory.playerIdForWallet("txch1bot1")).contains("bot1");
        assertThat(directory.playerIdForIndex(2)).contains("bot1");
        assertThat(directory.bots()).extracting(PlayerDirectory.Row::playerId).containsExactly("bot1");

        theTableHolds(row("p2", null, false, 3));
        directory.reload();

        assertThat(directory.byId("p1")).isEmpty();
        assertThat(directory.playerIdForIndex(2)).isEmpty();
        assertThat(directory.all()).extracting(PlayerDirectory.Row::playerId).containsExactly("p2");
        assertThat(directory.identitiesById()).containsOnlyKeys("p2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReloadThatFailsToReadTheTableLeavesTheCacheAsItWas() {
        theTableHolds(row("p1", "Ann", false, 1));
        directory.reload();
        when(db.read(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> directory.reload()).hasMessageContaining("db down");

        assertThat(directory.byId("p1")).isPresent();
        assertThat(directory.playerIdForWallet("txch1p1")).contains("p1");
        assertThat(directory.all()).hasSize(1);
    }

    @Test
    void anInsertTouchesTheCacheOnlyWhenTheCallerCachesTheRowAfterTheCommit() throws Exception {
        Connection connection = mock(Connection.class);

        PlayerDirectory.Row inserted = directory.insert(connection, "p9", "txch1p9", "Nine", false, null, 9, 5L);

        assertThat(inserted.playerId()).isEqualTo("p9");
        assertThat(directory.byId("p9")).as("not before the commit").isEmpty();
        assertThat(directory.playerIdForWallet("txch1p9")).isEmpty();

        directory.cache(inserted);   // the transaction committed

        assertThat(directory.byId("p9")).contains(inserted);
        assertThat(directory.playerIdForWallet("txch1p9")).contains("p9");
        assertThat(directory.playerIdForIndex(9)).contains("p9");
    }

    @Test
    void aNicknameChangesTheCacheOnlyAfterTheCommitAndKeepsTheRestOfTheRow() throws Exception {
        theTableHolds(row("p1", "Ann", false, 1));
        directory.reload();
        Connection connection = mock(Connection.class);

        directory.updateNickname(connection, "p1", "Anna");
        assertThat(directory.byId("p1").orElseThrow().nickname()).as("the commit has not happened").isEqualTo("Ann");

        directory.cacheNickname("p1", "Anna");

        PlayerDirectory.Row after = directory.byId("p1").orElseThrow();
        assertThat(after.nickname()).isEqualTo("Anna");
        assertThat(after.walletAddress()).isEqualTo("txch1p1");
        assertThat(after.playerIndex()).isEqualTo(1);
        directory.cacheNickname("nobody", "Nobody");   // an unknown player: nothing appears
        assertThat(directory.byId("nobody")).isEmpty();
    }
}
