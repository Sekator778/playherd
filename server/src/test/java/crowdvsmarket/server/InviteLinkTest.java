package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.dto.Auth;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.InviteRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Personal invite link ({@code ?ref=<code>&src=<channel>}): a code per player that is neither the player id nor the wallet, a
 * referrer and a source recorded once at registration and never again, self-referral and unknown codes ignored, and a counter
 * that leaves bots out. No rewards anywhere.
 */
@QuarkusTest
class InviteLinkTest {

    @Inject
    GameService gameService;
    @Inject
    InviteRepository invites;
    @Inject
    Db db;

    private static String wallet() {
        return "txch1invite" + UUID.randomUUID().toString().replace("-", "");
    }

    private String register(String wallet, String code, String source) {
        return gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis(), null, code, source).player().playerId();
    }

    private String column(String playerId, String column) {
        return db.read(c -> db.queryOne(c, "SELECT " + column + " FROM players WHERE player_id = ?", rs -> rs.getString(1), playerId));
    }

    @Test
    void aNewPlayerGetsAShortCodeThatIsNeitherTheIdNorTheWalletAndLooksItUpAgain() {
        String w = wallet();
        String id = register(w, null, null);

        String code = gameService.myInvite(id).code();
        assertThat(code).matches("[0-9a-f]{8}");
        assertThat(code).isNotEqualTo(id).doesNotContain(id).isNotIn(w);
        assertThat(gameService.myInvite(id).code()).as("stable").isEqualTo(code);
        assertThat(invites.playerIdForCode(code)).contains(id);
        assertThat(invites.playerIdForCode(code.toUpperCase())).as("case-insensitive").contains(id);
    }

    @Test
    void referrerAndSourceAreRecordedAtRegistrationAndCountedForTheInviter() {
        String inviter = register(wallet(), null, null);
        String code = gameService.myInvite(inviter).code();
        assertThat(gameService.myInvite(inviter).referredCount()).isZero();

        String guest = register(wallet(), code, "Discord");

        assertThat(column(guest, "referred_by")).isEqualTo(inviter);
        assertThat(column(guest, "source")).isEqualTo("discord");
        assertThat(gameService.myInvite(inviter).referredCount()).isEqualTo(1);
    }

    @Test
    void aSourceAloneIsRecordedWithoutAReferrer() {
        String guest = register(wallet(), null, "reddit");
        assertThat(column(guest, "referred_by")).isNull();
        assertThat(column(guest, "source")).isEqualTo("reddit");
    }

    @Test
    void laterSignInsNeverOverwriteWhatRegistrationRecorded() {
        String a = register(wallet(), null, null);
        String b = register(wallet(), null, null);
        String guestWallet = wallet();
        String guest = register(guestWallet, gameService.myInvite(a).code(), "youtube");

        // The same wallet signs in again through another player's link and another channel.
        String again = register(guestWallet, gameService.myInvite(b).code(), "reddit");

        assertThat(again).isEqualTo(guest);
        assertThat(column(guest, "referred_by")).isEqualTo(a);
        assertThat(column(guest, "source")).isEqualTo("youtube");
        assertThat(gameService.myInvite(a).referredCount()).isEqualTo(1);
        assertThat(gameService.myInvite(b).referredCount()).isZero();
    }

    @Test
    void anExistingPlayerWhoFollowsALinkLaterIsNotAttributed() {
        String inviter = register(wallet(), null, null);
        String oldWallet = wallet();
        String old = register(oldWallet, null, null);

        register(oldWallet, gameService.myInvite(inviter).code(), "discord");

        assertThat(column(old, "referred_by")).isNull();
        assertThat(column(old, "source")).isNull();
        assertThat(gameService.myInvite(inviter).referredCount()).isZero();
    }

    @Test
    void unknownOrMalformedCodesAndTagsAreIgnoredAndTheSignInStillWorks() {
        String guest = register(wallet(), "deadbeef", "bad tag!");
        assertThat(column(guest, "referred_by")).isNull();
        assertThat(column(guest, "source")).isNull();

        String other = register(wallet(), "'; drop table players; --", "x".repeat(40));
        assertThat(column(other, "referred_by")).isNull();
        assertThat(column(other, "source")).isNull();
        assertThat(column(other, "player_id")).isEqualTo(other);
    }

    @Test
    void selfReferralIsIgnored() throws Exception {
        String id = register(wallet(), null, null);
        // A player cannot hold a code before registering, so the guard sits in the repository: the owner's own id is no referrer.
        db.tx(c -> {
            invites.recordAtRegistration(c, id, id, null);
            return null;
        });
        assertThat(column(id, "referred_by")).isNull();
        assertThat(gameService.myInvite(id).referredCount()).isZero();
    }

    @Test
    void theCounterLeavesBotsOut() throws Exception {
        String inviter = register(wallet(), null, null);
        String human = register(wallet(), gameService.myInvite(inviter).code(), null);
        var bot = gameService.createBotPlayer("InviteBot" + UUID.randomUUID().toString().substring(0, 4), "TREND",
                System.currentTimeMillis());
        db.tx(c -> db.update(c, "UPDATE players SET referred_by = ? WHERE player_id = ?", inviter, bot.playerId()));

        assertThat(column(bot.playerId(), "referred_by")).isEqualTo(inviter);
        assertThat(gameService.myInvite(inviter).referredCount()).isEqualTo(1);
        assertThat(human).isNotEqualTo(bot.playerId());
    }

    @Test
    void aBotsCodeIsNeverResolvedAsAReferrer() {
        var bot = gameService.createBotPlayer("InviteBot" + UUID.randomUUID().toString().substring(0, 4), "TREND",
                System.currentTimeMillis());
        String code = invites.ensureCode(bot.playerId());
        assertThat(invites.playerIdForCode(code)).isEmpty();
    }

    @Test
    void theVerifyRequestKeepsTheOldFourArgumentShape() {
        Auth.AuthVerifyRequest req = new Auth.AuthVerifyRequest("n", "a", "pk", "sig");
        assertThat(req.referralCode()).isNull();
        assertThat(req.source()).isNull();
    }
}
