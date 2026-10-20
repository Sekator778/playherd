package crowdvsmarket.bots.brain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.persona.Personas;
import crowdvsmarket.bots.signal.Coin;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FixedModeBetTest {

    private static final BotBrain BRAIN = new BotBrain();
    private static final BotAccount ACCOUNT = BotAccount.fresh("bot-1", PlayAmount.ofWholePlay(100_000));

    private static MarketSnapshot market(boolean odds) {
        return new MarketSnapshot(MarketSpec.BTC, Interval.HOURLY, "BTC-r1", 0, 180_000, true, LedgerBook.MIN_STAKE, odds);
    }

    private static BotProfile profile(BetMode mode) {
        return new BotProfile("bot-1", new Coin(), Personas.byId("overtrader"), mode);
    }

    private static BetIntent decide(BotProfile p, MarketSnapshot m, String roundSuffix) {
        MarketSnapshot round = new MarketSnapshot(m.spec(), m.interval(), m.roundId() + roundSuffix, m.windowStartMs(),
                m.windowEndMs(), true, m.minStake(), m.odds());
        return BRAIN.decideMarket(p, ACCOUNT, round, List.of(), Optional.empty()).orElseThrow();
    }

    @Test
    void aFixedBotPlaysFixedOnAPriceMarketAndSendsTheModeWithNoMeaningfulLeverage() {
        for (int i = 0; i < 50; i++) {
            BetIntent intent = decide(profile(BetMode.FIXED), market(false), "-" + i);
            assertThat(intent.mode()).isEqualTo(BetMode.FIXED);
            BetRequest req = intent.toRequest();
            assertThat(req.mode()).isEqualTo(BetMode.FIXED);
            assertThat(req.isFixed()).isTrue();
            assertThat(req.leverage()).isEqualTo(Leverage.MIN);
            assertThat(req.stake()).isEqualTo(intent.stake());
        }
    }

    @Test
    void fixedIsNeverSentOnAnOddsMarket() {
        for (int i = 0; i < 50; i++) {
            BetIntent intent = decide(profile(BetMode.FIXED), market(true), "-" + i);
            assertThat(intent.mode()).isEqualTo(BetMode.PERCENTAGE);
            assertThat(intent.toRequest().mode()).isNull();
            assertThat(intent.toRequest().leverage()).isEqualTo(intent.leverage());
        }
    }

    @Test
    void aPercentageBotSendsNoModeAndKeepsItsLeverage() {
        for (int i = 0; i < 50; i++) {
            BetIntent intent = decide(profile(BetMode.PERCENTAGE), market(false), "-" + i);
            BetRequest req = intent.toRequest();
            assertThat(req.mode()).isNull();
            assertThat(req.isFixed()).isFalse();
            assertThat(req.leverage()).isEqualTo(intent.leverage());
        }
    }

    @Test
    void theModeChangesNothingElseInTheDecision() {
        for (int i = 0; i < 50; i++) {
            BetIntent fixed = decide(profile(BetMode.FIXED), market(false), "-" + i);
            BetIntent pct = decide(profile(BetMode.PERCENTAGE), market(false), "-" + i);
            assertThat(fixed.direction()).isEqualTo(pct.direction());
            assertThat(fixed.stake()).isEqualTo(pct.stake());
            assertThat(fixed.leverage()).isEqualTo(pct.leverage());
        }
    }

    @Test
    void theBalanceLeftForTheNextMarketCountsTheFixedFees() {
        // two markets in one pass: the second bet is sized against the balance after the first bet's stake and its fees
        MarketSnapshot a = new MarketSnapshot(MarketSpec.BTC, Interval.HOURLY, "BTC-r1", 0, 180_000, true, LedgerBook.MIN_STAKE);
        BotAccount poor = BotAccount.fresh("bot-1", PlayAmount.ofWholePlay(100).plus(EconomyRules.fixedFees(PlayAmount.ofWholePlay(100)).total()));
        Optional<BetIntent> one = BRAIN.decideMarket(profile(BetMode.FIXED), poor, a, List.of(), Optional.empty());
        assertThat(one).isPresent();
        PlayAmount spent = one.get().stake().plus(EconomyRules.fixedFees(one.get().stake()).total());
        assertThat(spent.compareTo(poor.balance())).isLessThanOrEqualTo(0);
    }
}
