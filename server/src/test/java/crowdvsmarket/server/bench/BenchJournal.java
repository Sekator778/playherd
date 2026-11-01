package crowdvsmarket.server.bench;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;

/**
 * Seeding and journal fabricator of the load bench (docs/research/load_bench_design_2026-10-17.md §4.1-4.3). One JVM call = one
 * transaction on the bench database:
 *
 * <pre>
 * seed      players, sessions, the economy cutover and one PlayerRegistered per player (S-C, and the base of every journal)
 * history   a settled history of bets, cancels and round settlements over all active markets (S-D, the restart cost)
 * hotround  N bets of N different players on one future round that the running server then settles live (S-B)
 * verify    replays the stored journal into an in-memory engine (the server's own EventApplier) and prints the totals
 * </pre>
 *
 * Deterministic: every random choice comes from {@code --seed}; ids of players and sessions are functions of the seed and the
 * player number. Idempotent: each call leaves a marker row {@code bench.<mode>...} in {@code server_meta}; the same call again
 * changes nothing, and a different call with the same marker key is refused (seed: unless {@code --reset}).
 *
 * <p>Writes through the server's own record classes and the engine (see {@link BenchWorld}); the run inside the server image:
 * {@code java -cp "/deployments/lib/main/*:/deployments/app/*:bench.jar" crowdvsmarket.server.bench.BenchJournal <mode> ...}.
 */
public final class BenchJournal {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    // ---- arguments ----

    public static final class Args {
        private final Map<String, String> values = new HashMap<>();

        public static Args of(String... kv) {
            Args a = new Args();
            for (int i = 0; i < kv.length; i += 2) {
                a.values.put(kv[i], kv[i + 1]);
            }
            return a;
        }

        static Args parse(String[] argv, int from) {
            Args a = new Args();
            for (int i = from; i < argv.length; i++) {
                if (!argv[i].startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument " + argv[i]);
                }
                String key = argv[i].substring(2);
                boolean flag = i + 1 >= argv.length || argv[i + 1].startsWith("--");
                a.values.put(key, flag ? "true" : argv[++i]);
            }
            return a;
        }

        String str(String key, String def) {
            return values.getOrDefault(key, def);
        }

        long num(String key, long def) {
            String v = values.get(key);
            return v == null ? def : Long.parseLong(v.replace("_", ""));
        }

        boolean flag(String key) {
            return Boolean.parseBoolean(values.getOrDefault(key, "false"));
        }

        String canonical(String... keys) {
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                sb.append(k).append('=').append(values.get(k)).append(';');
            }
            return sb.toString();
        }
    }

    // ---- entry point ----

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("usage: BenchJournal <seed|history|hotround|verify|cookies> [--key value ...]");
            System.exit(2);
        }
        Args args = Args.parse(argv, 1);
        if (argv[0].equals("cookies")) { // no database: the cookie file of a seed, regenerated
            writeCookies(Path.of(args.str("out", "cookies.tsv")), args.num("seed", 1), (int) args.num("players", 1000));
            return;
        }
        String url = args.str("url", System.getenv().getOrDefault("BENCH_DB_URL", "jdbc:postgresql://db:5432/herd"));
        String user = args.str("user", System.getenv().getOrDefault("BENCH_DB_USER", "herd"));
        String password = args.str("password", System.getenv("BENCH_DB_PASSWORD"));
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(false);
            Map<String, String> out = run(c, argv[0], args);
            c.commit();
            out.forEach((k, v) -> System.out.println(k + "=" + v));
        }
    }

    public static Map<String, String> run(Connection c, String mode, Args args) throws Exception {
        long t0 = System.nanoTime();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("mode", mode);
        switch (mode) {
            case "seed" -> seed(c, args, out);
            case "history" -> history(c, args, out);
            case "hotround" -> hotRound(c, args, out);
            case "verify" -> verify(c, out);
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        out.put("total_ms", Long.toString((System.nanoTime() - t0) / 1_000_000));
        return out;
    }

    // ---- deterministic ids ----

    static long mix(long seed, long i) { // splitmix64 of (seed, i): a bijection of i for a fixed seed
        long z = seed * GOLDEN + i * GOLDEN + GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static String playerId(long seed, long i) {
        return UUID.nameUUIDFromBytes(("herd-bench-player-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String wallet(long seed, long i) {
        return "txch1bench" + String.format("%016x", mix(seed, i));
    }

    /** The session id (cookie value) of player {@code i}: 32 bytes from the seed, base64url without padding, like SessionService. */
    public static String sessionId(long seed, long i) {
        byte[] b = new byte[32];
        long r = mix(seed ^ 0x5E55105EL, i);
        for (int k = 0; k < 4; k++) {
            r = mix(r, k);
            for (int j = 0; j < 8; j++) {
                b[k * 8 + j] = (byte) (r >>> (8 * j));
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static void writeCookies(Path out, long seed, int players) throws IOException {
        try (var w = Files.newBufferedWriter(out)) {
            for (int i = 0; i < players; i++) {
                w.write(sessionId(seed, i) + "\t" + playerId(seed, i) + "\n");
            }
        }
    }

    // ---- COPY ----

    static final class Copy implements AutoCloseable {
        private final OutputStream out;
        long rows;

        Copy(Connection c, String sql) throws SQLException {
            out = new BufferedOutputStream(new PGCopyOutputStream(c.unwrap(PGConnection.class), sql), 1 << 20);
        }

        void row(Object... cols) throws IOException {
            StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                Object v = cols[i];
                if (v == null) {
                    sb.append("\\N");
                } else {
                    String s = v.toString();
                    for (int k = 0; k < s.length(); k++) {
                        char ch = s.charAt(k);
                        switch (ch) {
                            case '\\' -> sb.append("\\\\");
                            case '\t' -> sb.append("\\t");
                            case '\n' -> sb.append("\\n");
                            case '\r' -> sb.append("\\r");
                            default -> sb.append(ch);
                        }
                    }
                }
            }
            sb.append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            rows++;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static final String EVENTS_COPY = "COPY game_events (event_type, payload, at_ms, schema_version) FROM STDIN";

    private static Copy eventsCopy(Connection c) throws SQLException {
        // D-107: the projector writes game_events with the journal's ids and never takes the sequence: the ids this copy takes by default
        // continue after the largest one in the table
        try (Statement st = c.createStatement()) {
            st.execute("SELECT setval(pg_get_serial_sequence('game_events', 'id'), (SELECT COALESCE(MAX(id), 0) + 1 FROM game_events), false)");
        }
        return new Copy(c, EVENTS_COPY);
    }

    // ---- shared state helpers ----

    private static String meta(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM server_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long origin(Connection c, Args args) throws SQLException {
        String o = args.values.containsKey("origin-ms") ? args.str("origin-ms", null) : meta(c, "origin_ms");
        if (o == null) {
            throw new IllegalStateException("server_meta.origin_ms is not set: start the server once on this database first "
                    + "(it writes the round calendar origin and lists the markets), or pass --origin-ms");
        }
        return Long.parseLong(o);
    }

    private static long hourlyMs(Args a) {
        return a.num("hourly-sec", 180) * 1000;
    }

    private static long dailyMs(Args a) {
        return a.num("daily-sec", 900) * 1000;
    }

    private static BenchWorld world(Connection c, boolean players, Map<String, String> out) throws Exception {
        BenchWorld w = new BenchWorld();
        w.loadMarkets(c);
        if (w.markets.isEmpty()) {
            throw new IllegalStateException("the markets table is empty: start the server once on this database first");
        }
        if (players) {
            w.loadPlayers(c);
        }
        long t = System.nanoTime();
        long n = w.replay(c);
        out.put("replayed_events", Long.toString(n));
        out.put("replay_ms", Long.toString((System.nanoTime() - t) / 1_000_000));
        return w;
    }

    // ---- seed ----

    private static void seed(Connection c, Args args, Map<String, String> out) throws Exception {
        long n = args.num("players", 1000);
        long seed = args.num("seed", 1);
        long openingPlay = args.num("opening-play", 1_000_000_000_000L);
        long firstIndex = args.num("first-index", 1 << 20);
        long ttlDays = args.num("ttl-days", 30);
        long now = args.num("now-ms", System.currentTimeMillis());
        String cookiesOut = args.str("cookies-out", null);
        String marker = "players=" + n + ";seed=" + seed + ";opening=" + openingPlay + ";first-index=" + firstIndex;
        String have = meta(c, "bench.seed");
        if (have != null && have.equals(marker) && !args.flag("reset")) {
            out.put("result", "already seeded (" + marker + ")");
            if (cookiesOut != null) {
                writeCookies(Path.of(cookiesOut), seed, (int) n);
            }
            return;
        }
        long existingPlayers = count(c, "players");
        if (args.flag("reset")) {
            reset(c);
            out.put("reset", "true");
        } else if (have != null || existingPlayers > 0) {
            throw new IllegalStateException("the database already holds a different seed or players (" + have + ", players="
                    + existingPlayers + "): pass --reset to wipe the game state of this bench database first");
        }
        BenchWorld w = world(c, false, out);
        long regBase = now - 3_600_000L;
        long t = System.nanoTime();
        long[] written = new long[1];
        try (Copy ev = eventsCopy(c)) {
            BenchWorld.Sink sink = (type, json, at) -> {
                ev.row(type, json, at, 1);
                written[0]++;
            };
            if (!w.hub.ledger().economyActive()) {
                w.emit(BenchWorld.cutover(PlayAmount.ofWholePlay(openingPlay).mojos(), regBase - 1000), regBase - 1000, sink);
            }
            for (long i = 0; i < n; i++) {
                long at = regBase + i * 3_000_000L / Math.max(1, n);
                w.emit(BenchWorld.registered(playerId(seed, i), at), at, sink);
            }
        }
        out.put("events_written", Long.toString(written[0]));
        out.put("events_ms", Long.toString((System.nanoTime() - t) / 1_000_000));

        t = System.nanoTime();
        try (Copy pl = new Copy(c, "COPY players (player_id, wallet_address, nickname, is_bot, bot_strategy, created_at_ms, "
                + "player_index, retired_at_ms, replaced) FROM STDIN")) {
            for (long i = 0; i < n; i++) {
                pl.row(playerId(seed, i), wallet(seed, i), null, "f", null, regBase + i * 3_000_000L / Math.max(1, n),
                        firstIndex + i, null, "f");
            }
        }
        try (Copy se = new Copy(c, "COPY sessions (session_id, player_id, created_at_ms, expires_at_ms) FROM STDIN")) {
            for (long i = 0; i < n; i++) {
                se.row(sessionId(seed, i), playerId(seed, i), now, now + ttlDays * 86_400_000L);
            }
        }
        setMeta(c, "next_player_index", Long.toString(firstIndex + n));
        setMeta(c, "bench.seed", marker);
        out.put("players_sessions_ms", Long.toString((System.nanoTime() - t) / 1_000_000));
        out.put("players", Long.toString(n));
        totals(w, out);
        if (cookiesOut != null) {
            writeCookies(Path.of(cookiesOut), seed, (int) n);
        }
    }

    /** Wipes the game state of the bench database (journal, players, sessions, read models); markets and the calendar stay. */
    private static void reset(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        for (String t : List.of("game_events", "players", "round_results", "player_round_history", "withdrawal_requests",
                "deposit_requests", "auth_challenges", "sessions", "oracle_records", "player_deposit_addresses",
                "deposit_confirmations", "price_snapshots", "round_venues", "feedback", "registration_ips", "ledger_entries", "engine_snapshots")) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT to_regclass('" + t + "') IS NOT NULL")) {
                rs.next();
                if (rs.getBoolean(1)) {
                    tables.add(t);
                }
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
            st.execute("DELETE FROM server_meta WHERE key LIKE 'bench.%'");
            st.execute("UPDATE server_meta SET value = '0' WHERE key = 'next_player_index'");
        }
    }

    // ---- history ----

    private static final class Series {
        final Market market;
        final Interval interval;
        final long stepMs;
        BigDecimal price;
        RoundWindow open;       // the window that has bets placed and is not settled yet
        List<String> bettors = new ArrayList<>();
        Set<String> used = new HashSet<>();

        Series(Market market, Interval interval, long stepMs, BigDecimal price) {
            this.market = market;
            this.interval = interval;
            this.stepMs = stepMs;
            this.price = price;
        }
    }

    private static void history(Connection c, Args args, Map<String, String> out) throws Exception {
        long targetEvents = args.num("events", 100_000);
        int betsPerRound = (int) args.num("bets-per-round", 30);
        long seed = args.num("seed", 1);
        double cancelRate = args.num("cancel-permille", 30) / 1000.0;
        double replaceRate = args.num("replace-permille", 50) / 1000.0;
        long now = args.num("now-ms", System.currentTimeMillis());
        long origin = origin(c, args);
        long hs = hourlyMs(args);
        long ds = dailyMs(args);
        String key = "bench.history." + seed;
        String marker = args.canonical("events", "bets-per-round", "cancel-permille", "replace-permille", "hourly-sec", "daily-sec");
        String have = meta(c, key);
        if (have != null) {
            if (have.equals(marker)) {
                out.put("result", "already fabricated (" + key + ": " + marker + ")");
                return;
            }
            throw new IllegalStateException(key + " exists with other parameters (" + have + "); use another --seed");
        }
        BenchWorld w = world(c, true, out);
        String[] players = w.playerIdsByIndex().toArray(String[]::new);
        if (players.length == 0) {
            throw new IllegalStateException("no players: run seed first");
        }
        SplittableRandom rng = new SplittableRandom(seed ^ 0x1157_0123L);
        List<Series> series = new ArrayList<>();
        int mi = 0;
        for (Market m : w.markets.values()) {
            if (!m.isActive()) {
                continue;
            }
            BigDecimal p0 = BigDecimal.valueOf(100 + 37L * mi).setScale(2);
            series.add(new Series(m, Interval.HOURLY, hs, p0));
            series.add(new Series(m, Interval.DAILY, ds, p0));
            mi++;
        }
        long tick = gcd(hs, ds);
        long maxStep = Math.max(hs, ds);
        double perRound = betsPerRound * (1 + cancelRate + replaceRate) + 1;
        double perTick = 0;
        for (Series s : series) {
            perTick += perRound * tick / s.stepMs;
        }
        long slots = (long) Math.ceil(targetEvents / perTick) + 2;
        long endT = origin + Math.floorDiv(now - 600_000 - origin, tick) * tick;
        long startT = endT - maxStep - slots * tick;
        startT = origin + Math.floorDiv(startT - origin, tick) * tick;

        long t0 = System.nanoTime();
        long[] written = new long[1];
        long[] bets = new long[1];
        long[] rounds = new long[1];
        try (Copy ev = eventsCopy(c)) {
            BenchWorld.Sink sink = (type, json, at) -> {
                ev.row(type, json, at, 1);
                written[0]++;
            };
            boolean placing = true;
            for (long t = startT; ; t += tick) {
                if (placing && (written[0] >= targetEvents || t > endT - maxStep)) {
                    placing = false;
                }
                boolean anyOpen = false;
                for (Series s : series) {
                    if ((t - origin) % s.stepMs != 0) {
                        anyOpen |= s.open != null;
                        continue;
                    }
                    RoundWindow next = placing ? BenchWorld.window(s.market.id(), s.interval, t, s.stepMs) : null;
                    RoundWindow ending = s.open;
                    if (next != null) {
                        s.open = next;
                        s.bettors = new ArrayList<>();
                        s.used = new HashSet<>();
                        placeBets(w, s, next, players, rng, betsPerRound, cancelRate, replaceRate, sink);
                        bets[0] += s.bettors.size();
                    } else {
                        s.open = null;
                    }
                    if (ending != null) {
                        resolve(w, s, ending, t, rng, sink);
                        rounds[0]++;
                    }
                    anyOpen |= s.open != null;
                }
                if (!placing && !anyOpen) {
                    break;
                }
                if (t > endT + 10 * maxStep) {
                    throw new IllegalStateException("history did not close its windows by " + (endT + 10 * maxStep));
                }
            }
        }
        if (written[0] < targetEvents) {
            out.put("warning", "target of " + targetEvents + " events not reached (no room before now, or no players with balance)");
        }
        setMeta(c, key, marker);
        out.put("events_written", Long.toString(written[0]));
        out.put("rounds_settled", Long.toString(rounds[0]));
        out.put("first_tick_ms", Long.toString(startT));
        out.put("last_tick_ms", Long.toString(endT));
        out.put("generate_and_copy_ms", Long.toString((System.nanoTime() - t0) / 1_000_000));
        totals(w, out);
    }

    /** The state a server must reach by replaying the journal: compared by the tests with the rebuilt server. */
    private static void totals(BenchWorld w, Map<String, String> out) {
        var ledger = w.hub.ledger();
        out.put("ledger_players", Integer.toString(ledger.playerIds().size()));
        out.put("players_total_mojos", Long.toString(ledger.playersTotal().mojos()));
        out.put("open_stakes_mojos", Long.toString(ledger.openStakes().mojos()));
        out.put("events_in_world", Long.toString(w.events));
    }

    private static void placeBets(BenchWorld w, Series s, RoundWindow window, String[] players, SplittableRandom rng,
                                  int betsPerRound, double cancelRate, double replaceRate, BenchWorld.Sink sink) throws IOException {
        int placed = 0;
        int attempts = 0;
        long span = s.stepMs;
        while (placed < betsPerRound && attempts < betsPerRound * 8L) {
            attempts++;
            String p = players[rng.nextInt(players.length)];
            if (s.used.contains(p)) {
                continue;
            }
            long stake = PlayAmount.ofWholePlay(100 + rng.nextInt(101)).mojos();
            GameEvent.BetPlaced e = BenchWorld.bet(s.market, window, p,
                    rng.nextBoolean() ? Direction.UP : Direction.DOWN, 2, stake, window.startMs() - span + (placed + 1) * span / (betsPerRound + 2L));
            long debit = stake + e.feeHerdMojos() + e.feeNetMojos();
            if (w.hub.ledger().balance(p).mojos() < debit * 2) {
                continue;
            }
            w.emit(e, e.atMs(), sink);
            s.used.add(p);
            s.bettors.add(p);
            placed++;
            if (rng.nextDouble() < replaceRate) { // the player changes the bet: a second BetPlaced replaces the first
                long stake2 = PlayAmount.ofWholePlay(100 + rng.nextInt(101)).mojos();
                GameEvent.BetPlaced e2 = BenchWorld.bet(s.market, window, p, rng.nextBoolean() ? Direction.UP : Direction.DOWN, 2,
                        stake2, e.atMs() + 1);
                w.emit(e2, e2.atMs(), sink);
            }
        }
        int cancels = 0;
        for (int i = s.bettors.size() - 1; i >= 0 && cancels < Math.ceil(placed * cancelRate); i--) {
            if (rng.nextDouble() < Math.min(1.0, cancelRate * 2)) {
                String p = s.bettors.remove(i);
                w.emit(BenchWorld.cancel(s.market, window, p, window.startMs() - 1), window.startMs() - 1, sink);
                cancels++;
            }
        }
    }

    private static void resolve(BenchWorld w, Series s, RoundWindow window, long atMs, SplittableRandom rng,
                                BenchWorld.Sink sink) throws IOException {
        boolean hasBets = !w.hub.engine(s.market.id(), s.interval).betBook().betsFor(window.roundId()).isEmpty();
        BigDecimal open = s.price;
        List<Candle> path = new ArrayList<>();
        BigDecimal last = open;
        BigDecimal high = open;
        BigDecimal low = open;
        long candleMs = 60_000L;
        int candles = (int) Math.max(1, s.stepMs / candleMs);
        for (int k = 0; k < candles && hasBets; k++) {
            BigDecimal o = last;
            BigDecimal cl = o.multiply(BigDecimal.valueOf(1 + rng.nextDouble(-0.002, 0.002))).setScale(2, RoundingMode.HALF_UP)
                    .max(new BigDecimal("1.00"));
            BigDecimal hi = o.max(cl).multiply(BigDecimal.valueOf(1 + rng.nextDouble(0, 0.0005))).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lo = o.min(cl).multiply(BigDecimal.valueOf(1 - rng.nextDouble(0, 0.0005))).setScale(2, RoundingMode.HALF_UP)
                    .max(new BigDecimal("1.00"));
            path.add(new Candle(window.startMs() + k * candleMs, window.startMs() + (k + 1) * candleMs - 1, o, hi, lo, cl,
                    BigDecimal.ZERO, 0));
            last = cl;
            high = high.max(hi);
            low = low.min(lo);
        }
        if (!hasBets) {
            path.add(new Candle(window.startMs(), window.endMs() - 1, open, open, open, open, BigDecimal.ZERO, 0));
        }
        BigDecimal close = hasBets ? last : open;
        w.emit(BenchWorld.resolved(s.market, window, open.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                close.setScale(2, RoundingMode.HALF_UP).toPlainString(), path, atMs, null), atMs, sink);
        s.price = close;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // ---- hot round ----

    private static void hotRound(Connection c, Args args, Map<String, String> out) throws Exception {
        long n = args.num("bets", 10_000);
        long seed = args.num("seed", 1);
        long now = args.num("now-ms", System.currentTimeMillis());
        long leadMs = args.num("lead-sec", 90) * 1000;
        Interval interval = Interval.valueOf(args.str("interval", "HOURLY"));
        long origin = origin(c, args);
        long step = interval == Interval.HOURLY ? hourlyMs(args) : dailyMs(args);
        if (leadMs >= step) {
            throw new IllegalArgumentException("--lead-sec must be shorter than the round (" + step / 1000 + " s): the round must be the next one to bet on");
        }
        BenchWorld w = world(c, true, out);
        String marketId = args.str("market", w.markets.values().stream().filter(Market::isActive).findFirst().orElseThrow().id());
        Market market = w.markets.get(marketId);
        if (market == null || !market.isActive()) {
            throw new IllegalArgumentException("no active market " + marketId);
        }
        String key = "bench.hotround." + marketId + "." + interval + "." + n + "." + seed;
        String have = meta(c, key);
        if (have != null) {
            out.put("result", "already fabricated (" + key + ": " + have + ")");
            return;
        }
        long start = origin + Math.floorDiv(now + leadMs - origin + step - 1, step) * step;
        RoundWindow window = BenchWorld.window(marketId, interval, start, step);
        String[] players = w.playerIdsByIndex().toArray(String[]::new);
        if (players.length < n) {
            throw new IllegalStateException("the round needs " + n + " different players, the database has " + players.length);
        }
        SplittableRandom rng = new SplittableRandom(seed ^ 0x40771L);
        long t0 = System.nanoTime();
        long placed = 0;
        try (Copy ev = eventsCopy(c)) {
            BenchWorld.Sink sink = (type, json, at) -> ev.row(type, json, at, 1);
            for (int i = 0; i < players.length && placed < n; i++) {
                long stake = PlayAmount.ofWholePlay(100 + rng.nextInt(101)).mojos();
                long at = now - 60_000 + placed * 59_000 / Math.max(1, n);
                GameEvent.BetPlaced e = BenchWorld.bet(market, window, players[i],
                        rng.nextBoolean() ? Direction.UP : Direction.DOWN, 2, stake, at);
                if (w.hub.ledger().balance(players[i]).mojos() < stake + e.feeHerdMojos() + e.feeNetMojos()) {
                    continue;
                }
                w.emit(e, at, sink);
                placed++;
            }
        }
        if (placed < n) {
            throw new IllegalStateException("only " + placed + " of " + n + " players could afford a bet");
        }
        String info = "round=" + window.roundId() + ";start=" + start + ";end=" + window.endMs() + ";bets=" + placed;
        setMeta(c, key, info);
        out.put("round_id", window.roundId());
        out.put("window_start_ms", Long.toString(start));
        out.put("window_end_ms", Long.toString(window.endMs()));
        out.put("bets", Long.toString(placed));
        out.put("generate_and_copy_ms", Long.toString((System.nanoTime() - t0) / 1_000_000));
        totals(w, out);
    }

    // ---- verify ----

    private static void verify(Connection c, Map<String, String> out) throws Exception {
        BenchWorld w = world(c, true, out);
        var ledger = w.hub.ledger();
        out.put("players_table", Integer.toString(w.directory.all().size()));
        totals(w, out);
        out.put("treasury_mojos", ledger.economyActive() ? Long.toString(ledger.treasury().mojos()) : "n/a");
        out.put("game_events_rows", Long.toString(count(c, "game_events")));
    }
}
