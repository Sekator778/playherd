package crowdvsmarket.server.service;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.OracleStatusCache;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.util.LogThrottle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * {@code serverInfo()} — the additive RPC that lets the UI say, honestly, which server it is talking to
 * (contract.ts {@code ServerInfoDto}). It lived in {@code GameService}; the read model of the markets
 * moved it here when it grew (docs/research/server_design.md §20).
 *
 * <p>Needs no engine state: the mode and the configuration, the market registry (memory), the oracle
 * status cache (a background refresh, never a chainsvc call on the request thread — {@link
 * OracleStatusCache}) and one indexed database read for {@code oracle.firstSeq}. So it is answered while the
 * engine is recovering from a failed settlement too ({@code EngineRebuildService}) — and says so: {@code
 * engineReady}. It is answered while the database does not answer, too: the two values it reads from there
 * ({@code firstSeq}, the pending count when chainsvc has no status) are then the last ones it read, or
 * {@code null}/0 when it never read one — a status page that fails exactly when the operator needs it is no use
 * (review of the core, finding 2).
 */
@ApplicationScoped
public class ServerInfoService {

    private static final Logger LOG = Logger.getLogger(ServerInfoService.class);
    private static final Pattern ASSET_ID = Pattern.compile("(?:0x)?([0-9a-fA-F]{64})");

    @Inject
    GameConfig config;
    @Inject
    PricesConfig prices;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    GameService gameService;
    @Inject
    OracleStatusCache oracleStatusCache;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    Db db;
    @Inject
    EngineRebuildService rebuildService;

    /** Set once the configured PLAY asset id was found malformed, so that it is said once, not per poll. */
    private volatile boolean badAssetIdLogged;
    /** What the database last said, for the moment it does not answer. */
    private volatile Long lastFirstOracleSeq;
    private volatile int lastPendingRecords;
    private final LogThrottle databaseLog = new LogThrottle(60_000L);

    public ServerInfo.ServerInfoDto serverInfo() {
        // Bug fix: never call the chainsvc client synchronously here — read the background-
        // refreshed cache instead (OracleStatusCache's own javadoc has the full story).
        ChainSvcClient.OracleStatus status = oracleStatusCache.get();
        int pendingRecords = status != null ? status.pendingRecords() : pendingRecordsFromTheDatabase();
        ServerInfo.OracleInfoDto oracle = new ServerInfo.OracleInfoDto(config.oracleNetwork(), config.oracleLauncherId(),
                status == null ? null : status.lastConfirmedSeq(), status == null ? null : status.lastConfirmedHeight(),
                pendingRecords, status == null ? null : status.operatorBalanceMojos(), firstOracleSeq());

        ServerInfo.ReplayInfoDto replay = null;
        if (config.mode() == GameMode.REPLAY) {
            List<Candle> candles = CandleLoaderCache.get();
            replay = new ServerInfo.ReplayInfoDto(Instant.ofEpochMilli(candles.get(0).openTimeMs()).toString(),
                    Instant.ofEpochMilli(candles.get(candles.size() - 1).closeTimeMs()).toString(), config.replaySpeedup());
        }
        ServerInfo.RoundDurationsDto durationsSec = new ServerInfo.RoundDurationsDto(
                config.roundDurations().forInterval(Interval.HOURLY) / 1000,
                config.roundDurations().forInterval(Interval.DAILY) / 1000);
        return new ServerInfo.ServerInfoDto(config.mode().name().toLowerCase(Locale.ROOT), priceSource(),
                activeMarkets(), replay, durationsSec, config.botsCount(), oracle, System.currentTimeMillis(),
                playAssetId(), config.testWalletEnabled(), rebuildService.isReady());
    }

    /** T-12: {@code "replay"} in {@code mode=replay} (accelerated history), otherwise {@code herd.prices.source}. */
    String priceSource() {
        return config.mode() == GameMode.REPLAY ? "replay" : prices.source().name().toLowerCase(Locale.ROOT);
    }

    /** Markets that are played here: active, and — in {@code mode=replay}, which plays BTC only — BTC
     *  ({@link GameService#runs}). */
    int activeMarkets() {
        return (int) marketRegistry.active().stream().filter(gameService::runs).count();
    }

    /** The lowest {@code seq} in {@code oracle_records}, {@code null} while the table is empty (an index read of
     *  the primary key); the last value read when the database does not answer. */
    Long firstOracleSeq() {
        try {
            Long first = db.read(c -> db.queryOne(c, "SELECT MIN(seq) AS first_seq FROM oracle_records",
                    rs -> (Long) rs.getObject("first_seq")));
            lastFirstOracleSeq = first;
            return first;
        } catch (RuntimeException e) {
            databaseDidNotAnswer(e);
            return lastFirstOracleSeq;
        }
    }

    /** The count of records not yet published, for the moment chainsvc has no status to give one; the last value
     *  read when the database does not answer. */
    private int pendingRecordsFromTheDatabase() {
        try {
            int pending = oracleRecords.pendingCount();
            lastPendingRecords = pending;
            return pending;
        } catch (RuntimeException e) {
            databaseDidNotAnswer(e);
            return lastPendingRecords;
        }
    }

    private void databaseDidNotAnswer(RuntimeException e) {
        if (databaseLog.allow("database", System.currentTimeMillis())) {
            LOG.warnf(e, "serverInfo could not read the database — answering with the last values it read");
        }
    }

    /** {@code herd.play.asset-id} as {@code "0x"} + 64 lower-case hex, or {@code null} (with one warning) when
     *  it is not an asset id: a wallet user pastes this into Sage, a wrong token must never be offered. */
    String playAssetId() {
        java.util.regex.Matcher m = ASSET_ID.matcher(config.playAssetId().strip());
        if (m.matches()) {
            return "0x" + m.group(1).toLowerCase(Locale.ROOT);
        }
        if (!badAssetIdLogged) {
            badAssetIdLogged = true;
            LOG.warnf("herd.play.asset-id is not a 64-digit hex asset id — serverInfo().playAssetId is left out");
        }
        return null;
    }

    /** Proposal: tiny lazy cache so {@code serverInfo()} does not re-parse the 5 129-row CSV on
     *  every call — {@code PriceService} already loads (and owns) the authoritative copy used for
     *  actual round pricing; this is only for the two ISO timestamps shown in {@code serverInfo}. */
    private static final class CandleLoaderCache {
        private static volatile List<Candle> cached;

        static List<Candle> get() {
            List<Candle> c = cached;
            if (c == null) {
                c = crowdvsmarket.server.replay.CandleLoader.loadFromClasspath("/replay/btc_1m.csv");
                cached = c;
            }
            return c;
        }
    }
}
