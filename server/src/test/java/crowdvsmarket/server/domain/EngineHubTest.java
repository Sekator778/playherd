package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import org.junit.jupiter.api.Test;

/** {@link EngineHub}: one shared ledger, per market one crowd account, two engines and one pair of
 *  comparison lines (docs/research/markets_architecture.md §4 п.1, Т-5, Т-6). */
class EngineHubTest {

    static Market market(String id, int maxLeverage, int lotExp, MarketStatus status) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, maxLeverage,
                lotExp, 1, status, 0L, null, null, null);
    }

    @Test
    void btcIsAlwaysThereAndNewMarketsGetTheirOwnEngines() {
        EngineHub hub = new EngineHub();
        assertThat(hub.findMarket("BTC")).isPresent();
        assertThat(hub.findMarket("ETH")).isEmpty();

        hub.ensureMarket(market("ETH", 25, 1, MarketStatus.ACTIVE));

        assertThat(hub.findMarket("ETH")).isPresent();
        assertThat(hub.marketIds()).containsExactlyInAnyOrder("BTC", "ETH");
        assertThat(hub.crowdAccount("ETH").spec().maxLeverage()).isEqualTo(25);
        assertThat(hub.crowdAccount("BTC").spec()).isEqualTo(MarketSpec.BTC);
        assertThat(hub.crowdAccount("ETH").capital()).isEqualByComparingTo(CrowdAccount.STARTING_CAPITAL);
    }

    @Test
    void enginesOfOneMarketShareItsCrowdAccountAndEveryEngineSharesTheLedger() {
        EngineHub hub = new EngineHub();
        hub.ensureMarket(market("ETH", 25, 1, MarketStatus.ACTIVE));

        assertThat(hub.engine("ETH", Interval.HOURLY)).isNotSameAs(hub.engine("ETH", Interval.DAILY));
        assertThat(hub.engine("ETH", Interval.HOURLY).crowdAccount()).isSameAs(hub.engine("ETH", Interval.DAILY).crowdAccount())
                .isSameAs(hub.crowdAccount("ETH"));
        assertThat(hub.engine("ETH", Interval.HOURLY).crowdAccount()).isNotSameAs(hub.crowdAccount("BTC"));
        assertThat(hub.engine("ETH", Interval.HOURLY).ledger()).isSameAs(hub.ledger())
                .isSameAs(hub.engine("BTC", Interval.DAILY).ledger());
        // bets of two markets never meet in one book
        assertThat(hub.engine("ETH", Interval.HOURLY).betBook()).isNotSameAs(hub.engine("BTC", Interval.HOURLY).betBook());
        assertThat(hub.comparison("ETH")).isNotSameAs(hub.comparison("BTC"));
    }

    @Test
    void ensureMarketIsIdempotentAndAClosedMarketGetsEnginesToo() {
        EngineHub hub = new EngineHub();
        var first = hub.ensureMarket(market("OLD", 10, 4, MarketStatus.CLOSED));
        var second = hub.ensureMarket(market("OLD", 10, 4, MarketStatus.CLOSED));

        assertThat(second).isSameAs(first);
        assertThat(hub.engine("OLD", Interval.HOURLY)).isNotNull();
    }

    @Test
    void resetEmptyDropsEveryMarketButBtcAndStartsANewLedger() {
        EngineHub hub = new EngineHub();
        hub.ensureMarket(market("ETH", 25, 1, MarketStatus.ACTIVE));
        var oldLedger = hub.ledger();
        var oldBtcCrowd = hub.crowdAccount("BTC");

        hub.resetEmpty();

        assertThat(hub.findMarket("ETH")).isEmpty();
        assertThat(hub.findMarket("BTC")).isPresent();
        assertThat(hub.ledger()).isNotSameAs(oldLedger);
        assertThat(hub.crowdAccount("BTC")).isNotSameAs(oldBtcCrowd);
        assertThat(hub.engine("BTC", Interval.HOURLY).ledger()).isSameAs(hub.ledger());
    }

    @Test
    void adoptTakesOverTheLedgerAndEveryMarketOfAHubBuiltAsideInOneStep() {
        EngineHub live = new EngineHub();
        live.ensureMarket(market("OLD", 10, 0, MarketStatus.ACTIVE));
        var liveLedger = live.ledger();
        var liveBtcCrowd = live.crowdAccount("BTC");
        // what a rebuild does: a hub of its own, filled while the live one goes on serving
        EngineHub built = new EngineHub();
        built.ensureMarket(market("ETH", 25, 1, MarketStatus.ACTIVE));
        var builtLedger = built.ledger();
        var builtEthCrowd = built.crowdAccount("ETH");
        assertThat(live.findMarket("ETH")).as("nothing of it is visible before the swap").isEmpty();
        assertThat(live.ledger()).isSameAs(liveLedger);

        live.adopt(built);

        assertThat(live.ledger()).isSameAs(builtLedger).isNotSameAs(liveLedger);
        assertThat(live.findMarket("OLD")).as("the old markets are gone with the old state").isEmpty();
        assertThat(live.crowdAccount("ETH")).isSameAs(builtEthCrowd);
        assertThat(live.crowdAccount("BTC")).isNotSameAs(liveBtcCrowd);
        assertThat(live.marketIds()).containsExactlyInAnyOrder("BTC", "ETH");
        // every engine of the adopted state works on the adopted ledger — the two never mix
        assertThat(live.engine("ETH", Interval.HOURLY).ledger()).isSameAs(live.ledger());
        assertThat(live.engine("BTC", Interval.DAILY).ledger()).isSameAs(live.ledger());
        // and a market ensured afterwards joins the adopted state, not the old one
        live.ensureMarket(market("SOL", 10, 2, MarketStatus.ACTIVE));
        assertThat(live.engine("SOL", Interval.HOURLY).ledger()).isSameAs(builtLedger);
    }

    @Test
    void anUnknownMarketIsAnErrorNotABtcFallback() {
        EngineHub hub = new EngineHub();

        assertThatThrownBy(() -> hub.engine("ETH", Interval.HOURLY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ETH");
        assertThatThrownBy(() -> hub.crowdAccount("ETH")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hub.comparison("ETH")).isInstanceOf(IllegalStateException.class);
    }
}
