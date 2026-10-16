package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Command line of the population simulation ({@link PopulationSim}): the bots' economy against the treasury over
 * historical candles, several replicates.
 *
 * <pre>
 * java -cp bots.jar:engine.jar crowdvsmarket.bots.backtest.PopulationMain &lt;candles-dir&gt; --out prefix
 *      [--markets BTC,ETH,SOL] [--loops 4] [--reps 20] [--copies 1] [--peek 0.0] [--population catalog|coin:persona,persona]
 * </pre>
 *
 * Writes {@code prefix_days.csv} (one line per replicate and day) and {@code prefix_lives.csv} (one line per bot life) and
 * prints a summary. Replicate {@code r} shifts the round grid by {@code r mod 3} minutes and reseeds every decision.
 */
public final class PopulationMain {

    private PopulationMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: PopulationMain <candles-dir> --out prefix [--markets A,B] [--loops N] [--reps N] [--copies N] [--peek P] [--population catalog|coin:persona,...]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        String out = null;
        List<String> marketIds = List.of("BTC", "ETH", "SOL");
        int loops = 4;
        int reps = 20;
        int copies = 1;
        double peek = 0;
        String population = "catalog";
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = args[++i];
                case "--markets" -> marketIds = Arrays.asList(args[++i].split(","));
                case "--loops" -> loops = Integer.parseInt(args[++i]);
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--copies" -> copies = Integer.parseInt(args[++i]);
                case "--peek" -> peek = Double.parseDouble(args[++i]);
                case "--population" -> population = args[++i];
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out prefix is required");
        }
        Map<String, List<BarDto>> raw = new java.util.HashMap<>();
        for (String id : marketIds) {
            raw.put(id, CandleFiles.read(dir.resolve(id + "_1m.csv")).bars());
        }
        // all markets must cover the same minutes: cut to the common range (the 3.5 days of Hyperliquid limit the others)
        long from = raw.values().stream().mapToLong(b -> b.get(0).openTimeMs()).max().orElseThrow();
        long to = raw.values().stream().mapToLong(b -> b.get(b.size() - 1).openTimeMs()).min().orElseThrow();
        final List<String> marketIdsF = marketIds;
        List<CatalogEntry> entries = population(population);
        final int copiesF = copies;
        final double peekF = peek;
        final int loopsF = loops;
        List<PopulationSim.Result> results = IntStream.range(0, reps).parallel().mapToObj(r -> {
            List<BacktestMarket> markets = new ArrayList<>();
            for (String id : marketIdsF) {
                List<BarDto> cut = raw.get(id).stream().filter(b -> b.openTimeMs() >= from && b.openTimeMs() <= to).toList();
                markets.add(BacktestMarket.of(id, PopulationSim.shifted(PopulationSim.loop(cut, loopsF), r % 3)));
            }
            PopulationSim.Config base = PopulationSim.Config.of(entries, markets, r);
            return new PopulationSim().run(new PopulationSim.Config(base.bots(), copiesF, base.markets(), base.salt(), peekF,
                    base.pack(), base.retireBelow(), base.respawnDelayMs()));
        }).toList();

        StringBuilder days = new StringBuilder("rep,day,bets,stakes,fees,payouts,packs,remainders,retirements,liquidations\n");
        StringBuilder lives = new StringBuilder("rep,catalogId,startMs,endMs,bets,net\n");
        for (int r = 0; r < results.size(); r++) {
            for (PopulationSim.Day d : results.get(r).days()) {
                days.append(String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d%n", r, d.day(), d.bets(), d.stakes(),
                        d.fees(), d.payouts(), d.packs(), d.remainders(), d.retirements(), d.liquidations()));
            }
            for (PopulationSim.Life l : results.get(r).lives()) {
                lives.append(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%.3f%n", r, l.catalogId(), l.startMs(), l.endMs(), l.bets(), l.net()));
            }
        }
        Files.writeString(Path.of(out + "_days.csv"), days.toString());
        Files.writeString(Path.of(out + "_lives.csv"), lives.toString());
        double[] dd = results.stream().mapToDouble(PopulationSim.Result::maxDrawdown).toArray();
        double[] fc = results.stream().mapToDouble(PopulationSim.Result::finalChange).toArray();
        System.out.printf(Locale.ROOT, "%d replicates, %d bots x %d copies, markets %s, history %d x common range; max drawdown of the treasury: mean %.0f, max %.0f PLAY; change over the run: mean %.0f, min %.0f, max %.0f%n",
                results.size(), entries.size(), copies, marketIds, loops, Arrays.stream(dd).average().orElse(0),
                Arrays.stream(dd).max().orElse(0), Arrays.stream(fc).average().orElse(0), Arrays.stream(fc).min().orElse(0),
                Arrays.stream(fc).max().orElse(0));
    }

    /** {@code catalog} (all 21 bots) or {@code coin:persona,persona} (the coin strategy with each persona, to study personas alone). */
    static List<CatalogEntry> population(String spec) {
        if (spec.equals("catalog")) {
            return Catalog.load().bots();
        }
        if (spec.equals("legacy")) {
            // today's server bots: Maximalist, Contrarian, Trend Follower, Wildcard, Cautious; run with --copies 4
            return List.of(
                    new CatalogEntry("legacy-maximalist", "Maximalist", "coin", Map.of(), "legacy-maximalist"),
                    new CatalogEntry("legacy-contrarian", "Contrarian", "crowd-contrarian", Map.of(), "legacy-mixed"),
                    new CatalogEntry("legacy-follower", "Trend Follower", "crowd-follow", Map.of(), "legacy-mixed"),
                    new CatalogEntry("legacy-wildcard", "Wildcard", "coin", Map.of(), "legacy-mixed"),
                    new CatalogEntry("legacy-cautious", "Cautious", "coin", Map.of(), "legacy-cautious"));
        }
        if (spec.startsWith("coin:")) {
            List<CatalogEntry> out = new ArrayList<>();
            for (String p : spec.substring(5).split(",")) {
                out.add(new CatalogEntry("coin-" + p, "Coin " + p, "coin", Map.of(), p));
            }
            return out;
        }
        throw new IllegalArgumentException("unknown population " + spec);
    }
}
