package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Wave-2 MAJOR fix: {@code GameService.home()}/{@code ratings()} (and other read paths) used to
 * read {@code LedgerBook}/{@code BetBook}/{@code CrowdAccount} state without the lock mutators
 * hold, so a read iterating one of their plain {@code ArrayList}/{@code LinkedHashMap} fields while
 * a concurrent {@code placeBet}/{@code registerOrTouchPlayer} mutated that same structure could
 * throw {@code ArrayIndexOutOfBoundsException}/{@code ConcurrentModificationException}. This test
 * hammers reads concurrently with registrations and bet placement and asserts nothing but the
 * expected domain exceptions (e.g. a bet landing exactly on a round boundary) ever escapes.
 */
@QuarkusTest
class GameServiceConcurrencyTest {

    @Inject
    GameService gameService;
    @Inject
    RoundProgress roundProgress;

    @Test
    void readsSurviveConcurrentRegistrationsAndBetPlacement() throws InterruptedException {
        int readerThreads = 8;
        int writerThreads = 4;
        int readIterations = 300;
        int writeIterations = 150;

        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + writerThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readerThreads + writerThreads);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger readsCompleted = new AtomicInteger();

        for (int i = 0; i < readerThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < readIterations; j++) {
                        gameService.home(null);
                        gameService.ratings(null);
                        readsCompleted.incrementAndGet();
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < writerThreads; i++) {
            int threadIdx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < writeIterations; j++) {
                        long now = System.currentTimeMillis();
                        String wallet = "txch1conc" + threadIdx + "-" + j + "-"
                                + UUID.randomUUID().toString().replace("-", "");
                        String playerId = gameService.registerOrTouchPlayer(wallet, now).player().playerId();
                        RoundWindow bettingWindow = roundProgress.bettingWindow("BTC", Interval.HOURLY);
                        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(bettingWindow.roundId(),
                                j % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN, 2, "100.000");
                        try {
                            gameService.placeBet(playerId, req, now);
                        } catch (ApiException expected) {
                            // Acceptable: e.g. ROUND_NOT_OPEN_FOR_BETTING if this exact call lands
                            // on/after the round boundary during the run — a normal domain outcome,
                            // not the concurrency bug under test.
                        }
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("all threads should finish within the timeout").isTrue();
        assertThat(unexpected)
                .as("no read should ever throw while racing concurrent writes: %s", unexpected)
                .isEmpty();
        assertThat(readsCompleted.get()).isEqualTo(readerThreads * readIterations);
    }
}
