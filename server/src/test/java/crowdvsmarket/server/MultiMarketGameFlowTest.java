package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Crowd;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The multi-market game end to end (Р-69, docs/research/markets_architecture.md): one PLAY balance
 * behind bets on BTC, a 25x perpetual and a 10x probability market in the very same window, each
 * settled in its own units by its own engines and crowd account, with every price leaving the server as
 * the market's raw string. Time and prices are the test's: the scheduler is off and
 * {@code scheduler.tick(now)} is called with chosen timestamps, prices come from a scripted
 * {@code MarketPrices}. Each test lists markets of its own — markets are never deleted and cursors only
 * move forward — so the tests do not depend on each other's state; only the first one bets on BTC.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class MultiMarketGameFlowTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    private static void assertApiError(Runnable call, Enums.ApiErrorCode code, int httpStatus) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.httpStatus()).isEqualTo(httpStatus);
        });
    }

    private Rounds.RoundSettled settled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundSettled.class);
        return (Rounds.RoundSettled) dto;
    }

    private Rounds.PublicPositionDto positionOf(Rounds.RoundSettled round, String playerId) {
        return round.positions().stream().filter(p -> p.playerId().equals(playerId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // The headline: three markets, one window, one player, one balance
    // ------------------------------------------------------------------------------------------

    @Test
    void onePlayerBetsOnThreeMarketsInOneWindowAndEachSettlesInItsOwnUnits() throws Exception {
        Market btc = registry.require("BTC");
        Market eth = list("T_ETH", MarketKind.PERP, Category.CRYPTO, "ETH", 25, 1);   // priceDecimals 3
        Market odds = list("T_PM1", MarketKind.ODDS, Category.POLITICS, "GER", 10, 4);  // priceDecimals 6
        RoundWindow running = roundProgress.next("BTC", HOURLY);
        alignTo(eth, HOURLY, running);
        alignTo(odds, HOURLY, running);
        prices.setPrice(btc, "50000.00");
        prices.setPrice(eth, "2500.000");
        prices.setPrice(odds, "0.400000");
        String p1 = signUp("p1");
        String p2 = signUp("p2");

        RoundWindow btcBet = roundProgress.bettingWindow("BTC", HOURLY);
        RoundWindow ethBet = roundProgress.bettingWindow(eth.id(), HOURLY);
        RoundWindow oddsBet = roundProgress.bettingWindow(odds.id(), HOURLY);
        assertThat(ethBet.startMs()).isEqualTo(btcBet.startMs());
        assertThat(oddsBet.startMs()).isEqualTo(btcBet.startMs());
        assertThat(btcBet.roundId()).startsWith("1h-");            // BTC keeps the legacy id
        assertThat(ethBet.roundId()).isEqualTo("T_ETH-1h-" + btcBet.startMs());
        long nowMs = btcBet.startMs() - 1;

        // the same player, the same window, three markets (Т-5); the 25x/10x markets take their caps
        gameService.placeBet(p1, bet(btcBet, Enums.Direction.UP, 5, "500.000"), nowMs);
        gameService.placeBet(p1, bet(ethBet, Enums.Direction.DOWN, 20, "300.000"), nowMs);
        gameService.placeBet(p1, bet(oddsBet, Enums.Direction.UP, 10, "200.000"), nowMs);
        gameService.placeBet(p2, bet(btcBet, Enums.Direction.DOWN, 5, "100.000"), nowMs);
        assertThat(gameService.account(p1).balance()).isEqualTo("9000.000");
        assertThat(gameService.account(p1).inPlay()).isEqualTo("1000.000");

        // the betting DTO of a market names it and shows only that market's split
        Rounds.RoundBetting ethBetting = (Rounds.RoundBetting) gameService.roundsPair(Enums.Interval.HOURLY, eth.id(), p1)
                .betting();
        assertThat(ethBetting.roundId()).isEqualTo(ethBet.roundId());
        assertThat(ethBetting.marketId()).isEqualTo("T_ETH");
        assertThat(ethBetting.asset()).isEqualTo("ETH");
        assertThat(ethBetting.participantCount()).isEqualTo(1);
        assertThat(ethBetting.downStake()).isEqualTo("300.000");
        assertThat(ethBetting.myBet().leverage()).isEqualTo(20);
        Rounds.RoundBetting btcBetting = (Rounds.RoundBetting) gameService.roundsPair(Enums.Interval.HOURLY, null, p1)
                .betting();
        assertThat(btcBetting.marketId()).isEqualTo("BTC");
        assertThat(btcBetting.participantCount()).isEqualTo(2);
        assertThat(gameService.round(oddsBet.roundId(), p1)).isInstanceOfSatisfying(Rounds.RoundBetting.class, r -> {
            assertThat(r.marketId()).isEqualTo("T_PM1");
            assertThat(r.asset()).isEqualTo("GER");
            assertThat(r.upStake()).isEqualTo("200.000");
        });
        assertThat(gameService.betFeed(ethBet.roundId(), null)).satisfies(feed -> {
            assertThat(feed.marketId()).isEqualTo("T_ETH");
            assertThat(feed.asset()).isEqualTo("ETH");
            assertThat(feed.entries()).hasSize(1);
        });

        // the running window ends (nobody bet on it), then the bet window ends with scripted prices
        scheduler.tick(running.endMs());
        prices.script(btc, btcBet, "50000.00", "51000.00", "50000.00", "51000.00");
        prices.script(eth, ethBet, "2500.000", "2400.000", "2400.000", "2510.000");
        prices.script(odds, oddsBet, "0.400000", "0.440000", "0.395000", "0.440000");
        scheduler.tick(btcBet.endMs());

        // --- every market settled its own round; DTO prices are the market's raw strings ---
        Rounds.RoundSettled btcRound = settled(btcBet.roundId());
        assertThat(btcRound.marketId()).isEqualTo("BTC");
        assertThat(btcRound.asset()).isEqualTo("BTC");
        assertThat(btcRound.openPrice()).isEqualTo("50000.00");
        assertThat(btcRound.closePrice()).isEqualTo("51000.00");
        Rounds.PublicPositionDto btcPosition = positionOf(btcRound, p1);
        assertThat(btcPosition.entryPrice()).isEqualTo("50000.00");
        assertThat(btcPosition.liquidationPrice()).isEqualTo("40506.33");
        assertThat(btcPosition.size()).isEqualTo("0.05000000");
        assertThat(btcPosition.payout()).isEqualTo("550.000");
        assertThat(btcPosition.pnl()).isEqualTo("50.000");
        assertThat(positionOf(btcRound, p2).payout()).isEqualTo("90.000");
        assertThat(btcRound.crowd().entryPrice()).isEqualTo("50000.00");

        Rounds.RoundSettled ethRound = settled(ethBet.roundId());
        assertThat(ethRound.marketId()).isEqualTo("T_ETH");
        assertThat(ethRound.asset()).isEqualTo("ETH");
        assertThat(ethRound.openPrice()).isEqualTo("2500.000");
        assertThat(ethRound.closePrice()).isEqualTo("2400.000");
        Rounds.PublicPositionDto ethPosition = positionOf(ethRound, p1);
        assertThat(ethPosition.entryPrice()).isEqualTo("2500.000");
        assertThat(ethPosition.liquidationPrice()).isEqualTo("2573.529");   // 25735.29 lot price
        assertThat(ethPosition.size()).isEqualTo("0.24000000");
        assertThat(ethPosition.payout()).isEqualTo("540.000");
        assertThat(ethPosition.pnl()).isEqualTo("240.000");
        assertThat(ethRound.crowd().entryPrice()).isEqualTo("2500.000");
        assertThat(ethRound.crowd().direction()).isEqualTo(Enums.Direction.DOWN);
        assertThat(ethRound.crowd().leverage()).isEqualTo(20);

        Rounds.RoundSettled oddsRound = settled(oddsBet.roundId());
        assertThat(oddsRound.marketId()).isEqualTo("T_PM1");
        assertThat(oddsRound.asset()).isEqualTo("GER");
        assertThat(oddsRound.openPrice()).isEqualTo("0.400000");
        assertThat(oddsRound.closePrice()).isEqualTo("0.440000");
        Rounds.PublicPositionDto oddsPosition = positionOf(oddsRound, p1);
        assertThat(oddsPosition.entryPrice()).isEqualTo("0.400000");
        assertThat(oddsPosition.liquidationPrice()).isEqualTo("0.378947");  // 3789.47 lot price
        assertThat(oddsPosition.payout()).isEqualTo("400.000");
        assertThat(oddsRound.crowd().entryPrice()).isEqualTo("0.400000");

        // --- ONE balance: 10000 - 1000 staked + 550 + 540 + 400 paid out; p2 lost 10 on BTC ---
        assertThat(gameService.account(p1).balance()).isEqualTo("10490.000");
        assertThat(gameService.account(p1).inPlay()).isEqualTo("0.000");
        assertThat(gameService.account(p2).balance()).isEqualTo("9990.000");

        // --- one crowd account per market: own capital, own chart, own comparison lines ---
        Crowd.CrowdAccountDto btcCrowd = gameService.crowdAccount(null);          // omitted -> BTC
        Crowd.CrowdAccountDto ethCrowd = gameService.crowdAccount(eth.id());
        Crowd.CrowdAccountDto oddsCrowd = gameService.crowdAccount(odds.id());
        assertThat(btcCrowd.marketId()).isEqualTo("BTC");
        assertThat(btcCrowd.symbol()).isEqualTo("BTC");
        assertThat(btcCrowd.capital()).isEqualTo("101000.00");   // UP 5x on 10 000 margin, +2% -> +1000
        assertThat(ethCrowd.marketId()).isEqualTo("T_ETH");
        assertThat(ethCrowd.symbol()).isEqualTo("ETH");
        assertThat(ethCrowd.capital()).isEqualTo("108000.00");   // DOWN 20x, -4% -> +8000
        assertThat(oddsCrowd.capital()).isEqualTo("110000.00");  // UP 10x, +10% -> +10 000
        assertThat(btcCrowd.history()).extracting(Crowd.CrowdHistoryPointDto::roundId).contains(btcBet.roundId());
        assertThat(ethCrowd.history()).extracting(Crowd.CrowdHistoryPointDto::roundId)
                .filteredOn(id -> id.startsWith("T_ETH-1h-"))   // (the daily series may have settled flat rounds too)
                .containsExactly(new RoundWindow(eth.id(), HOURLY, running.startMs(), running.endMs()).roundId(),
                        ethBet.roundId());
        Crowd.CrowdHistoryPointDto ethPoint = ethCrowd.history().get(ethCrowd.history().size() - 1);
        assertThat(ethPoint.crowdCapital()).isEqualTo("108000.00");
        assertThat(ethPoint.holdCapital()).isEqualTo("96000.00");        // 4 lots bought at 25000, now 24000
        assertThat(ethPoint.antiCrowdCapital()).isEqualTo("90000.00");   // the opposite 20x long is liquidated
        Crowd.CrowdHistoryPointDto btcPoint = btcCrowd.history().get(btcCrowd.history().size() - 1);
        assertThat(btcPoint.holdCapital()).isEqualTo("102000.00");
        assertThat(btcPoint.antiCrowdCapital()).isEqualTo("99000.00");
        assertThat(oddsCrowd.history().get(oddsCrowd.history().size() - 1).holdCapital()).isEqualTo("110000.00");

        // --- the read model: market columns, previous majority, latest window per market ---
        assertThat(roundResults.lastMajorityDirection("BTC", HOURLY)).contains(Enums.Direction.UP);
        assertThat(roundResults.lastMajorityDirection(eth.id(), HOURLY)).contains(Enums.Direction.DOWN);
        assertThat(roundResults.lastMajorityDirection(odds.id(), HOURLY)).contains(Enums.Direction.UP);
        assertThat(roundResults.lastMajorityDirection(eth.id(), Interval.DAILY)).isEmpty(); // other series
        assertThat(roundResults.latestWindowEnd(eth.id(), HOURLY)).contains(ethBet.endMs());
        List<Identity.ProfileRoundEntryDto> history = gameService.publicProfile(p1).history();
        assertThat(history).extracting(Identity.ProfileRoundEntryDto::marketId)
                .containsExactlyInAnyOrder("BTC", "T_ETH", "T_PM1");
        assertThat(history).extracting(Identity.ProfileRoundEntryDto::asset)
                .containsExactlyInAnyOrder("BTC", "ETH", "GER");
        assertThat(gameService.myUnseenResults(p1)).extracting(Identity.ProfileRoundEntryDto::roundId)
                .contains(btcBet.roundId(), ethBet.roundId(), oddsBet.roundId());

        // --- one oracle record per round of every market, one shared seq, the registry index as asset,
        // the engine (lot) close price x 100 ---
        record Oracle(String market, int asset, long cents, long seq) {
        }
        List<Oracle> oracle = new ArrayList<>();
        for (RoundWindow w : List.of(btcBet, ethBet, oddsBet)) {
            oracle.add(db.read(c -> db.queryOne(c, "SELECT market, asset_index, price_cents, seq FROM oracle_records "
                            + "WHERE round_id = ?", rs -> new Oracle(rs.getString("market"), rs.getInt("asset_index"),
                            rs.getLong("price_cents"), rs.getLong("seq")), w.roundId())));
        }
        assertThat(oracle.get(0)).satisfies(o -> {
            assertThat(o.market()).isEqualTo("BTC");
            assertThat(o.asset()).isZero();
            assertThat(o.cents()).isEqualTo(5_100_000L);
        });
        assertThat(oracle.get(1)).satisfies(o -> {
            assertThat(o.market()).isEqualTo("T_ETH");
            assertThat(o.asset()).isEqualTo(eth.oracleIndex()).isPositive();
            assertThat(o.cents()).isEqualTo(2_400_000L);   // lot price 24000.00 x 100, not the raw 2400.000
        });
        assertThat(oracle.get(2)).satisfies(o -> {
            assertThat(o.market()).isEqualTo("T_PM1");
            assertThat(o.asset()).isEqualTo(odds.oracleIndex()).isNotEqualTo(eth.oracleIndex());
            assertThat(o.cents()).isEqualTo(440_000L);     // lot price 4400.00 x 100
        });
        assertThat(oracle).extracting(Oracle::seq).doesNotHaveDuplicates();
        List<Long> seqs = db.read(c -> db.queryList(c, "SELECT seq FROM oracle_records ORDER BY seq ASC",
                rs -> rs.getLong("seq")));
        assertThat(seqs.get(seqs.size() - 1) - seqs.get(0) + 1).as("seq has no holes").isEqualTo(seqs.size());

        // --- canonical JSON: BTC without the key, other markets with it; the hash is the sha256 of the bytes ---
        String btcJson = roundResults.canonicalJson(btcBet.roundId()).orElseThrow();
        String ethJson = roundResults.canonicalJson(ethBet.roundId()).orElseThrow();
        assertThat(btcJson).doesNotContain("\"market\"");
        assertThat(ethJson).contains("\"market\":\"T_ETH\"").contains("\"roundId\":\"" + ethBet.roundId() + "\"")
                .contains("\"openPriceCents\":2500000").contains("\"closePriceCents\":2400000");
        assertThat(ethRound.resultsHash()).isEqualTo("0x" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ethJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(btcRound.resultsHash()).isEqualTo("0x" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(btcJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        // --- a restart changes nothing: balances, crowd accounts and chart lines come back from the log ---
        String btcCapital = gameService.crowdAccount("BTC").capital();
        int ethPoints = gameService.crowdAccount(eth.id()).history().size();
        rebuild.rebuild();
        assertThat(gameService.account(p1).balance()).isEqualTo("10490.000");
        assertThat(gameService.account(p2).balance()).isEqualTo("9990.000");
        assertThat(gameService.crowdAccount("BTC").capital()).isEqualTo(btcCapital);
        assertThat(gameService.crowdAccount(eth.id()).capital()).isEqualTo("108000.00");
        assertThat(gameService.crowdAccount(odds.id()).capital()).isEqualTo("110000.00");
        assertThat(gameService.crowdAccount(eth.id()).history()).hasSize(ethPoints);
        Crowd.CrowdHistoryPointDto ethPointAfter = gameService.crowdAccount(eth.id()).history().get(ethPoints - 1);
        assertThat(ethPointAfter.holdCapital()).isEqualTo("96000.00");
        assertThat(ethPointAfter.antiCrowdCapital()).isEqualTo("90000.00");
        // every series resumes right after its last settled round
        assertThat(roundProgress.next(eth.id(), HOURLY)).isEqualTo(RoundClock.next(ethBet));
        assertThat(roundProgress.next("BTC", HOURLY)).isEqualTo(RoundClock.next(btcBet));
    }

    // ------------------------------------------------------------------------------------------
    // Listing and restarting
    // ------------------------------------------------------------------------------------------

    @Test
    void aListedMarketGetsItsEnginesAndItsSeriesAtTheWindowContainingNowAndARebuildKeepsItThere() {
        long before = System.currentTimeMillis();

        Market market = list("T_FRESH", MarketKind.PERP, Category.CRYPTO, "FRS", 10, 0);   // the registry tells the game

        long after = System.currentTimeMillis();
        assertThat(hub.findMarket(market.id())).isPresent();
        assertThat(hub.crowdAccount(market.id()).spec().maxLeverage()).isEqualTo(10);
        for (Interval interval : Interval.values()) {
            RoundWindow running = roundProgress.next(market.id(), interval);
            // §4 п.2: the window running NOW — not the calendar's first window — is the running round
            assertThat(running.market()).isEqualTo(market.id());
            assertThat(running.startMs()).isLessThanOrEqualTo(after);
            assertThat(running.endMs()).isGreaterThan(before);
            assertThat(running).isEqualTo(calendar.windowContaining(market.id(), interval, running.startMs()));
        }

        // a restart: no round of the market ever settled, so it is seeded the same way again
        long beforeRebuild = System.currentTimeMillis();
        rebuild.rebuild();
        long afterRebuild = System.currentTimeMillis();
        for (Interval interval : Interval.values()) {
            RoundWindow running = roundProgress.next(market.id(), interval);
            assertThat(running.startMs()).isLessThanOrEqualTo(afterRebuild);
            assertThat(running.endMs()).isGreaterThan(beforeRebuild);
        }
        assertThat(roundProgress.next("BTC", HOURLY).market()).isEqualTo("BTC");
    }

    // ------------------------------------------------------------------------------------------
    // Rules at the door of placeBet
    // ------------------------------------------------------------------------------------------

    @Test
    void leverageAboveTheMarketsCapIsRejectedAndTheCapItselfIsAccepted() {
        Market cap25 = list("T_CAP25", MarketKind.PERP, Category.CRYPTO, "CAP", 25, 0);
        Market cap10 = list("T_CAP10", MarketKind.ODDS, Category.SPORTS, "ODD", 10, 4);
        Market cap40 = list("T_CAP40", MarketKind.PERP, Category.CRYPTO, "C40", 40, 0);
        RoundWindow like = roundProgress.next("BTC", HOURLY);
        alignTo(cap25, HOURLY, like);
        alignTo(cap10, HOURLY, like);
        alignTo(cap40, HOURLY, like);
        RoundWindow w25 = roundProgress.bettingWindow(cap25.id(), HOURLY);
        RoundWindow w10 = roundProgress.bettingWindow(cap10.id(), HOURLY);
        RoundWindow w40 = roundProgress.bettingWindow(cap40.id(), HOURLY);
        String p = signUp("cap");
        long nowMs = w25.startMs() - 1;

        assertThatThrownBy(() -> gameService.placeBet(p, bet(w25, Enums.Direction.UP, 30, "100.000"), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.INVALID_LEVERAGE);
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("25");   // names the cap
                });
        assertThatThrownBy(() -> gameService.placeBet(p, bet(w10, Enums.Direction.UP, 11, "100.000"), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.INVALID_LEVERAGE);
                    assertThat(e.getMessage()).contains("10");
                });
        assertApiError(() -> gameService.placeBet(p, bet(w25, Enums.Direction.UP, 1, "100.000"), nowMs),
                Enums.ApiErrorCode.INVALID_LEVERAGE, 422);
        // nothing was reserved by the rejected bets
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");

        gameService.placeBet(p, bet(w25, Enums.Direction.UP, 25, "100.000"), nowMs);
        gameService.placeBet(p, bet(w10, Enums.Direction.DOWN, 10, "100.000"), nowMs);
        gameService.placeBet(p, bet(w40, Enums.Direction.UP, 30, "100.000"), nowMs);    // a 40x market allows 30
        assertThat(gameService.account(p).inPlay()).isEqualTo("300.000");

        // the preview obeys the same cap, and is computed in the market's own units
        assertApiError(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(cap25.id(),
                Enums.Direction.UP, 30, "100.000", "100.00")), Enums.ApiErrorCode.INVALID_LEVERAGE, 422);
    }

    @Test
    void anUnknownMarketIsMarketNotFoundEverywhere() {
        String p = signUp("ghost");
        RoundWindow bogus = new RoundWindow("GHOST", HOURLY, 60_000L, 120_000L);

        assertApiError(() -> gameService.placeBet(p, bet(bogus, Enums.Direction.UP, 2, "100.000"), 1L),
                Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.roundsPair(Enums.Interval.HOURLY, "GHOST", null),
                Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.crowdAccount("GHOST"), Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.referencePrice("GHOST"), Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.round(bogus.roundId(), null), Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.cancelBet(p, bogus.roundId(), 1L), Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        assertApiError(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest("GHOST",
                Enums.Direction.UP, 5, "100.000", "1.00")), Enums.ApiErrorCode.MARKET_NOT_FOUND, 404);
        // a malformed id is a different failure
        assertApiError(() -> gameService.round("not-a-round", null), Enums.ApiErrorCode.BAD_REQUEST, 400);
        assertApiError(() -> gameService.placeBet(p, new Betting.PlaceBetRequest("1h-05", Enums.Direction.UP, 2,
                "100.000"), 1L), Enums.ApiErrorCode.BAD_REQUEST, 400);
    }

    @Test
    void aRoundCancelledByTheOperatorBeforeItStartedTakesNoMoreBets() {
        Market market = list("T_OPCANCEL", MarketKind.PERP, Category.CRYPTO, "OPC", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String early = signUp("early");
        String late = signUp("late");
        long nowMs = betting.startMs() - 1;
        gameService.placeBet(early, bet(betting, Enums.Direction.UP, 5, "250.000"), nowMs);
        assertThat(gameService.account(early).inPlay()).isEqualTo("250.000");

        Rounds.CancellationDto cancellation = gameService.cancelRoundById(betting.roundId(), "operator test", nowMs);

        assertThat(cancellation.refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(early).balance()).isEqualTo("10000.000");
        // the round is cancelled in the read model, so a new bet on it would sit there with its stake
        // debited and never be settled or refunded: it is refused
        assertApiError(() -> gameService.placeBet(late, bet(betting, Enums.Direction.UP, 5, "100.000"), nowMs),
                Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING, 409);
        assertThat(gameService.account(late).balance()).isEqualTo("10000.000");
        assertThat(gameService.hasPendingBets(betting)).isFalse();
        assertThat(gameService.roundsPair(Enums.Interval.HOURLY, market.id(), null).betting())
                .isInstanceOf(Rounds.RoundCancelled.class);

        // and the scheduler steps over it without resolving it
        scheduler.tick(betting.startMs());                // the running window ends: betting round becomes running
        scheduler.tick(betting.endMs());
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(betting.endMs());
        assertThat(roundResults.findRoundDto(betting.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundCancelled.class);
    }

    // ------------------------------------------------------------------------------------------
    // A market closes
    // ------------------------------------------------------------------------------------------

    @Test
    void closingAMarketRefundsPendingBetsStopsItsSeriesAndRefusesNewBets() {
        Market market = list("T_CLOSE", MarketKind.ODDS, Category.POLITICS, "CLS", 10, 4);
        prices.setPrice(market, "0.500000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        alignTo(market, Interval.DAILY, roundProgress.next("BTC", Interval.DAILY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        RoundWindow dailyBetting = roundProgress.bettingWindow(market.id(), Interval.DAILY);
        String bettor = signUp("closing");
        String other = signUp("closing2");
        gameService.placeBet(bettor, bet(betting, Enums.Direction.UP, 5, "400.000"), betting.startMs() - 1);
        gameService.placeBet(other, bet(dailyBetting, Enums.Direction.DOWN, 2, "150.000"), dailyBetting.startMs() - 1);
        // a bet already running: the running window of the series holds one as well
        scheduler.tick(running.endMs());   // running resolves (empty); `betting` is now the running round
        RoundWindow nextBetting = roundProgress.bettingWindow(market.id(), HOURLY);
        gameService.placeBet(other, bet(nextBetting, Enums.Direction.UP, 3, "200.000"), nextBetting.startMs() - 1);
        assertThat(gameService.account(bettor).inPlay()).isEqualTo("400.000");
        assertThat(gameService.account(other).inPlay()).isEqualTo("350.000");

        registry.close(market.id(), "closed at the source", System.currentTimeMillis());

        // both open rounds of both series were cancelled with a refund, publicly, with the reason
        assertThat(gameService.account(bettor).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(bettor).inPlay()).isEqualTo("0.000");
        assertThat(gameService.account(other).balance()).isEqualTo("10000.000");
        for (RoundWindow w : List.of(betting, nextBetting, dailyBetting)) {
            Rounds.RoundDto dto = roundResults.findRoundDto(w.roundId()).orElseThrow();
            assertThat(dto).as(w.roundId()).isInstanceOf(Rounds.RoundCancelled.class);
            assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo("market closed: closed at the source");
            assertThat(((Rounds.RoundCancelled) dto).marketId()).isEqualTo("T_CLOSE");
        }
        assertThat(((Rounds.RoundCancelled) roundResults.findRoundDto(betting.roundId()).orElseThrow())
                .cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.cancellations(new crowdvsmarket.server.dto.Paging.PageParams(50, null)).items())
                .extracting(Rounds.CancellationDto::roundId).contains(betting.roundId(), nextBetting.roundId());
        assertThat(gameService.hasPendingBets(betting)).isFalse();

        // the series stopped: no cursor, the scheduler ignores the market
        assertThat(roundProgress.isSeeded(market.id(), HOURLY)).isFalse();
        assertThat(roundProgress.isSeeded(market.id(), Interval.DAILY)).isFalse();
        scheduler.tick(System.currentTimeMillis() + 10 * 60_000L);
        assertThat(roundResults.latestWindowEnd(market.id(), HOURLY)).contains(nextBetting.endMs());

        // a bet on a closed market is rejected as such, wherever its round id points
        assertApiError(() -> gameService.placeBet(bettor, bet(nextBetting, Enums.Direction.UP, 2, "100.000"),
                nextBetting.startMs() - 1), Enums.ApiErrorCode.MARKET_CLOSED, 409);
        assertApiError(() -> gameService.roundsPair(Enums.Interval.HOURLY, market.id(), null),
                Enums.ApiErrorCode.MARKET_CLOSED, 409);
        // its history stays readable, and it keeps its engines (a restart replays them)
        assertThat(gameService.crowdAccount(market.id()).marketId()).isEqualTo("T_CLOSE");
        assertThat(gameService.round(betting.roundId(), null)).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(registry.require(market.id()).isActive()).isFalse();

        rebuild.rebuild();
        assertThat(hub.findMarket(market.id())).isPresent();
        assertThat(roundProgress.isSeeded(market.id(), HOURLY)).as("a closed market is not seeded again").isFalse();
        assertThat(gameService.account(bettor).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(other).balance()).isEqualTo("10000.000");
    }

    @Test
    void aMarketThatHadSettledRoundsKeepsItsCrowdHistoryAcrossARebuildAfterItClosed() {
        Market market = list("T_OLDMKT", MarketKind.PERP, Category.CRYPTO, "OLD", 20, 2);
        prices.setPrice(market, "250.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("old");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 10, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "250.00", "255.00", "250.00", "255.00");
        scheduler.tick(betting.endMs());
        int points = gameService.crowdAccount(market.id()).history().size();
        String capital = gameService.crowdAccount(market.id()).capital();
        assertThat(capital).isNotEqualTo("100000.00");   // the crowd traded and won
        registry.close(market.id(), "delisted", System.currentTimeMillis());

        rebuild.rebuild();

        assertThat(gameService.crowdAccount(market.id()).history()).hasSize(points);
        assertThat(gameService.crowdAccount(market.id()).capital()).isEqualTo(capital);
        assertThat(roundResults.findRoundDto(betting.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
    }

    // ------------------------------------------------------------------------------------------
    // Restart safety nets: no bet is ever left behind a cursor
    // ------------------------------------------------------------------------------------------

    @Test
    void aRestartAfterTheOperatorCancelledTheBettingRoundDoesNotStrandTheBetsOfTheRunningRound() {
        Market market = list("T_STEPBACK", MarketKind.PERP, Category.CRYPTO, "STB", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow first = roundProgress.next(market.id(), HOURLY);
        RoundWindow bettingNow = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("stepback");
        gameService.placeBet(p, bet(bettingNow, Enums.Direction.UP, 2, "100.000"), bettingNow.startMs() - 1);
        scheduler.tick(first.endMs());                    // `bettingNow` is the running round, with the bet
        RoundWindow nextBetting = roundProgress.bettingWindow(market.id(), HOURLY);
        gameService.cancelRoundById(nextBetting.roundId(), "operator test", nextBetting.startMs() - 1);
        // the newest result row now belongs to the window AFTER the running one

        rebuild.rebuild();

        assertThat(roundProgress.next(market.id(), HOURLY)).as("not skipped to the window after the cancelled one")
                .isEqualTo(bettingNow);
        assertThat(gameService.account(p).inPlay()).isEqualTo("100.000");
        scheduler.tick(bettingNow.endMs());
        assertThat(roundResults.findRoundDto(bettingNow.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(nextBetting);
        // the cancelled window is stepped over, never resolved
        scheduler.tick(nextBetting.endMs());
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(nextBetting.endMs());
        assertThat(roundResults.findRoundDto(nextBetting.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundCancelled.class);
    }

    @Test
    void aMarketClosedInTheDatabaseButNotYetCancelledIsCleanedUpByTheNextRebuild() {
        Market market = list("T_CRASH", MarketKind.PERP, Category.CRYPTO, "CRS", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("crash");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "300.000"), betting.startMs() - 1);
        assertThat(gameService.account(p).inPlay()).isEqualTo("300.000");
        // the process "stopped" between the registry's commit and the listener's cancellations; the market
        // closed during the window that was running, just before the one open for betting began
        long closedAt = betting.startMs() - 1;
        db.tx(c -> db.update(c, "UPDATE markets SET status = 'CLOSED', closed_at_ms = ?, close_reason = 'crash test' "
                + "WHERE id = ?", closedAt, market.id()));

        rebuild.rebuild();

        Rounds.RoundDto dto = roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo("market closed: crash test");
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.isSeeded(market.id(), HOURLY)).isFalse();
        // and a second restart finds nothing left to do
        long cancelledBefore = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM round_results WHERE market = ?",
                rs -> rs.getLong("n"), market.id()));
        rebuild.rebuild();
        long cancelledAfter = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM round_results WHERE market = ?",
                rs -> rs.getLong("n"), market.id()));
        assertThat(cancelledAfter).isEqualTo(cancelledBefore);
    }

    // ------------------------------------------------------------------------------------------
    // The scheduler: catch-up, no price, one failing market
    // ------------------------------------------------------------------------------------------

    @Test
    void catchUpSkipsEmptyOverdueWindowsButStillResolvesTheWindowThatHasABet() {
        Market market = list("T_CATCH", MarketKind.PERP, Category.CRYPTO, "CU", 10, 0);
        prices.setPrice(market, "1000.00");
        String p = signUp("catch");
        long nowMs = System.currentTimeMillis();
        long step = 60_000L;
        RoundWindow w0 = calendar.windowContaining(market.id(), HOURLY, nowMs - 100 * step);
        roundProgress.seed(market.id(), HOURLY, w0);       // the series was stopped for ~100 windows
        RoundWindow w1 = RoundClock.next(w0);              // the window open for betting at the cursor
        gameService.placeBet(p, bet(w1, Enums.Direction.UP, 2, "100.000"), w1.startMs() - 1);
        prices.script(market, w1, "1000.00", "1010.00", "1000.00", "1010.00");

        scheduler.tick(nowMs);

        // the rule, re-derived independently: resolved are the window with the bet and every window that
        // ended less than one step ago; every other empty window is skipped without a trace
        RoundWindow containing = calendar.windowContaining(market.id(), HOURLY, nowMs);
        List<Long> expectedStarts = new ArrayList<>();
        for (RoundWindow w = w0; w.startMs() < containing.startMs(); w = RoundClock.next(w)) {
            if (w.equals(w1) || nowMs - w.endMs() <= step) {
                expectedStarts.add(w.startMs());
            }
        }
        assertThat(expectedStarts).contains(w1.startMs()).hasSizeBetween(2, 3);
        List<Long> resolvedStarts = db.read(c -> db.queryList(c, "SELECT window_start_ms FROM round_results "
                        + "WHERE market = ? AND interval = 'HOURLY' ORDER BY window_start_ms", rs -> rs.getLong(1),
                market.id()));
        assertThat(resolvedStarts).isEqualTo(expectedStarts);
        long resolvedEvents = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events "
                        + "WHERE event_type = 'RoundResolved' AND payload->>'market' = ? "
                        + "AND payload->>'interval' = 'HOURLY'", rs -> rs.getLong("n"), market.id()));
        assertThat(resolvedEvents).isEqualTo(expectedStarts.size());
        long oracleRows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM oracle_records "
                        + "WHERE market = ? AND round_id LIKE ?", rs -> rs.getLong("n"), market.id(),
                market.id() + "-1h-%"));
        assertThat(oracleRows).isEqualTo(expectedStarts.size());
        // the window with the bet was settled, the bet paid out and no longer "in play"
        assertThat(settled(w1.roundId()).positions()).singleElement()
                .satisfies(pos -> assertThat(pos.playerId()).isEqualTo(p));
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        // the series is back on the clock: running is the window that contains "now"
        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(containing);
        assertThat(roundResults.exists(w0.roundId())).as("the first, empty, overdue window").isFalse();
    }

    @Test
    void noPriceMeansRetryAndOneHourAfterTheWindowEndsACancellationWithRefund() {
        Market noPrice = list("T_NOPRICE", MarketKind.PERP, Category.CRYPTO, "NP", 10, 0);
        Market healthy = registry.require("BTC");
        prices.setPrice(noPrice, "100.00");
        prices.setPrice(healthy, "50000.00");
        prices.failAllResolves(noPrice, new PriceUnavailableException("source is down"));
        alignTo(noPrice, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(noPrice.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(noPrice.id(), HOURLY);
        String p = signUp("noprice");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        long btcBefore = roundProgress.next("BTC", HOURLY).startMs();

        // the running window ends, its price cannot be read: not resolved, the cursor waits — and
        // another market (BTC) is not held up by it
        scheduler.tick(running.endMs() + 1);
        assertThat(roundResults.exists(running.roundId())).isFalse();
        assertThat(roundProgress.next(noPrice.id(), HOURLY)).isEqualTo(running);
        assertThat(roundProgress.next("BTC", HOURLY).startMs()).isGreaterThan(btcBefore);

        // 1 h after the end of the window with the bet its price is still missing: cancelled, refunded
        scheduler.tick(running.endMs() + 3_600_000L);   // running is empty and overdue by more than a step: skipped
        assertThat(roundProgress.next(noPrice.id(), HOURLY)).isEqualTo(betting);
        assertThat(roundResults.exists(betting.roundId())).as("1 h not over yet").isFalse();
        assertThat(gameService.account(p).inPlay()).isEqualTo("100.000");
        scheduler.tick(betting.endMs() + 3_600_000L);

        Rounds.RoundDto dto = roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason())
                .isEqualTo("no oracle price available within 1 hour of round close");
        assertThat(((Rounds.RoundCancelled) dto).cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(noPrice.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(betting.endMs());
        assertThat(roundResults.exists(running.roundId())).as("the empty overdue window was skipped").isFalse();
    }

    @Test
    void aMarketThatThrowsDoesNotStopTheOthers() {
        Market broken = list("T_BROKEN", MarketKind.PERP, Category.CRYPTO, "BRK", 10, 0);
        Market fine = list("T_FINE", MarketKind.PERP, Category.CRYPTO, "FIN", 10, 0);
        prices.setPrice(broken, "10.00");
        prices.setPrice(fine, "20.00");
        prices.failAllResolves(broken, new IllegalStateException("boom"));
        RoundWindow like = roundProgress.next("BTC", HOURLY);
        alignTo(broken, HOURLY, like);
        alignTo(fine, HOURLY, like);
        RoundWindow running = roundProgress.next(fine.id(), HOURLY);

        scheduler.tick(running.endMs());

        assertThat(roundResults.exists(running.roundId())).isTrue();
        assertThat(roundResults.exists(new RoundWindow(broken.id(), HOURLY, running.startMs(), running.endMs()).roundId()))
                .isFalse();
        assertThat(roundProgress.next(broken.id(), HOURLY).startMs()).isEqualTo(running.startMs());
        assertThat(roundProgress.next(fine.id(), HOURLY).startMs()).isEqualTo(running.endMs());
    }

    @Test
    void aRoundNobodyBetOnStoresASingleOpenToCloseCandleAndOneWithBetsStoresTheSourcePath() {
        Market market = list("T_PATH", MarketKind.PERP, Category.CRYPTO, "PTH", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("path");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        prices.script(market, running, "100.00", "101.00", "99.00", "102.00");   // path = one scripted candle
        prices.script(market, betting, "101.00", "103.00", "100.00", "104.00");

        scheduler.tick(running.endMs());
        scheduler.tick(betting.endMs());

        List<Integer> pathLengths = db.read(c -> db.queryList(c, "SELECT jsonb_array_length(payload->'path') AS n "
                        + "FROM game_events WHERE event_type = 'RoundResolved' AND payload->>'market' = ? "
                        + "AND payload->>'interval' = 'HOURLY' ORDER BY id", rs -> rs.getInt("n"), market.id()));
        assertThat(pathLengths).containsExactly(1, 1);
        // the empty round's single candle is open -> close (101.00 / 99.00 high-low of the scripted path is NOT used)
        String emptyRoundCandle = db.read(c -> db.queryOne(c, "SELECT (payload->'path'->0->>'high') || '/' || "
                        + "(payload->'path'->0->>'low') AS hl FROM game_events WHERE event_type = 'RoundResolved' "
                        + "AND payload->>'market' = ? AND payload->>'interval' = 'HOURLY' ORDER BY id LIMIT 1",
                rs -> rs.getString("hl"), market.id()));
        assertThat(emptyRoundCandle).isEqualTo("101.00/100.00");   // max/min of open 100.00 and close 101.00
        String betRoundCandle = db.read(c -> db.queryOne(c, "SELECT (payload->'path'->0->>'high') || '/' || "
                        + "(payload->'path'->0->>'low') AS hl FROM game_events WHERE event_type = 'RoundResolved' "
                        + "AND payload->>'market' = ? AND payload->>'interval' = 'HOURLY' ORDER BY id DESC LIMIT 1",
                rs -> rs.getString("hl"), market.id()));
        assertThat(betRoundCandle).isEqualTo("104.00/100.00");     // the source's own candle (high 104, low 100)
    }

    // ------------------------------------------------------------------------------------------
    // Reading prices and the running round
    // ------------------------------------------------------------------------------------------

    @Test
    void previewAndReferencePriceAreInTheMarketsOwnRawUnits() {
        Market odds = list("T_PREVIEW", MarketKind.ODDS, Category.SPORTS, "PRV", 10, 4);
        prices.setPrice(odds, "0.400000");

        Betting.MarketReferenceDto reference = gameService.referencePrice(odds.id());
        Betting.LiquidationPreviewDto fromLatest = gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(
                odds.id(), Enums.Direction.UP, 10, "200.000", null));
        Betting.LiquidationPreviewDto fromGiven = gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(
                odds.id(), Enums.Direction.DOWN, 10, "200.000", "0.4"));

        assertThat(reference.price()).isEqualTo("0.400000");
        assertThat(fromLatest.entryPriceUsed()).isEqualTo("0.400000");
        assertThat(fromLatest.liquidationPrice()).isEqualTo("0.378947");
        assertThat(fromLatest.percentToLiquidation()).isCloseTo(0.0526325, within(0.00001));
        assertThat(fromGiven.entryPriceUsed()).isEqualTo("0.400000");
        assertThat(fromGiven.liquidationPrice()).isEqualTo("0.419048");   // short 10x: 4190.48 lot price
        // an omitted market is BTC, and BTC has no price here until it ticks
        prices.clearLatest(registry.require("BTC"));
        assertApiError(() -> gameService.btcReferencePrice(), Enums.ApiErrorCode.PRICE_UNAVAILABLE, 503);
        assertApiError(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(null,
                Enums.Direction.UP, 10, "100.000", null)), Enums.ApiErrorCode.PRICE_UNAVAILABLE, 503);
        prices.setPrice(registry.require("BTC"), "83451.50");
        assertThat(gameService.btcReferencePrice().price()).isEqualTo("83451.50");
        assertThat(gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(null, Enums.Direction.UP, 10,
                "100.000", null)).entryPriceUsed()).isEqualTo("83451.50");
        // garbage is a 400, not a 500
        assertApiError(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(odds.id(),
                Enums.Direction.UP, 10, "100.000", "abc")), Enums.ApiErrorCode.BAD_REQUEST, 400);
        assertApiError(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(odds.id(),
                Enums.Direction.UP, 10, "100.000", "0")), Enums.ApiErrorCode.BAD_REQUEST, 400);
    }

    @Test
    void theRunningRoundShowsPositionsFromTheSoFarPricesAndDoesNotFailWhileTheOpenPriceIsUnknown() {
        Market market = list("T_RUNNING", MarketKind.PERP, Category.CRYPTO, "RUN", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("running");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 20, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());                       // betting becomes the running round
        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(betting);

        // the open price is not known yet: "waiting for the opening price" — openPrice is null (neither a
        // zero nor the latest tick, which is not the round's open), no positions, no crowd, no failure
        Rounds.RoundRunning pending = (Rounds.RoundRunning) gameService.roundsPair(Enums.Interval.HOURLY, market.id(), p)
                .running();
        assertThat(pending.marketId()).isEqualTo("T_RUNNING");
        assertThat(pending.openPrice()).isNull();
        assertThat(pending.positions()).isEmpty();
        assertThat(pending.crowd()).isNull();
        prices.clearLatest(market);   // not even a latest tick changes that
        assertThat(((Rounds.RoundRunning) gameService.round(betting.roundId(), p)).openPrice()).isNull();

        // open known: positions are derived from it, in raw units, and the crowd preview with them
        prices.setSoFar(market, "2500.000", "2520.000");
        Rounds.RoundRunning live = (Rounds.RoundRunning) gameService.round(betting.roundId(), p);
        assertThat(live.openPrice()).isEqualTo("2500.000");
        assertThat(live.positions()).singleElement().satisfies(pos -> {
            assertThat(pos.entryPrice()).isEqualTo("2500.000");
            assertThat(pos.liquidationPrice()).isEqualTo("2423.469");  // long 20x, l = 1/50: 24234.69 lot price
            assertThat(pos.liquidated()).isFalse();
            assertThat(pos.pnl()).isEqualTo("48.00");                   // 0.24 lots * (25200 - 25000)
            assertThat(pos.payout()).isNull();
        });
        assertThat(live.crowd().entryPrice()).isEqualTo("2500.000");
        assertThat(live.crowd().liquidationPrice()).isEqualTo("2423.469");
        assertThat(live.crowd().leverage()).isEqualTo(20);
        // a path that already touched the liquidation price shows it
        prices.setSoFar(market, "2500.000", "2300.000");
        assertThat(((Rounds.RoundRunning) gameService.round(betting.roundId(), p)).positions().get(0).liquidated())
                .isTrue();
    }

    @Test
    void crowdOfTwoMarketsAreIndependentAndAnUnbetRoundTradesNothing() {
        Market a = list("T_IND_A", MarketKind.PERP, Category.CRYPTO, "IA", 10, 0);
        Market b = list("T_IND_B", MarketKind.PERP, Category.CRYPTO, "IB", 10, 0);
        prices.setPrice(a, "100.00");
        prices.setPrice(b, "100.00");
        RoundWindow like = roundProgress.next("BTC", HOURLY);
        alignTo(a, HOURLY, like);
        alignTo(b, HOURLY, like);
        RoundWindow running = roundProgress.next(a.id(), HOURLY);
        RoundWindow aBet = roundProgress.bettingWindow(a.id(), HOURLY);
        String btcCapital = gameService.crowdAccount("BTC").capital();
        String p = signUp("ind");
        gameService.placeBet(p, bet(aBet, Enums.Direction.UP, 5, "100.000"), aBet.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(a, aBet, "100.00", "110.00", "100.00", "110.00");

        scheduler.tick(aBet.endMs());

        assertThat(gameService.crowdAccount(a.id()).capital()).isEqualTo("105000.00"); // 500 lots (10 000 x 5 / 100) x +10
        assertThat(gameService.crowdAccount(b.id()).capital()).isEqualTo("100000.00");
        assertThat(gameService.crowdAccount(b.id()).history()).extracting(Crowd.CrowdHistoryPointDto::roundId)
                .allSatisfy(id -> assertThat(id).startsWith("T_IND_B-"));
        assertThat(gameService.crowdAccount(a.id()).history()).extracting(Crowd.CrowdHistoryPointDto::roundId)
                .allSatisfy(id -> assertThat(id).startsWith("T_IND_A-"));
        assertThat(gameService.crowdAccount("BTC").capital()).as("BTC's crowd was not touched").isEqualTo(btcCapital);
    }
}
