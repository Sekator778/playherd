package crowdvsmarket.bots.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PopulationSimTest {

    private static List<BarDto> noisyBars(String seedName, int minutes, double vol) {
        List<BarDto> bars = new ArrayList<>();
        java.util.SplittableRandom rng = new java.util.SplittableRandom(seedName.hashCode());
        double p = 60_000;
        for (int i = 0; i < minutes; i++) {
            double c = p * (1 + rng.nextDouble(-vol, vol));
            bars.add(new BarDto(i * 60_000L, p, Math.max(p, c) * 1.0002, Math.min(p, c) * 0.9998, c));
            p = c;
        }
        return bars;
    }

    private static List<BacktestMarket> markets() {
        return List.of(BacktestMarket.of("BTC", noisyBars("BTC", 3 * 24 * 60, 0.0008)),
                BacktestMarket.of("ETH", noisyBars("ETH", 3 * 24 * 60, 0.001)));
    }

    @Test
    void everyStakeHasItsPayoutAndTheTreasuryIsTheMirrorOfTheBots() {
        PopulationSim.Result r = new PopulationSim().run(PopulationSim.Config.of(Catalog.load().bots(), markets(), 1));
        assertThat(r.days()).hasSizeBetween(3, 4); // the last bets settle a few minutes into the next day
        double tradingGain = r.days().stream().mapToDouble(PopulationSim.Day::tradingGain).sum();
        double bots = r.lives().stream().mapToDouble(PopulationSim.Life::net).sum();
        // what the treasury gains from bets is what the bots lose, to the cent per ten thousand bets
        assertThat(tradingGain).isCloseTo(-bots, org.assertj.core.data.Offset.offset(1.0));
        double change = r.days().stream().mapToDouble(PopulationSim.Day::treasuryChange).sum();
        assertThat(change).isCloseTo(r.finalChange(), org.assertj.core.data.Offset.offset(1.0));
        assertThat(r.days().stream().mapToLong(PopulationSim.Day::bets).sum()).isGreaterThan(1000);
        // fees are positive and every bet pays at least the network fee
        assertThat(r.days().stream().mapToDouble(PopulationSim.Day::fees).sum())
                .isGreaterThanOrEqualTo(0.1 * r.days().stream().mapToLong(PopulationSim.Day::bets).sum());
    }

    @Test
    void theRunIsDeterministicAndTheSaltChangesIt() {
        PopulationSim.Config a = PopulationSim.Config.of(Catalog.load().bots(), markets(), 5);
        assertThat(new PopulationSim().run(a)).isEqualTo(new PopulationSim().run(a));
        assertThat(new PopulationSim().run(PopulationSim.Config.of(Catalog.load().bots(), markets(), 6)).finalChange())
                .isNotEqualTo(new PopulationSim().run(a).finalChange());
    }

    @Test
    void botsThatRunLowRetireAndAreReplacedByStarterPacksFromTheTreasury() {
        CatalogEntry cautious = new CatalogEntry("coin-cautious", "x", "coin", Map.of(), "cautious");
        PopulationSim.Config base = PopulationSim.Config.of(List.of(cautious), markets(), 2);
        // a small starter pack (300) is spent on fees and swings within the three days
        PopulationSim.Config small = new PopulationSim.Config(base.bots(), 5, base.markets(), 2, 0.0,
                crowdvsmarket.engine.ledger.PlayAmount.ofWholePlay(300), base.retireBelow(), base.respawnDelayMs());
        PopulationSim.Result r = new PopulationSim().run(small);
        long retired = r.days().stream().mapToInt(PopulationSim.Day::retirements).sum();
        double packs = r.days().stream().mapToDouble(PopulationSim.Day::packs).sum();
        assertThat(retired).isPositive();
        // a replacement arrives an hour after each retirement, so at most one pack per retirement
        assertThat(packs).isLessThanOrEqualTo(300.0 * retired).isPositive();
        assertThat(r.lives().stream().filter(l -> l.endMs() >= 0)).hasSize((int) retired);
        // a retired bot is gone for an hour: no life starts less than an hour after the previous one ended
        assertThat(r.lives().stream().filter(l -> l.startMs() > 0)).allMatch(l -> l.startMs() >= 3_600_000L);
    }

    @Test
    void aBotThatPeeksAtTheOutcomeDrainsTheTreasury() {
        CatalogEntry cautious = new CatalogEntry("coin-cautious", "x", "coin", Map.of(), "cautious");
        PopulationSim.Config fair = PopulationSim.Config.of(List.of(cautious), markets(), 3);
        PopulationSim.Config peeking = new PopulationSim.Config(fair.bots(), 1, fair.markets(), 3, 0.5, fair.pack(),
                fair.retireBelow(), fair.respawnDelayMs());
        assertThat(new PopulationSim().run(peeking).finalChange()).isLessThan(new PopulationSim().run(fair).finalChange());
    }

    @Test
    void loopedHistoryHasNoJumpAtTheSeam() {
        List<BarDto> bars = noisyBars("X", 60, 0.001);
        List<BarDto> looped = PopulationSim.loop(bars, 3);
        assertThat(looped).hasSize(180);
        assertThat(looped.get(60).open()).isCloseTo(bars.get(59).close(), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(looped.get(60).openTimeMs() - looped.get(59).openTimeMs()).isEqualTo(60_000L);
        assertThat(PopulationSim.shifted(bars, 1).get(0).openTimeMs()).isEqualTo(-60_000L);
    }
}
