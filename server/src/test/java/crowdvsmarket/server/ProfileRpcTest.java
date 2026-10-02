package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Profiles (Р-71): {@code PublicProfileDto.streak} — wins in a row over ALL of a player's settled rounds,
 * newest first, a flat round transparent, a cancelled round not a round — and {@code
 * ProfileRoundEntryDto.durationSec}, the real length of the round, on the profile history and on {@code
 * unseenResults}. Rounds are really played through {@code scheduler.tick(now)} with scripted prices; the
 * rule itself is tested in {@code WinStreakTest}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ProfileRpcTest extends LiveRpcTestBase {

    private static void assertKeys(JsonNode node, String... keys) {
        Set<String> found = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(found::add);
        assertThat(found).as("the fields of %s", node).isEqualTo(new TreeSet<>(Set.of(keys)));
    }

    /** A 25x market at 2500 on which every price has one decimal (lot exponent 1). */
    private Market market(String id) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, "PRF", 25, 1);
        prices.setPrice(market, "2500.000");
        return market;
    }

    /**
     * One 100 PLAY bet UP at {@code leverage} on the round open for betting in {@code interval}, played to its
     * settlement: scripted open 2500, then {@code close}, with the candle's low and high as given; one tick at
     * the end of the round settles it (and whatever is overdue before it, which has no bets).
     */
    private RoundWindow play(Market market, Interval interval, String playerId, int leverage, String close, String low,
                             String high) {
        RoundWindow betting = roundProgress.bettingWindow(market.id(), interval);
        gameService.placeBet(playerId, bet(betting, Enums.Direction.UP, leverage, "100.000"), betting.startMs() - 1);
        prices.script(market, betting, "2500.000", close, low, high);
        scheduler.tick(betting.endMs());
        return betting;
    }

    private RoundWindow win(Market market, String player) {
        return play(market, Interval.HOURLY, player, 2, "2510.000", "2500.000", "2510.000");
    }

    private RoundWindow loss(Market market, String player) {
        return play(market, Interval.HOURLY, player, 2, "2490.000", "2490.000", "2500.000");
    }

    private RoundWindow flat(Market market, String player) {
        return play(market, Interval.HOURLY, player, 2, "2500.000", "2495.000", "2505.000");
    }

    private RoundWindow liquidation(Market market, String player) {
        return play(market, Interval.HOURLY, player, 25, "2400.000", "2400.000", "2500.000");
    }

    @Test
    void theStreakIsTheRunOfWinsFromTheNewestRoundAndTheBestRunEverAFlatRoundBreaksNothing() throws Exception {
        Market market = market("T_PF_STREAK");
        Session me = signIn("streak");
        // oldest to newest: a lost daily round, then W W W L W flat W
        play(market, Interval.DAILY, me.playerId(), 2, "2490.000", "2490.000", "2500.000");
        win(market, me.playerId());
        win(market, me.playerId());
        win(market, me.playerId());
        loss(market, me.playerId());
        win(market, me.playerId());
        flat(market, me.playerId());
        win(market, me.playerId());

        JsonNode profile = ok(me, "myProfile");

        assertKeys(profile, "player", "seasonRecord", "history", "streak");
        assertKeys(profile.path("streak"), "current", "best");
        // newest first: W, flat, W, L, W, W, W, L(daily) — two now (the flat round neither extends nor breaks
        // the run), three at best
        assertThat(profile.path("streak").path("current").asInt()).isEqualTo(2);
        assertThat(profile.path("streak").path("best").asInt()).isEqualTo(3);
        assertThat(profile.path("history")).hasSize(8);
        // the same for anybody who looks at the profile
        JsonNode public_ = ok(null, "publicProfile", me.playerId());
        assertThat(public_.path("streak").path("current").asInt()).isEqualTo(2);
        assertThat(public_.path("streak").path("best").asInt()).isEqualTo(3);
        assertThat(ok(signIn("viewer"), "publicProfile", me.playerId()).path("streak").path("best").asInt()).isEqualTo(3);

        // a liquidation is a loss: it ends the current run
        liquidation(market, me.playerId());
        JsonNode afterLiquidation = ok(me, "myProfile");
        assertThat(afterLiquidation.path("history").get(0).path("liquidated").asBoolean()).isTrue();
        assertThat(afterLiquidation.path("history").get(0).path("payout").asText()).isEqualTo("0.000");
        assertThat(afterLiquidation.path("streak").path("current").asInt()).isZero();
        assertThat(afterLiquidation.path("streak").path("best").asInt()).isEqualTo(3);
    }

    @Test
    void aCancelledRoundIsNotARoundItLeavesNoHistoryRowAndTheStreakAlone() throws Exception {
        Market market = market("T_PF_CANCEL");
        Session me = signIn("cancel");
        win(market, me.playerId());
        win(market, me.playerId());
        BigDecimal balanceBefore = new BigDecimal(ok(me, "myAccount").path("balance").asText());

        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(me.playerId(), bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        gameService.cancelRoundById(betting.roundId(), "operator test", betting.startMs() - 1);
        scheduler.tick(betting.endMs()); // the series steps over the cancelled round

        JsonNode profile = ok(me, "myProfile");
        assertThat(profile.path("history")).as("no row for a cancelled round").hasSize(2);
        assertThat(profile.path("streak").path("current").asInt()).isEqualTo(2);
        assertThat(profile.path("streak").path("best").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(ok(me, "myAccount").path("balance").asText()))
                .as("the stake came back").isEqualByComparingTo(balanceBefore);
        assertThat(roundResults.exists(betting.roundId())).isTrue();
    }

    @Test
    void aPlayerWithoutRoundsHasAStreakOfNothing() throws Exception {
        JsonNode profile = ok(signIn("fresh"), "myProfile");

        assertThat(profile.path("history")).isEmpty();
        assertThat(profile.path("streak").path("current").asInt()).isZero();
        assertThat(profile.path("streak").path("best").asInt()).isZero();
    }

    @Test
    void theStreakCountsEveryRoundOfThePlayerNotJustTheHundredOfTheHistoryPage() throws Exception {
        Session me = signIn("longrun");
        // 130 settled rounds, all won, older than anything else — written the way a settlement writes them
        // (their round results are not needed: the history row stands for itself)
        db.tx(c -> {
            for (int i = 0; i < 130; i++) {
                db.update(c, """
                        INSERT INTO player_round_history (round_id, player_id, interval, settled_at_ms, direction,
                            leverage, stake_mojos, liquidated, direction_correct, payout_mojos, market)
                        VALUES (?, ?, 'HOURLY', ?, 'UP', 2, 100000, FALSE, TRUE, 100800, 'BTC')
                        """, "1h-" + (1_000L + i), me.playerId(), 1_000L + i);
            }
            return null;
        });

        JsonNode profile = ok(me, "myProfile");

        assertThat(profile.path("history")).as("the page is capped").hasSize(100);
        assertThat(profile.path("streak").path("current").asInt()).isEqualTo(130);
        assertThat(profile.path("streak").path("best").asInt()).isEqualTo(130);
    }

    @Test
    void historyEntriesAndUnseenResultsCarryTheRealLengthOfTheRound() throws Exception {
        Market market = market("T_PF_LEN");
        Session me = signIn("length");
        RoundWindow daily = play(market, Interval.DAILY, me.playerId(), 2, "2510.000", "2500.000", "2510.000");
        RoundWindow hourly = win(market, me.playerId());

        JsonNode profile = ok(me, "myProfile");
        List<JsonNode> history = new ArrayList<>();
        profile.path("history").forEach(history::add);

        assertThat(history).hasSize(2);
        for (JsonNode entry : history) {
            assertKeys(entry, "roundId", "interval", "marketId", "asset", "durationSec", "settledAtMs", "direction",
                    "leverage", "stake", "liquidated", "directionCorrect", "payout");
            assertThat(entry.path("marketId").asText()).isEqualTo("T_PF_LEN");
            assertThat(entry.path("asset").asText()).isEqualTo("PRF");
        }
        JsonNode hourlyEntry = history.stream().filter(e -> e.path("roundId").asText().equals(hourly.roundId()))
                .findFirst().orElseThrow();
        JsonNode dailyEntry = history.stream().filter(e -> e.path("roundId").asText().equals(daily.roundId()))
                .findFirst().orElseThrow();
        assertThat(hourlyEntry.path("interval").asText()).isEqualTo("HOURLY");
        assertThat(hourlyEntry.path("durationSec").asLong()).isEqualTo(60);
        assertThat(dailyEntry.path("interval").asText()).isEqualTo("DAILY");
        assertThat(dailyEntry.path("durationSec").asLong()).isEqualTo(120);

        JsonNode unseen = ok(me, "unseenResults");
        assertThat(unseen).hasSize(2);
        for (JsonNode entry : unseen) {
            assertKeys(entry, "roundId", "interval", "marketId", "asset", "durationSec", "settledAtMs", "direction",
                    "leverage", "stake", "liquidated", "directionCorrect", "payout");
            assertThat(entry.path("durationSec").asLong()).isEqualTo(entry.path("interval").asText().equals("DAILY") ? 120 : 60);
        }
    }

    @Test
    void aHistoryRowWithoutItsRoundResultHasAnUnknownLengthNotAGuess() throws Exception {
        Session me = signIn("orphan");
        db.tx(c -> db.update(c, """
                INSERT INTO player_round_history (round_id, player_id, interval, settled_at_ms, direction, leverage,
                    stake_mojos, liquidated, direction_correct, payout_mojos, market)
                VALUES ('1h-777', ?, 'HOURLY', 777, 'UP', 2, 100000, FALSE, TRUE, 100800, 'BTC')
                """, me.playerId()));

        JsonNode entry = ok(me, "myProfile").path("history").get(0);

        assertThat(entry.has("durationSec")).isTrue();
        assertThat(entry.get("durationSec").isNull()).isTrue();
    }
}
