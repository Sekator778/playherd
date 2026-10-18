package crowdvsmarket.server.bench;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.LiquidationParams;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketEconomy;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine side of the load bench's journal fabricator (docs/research/load_bench_design_2026-10-17.md §4.3): an in-memory
 * {@link EngineHub}, {@link BetFeed} and {@link PlayerDirectory} over the server's own {@link EventApplier} (the same
 * {@code EventApplier.over(...)} a rebuild uses), so every fabricated event has been applied by the real engine before it is
 * written, and the events themselves are the server's own {@link GameEvent} records, built with the server's own fee and
 * liquidation rules and serialised by Jackson exactly as {@code EventStore.append} does.
 *
 * <p>Test scope on purpose: nothing of the product changes. It runs inside the server image (classes of the image plus
 * the bench jar), never in the product process.
 */
public final class BenchWorld {

    /** Receives a fabricated event: the {@code game_events} columns. */
    public interface Sink {
        void accept(String eventType, String payloadJson, long atMs) throws IOException;
    }

    public final EngineHub hub = new EngineHub();
    public final BetFeed feed = new BetFeed();
    public final PlayerDirectory directory = new PlayerDirectory();
    public final EventApplier applier = EventApplier.over(hub, feed, directory);
    public final ObjectMapper mapper = mapper();
    public final Map<String, Market> markets = new LinkedHashMap<>();
    /** Events applied so far (replayed from the database plus fabricated). */
    public long events;

    /** Jackson as the server's {@code Db} uses it for {@code game_events.payload}: the records' own declared order and names. */
    public static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void install(Market market) {
        hub.install(market);
        markets.put(market.id(), market);
    }

    /** The {@code markets} rows, mapped as {@code DbMarketRegistry#toMarket} does, in {@code oracle_index} order. */
    public void loadMarkets(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM markets ORDER BY oracle_index ASC")) {
            while (rs.next()) {
                install(new Market(rs.getString("id"), MarketKind.valueOf(rs.getString("kind")), rs.getString("category"),
                        rs.getString("symbol"), rs.getString("title"), rs.getString("subtitle"), rs.getString("icon_url"),
                        rs.getString("source"), rs.getString("source_ref"), rs.getString("source_meta"),
                        rs.getInt("max_leverage"), rs.getInt("lot_exp"), rs.getInt("oracle_index"),
                        MarketStatus.valueOf(rs.getString("status")), rs.getLong("listed_at_ms"),
                        (Long) rs.getObject("closed_at_ms"), rs.getString("close_reason"), (Long) rs.getObject("ends_at_ms"),
                        new MarketEconomy(rs.getInt("cap_hourly"), rs.getInt("cap_daily"), rs.getBigDecimal("kappa"),
                                rs.getBigDecimal("herd_fee_bp"), rs.getBigDecimal("stress_move"))));
            }
        }
    }

    /** The {@code players} rows into the directory (what {@code PlayerDirectory#reload} does). */
    public void loadPlayers(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setFetchSize(20_000);
            try (ResultSet rs = st.executeQuery("SELECT player_id, wallet_address, nickname, is_bot, bot_strategy, player_index, "
                    + "created_at_ms, retired_at_ms, replaced FROM players ORDER BY player_index")) {
                while (rs.next()) {
                    directory.cache(new PlayerDirectory.Row(rs.getString("player_id"), rs.getString("wallet_address"),
                            rs.getString("nickname"), rs.getBoolean("is_bot"), rs.getString("bot_strategy"),
                            rs.getInt("player_index"), rs.getLong("created_at_ms"), (Long) rs.getObject("retired_at_ms"),
                            rs.getBoolean("replaced")));
                }
            }
        }
    }

    /** Replays the whole stored journal into this world, in id order, as {@code EngineStateBuilder} does. Needs a
     *  connection with auto-commit off (a streaming cursor). @return the number of events replayed */
    public long replay(Connection c) throws SQLException, IOException {
        long n = 0;
        try (Statement st = c.createStatement()) {
            st.setFetchSize(20_000);
            try (ResultSet rs = st.executeQuery("SELECT event_type, payload FROM game_events ORDER BY id ASC")) {
                while (rs.next()) {
                    applier.apply(decode(rs.getString(1), rs.getString(2)));
                    n++;
                }
            }
        }
        events += n;
        return n;
    }

    public GameEvent decode(String type, String payload) throws IOException {
        try {
            Class<?> k = Class.forName(GameEvent.class.getName() + "$" + type);
            if (!GameEvent.class.isAssignableFrom(k)) {
                throw new IllegalStateException("not a game event: " + type);
            }
            return (GameEvent) mapper.readValue(payload, k);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("unknown event_type " + type, e);
        }
    }

    /** Applies {@code event} to the engine (it throws when the engine refuses it: nothing invalid is written), then hands its
     *  bytes to {@code sink}. */
    public void emit(GameEvent event, long atMs, Sink sink) throws IOException {
        applier.apply(event);
        events++;
        sink.accept(event.getClass().getSimpleName(), mapper.writeValueAsString(event), atMs);
    }

    // ---- the events, built the way GameService builds them ----

    public static GameEvent.PlayerRegistered registered(String playerId, long atMs) {
        return new GameEvent.PlayerRegistered(playerId, atMs);
    }

    public static GameEvent.EconomyCutover cutover(long openingTotalMojos, long atMs) {
        return new GameEvent.EconomyCutover(openingTotalMojos, atMs);
    }

    /** {@code GameService#placeBet}'s event: the fees and the liquidation parameters come from the market's rules. */
    public static GameEvent.BetPlaced bet(Market market, RoundWindow window, String playerId, Direction direction, int leverage,
                                           long stakeMojos, long atMs) {
        var spec = market.spec();
        Fees fees = EconomyRules.fees(spec, window.interval(), leverage, new PlayAmount(stakeMojos));
        LiquidationParams liquidation = LiquidationParams.of(spec, window.interval());
        return new GameEvent.BetPlaced(playerId, window.interval(), window.startMs(), window.endMs(), direction, leverage,
                stakeMojos, atMs, market.id(), fees.herd().mojos(), fees.network().mojos(), liquidation.cap(),
                liquidation.kappa().toPlainString());
    }

    public static GameEvent.BetCancelled cancel(Market market, RoundWindow window, String playerId, long atMs) {
        return new GameEvent.BetCancelled(playerId, window.interval(), window.startMs(), window.endMs(), atMs, market.id());
    }

    public static GameEvent.RoundResolved resolved(Market market, RoundWindow window, String open, String close,
                                                    List<Candle> path, long atMs, String venue) {
        return new GameEvent.RoundResolved(window.interval(), window.startMs(), window.endMs(), open, close,
                EventApplier.fromCandles(path), atMs, market.id(), venue);
    }

    public static GameEvent.RoundCancelled cancelled(Market market, RoundWindow window, String reason, long atMs) {
        return new GameEvent.RoundCancelled(window.interval(), window.startMs(), window.endMs(), reason, atMs, market.id());
    }

    /** A window of {@code market}'s series on the grid {@code origin + k * step}. */
    public static RoundWindow window(String market, Interval interval, long startMs, long stepMs) {
        return new RoundWindow(market, interval, startMs, startMs + stepMs);
    }

    public List<String> playerIdsByIndex() {
        List<PlayerDirectory.Row> rows = new ArrayList<>(directory.all());
        rows.sort(java.util.Comparator.comparingInt(PlayerDirectory.Row::playerIndex));
        List<String> ids = new ArrayList<>(rows.size());
        for (PlayerDirectory.Row r : rows) {
            ids.add(r.playerId());
        }
        return ids;
    }
}
