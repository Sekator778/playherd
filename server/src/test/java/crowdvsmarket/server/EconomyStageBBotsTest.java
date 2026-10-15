package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.CleanDatabasePerTestClass;
import crowdvsmarket.server.testsupport.EconomyWithBotsProfile;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Economy stage B4 (Р-92) through the service, with three bots ({@link EconomyWithBotsProfile}): a bot pays for its pack from TREASURY, a bot
 * that ran out of PLAY retires without a trace in any list, an hour later a bot with a new generated name takes its place and gets a pack, and
 * once a day the fees the bots paid - and only those - move from HERD to TREASURY. The log replays to the same books.
 */
@QuarkusTest
@TestProfile(EconomyWithBotsProfile.class)
class EconomyStageBBotsTest extends LiveGameTestBase {

    @Inject
    BotService botService;
    @Inject
    PlayerDirectory players;

    @BeforeEach
    void freshBooks() {
        CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    private LedgerBook ledger() {
        return hub.ledger();
    }

    private List<PlayerDirectory.Row> liveBots() {
        return players.bots().stream().filter(b -> !b.isRetired()).toList();
    }

    /** Leaves {@code botId} with {@code left} PLAY by losing the rest in a bet booked straight on the live ledger. */
    private void drain(String botId, long left) {
        Instant now = Instant.now();
        PlayAmount stake = ledger().balance(botId).minus(PlayAmount.ofWholePlay(left));
        ledger().reserveStake(botId, "drain-" + botId, stake, now);
        ledger().settleRound(botId, "drain-" + botId, stake, PlayAmount.ZERO, Optional.of(false), now);
    }

    private Map<String, Object> books() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("treasury", ledger().treasury());
        out.put("herd", ledger().herd());
        out.put("openStakes", ledger().openStakes());
        out.put("lastSweep", ledger().lastSweepAtMs());
        for (PlayerDirectory.Row bot : players.bots()) {
            out.put("balance " + bot.playerId(), ledger().balance(bot.playerId()));
            out.put("retired " + bot.playerId(), ledger().isRetired(bot.playerId()));
        }
        return out;
    }

    @Test
    void theBotsGetTheirPacksFromTreasury() {
        assertThat(liveBots()).hasSize(3);
        assertThat(ledger().treasury()).isEqualTo(PlayAmount.ofWholePlay(1_000_000 - 3 * 10_000));
        for (PlayerDirectory.Row bot : liveBots()) {
            assertThat(ledger().balance(bot.playerId())).isEqualTo(PlayAmount.ofWholePlay(10_000));
            assertThat(ledger().packCredited(bot.playerId())).isEqualTo(PlayAmount.ofWholePlay(10_000));
        }
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aBotThatRanOutRetiresAndANewOneWithANewNameComesAnHourLater() {
        PlayerDirectory.Row broke = liveBots().get(0);
        PlayerDirectory.Row healthy = liveBots().get(1);
        drain(broke.playerId(), 50);
        drain(healthy.playerId(), 100); // 100 PLAY is not below the threshold
        PlayAmount treasuryBefore = ledger().treasury();
        long now = System.currentTimeMillis();

        List<String> retired = gameService.retireBotsBelowThreshold(now);

        assertThat(retired).containsExactly(broke.playerId());
        assertThat(players.byId(broke.playerId()).orElseThrow().isRetired()).isTrue();
        assertThat(ledger().isRetired(broke.playerId())).isTrue();
        assertThat(ledger().balance(broke.playerId())).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger().treasury()).as("its 50 PLAY went to TREASURY").isEqualTo(treasuryBefore.plus(PlayAmount.ofWholePlay(50)));
        assertThat(liveBots()).hasSize(2);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
        // nothing public: no ratings, and the event is not one of the round or player events of the bet feed
        assertThat(gameService.ratings(null).rich()).noneMatch(e -> e.player().playerId().equals(broke.playerId()));
        assertThat(gameService.retireBotsBelowThreshold(now + 1_000)).as("retired once").isEmpty();

        // not an hour yet: nobody comes
        botService.ensureBotsExist(now + 59 * 60_000L);
        assertThat(liveBots()).hasSize(2);
        assertThat(players.byId(broke.playerId()).orElseThrow().replaced()).isFalse();

        // after the hour: a new bot of the same strategy under a new name, with a pack from TREASURY
        PlayAmount treasuryBeforeNewBot = ledger().treasury();
        botService.ensureBotsExist(now + 61 * 60_000L);

        assertThat(liveBots()).hasSize(3);
        PlayerDirectory.Row replacement = liveBots().stream()
                .filter(b -> !b.playerId().equals(healthy.playerId()) && b.createdAtMs() > broke.createdAtMs()).findFirst().orElseThrow();
        assertThat(replacement.botStrategy()).isEqualTo(broke.botStrategy());
        assertThat(replacement.nickname()).startsWith("Bot ").isNotEqualTo(broke.nickname());
        assertThat(players.all()).extracting(PlayerDirectory.Row::nickname).filteredOn(replacement.nickname()::equals).hasSize(1);
        assertThat(ledger().balance(replacement.playerId())).isEqualTo(PlayAmount.ofWholePlay(10_000));
        assertThat(ledger().treasury()).isEqualTo(treasuryBeforeNewBot.minus(PlayAmount.ofWholePlay(10_000)));
        assertThat(players.byId(broke.playerId()).orElseThrow().replaced()).isTrue();
        botService.ensureBotsExist(now + 2 * 3_600_000L);
        assertThat(liveBots()).as("one replacement per retired bot").hasSize(3);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void theRetirementAndTheReplacementReplayToTheSameBooks() {
        PlayerDirectory.Row broke = liveBots().get(0);
        drain(broke.playerId(), 20);
        long now = System.currentTimeMillis();
        gameService.retireBotsBelowThreshold(now);
        botService.ensureBotsExist(now + 2 * 3_600_000L);
        // the direct drain above was never an event: the log has the retirement of a bot that held 10 000, which the replay sweeps whole
        PlayAmount sweptInReplay = PlayAmount.ofWholePlay(10_000);

        rebuild.rebuild(now + 2 * 3_600_000L);

        assertThat(ledger().isRetired(broke.playerId())).isTrue();
        assertThat(ledger().balance(broke.playerId())).isEqualTo(PlayAmount.ZERO);
        assertThat(liveBots()).hasSize(3);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
        Map<String, Object> afterFirst = books();
        rebuild.rebuild(now + 2 * 3_600_000L);
        assertThat(books()).as("replaying again changes nothing").isEqualTo(afterFirst);
        assertThat(ledger().treasury()).isEqualTo(PlayAmount.ofWholePlay(1_000_000 - 4 * 10_000).plus(sweptInReplay));
    }

    @Test
    void theFeesTheBotsPaidMoveToTreasuryOnceADayAndThePeoplesStayInHerd() {
        Market sol = list("T_ECOB_BOTFEES", MarketKind.PERP, Category.CRYPTO, "SOL", 25, 0);
        alignTo(sol, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        prices.setPrice(sol, "100.00");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), Interval.HOURLY);
        String human = signUp("human");
        PlayerDirectory.Row bot = liveBots().get(0);
        long nowMs = betting.startMs() - 1;
        gameService.placeBet(bot.playerId(), bet(betting, Enums.Direction.UP, 100, "200.000"), nowMs);   // 2.000 + 0.100
        gameService.placeBet(human, bet(betting, Enums.Direction.UP, 10, "200.000"), nowMs);             // 0.200 + 0.100
        PlayAmount herdBefore = ledger().herd();
        PlayAmount treasuryBefore = ledger().treasury();
        assertThat(herdBefore).isEqualTo(PlayAmount.fromMoney(new java.math.BigDecimal("2.400")));
        assertThat(ledger().feesPaid(bot.playerId())).isEqualTo(PlayAmount.fromMoney(new java.math.BigDecimal("2.100")));

        PlayAmount moved = gameService.sweepBotFeesIfDue(nowMs);

        assertThat(moved).isEqualTo(PlayAmount.fromMoney(new java.math.BigDecimal("2.100")));
        assertThat(ledger().herd()).as("the person's fees stay").isEqualTo(PlayAmount.fromMoney(new java.math.BigDecimal("0.300")));
        assertThat(ledger().treasury()).isEqualTo(treasuryBefore.plus(moved));
        assertThat(gameService.sweepBotFeesIfDue(nowMs + 3_600_000L)).as("once a day").isEqualTo(PlayAmount.ZERO);
        assertThat(gameService.sweepBotFeesIfDue(nowMs + 86_400_000L)).as("nothing new to move").isEqualTo(PlayAmount.ZERO);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);

        Map<String, Object> before = books();
        rebuild.rebuild(nowMs);
        assertThat(books()).as("the sweep replays").isEqualTo(before);
    }
}
