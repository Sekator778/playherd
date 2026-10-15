package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * {@code cancelBet} for a player who has no bet on that round persists nothing (review of the core, finding 9). It
 * used to append a {@code BetCancelled} event on every call — under the engine write lock, about 1 300 calls a second
 * measured — and every rebuild replays them all (about 5.6 µs each), so an endless cheap call made the log, and every
 * later restart, longer. A bet that exists is cancelled and refunded as before, once.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CancelBetWithoutABetTest extends LiveGameTestBase {

    private long cancelEvents() {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetCancelled'",
                rs -> rs.getLong("n")));
    }

    @Test
    void aThousandCancelsByAPlayerWithoutABetAppendNothing() {
        Market market = list("T_CB_NONE", MarketKind.PERP, Category.CRYPTO, "CBN", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String idle = signUp("cbidle");
        String other = signUp("cbother");
        gameService.placeBet(other, bet(betting, Enums.Direction.UP, 3, "100.000"), betting.startMs() - 1);
        long events = cancelEvents();

        for (int i = 0; i < 1_000; i++) {
            gameService.cancelBet(idle, betting.roundId(), betting.startMs() - 1);
        }

        assertThat(cancelEvents()).as("nothing was written").isEqualTo(events);
        assertThat(gameService.account(other).inPlay()).as("and the other player's bet on the round is untouched")
                .isEqualTo("100.000");
        assertThat(gameService.account(idle).balance()).isEqualTo("10000.000");
    }

    @Test
    void aBetThatExistsIsCancelledAndRefundedOnceAndASecondCancelIsAQuietNoOp() {
        Market market = list("T_CB_BET", MarketKind.PERP, Category.CRYPTO, "CBB", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String player = signUp("cbbet");
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 4, "250.000"), betting.startMs() - 1);
        assertThat(gameService.account(player).balance()).isEqualTo("9749.800"); // 250 staked + herd fee 250 * 4 bp = 0.1 + network fee 0.1
        long events = cancelEvents();

        gameService.cancelBet(player, betting.roundId(), betting.startMs() - 1);
        gameService.cancelBet(player, betting.roundId(), betting.startMs() - 1);

        assertThat(cancelEvents()).as("one event, for the bet that was there").isEqualTo(events + 1);
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        rebuild.rebuild();
        assertThat(gameService.account(player).balance()).as("a replay gives the same").isEqualTo("10000.000");
    }

    @Test
    void aCancelOnARoundThatIsNotOpenForBettingIsStillRefusedWhetherOrNotThereIsABet() {
        Market market = list("T_CB_CLOSED", MarketKind.PERP, Category.CRYPTO, "CBC", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String player = signUp("cbclosed");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> gameService.cancelBet(player, betting.roundId(), betting.startMs()))
                .isInstanceOfSatisfying(crowdvsmarket.server.dto.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING));
    }
}
