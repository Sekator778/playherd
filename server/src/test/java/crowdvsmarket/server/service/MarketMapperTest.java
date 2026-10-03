package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.prices.PriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a market, a quote and the pending bets of a round turn into the fields of contract.ts' {@code
 * MarketDto}: raw prices with the market's own {@code priceDecimals} for a PERP with lot exponent 0, a PERP
 * with lot exponent 4 and an ODDS market (lot exponent 4); the live split and its majority; the kick-off
 * time of a game from the catalog's note; the crowd's return.
 */
class MarketMapperTest {

    private static Market market(String id, MarketKind kind, int lotExp, String sourceMeta) {
        return new Market(id, kind, kind == MarketKind.ODDS ? "politics" : "crypto", id, id + " title", null, null,
                "synthetic", id, sourceMeta, 10, lotExp, 1, MarketStatus.ACTIVE, 0L, null, null, null);
    }

    private static Market perpLot0() {
        return market("P0", MarketKind.PERP, 0, null);
    }

    private static Market perpLot4() {
        return market("P4", MarketKind.PERP, 4, null);
    }

    private static Market odds() {
        return market("PM1", MarketKind.ODDS, Market.ODDS_LOT_EXP, null);
    }

    private static Bet bet(String player, Direction direction, long wholePlay) {
        return new Bet(player, "round", direction, new Leverage(2), PlayAmount.ofWholePlay(wholePlay));
    }

    // ---------------------------------------------------------------------------------------
    // prices
    // ---------------------------------------------------------------------------------------

    @Test
    void aPerpWithLotExponentZeroHasTwoPriceDecimals() {
        Market market = perpLot0();

        assertThat(market.priceDecimals()).isEqualTo(2);
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("84012.3"))).isEqualTo("84012.30");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("84012"))).isEqualTo("84012.00");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("84012.345"))).as("HALF_UP").isEqualTo("84012.35");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("1E+5"))).as("never scientific").isEqualTo("100000.00");
    }

    @Test
    void aPerpWithLotExponentFourHasSixPriceDecimals() {
        Market market = perpLot4();

        assertThat(market.priceDecimals()).isEqualTo(6);
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("0.4511"))).isEqualTo("0.451100");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("0.0000125"))).isEqualTo("0.000013");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("12.5"))).isEqualTo("12.500000");
    }

    @Test
    void anOddsMarketIsAProbabilityWithSixDecimals() {
        Market market = odds();

        assertThat(market.priceDecimals()).isEqualTo(6);
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("0.523"))).isEqualTo("0.523000");
        assertThat(MarketMapper.rawPrice(market, new BigDecimal("0.0005"))).isEqualTo("0.000500");
        assertThat(MarketMapper.rawPrice(market, BigDecimal.ONE)).isEqualTo("1.000000");
    }

    @Test
    void theQuoteKeepsItsTimeItsChangeAndItsStaleness() {
        Markets.MarketPriceDto fresh = MarketMapper.price(odds(),
                new PriceQuote(new BigDecimal("0.64"), 1_790_000_000_123L, 0.0156, false));
        assertThat(fresh).isEqualTo(new Markets.MarketPriceDto("0.640000", 1_790_000_000_123L, 0.0156, false));

        Markets.MarketPriceDto stale = MarketMapper.price(perpLot0(),
                new PriceQuote(new BigDecimal("2700.5"), 5L, null, true));
        assertThat(stale).isEqualTo(new Markets.MarketPriceDto("2700.50", 5L, null, true));
    }

    @Test
    void aQuoteFromTheBackupExchangeSaysSoAndOneFromThePrimaryDoesNot() {
        Markets.MarketPriceDto fromTheBackup = MarketMapper.price(perpLot0(),
                new PriceQuote(new BigDecimal("2700.5"), 7L, 0.01, false, "kraken"));
        Markets.MarketPriceDto fromThePrimary = MarketMapper.price(perpLot0(),
                new PriceQuote(new BigDecimal("2700.5"), 7L, 0.01, false));

        assertThat(fromTheBackup).isEqualTo(new Markets.MarketPriceDto("2700.50", 7L, 0.01, false, "kraken"));
        assertThat(fromTheBackup.venue()).isEqualTo("kraken");
        assertThat(fromThePrimary.venue()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // the live split
    // ---------------------------------------------------------------------------------------

    @Test
    void anEmptyRoundIsATie() {
        MarketMapper.Split split = MarketMapper.split(List.of());

        assertThat(split.participantCount()).isZero();
        assertThat(split.upStake().toMoney().toPlainString()).isEqualTo("0.000");
        assertThat(split.downStake().toMoney().toPlainString()).isEqualTo("0.000");
        assertThat(split.majorityDirection()).as("0 vs 0 has no majority").isNull();
    }

    @Test
    void theSideWithMoreStakeIsTheMajorityRegardlessOfHeadcount() {
        // two small UP bets (150) against one big DOWN bet (400): DOWN has more stake and fewer players
        MarketMapper.Split split = MarketMapper.split(List.of(bet("a", Direction.UP, 100), bet("b", Direction.UP, 50),
                bet("c", Direction.DOWN, 400)));

        assertThat(split.participantCount()).isEqualTo(3);
        assertThat(split.upStake().toMoney().toPlainString()).isEqualTo("150.000");
        assertThat(split.downStake().toMoney().toPlainString()).isEqualTo("400.000");
        assertThat(split.majorityDirection()).isEqualTo(Enums.Direction.DOWN);
        assertThat(MarketMapper.split(List.of(bet("a", Direction.UP, 300), bet("b", Direction.DOWN, 299)))
                .majorityDirection()).isEqualTo(Enums.Direction.UP);
    }

    @Test
    void equalStakeOnBothSidesIsATieEvenWithPlayers() {
        MarketMapper.Split split = MarketMapper.split(List.of(bet("a", Direction.UP, 100), bet("b", Direction.DOWN, 100)));

        assertThat(split.participantCount()).isEqualTo(2);
        assertThat(split.majorityDirection()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // the kick-off of a game
    // ---------------------------------------------------------------------------------------

    @Test
    void aMarketWithoutAGameNoteHasNoKickOff() {
        assertThat(MarketMapper.eventStartsAtMs(perpLot0())).isNull();
        assertThat(MarketMapper.eventStartsAtMs(market("X", MarketKind.PERP, 0, ""))).isNull();
        assertThat(MarketMapper.eventStartsAtMs(market("X", MarketKind.PERP, 0, "{\"start\":\"84000\"}"))).isNull();
        assertThat(MarketMapper.eventStartsAtMs(market("X", MarketKind.ODDS, 4,
                "{\"slug\":\"will-x-win\",\"eventKey\":\"e1\"}"))).as("a politics market is no game").isNull();
    }

    @Test
    void gameStartMsIsTheKickOffWhenTheCatalogWritesIt() {
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4, "{\"gameStartMs\":1790880300000}")))
                .isEqualTo(1_790_880_300_000L);
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4, "{\"gameStartMs\":\"1790880300000\"}")))
                .as("a number kept as text").isEqualTo(1_790_880_300_000L);
    }

    @Test
    void gameStartTimeInGammasSpellingIsReadToo() {
        long kickOff = Instant.parse("2026-10-01T18:45:00Z").toEpochMilli();

        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4,
                "{\"gameStartTime\":\"2026-10-01 18:45:00+00\"}"))).isEqualTo(kickOff);
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4,
                "{\"gameStartTime\":\"2026-10-01T18:45:00Z\"}"))).isEqualTo(kickOff);
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4,
                "{\"gameStartTime\":\"2026-10-01 20:45:00+02\"}"))).as("an offset").isEqualTo(kickOff);
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4,
                "{\"gameStartTime\":\"2026-10-01T20:45:00+02:00\"}"))).isEqualTo(kickOff);
    }

    @Test
    void gameStartMsWinsOverGameStartTime() {
        assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4,
                "{\"gameStartTime\":\"2026-10-01 18:45:00+00\",\"gameStartMs\":1790000000000}")))
                .isEqualTo(1_790_000_000_000L);
    }

    @Test
    void aNoteThatCannotBeReadIsNoKickOffAndNeverAnError() {
        for (String meta : List.of("not json at all", "[1,2,3]", "{\"gameStartMs\":\"soon\"}",
                "{\"gameStartMs\":0}", "{\"gameStartMs\":-5}", "{\"gameStartMs\":1.5e3}",
                "{\"gameStartTime\":\"tomorrow evening\"}", "{\"gameStartTime\":12345}", "{\"gameStartTime\":null}",
                "{\"gameStartTime\":\"2026-10-01\"}")) {
            assertThat(MarketMapper.eventStartsAtMs(market("G", MarketKind.ODDS, 4, meta))).as(meta).isNull();
        }
    }

    // ---------------------------------------------------------------------------------------
    // the crowd's return
    // ---------------------------------------------------------------------------------------

    @Test
    void returnIsCapitalOverTheStartingHundredThousandMinusOne() {
        assertThat(MarketMapper.returnPct(new BigDecimal("100000.00"))).isZero();
        assertThat(MarketMapper.returnPct(new BigDecimal("103400.00"))).isCloseTo(0.034, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(MarketMapper.returnPct(new BigDecimal("97000.00"))).isCloseTo(-0.03, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(MarketMapper.returnPct(new BigDecimal("500000.00"), new BigDecimal("400000.00")))
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-12));
    }
}
