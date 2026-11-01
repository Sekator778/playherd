package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.EconomyLimits;
import crowdvsmarket.server.testsupport.EconomyProfile;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * D-106, docs/product/fixed_payout_rules.md through the service, with the economy on ({@link EconomyProfile}: TREASURY about 1 000 000
 * PLAY, so the fixed-mode limits - 0.1% of it per side, 0.4% for the crypto bucket - are a thousand and four thousand PLAY): the bet, the
 * journal event, the settlement by the open and the close, the history row, the limits and their message, the refusal on odds markets, a
 * round that mixes both modes, and the replay of the journal.
 */
@QuarkusTest
@TestProfile(EconomyProfile.class)
class FixedPayoutFlowTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @BeforeEach
    void freshBooks() {
        crowdvsmarket.server.testsupport.CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    private Market listPriced(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 25, 0);
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        prices.setPrice(market, "100.00");
        return market;
    }

    private LedgerBook ledger() {
        return hub.ledger();
    }

    private static Betting.PlaceBetRequest fixedBet(RoundWindow window, Enums.Direction direction, String stake) {
        return new Betting.PlaceBetRequest(window.roundId(), direction, 0, stake, Enums.BetMode.FIXED);
    }

    private String balance(String player) {
        return gameService.account(player).balance();
    }

    /** Runs the round after {@code betting} from {@code open} to {@code close}; settles {@code betting} at its end. */
    private void settle(Market market, RoundWindow betting, String open, String close) {
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        scheduler.tick(running.endMs());
        prices.script(market, betting, open, close, open.compareTo(close) < 0 ? open : close, open.compareTo(close) < 0 ? close : open);
        scheduler.tick(betting.endMs());
    }

    private String eventPayload(String playerId) {
        return db.read(c -> db.queryOne(c, "SELECT payload::text AS p FROM game_events WHERE event_type = 'BetPlaced' "
                + "AND payload->>'playerId' = ? ORDER BY id DESC LIMIT 1", rs -> rs.getString("p"), playerId)).replaceAll("\\s", "");
    }

    private Map<String, Object> books(String... players) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("treasury", ledger().treasury());
        out.put("herd", ledger().herd());
        out.put("openStakes", ledger().openStakes());
        for (String p : players) {
            out.put("balance " + p, ledger().balance(p));
        }
        return out;
    }

    @Test
    void aFixedBetIsChargedItsFeesPaidTwiceTheStakeOrNothingAndTheJournalCarriesTheMode() {
        Market market = listPriced("T_FIXED_FLOW", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String winner = signUp("fxwin");
        String loser = signUp("fxlose");
        long nowMs = betting.startMs() - 1;
        PlayAmount herd0 = ledger().herd();

        Rounds.BetDto dto = gameService.placeBet(winner, fixedBet(betting, Enums.Direction.UP, "200.000"), nowMs);
        gameService.placeBet(loser, fixedBet(betting, Enums.Direction.DOWN, "200.000"), nowMs);

        assertThat(dto.mode()).isEqualTo(Enums.BetMode.FIXED);
        assertThat(dto.stake()).isEqualTo("200.000");
        assertThat(dto.feeHerd()).as("3% of 200").isEqualTo("6.000");
        assertThat(dto.feeNetwork()).isEqualTo("0.100");
        assertThat(dto.maxLeverageForStake()).as("no leverage").isNull();
        assertThat(balance(winner)).isEqualTo("9793.900");
        assertThat(ledger().herd()).isEqualTo(herd0.plus(PlayAmount.fromMoney(new BigDecimal("12.200"))));
        String payload = eventPayload(winner);
        assertThat(payload).contains("\"mode\":\"FIXED\"").doesNotContain("liq").contains("\"feeHerdMojos\":6000")
                .contains("\"feeNetMojos\":100");

        settle(market, betting, "100.00", "100.10");

        assertThat(balance(winner)).as("10000 - 200 - 6.1 + 400").isEqualTo("10193.900");
        assertThat(balance(loser)).as("10000 - 200 - 6.1").isEqualTo("9793.900");
        assertThat(ledger().openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
        // the history rows carry the mode, the payout and the fees; the result of the round for the ratings is payout - stake - fees
        assertThat(historyRow(winner)).containsExactly("FIXED", 400_000L, 6_100L);
        assertThat(historyRow(loser)).containsExactly("FIXED", 0L, 6_100L);
        assertThat(roundResults.playerHistory(winner, 10).get(0).mode()).isEqualTo(Enums.BetMode.FIXED);
        // the settled positions of the round say so, and have no liquidation price
        Rounds.RoundSettled settled = (Rounds.RoundSettled) gameService.round(betting.roundId(), winner);
        assertThat(settled.positions()).allSatisfy(p -> {
            assertThat(p.mode()).isEqualTo(Enums.BetMode.FIXED);
            assertThat(p.liquidationPrice()).isNull();
            assertThat(p.liquidated()).isFalse();
        });

        // a replay of the whole log gives the same books
        Map<String, Object> before = books(winner, loser);
        rebuild.rebuild();
        assertThat(books(winner, loser)).isEqualTo(before);
    }

    private List<Object> historyRow(String playerId) {
        return db.read(c -> db.queryOne(c, "SELECT mode, payout_mojos, fees_mojos FROM player_round_history WHERE player_id = ?",
                rs -> List.<Object>of(rs.getString("mode"), rs.getLong("payout_mojos"), rs.getLong("fees_mojos")), playerId));
    }

    @Test
    void aTieGivesTheStakeBackAndKeepsTheFees() {
        Market market = listPriced("T_FIXED_TIE", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("fxtie");
        gameService.placeBet(player, fixedBet(betting, Enums.Direction.UP, "300.000"), betting.startMs() - 1);

        settle(market, betting, "100.00", "100.00");

        assertThat(balance(player)).as("10000 - 9.1 of fees").isEqualTo("9990.900");
        assertThat(historyRow(player)).containsExactly("FIXED", 300_000L, 9_100L);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aPercentageBetIsWhatItWasNoModeInTheEventNoModeInTheAnswer() throws Exception {
        Market market = listPriced("T_FIXED_PCT", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("pct");

        Rounds.BetDto dto = gameService.placeBet(player, new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 5, "200.000"),
                betting.startMs() - 1);

        assertThat(dto.mode()).isNull();
        assertThat(dto.maxLeverageForStake()).isNotNull();
        assertThat(db.json().writeValueAsString(dto)).doesNotContain("mode");
        assertThat(eventPayload(player)).doesNotContain("mode").contains("liqCap");
        settle(market, betting, "100.00", "101.00");
        assertThat(historyRow(player).get(0)).isEqualTo("PERCENTAGE");
        assertThat(roundResults.playerHistory(player, 10).get(0).mode()).isNull();
    }

    @Test
    void theFixedModeIsRefusedOnAnOddsMarketWithAClearMessage() {
        Market odds = list("T_FIXED_ODDS", MarketKind.ODDS, Category.POLITICS, "FXO", 10, 4);
        alignTo(odds, HOURLY, roundProgress.next("BTC", HOURLY));
        prices.setPrice(odds, "0.5000");
        RoundWindow betting = roundProgress.bettingWindow(odds.id(), HOURLY);
        String player = signUp("fxodds");

        assertThatThrownBy(() -> gameService.placeBet(player, fixedBet(betting, Enums.Direction.UP, "200.000"), betting.startMs() - 1))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("odds markets").contains("percentage");
                });
        assertThat(balance(player)).as("nothing reserved").isEqualTo("10000.000");
        // the percentage mode still works there
        gameService.placeBet(player, new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 2, "200.000"), betting.startMs() - 1);
    }

    @Test
    void theStakesOfASideOfARoundAreLimitedToATenthOfAPercentOfTheTreasuryAndTheRejectionNamesTheRoomLeft() {
        Market market = listPriced("T_FIXED_LIMIT", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String a = signUp("fxla");
        String b = signUp("fxlb");
        String c = signUp("fxlc");
        long nowMs = betting.startMs() - 1;
        BigDecimal limit = ledger().treasury().toMoney().multiply(EconomyLimits.FIXED_MARKET_SHARE)
                .setScale(3, java.math.RoundingMode.DOWN);
        assertThat(limit).as("0.1% of about 1 000 000").isBetween(new BigDecimal("900"), new BigDecimal("1000"));

        gameService.placeBet(a, fixedBet(betting, Enums.Direction.UP, "600.000"), nowMs);
        BigDecimal room = limit.subtract(new BigDecimal("600"));
        assertThatThrownBy(() -> gameService.placeBet(b, fixedBet(betting, Enums.Direction.UP, "600.000"), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.EXPOSURE_LIMIT_EXCEEDED);
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("the room left is " + room.stripTrailingZeros().toPlainString() + " PLAY");
                });
        assertThat(balance(b)).as("a rejected bet reserves nothing").isEqualTo("10000.000");
        gameService.placeBet(b, fixedBet(betting, Enums.Direction.DOWN, "600.000"), nowMs);   // the other side is its own limit
        gameService.placeBet(c, fixedBet(betting, Enums.Direction.UP, room.toBigInteger().toString() + ".000"), nowMs); // exactly the room, rounded down to whole PLAY
        gameService.placeBet(a, fixedBet(betting, Enums.Direction.UP, "600.000"), nowMs);     // replacing a bet does not count the old one twice
        // a percentage bet on the same side has its own limit and is not affected by the fixed stakes
        String d = signUp("fxld");
        gameService.placeBet(d, new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 2, "200.000"), nowMs);
    }

    @Test
    void theCryptoMarketsShareOneFixedLimitOfFourTenthsOfAPercentPerSide() {
        String[] symbols = {"SOL", "XRP", "HYPE", "ETH", "DOGE"};
        List<Market> markets = new ArrayList<>();
        List<String> players = new ArrayList<>();
        for (String symbol : symbols) {
            markets.add(listPriced("T_FIXED_BKT_" + symbol, symbol));
            players.add(signUp("fxb" + symbol));
        }
        long nowMs = roundProgress.bettingWindow(markets.get(0).id(), HOURLY).startMs() - 1;
        PlayAmount treasury = ledger().treasury();
        BigDecimal marketLimit = treasury.toMoney().multiply(EconomyLimits.FIXED_MARKET_SHARE).setScale(3, java.math.RoundingMode.DOWN);
        BigDecimal bucket = treasury.toMoney().multiply(EconomyLimits.FIXED_CRYPTO_BUCKET_SHARE).setScale(3, java.math.RoundingMode.DOWN);
        for (int i = 0; i < 4; i++) { // four markets take 900 PLAY each, under their own limit of about 950
            RoundWindow betting = roundProgress.bettingWindow(markets.get(i).id(), HOURLY);
            gameService.placeBet(players.get(i), fixedBet(betting, Enums.Direction.UP, "900.000"), nowMs);
        }
        BigDecimal room = bucket.subtract(new BigDecimal("3600"));
        assertThat(room).isLessThan(marketLimit).isGreaterThan(BigDecimal.ZERO);
        RoundWindow dogeBetting = roundProgress.bettingWindow(markets.get(4).id(), HOURLY);
        assertThatThrownBy(() -> gameService.placeBet(players.get(4), fixedBet(dogeBetting, Enums.Direction.UP, room.add(BigDecimal.ONE).setScale(3).toPlainString()), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.EXPOSURE_LIMIT_EXCEEDED);
                    assertThat(e.getMessage()).contains("share one limit").contains("the room left is " + room.stripTrailingZeros().toPlainString());
                });
        gameService.placeBet(players.get(4), fixedBet(dogeBetting, Enums.Direction.DOWN, "900.000"), nowMs); // the other side is free
        gameService.placeBet(players.get(4), fixedBet(dogeBetting, Enums.Direction.UP, room.setScale(3).toPlainString()), nowMs); // exactly the room
    }

    @Test
    void aRoundWithBothModesSettlesEachByItsRulesAndTheJournalReplaysToTheSameBooks() {
        Market market = listPriced("T_FIXED_MIXED", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String fixedUp = signUp("mxfu");
        String fixedDown = signUp("mxfd");
        String pctUp = signUp("mxpu");
        long nowMs = betting.startMs() - 1;
        gameService.placeBet(fixedUp, fixedBet(betting, Enums.Direction.UP, "200.000"), nowMs);
        gameService.placeBet(fixedDown, fixedBet(betting, Enums.Direction.DOWN, "200.000"), nowMs);
        gameService.placeBet(pctUp, new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 2, "200.000"), nowMs);

        settle(market, betting, "100.00", "101.00");

        assertThat(balance(fixedUp)).isEqualTo("10193.900");
        assertThat(balance(fixedDown)).isEqualTo("9793.900");
        // x2 long +1% = +4.00; fees 0.05 + 0.1 (the short round's herd fee is k x r_m x leverage of the stake)
        assertThat(new BigDecimal(balance(pctUp))).isEqualByComparingTo(new BigDecimal("10000").add(new BigDecimal("4.00"))
                .subtract(new BigDecimal(historyFees(pctUp))));
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
        Map<String, Object> before = books(fixedUp, fixedDown, pctUp);
        rebuild.rebuild();
        assertThat(books(fixedUp, fixedDown, pctUp)).isEqualTo(before);
    }

    private String historyFees(String playerId) {
        long fees = db.read(c -> db.queryOne(c, "SELECT fees_mojos FROM player_round_history WHERE player_id = ?",
                rs -> rs.getLong("fees_mojos"), playerId));
        return BigDecimal.valueOf(fees, 3).toPlainString();
    }

    @Test
    void cancellingAFixedBetRefundsTheStakeAndBothFees() {
        Market market = listPriced("T_FIXED_CANCEL", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("fxcancel");
        PlayAmount herd0 = ledger().herd();
        gameService.placeBet(player, fixedBet(betting, Enums.Direction.UP, "250.000"), betting.startMs() - 1);
        assertThat(balance(player)).isEqualTo("9742.400");

        gameService.cancelBet(player, betting.roundId(), betting.startMs() - 1);

        assertThat(balance(player)).isEqualTo("10000.000");
        assertThat(ledger().herd()).isEqualTo(herd0);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }
}
