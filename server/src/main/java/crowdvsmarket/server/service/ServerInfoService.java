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
 * engine is recovering from a failed settlement too ({@code EngineRebuildService}).
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

    /** Set once the configured PLAY asset id was found malformed, so that it is said once, not per poll. */
    private volatile boolean badAssetIdLogged;

    public ServerInfo.ServerInfoDto serverInfo() {
        // Bug fix: never call the chainsvc client synchronously here — read the background-
        // refreshed cache instead (OracleStatusCache's own javadoc has the full story).
        ChainSvcClient.OracleStatus status = oracleStatusCache.get();
        int pendingRecords = status != null ? status.pendingRecords() : oracleRecords.pendingCount();
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
                playAssetId(), config.testWalletEnabled());
    }

    /** Т-12: {@code "replay"} in {@code mode=replay} (accelerated history), otherwise {@code herd.prices.source}. */
    String priceSource() {
        return config.mode() == GameMode.REPLAY ? "replay" : prices.source().name().toLowerCase(Locale.ROOT);
    }

    /** Markets that are played here: active, and — in {@code mode=replay}, which plays BTC only — BTC
     *  ({@link GameService#runs}). */
    int activeMarkets() {
        return (int) marketRegistry.active().stream().filter(gameService::runs).count();
    }

    /** The lowest {@code seq} in {@code oracle_records}, {@code null} while the table is empty (an index read of
     *  the primary key). */
    Long firstOracleSeq() {
        return db.read(c -> db.queryOne(c, "SELECT MIN(seq) AS first_seq FROM oracle_records",
                rs -> (Long) rs.getObject("first_seq")));
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

    /** Предложение: tiny lazy cache so {@code serverInfo()} does not re-parse the 5 129-row CSV on
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
