package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code setNickname} used to be {@code synchronized} on the service's own monitor while everything else
 * that changes player state — sign-in inserting a row, bot creation, a rebuild reloading the whole
 * directory — holds the engine write lock (status review 2026-10-01, §2.5). That excludes nothing but
 * another {@code setNickname}: a rebuild that reloaded the directory between the call's UPDATE and its
 * commit read the old nickname and left it in the cache while the database had the new one, and the
 * directory's cache was written before the commit. There is no uniqueness check to protect (nicknames are
 * not unique); what must hold is that the cache and the table agree, whatever runs next to it.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class NicknameConcurrencyTest extends LiveGameTestBase {

    @InjectSpy
    PlayerDirectory playersSpy;
    @Inject
    PlayerDirectory directory;

    @AfterEach
    void healTheSpy() {
        Mockito.reset(playersSpy);
    }

    private String storedNickname(String playerId) {
        return db.read(c -> db.queryOne(c, "SELECT nickname FROM players WHERE player_id = ?",
                rs -> rs.getString("nickname"), playerId));
    }

    @Test
    void aRebuildWaitsForANicknameChangeInFlightInsteadOfReloadingTheDirectoryUnderIt() throws Exception {
        String player = signUp("nickhold");
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the change is held after its UPDATE, before its commit
        doAnswer(inv -> {
            inv.callRealMethod();
            updated.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(playersSpy).updateNickname(any(Connection.class), eq(player), eq("Brand New"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> changing = pool.submit(() -> gameService.setNickname(player, "Brand New"));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> rebuilder = new AtomicReference<>();
            Future<?> rebuilding = pool.submit(() -> {
                rebuilder.set(Thread.currentThread());
                rebuild.rebuild();
            });

            // the rebuild is parked on the lock the nickname change holds — not running, not finished
            await().atMost(Duration.ofSeconds(10)).until(() -> rebuilder.get() != null
                    && rebuilder.get().getState() == Thread.State.WAITING);
            assertThat(rebuilding.isDone()).isFalse();
            assertThat(storedNickname(player)).as("the commit has not happened").isNotEqualTo("Brand New");

            release.countDown();
            changing.get(20, TimeUnit.SECONDS);
            rebuilding.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(storedNickname(player)).isEqualTo("Brand New");
        assertThat(gameService.summary(player).nickname()).as("the directory agrees with the table").isEqualTo("Brand New");
    }

    @Test
    void nicknamesSignInsBotsAndRebuildsRunningTogetherLeaveTheDirectoryAndTheTableInAgreement() throws Exception {
        String shared = signUp("nickshared");
        int writers = 3;
        long runForMs = 2_500L;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 3);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        List<String> created = new CopyOnWriteArrayList<>();
        Set<String> submitted = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            futures.add(pool.submit(() -> {
                int i = 0;
                while (!stop.get()) {
                    try {
                        String nickname = "Name " + writer + " " + (i++ % 50);
                        submitted.add(nickname);
                        gameService.setNickname(shared, nickname);
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }
            }));
        }
        futures.add(pool.submit(() -> {
            while (!stop.get()) {
                try {
                    String wallet = "txch1nick" + UUID.randomUUID().toString().replace("-", "");
                    created.add(gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()).player()
                            .playerId());
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        }));
        futures.add(pool.submit(() -> {
            while (!stop.get()) {
                try {
                    created.add(gameService.createBotPlayer("Bot " + UUID.randomUUID().toString().substring(0, 8),
                            "RANDOM", System.currentTimeMillis()).playerId());
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        }));
        futures.add(pool.submit(() -> {
            while (!stop.get()) {
                try {
                    rebuild.rebuild();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        }));

        Thread.sleep(runForMs);
        stop.set(true);
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(unexpected).as("nothing but the domain's own exceptions: %s", unexpected)
                .allSatisfy(t -> assertThat(t).isInstanceOf(ApiException.class));
        assertThat(created).isNotEmpty();
        // the directory and the table agree about the nickname that won, and it is one that was submitted
        String stored = storedNickname(shared);
        assertThat(gameService.summary(shared).nickname()).isEqualTo(stored);
        assertThat(submitted).contains(stored);
        // ... about every player that was created, bots included ...
        Map<String, String> table = db.read(c -> db.queryList(c, "SELECT player_id, nickname FROM players",
                        rs -> Map.entry(rs.getString("player_id"), String.valueOf(rs.getString("nickname")))))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (String id : created) {
            assertThat(table).containsKey(id);
        }
        Map<String, String> cache = new java.util.HashMap<>();
        for (PlayerDirectory.Row row : directory.all()) {
            cache.put(row.playerId(), String.valueOf(row.nickname()));
        }
        assertThat(cache).isEqualTo(table);
        // ... and a restart changes nothing
        rebuild.rebuild();
        assertThat(gameService.summary(shared).nickname()).isEqualTo(stored);
    }
}
