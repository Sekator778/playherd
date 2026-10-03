package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The choice of the venue of a round, against an oracle written from the rule and not from the ring: for random rings of
 * the primary and the backup — live ticks, history points, carried ticks and pins at random times around the round's start,
 * fresh and stale — {@link LiveMarketPrices#openRound} decides what the rule says.
 *
 * <p>The rule: (1) the primary when the nearest <i>real</i> tick (live or history; a pin or a carried tick is a copy and
 * stands for the real tick before it) at or before the start is a live one and fresh, (2) else the backup when that is so
 * of it, (3) else — no live tick on either side — the primary when any tick of its ring is fresh at the start, (4) else the
 * backup when any is, (5) else nothing. Whatever the pins and the history of the other venue hold.
 */
class VenueChoiceFuzzTest {

    private static final long START = 1_791_030_660_000L;
    private static final long STALE = 15_000L;

    private enum Kind {
        LIVE, HISTORY, CARRIED, PIN
    }

    /** The oracle's word about one ring (its ticks by time): is the nearest real tick at or before the start a fresh live one? */
    private static boolean liveFresh(TreeMap<Long, Kind> ring) {
        Map.Entry<Long, Kind> floor = ring.floorEntry(START);
        if (floor == null) {
            return false;
        }
        for (Map.Entry<Long, Kind> e = floor; e != null; e = ring.lowerEntry(e.getKey())) {
            if (e.getValue() == Kind.LIVE || e.getValue() == Kind.HISTORY) { // the nearest real tick at or before the floor
                return e.getValue() == Kind.LIVE && START - e.getKey() <= STALE;
            }
        }
        return false; // nothing real behind a copy: a copy is no live tick
    }

    private static boolean anyFresh(TreeMap<Long, Kind> ring) {
        Map.Entry<Long, Kind> floor = ring.floorEntry(START);
        return floor != null && START - floor.getKey() <= STALE;
    }

    private static String expected(TreeMap<Long, Kind> primary, TreeMap<Long, Kind> backup) {
        if (liveFresh(primary)) {
            return "coinbase";
        }
        if (liveFresh(backup)) {
            return "kraken";
        }
        if (anyFresh(primary)) {
            return "coinbase";
        }
        return anyFresh(backup) ? "kraken" : null;
    }

    /** A random ring: up to 8 ticks, 0..40 s before the start (and the odd one after it), of any kind. */
    private static TreeMap<Long, Kind> randomRing(Random random) {
        TreeMap<Long, Kind> ring = new TreeMap<>();
        int n = random.nextInt(9);
        for (int i = 0; i < n; i++) {
            long at = START - random.nextInt(40_001) + (random.nextInt(8) == 0 ? 50_000 : 0);
            if (random.nextInt(5) == 0) {
                at = START; // exactly the boundary: where a pin of the round before sits
            }
            ring.putIfAbsent(at, Kind.values()[random.nextInt(Kind.values().length)]);
        }
        return ring;
    }

    private static void fill(TickStore store, Market instrument, String feed, TreeMap<Long, Kind> ring) {
        BigDecimal px = new BigDecimal("100.0");
        for (Map.Entry<Long, Kind> e : ring.entrySet()) {
            if (e.getValue() == Kind.LIVE) {
                store.append(feed, e.getKey(), Map.of(instrument.id(), px));
            }
        }
        for (Map.Entry<Long, Kind> e : ring.entrySet()) {
            PricePoint p = new PricePoint(e.getKey(), px);
            switch (e.getValue()) {
                case HISTORY -> store.mergeHistory(instrument.id(), List.of(p));
                case CARRIED -> store.mergeCarried(instrument.id(), List.of(p));
                default -> {
                }
            }
        }
        for (Map.Entry<Long, Kind> e : ring.entrySet()) { // pins last: a pin closes the ring behind it to every merge
            if (e.getValue() == Kind.PIN) {
                store.pinBoundary(instrument.id(), e.getKey(), Px.encode(px));
            }
        }
    }

    @Test
    void theVenueIsWhatTheRuleSaysForRandomRingsOfBothVenues() {
        Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
        Market shadow = Instruments.shadowOf(btc).orElseThrow();
        PricesConfig config = PricesConfig.defaults();
        RoundWindow window = new RoundWindow(btc.id(), Interval.HOURLY, START, START + 180_000L);
        Random random = new Random(20261003L);
        int decided = 0;
        int live = 0;
        int nonLive = 0;
        int undecided = 0;
        List<String> mismatches = new ArrayList<>();
        for (int round = 0; round < 3_000; round++) {
            AtomicLong now = new AtomicLong(START + 1_000);
            TickStore store = new TickStore(new FakeSnapshotStore(), config.retentionMs(), now::get);
            TreeMap<Long, Kind> primaryRing = randomRing(random);
            TreeMap<Long, Kind> backupRing = randomRing(random);
            fill(store, btc, "coinbase", primaryRing);
            fill(store, shadow, "kraken", backupRing);
            LiveMarketPrices prices = new LiveMarketPrices(store, new PriceSources(List.of(source("coinbase"), source("kraken"))),
                    config, new RoundVenues(new FakeRoundVenueStore(), config.retentionMs(), now::get), now::get);

            Optional<String> venue = prices.openRound(btc, window, START + 500);

            String want = expected(primaryRing, backupRing);
            if (!Optional.ofNullable(want).equals(venue)) {
                mismatches.add("primary " + primaryRing + " backup " + backupRing + ": rule says " + want + ", got " + venue);
            }
            if (want == null) {
                undecided++;
            } else if (liveFresh(primaryRing) || liveFresh(backupRing)) {
                live++;
            } else {
                nonLive++;
            }
            decided += want == null ? 0 : 1;
        }

        assertThat(mismatches).as("rings where the venue is not the one the rule gives").isEmpty();
        // the generator reaches every branch of the rule
        assertThat(live).isGreaterThan(300);
        assertThat(nonLive).isGreaterThan(300);
        assertThat(undecided).isGreaterThan(100);
        assertThat(decided).isEqualTo(live + nonLive);
    }

    private static PriceSource source(String name) {
        return new PriceSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<Feed> feeds() {
                return List.of(new Feed(name, STALE, 30_000L, m -> name.equals(m.source())));
            }

            @Override
            public Map<String, Quote> poll(Feed feed, List<Market> markets) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PricePoint> history(Market market, long fromMs, long toMs) {
                return List.of();
            }
        };
    }
}
