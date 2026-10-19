package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code AuthSessionDto.starterPackGranted} (first tester pass, D1): a sign-in says whether IT credited a starter pack. The web
 * app told every returning player "You've got 10,000 PLAY" over a balance that was not 10,000, and then — once it told only a new
 * player — said nothing to the returning player whose month had rolled over, who is given a new pack on top of what they have.
 * The answer is "a pack was credited by this sign-in", nothing else: a new player's, the first sign-in of a new season's — and
 * not the second sign-in of a month, nor one that appends an event that credits nothing.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class StarterPackGrantedTest extends LiveRpcTestBase {

    private static final long MARCH = Instant.parse("2026-03-15T10:00:00Z").toEpochMilli();
    private static final long MARCH_LAST_SECOND = Instant.parse("2026-03-31T23:59:59Z").toEpochMilli();
    private static final long APRIL_FIRST = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
    private static final long APRIL = Instant.parse("2026-04-20T10:00:00Z").toEpochMilli();
    private static final long FEBRUARY = Instant.parse("2026-02-10T10:00:00Z").toEpochMilli();

    private static String wallet() {
        return "txch1pack" + UUID.randomUUID().toString().replace("-", "");
    }

    /** How many starter packs the ledger has credited to the player — the fact the flag has to agree with. */
    private long packsCredited(String playerId) {
        gameService.flushLedgerRows(); // the ledger keeps no history of its own: it is the ledger_entries table
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM ledger_entries WHERE player_id = ? AND type = ?",
                rs -> rs.getLong("n"), playerId, LedgerEntryType.STARTER_PACK.name()));
    }

    @Test
    void aNewPlayersFirstSignInCreditsTheFirstPackAndSaysSo() {
        GameService.PlayerTouchResult first = gameService.registerOrTouchPlayer(wallet(), MARCH);

        assertThat(first.isNewPlayer()).isTrue();
        assertThat(first.starterPackGranted()).isTrue();
        assertThat(packsCredited(first.player().playerId())).isEqualTo(1);
    }

    @Test
    void aSecondSignInInTheSameMonthCreditsNothingAndSaysSo() {
        String wallet = wallet();
        GameService.PlayerTouchResult first = gameService.registerOrTouchPlayer(wallet, MARCH);

        GameService.PlayerTouchResult second = gameService.registerOrTouchPlayer(wallet, MARCH + 5_000L);
        GameService.PlayerTouchResult lastSecondOfTheMonth = gameService.registerOrTouchPlayer(wallet, MARCH_LAST_SECOND);

        assertThat(second.isNewPlayer()).isFalse();
        assertThat(second.starterPackGranted()).isFalse();
        assertThat(lastSecondOfTheMonth.starterPackGranted()).isFalse();
        assertThat(packsCredited(first.player().playerId())).isEqualTo(1);
    }

    @Test
    void theFirstSignInOfANewMonthCreditsAPackOnTopOfTheBalanceAndSaysSo_theNextOneDoesNot() {
        String wallet = wallet();
        String playerId = gameService.registerOrTouchPlayer(wallet, MARCH).player().playerId();

        GameService.PlayerTouchResult rollover = gameService.registerOrTouchPlayer(wallet, APRIL_FIRST);
        GameService.PlayerTouchResult later = gameService.registerOrTouchPlayer(wallet, APRIL);

        assertThat(rollover.isNewPlayer()).as("the same player, not a new one").isFalse();
        assertThat(rollover.player().playerId()).isEqualTo(playerId);
        assertThat(rollover.starterPackGranted()).as("the month rolled over: a pack was credited by this sign-in").isTrue();
        assertThat(later.starterPackGranted()).as("the second sign-in of April").isFalse();
        assertThat(packsCredited(playerId)).isEqualTo(2);
        assertThat(hub.ledger().balance(playerId)).as("the new pack comes on top of what the player had")
                .isEqualTo(PlayAmount.ofWholePlay(20_000));
    }

    @Test
    void aSignInDatedBeforeTheAccountsSeasonAppendsAnEventThatCreditsNothing_andDoesNotSayItGrantedAPack() {
        String wallet = wallet();
        String playerId = gameService.registerOrTouchPlayer(wallet, MARCH).player().playerId();

        // February was never "touched" by this player, so a PlayerRegistered event is appended; the ledger is already in March
        // and credits nothing for an earlier season. The flag follows the credit, not the event.
        GameService.PlayerTouchResult backdated = gameService.registerOrTouchPlayer(wallet, FEBRUARY);

        assertThat(backdated.starterPackGranted()).isFalse();
        assertThat(packsCredited(playerId)).isEqualTo(1);
    }

    @Test
    void theFlagIsAlwaysOnTheWire_trueOnTheFirstSignInAndFalseOnTheNext() {
        String wallet = wallet();

        RpcClient.SignIn first = RpcClient.signInWithAnswer(chainSvcClient, wallet);
        RpcClient.SignIn second = RpcClient.signInWithAnswer(chainSvcClient, wallet);

        assertThat(first.answer().path("isNewPlayer").isBoolean()).isTrue();
        assertThat(first.answer().path("isNewPlayer").asBoolean()).isTrue();
        assertThat(first.answer().path("starterPackGranted").isBoolean()).as("a boolean, never left out").isTrue();
        assertThat(first.answer().path("starterPackGranted").asBoolean()).isTrue();
        assertThat(second.answer().path("isNewPlayer").asBoolean()).isFalse();
        assertThat(second.answer().path("starterPackGranted").isBoolean()).as("a boolean, never left out").isTrue();
        assertThat(second.answer().path("starterPackGranted").asBoolean()).isFalse();
        assertThat(second.session().playerId()).isEqualTo(first.session().playerId());
    }
}
