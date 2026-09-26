package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Wave-2 MINOR fix: {@code placeBet}/{@code cancelBet} must reject strictly at/after
 * {@code bettingWindow.startMs()} (the request's own arrival time), not only when
 * {@code RoundProgress}'s cursor (advanced only by {@code RoundSchedulerService}'s ~1s tick) has
 * caught up to that instant. Calls {@link GameService} directly with a controlled {@code nowMs},
 * rather than racing the real scheduler over HTTP, so the exact boundary is deterministic.
 */
@QuarkusTest
class PlaceBetWindowBoundaryTest {

    @Inject
    GameService gameService;
    @Inject
    RoundProgress roundProgress;

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1boundary" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    @Test
    void placeBetSucceedsOneMillisecondBeforeTheWindowStarts() {
        String playerId = signUpPlayer("ok");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 2, "100.000");

        var bet = gameService.placeBet(playerId, req, bettingWindow.startMs() - 1);

        assertThat(bet.roundId()).isEqualTo(bettingWindow.roundId());
    }

    @Test
    void placeBetIsRejectedExactlyAtTheWindowStartMs() {
        String playerId = signUpPlayer("exact");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 2, "100.000");

        // Exactly at window.startMs — the round has, by definition, already started running — must
        // be rejected even though RoundProgress's own cursor (advanced only by
        // RoundSchedulerService's ~1s tick) may not have caught up to this instant yet.
        assertThatThrownBy(() -> gameService.placeBet(playerId, req, bettingWindow.startMs()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING);
    }

    @Test
    void placeBetIsRejectedWellAfterTheWindowStartMs() {
        String playerId = signUpPlayer("late");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 2, "100.000");

        assertThatThrownBy(() -> gameService.placeBet(playerId, req, bettingWindow.startMs() + 900))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING);
    }

    @Test
    void cancelBetIsRejectedExactlyAtTheWindowStartMs() {
        String playerId = signUpPlayer("cancel");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 2, "100.000");
        gameService.placeBet(playerId, req, bettingWindow.startMs() - 1);

        assertThatThrownBy(() -> gameService.cancelBet(playerId, bettingWindow.roundId(), bettingWindow.startMs()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING);
    }
}
