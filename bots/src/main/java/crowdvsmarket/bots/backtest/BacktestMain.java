package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Command line of the backtest.
 *
 * <pre>
 * java -cp bots.jar:engine.jar crowdvsmarket.bots.backtest.BacktestMain &lt;candles-dir&gt; [--stake PLAY] [--out report.md]
 * </pre>
 *
 * Reads every {@code <MARKET>_1m.csv} of the directory (see {@link CandleFiles}; written by
 * {@code bots/scripts/download_candles.py}), replays the catalog and prints markdown: the data used, the base rates of the
 * windows, and per horizon a table of variants by markets.
 */
public final class BacktestMain {

    private BacktestMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: BacktestMain <candles-dir> [--stake PLAY] [--out report.md]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        long stake = Backtester.DEFAULT_STAKE_PLAY;
        Path out = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--stake" -> stake = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        String report = report(dir, stake);
        if (out == null) {
            System.out.print(report);
        } else {
            Files.writeString(out, report);
            System.err.println("written " + out);
        }
    }

    /** The whole markdown report for the candle files in {@code dir}. */
    static String report(Path dir, long stakePlay) throws IOException {
        List<BacktestMarket> markets = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*_1m.csv")) {
            List<Path> sorted = new ArrayList<>();
            files.forEach(sorted::add);
            sorted.sort(null);
            for (Path f : sorted) {
                String id = f.getFileName().toString().replace("_1m.csv", "");
                CandleFiles.Loaded loaded = CandleFiles.read(f);
                List<BarDto> bars = loaded.bars();
                markets.add(BacktestMarket.of(id, bars));
                data.append("| ").append(id).append(" | ").append(bars.size()).append(" | ")
                        .append(bars.isEmpty() ? "-" : java.time.Instant.ofEpochMilli(bars.get(0).openTimeMs()))
                        .append(" | ")
                        .append(bars.isEmpty() ? "-" : java.time.Instant.ofEpochMilli(bars.get(bars.size() - 1).openTimeMs()))
                        .append(" | ").append(loaded.dropped()).append(" |\n");
            }
        }
        Catalog catalog = Catalog.load();
        List<CatalogEntry> entries = catalog.bots();
        Backtester bt = new Backtester(stakePlay);
        List<Backtester.Result> results = bt.run(entries, markets);

        StringBuilder sb = new StringBuilder();
        sb.append("Stake per bet: ").append(stakePlay).append(" PLAY.\n\n");
        sb.append("| market | 1m candles | first | last | dropped lines |\n|---|---|---|---|---|\n").append(data).append('\n');

        sb.append("Base rates (windows with a decided move, share closing up):\n\n");
        sb.append("| horizon | market | windows | up | down | up share |\n|---|---|---|---|---|---|\n");
        for (Backtester.Baseline b : bt.baselines(markets)) {
            sb.append("| ").append(b.horizonMinutes()).append(" min | ").append(b.marketId()).append(" | ").append(b.windows())
                    .append(" | ").append(b.up()).append(" | ").append(b.down()).append(" | ").append(pct(b.upShare())).append(" |\n");
        }
        sb.append('\n');

        for (int h : Backtester.HORIZONS) {
            sb.append("## Horizon ").append(h).append(" min\n\n");
            sb.append("Cell: hit rate of decided bets (bets) / share of windows without a signal / net P&L in PLAY (mean leverage).\n\n");
            sb.append("| variant (persona) |");
            for (BacktestMarket m : markets) {
                sb.append(' ').append(m.id()).append(" |");
            }
            sb.append(" all markets |\n|---|");
            sb.append("---|".repeat(markets.size() + 1)).append('\n');
            for (CatalogEntry e : entries) {
                if (!Backtester.replayable(e)) {
                    continue;
                }
                sb.append("| ").append(e.catalogId()).append(" |");
                List<Backtester.Result> mine = new ArrayList<>();
                for (BacktestMarket m : markets) {
                    Backtester.Result r = results.stream().filter(x -> x.catalogId().equals(e.catalogId())
                            && x.marketId().equals(m.id()) && x.horizonMinutes() == h).findFirst().orElseThrow();
                    mine.add(r);
                    sb.append(' ').append(cell(r)).append(" |");
                }
                sb.append(' ').append(cell(total(mine, h))).append(" |\n");
            }
            sb.append('\n');
        }
        sb.append("Crowd strategies (crowd-follow, crowd-contrarian) are not replayed: candles carry no previous-round majority.\n");
        return sb.toString();
    }

    private static Backtester.Result total(List<Backtester.Result> rs, int h) {
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        int w = 0;
        int ns = 0;
        int bets = 0;
        int ties = 0;
        int hits = 0;
        int liq = 0;
        long lev = 0;
        for (Backtester.Result r : rs) {
            pnl = pnl.add(r.pnl());
            fees = fees.add(r.fees());
            w += r.windows();
            ns += r.noSignal();
            bets += r.bets();
            ties += r.ties();
            hits += r.hits();
            liq += r.liquidations();
            lev += r.leverageSum();
        }
        Backtester.Result f = rs.get(0);
        return new Backtester.Result(f.catalogId(), f.strategyId(), f.persona(), "ALL", h, w, ns, bets, ties, hits, liq, pnl, fees, lev);
    }

    private static String cell(Backtester.Result r) {
        return pct(r.hitRate()) + " (" + r.bets() + ") / " + pct(r.noSignalShare()) + " / "
                + r.pnl().setScale(0, RoundingMode.HALF_UP).toPlainString()
                + (r.bets() == 0 ? "" : " (x" + Math.round(r.meanLeverage()) + ")");
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    /** Result lookup for tests and for the report's callers. */
    static Map<String, Backtester.Result> byKey(List<Backtester.Result> results) {
        Map<String, Backtester.Result> m = new TreeMap<>();
        for (Backtester.Result r : results) {
            m.put(r.catalogId() + "|" + r.marketId() + "|" + r.horizonMinutes(), r);
        }
        return m;
    }
}
