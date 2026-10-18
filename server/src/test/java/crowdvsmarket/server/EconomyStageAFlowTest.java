package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Economy stage A (Р-92, docs/product/economy_implementation_plan.md A1-A5) through the service: the symbol "SOL" is one of
 * the plan's twelve price markets, so the market gets its caps 600 (short round, {@code HOURLY}) / 300 (long round,
 * {@code DAILY}) and {@code r_m = 1.25 bp} (Р-102), whatever single cap it is listed with. Bets of 200 PLAY: the first tier, the full cap.
 *
 * <pre>
 * 100x on 200 PLAY: herd fee 200 * min(20%, 0.000125 * 100) = 2.500, network fee 0.100 -> 202.600 leaves the balance
 * entry 100.00: Q = 200 * 100 / 100 = 200 lots, l = 0.1 / 600, liquidation (20000 - 200) / (200 * (1 - l)) = 99.0166 -> 99.02
 * low 99.01: remainder = 200 + 200 * (99.01 - 100.00) = 2.00
 * </pre>
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class EconomyStageAFlowTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    private Market listSol(String id) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, "SOL", 25, 0);
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        prices.setPrice(market, "100.00");
        return market;
    }

    private static void assertInvalidLeverage(Runnable call, String mentions) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.INVALID_LEVERAGE);
            assertThat(e.httpStatus()).isEqualTo(422);
            assertThat(e.getMessage()).contains(mentions);
        });
    }

    @Test
    void aBetIsChargedItsFeesAndTheAnswerSaysSo() {
        Market sol = listSol("T_ECO_FEES");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("fees");
        long nowMs = betting.startMs() - 1;

        Rounds.BetDto placed = gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), nowMs);

        assertThat(placed.stake()).isEqualTo("200.000");
        assertThat(placed.feeHerd()).isEqualTo("2.500");
        assertThat(placed.feeNetwork()).isEqualTo("0.100");
        assertThat(placed.maxLeverageForStake()).isEqualTo(600);
        assertThat(gameService.account(p).balance()).isEqualTo("9797.400");
        assertThat(gameService.account(p).inPlay()).as("in play is the stake, not the fees").isEqualTo("200.000");
        Rounds.RoundBetting dto = (Rounds.RoundBetting) gameService.roundsPair(Enums.Interval.HOURLY, sol.id(), p).betting();
        assertThat(dto.myBet().feeHerd()).isEqualTo("2.500");
        assertThat(dto.myBet().maxLeverageForStake()).isEqualTo(600);
        // the ledger names the fees, with the round, so stage B can route them
        assertThat(gameService.myLedger(p, new Paging.PageParams(null, null)).items()).filteredOn(e -> betting.roundId().equals(e.roundId()))
                .extracting(Ledger.LedgerEntryDto::type)
                .contains(Enums.LedgerEntryType.STAKE_DEBIT, Enums.LedgerEntryType.FEE_HERD, Enums.LedgerEntryType.FEE_NETWORK);
    }

    @Test
    void theCapOfTheRoundLengthAndTheTierOfTheStakeBoundTheLeverageWithAClearMessage() {
        Market sol = listSol("T_ECO_CAPS");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("caps");
        long nowMs = betting.startMs() - 1;

        assertInvalidLeverage(() -> gameService.placeBet(p, bet(betting, Enums.Direction.UP, 601, "200.000"), nowMs), "600");
        // 300 PLAY is in the second tier: 600 / 2
        assertInvalidLeverage(() -> gameService.placeBet(p, bet(betting, Enums.Direction.UP, 301, "300.000"), nowMs), "300");
        // 1 500 PLAY: 600 / 10
        assertInvalidLeverage(() -> gameService.placeBet(p, bet(betting, Enums.Direction.UP, 61, "1500.000"), nowMs), "60");
        assertThat(gameService.account(p).balance()).as("a rejected bet reserves nothing").isEqualTo("10000.000");

        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 600, "250.000"), nowMs);
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 300, "300.000"), nowMs);   // replaces it
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 60, "1500.000"), nowMs);   // replaces it again
        assertThat(gameService.account(p).inPlay()).isEqualTo("1500.000");
    }

    @Test
    void theBalanceMustCoverTheStakePlusTheFees() {
        Market sol = listSol("T_ECO_BAL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("bal");

        // 10 000 PLAY is the whole balance; the fees come on top
        assertThatThrownBy(() -> gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "10000.000"),
                betting.startMs() - 1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    void cancellingAndReplacingABetRefundTheFees() {
        Market sol = listSol("T_ECO_CANCEL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("cancel");
        long nowMs = betting.startMs() - 1;

        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), nowMs);
        gameService.cancelBet(p, betting.roundId(), nowMs);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");

        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), nowMs);
        gameService.placeBet(p, bet(betting, Enums.Direction.DOWN, 10, "200.000"), nowMs);   // herd fee 200 * 0.125% = 0.250
        assertThat(gameService.account(p).balance()).isEqualTo("9799.650");
    }

    @Test
    void aLiquidatedBetIsPaidItsRemainderAtTheCandleExtremeAndTheFeesAreNotReturned() throws Exception {
        Market sol = listSol("T_ECO_LIQ");
        RoundWindow running = roundProgress.next(sol.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("liq");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), betting.startMs() - 1);

        scheduler.tick(running.endMs());
        prices.script(sol, betting, "100.00", "100.50", "99.01", "100.60");   // low 99.01 touches the liquidation price 99.02
        scheduler.tick(betting.endMs());

        Rounds.RoundSettled round = (Rounds.RoundSettled) roundResults.findRoundDto(betting.roundId()).orElseThrow();
        Rounds.PublicPositionDto position = round.positions().stream().filter(x -> x.playerId().equals(p)).findFirst()
                .orElseThrow();
        assertThat(position.liquidated()).isTrue();
        assertThat(position.liquidationPrice()).isEqualTo("99.02");
        assertThat(position.payout()).isEqualTo("2.000");
        assertThat(position.pnl()).isEqualTo("-198.000");
        // 10 000 - 202.6 + 2
        assertThat(gameService.account(p).balance()).isEqualTo("9799.400");
    }

    @Test
    void thePreviewForARoundLengthCarriesTheFeesAndTheMaximumForTheStake() {
        Market sol = listSol("T_ECO_PREVIEW");

        Betting.LiquidationPreviewDto dto = gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(sol.id(),
                Enums.Direction.UP, 100, "200.000", "100.00", Enums.Interval.HOURLY));

        assertThat(dto.liquidationPrice()).isEqualTo("99.02");
        assertThat(dto.feeHerd()).isEqualTo("2.500");
        assertThat(dto.feeNetwork()).isEqualTo("0.100");
        assertThat(dto.totalDebit()).isEqualTo("202.600");
        assertThat(dto.maxLeverageForStake()).isEqualTo(600);
        // on the long round the herd fee has k = 2.2: 200 * 0.000125 * 100 * 2.2 = 5.500 (Р-102)
        assertThat(gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(sol.id(), Enums.Direction.UP, 100,
                "200.000", "100.00", Enums.Interval.DAILY)).feeHerd()).isEqualTo("5.500");
        // on the long round the cap is 300
        assertInvalidLeverage(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(sol.id(),
                Enums.Direction.UP, 301, "200.000", "100.00", Enums.Interval.DAILY)), "300");
        // without a round length: the answer of old
        Betting.LiquidationPreviewDto old = gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(sol.id(),
                Enums.Direction.UP, 20, "200.000", "100.00"));
        assertThat(old.feeHerd()).isNull();
    }
}
