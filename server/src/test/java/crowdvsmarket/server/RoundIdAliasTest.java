package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * {@code RoundWindow.parseRoundId} accepts one alias: the explicit spelling of a BTC round,
 * {@code "BTC-1h-<start>"} for {@code "1h-<start>"}, so an id built generically as market + code +
 * start parses whatever the market is. {@code placeBet} and {@code cancelBet} made the id canonical;
 * {@code round} and {@code betFeed} used the alias raw as the key of the read model and the bet feed
 * (review of the multi-market core, finding 4): a client using the alias placed its bet, then saw an
 * empty feed and, once the round was settled, "unknown round". All four now go through the same
 * canonical id.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class RoundIdAliasTest extends LiveGameTestBase {

    @Test
    void theExplicitSpellingOfABtcRoundIdIsTheSameRoundInEveryCall() {
        Market btc = registry.require("BTC");
        prices.setPrice(btc, "50000.00");
        RoundWindow running = roundProgress.next("BTC", Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow("BTC", Interval.HOURLY);
        String canonical = betting.roundId();
        String alias = "BTC-" + canonical;
        assertThat(canonical).startsWith("1h-");
        String p = signUp("alias");

        Rounds.BetDto placed = gameService.placeBet(p,
                new Betting.PlaceBetRequest(alias, Enums.Direction.UP, 5, "100.000"), betting.startMs() - 1);

        assertThat(placed.roundId()).isEqualTo(canonical);
        // the feed: under either spelling, the same entry, named by the canonical id
        Rounds.BetFeedDto viaAlias = gameService.betFeed(alias, null);
        assertThat(viaAlias.roundId()).isEqualTo(canonical);
        assertThat(viaAlias.entries()).hasSize(1);
        assertThat(gameService.betFeed(canonical, null).entries()).isEqualTo(viaAlias.entries());
        assertThat(gameService.betFeed(alias, viaAlias.entries().get(0).seq()).entries()).isEmpty(); // paging works
        // the round while it is open for betting
        assertThat(gameService.round(alias, p)).isInstanceOfSatisfying(Rounds.RoundBetting.class, r -> {
            assertThat(r.roundId()).isEqualTo(canonical);
            assertThat(r.participantCount()).isEqualTo(1);
            assertThat(r.myBet()).isNotNull();
        });
        // ... and once it is settled
        scheduler.tick(running.endMs());
        prices.script(btc, betting, "50000.00", "51000.00", "50000.00", "51000.00");
        scheduler.tick(betting.endMs());
        assertThat(gameService.round(alias, p)).isInstanceOfSatisfying(Rounds.RoundSettled.class, r -> {
            assertThat(r.roundId()).isEqualTo(canonical);
            assertThat(r.positions()).hasSize(1);
        });
        assertThat(gameService.round(canonical, p)).isEqualTo(gameService.round(alias, p));
        // the alias can be cancelled by its bettor like the canonical id (cancelBet): still the same round
        RoundWindow next = roundProgress.bettingWindow("BTC", Interval.HOURLY);
        gameService.placeBet(p, new Betting.PlaceBetRequest("BTC-" + next.roundId(), Enums.Direction.DOWN, 3, "100.000"),
                next.startMs() - 1);
        gameService.cancelBet(p, "BTC-" + next.roundId(), next.startMs() - 1);
        assertThat(gameService.hasPendingBets(next)).isFalse();
    }
}
