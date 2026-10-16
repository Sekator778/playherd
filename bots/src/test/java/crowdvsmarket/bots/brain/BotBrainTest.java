package crowdvsmarket.bots.brain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.TestBars;
import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.persona.Personas;
import crowdvsmarket.bots.port.BettingPort;
import crowdvsmarket.bots.port.InMemoryExchange;
import crowdvsmarket.bots.signal.Coin;
import crowdvsmarket.bots.signal.Momentum;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotBrainTest {

    private final InMemoryExchange exchange = botsIn(new InMemoryExchange());

    private static InMemoryExchange botsIn(InMemoryExchange e) {
        for (int i = 0; i < 20; i++) {
            e.withBot("bot-" + i, 100_000);
        }
        return e.withBot("bot-1", 100_000);
    }
    private final BotBrain brain = new BotBrain();

    private Map<String, List<BarDto>> allBars() {
        Map<String, List<BarDto>> bars = new HashMap<>();
        for (MarketSnapshot m : exchange.markets()) {
            bars.put(m.marketId(), exchange.bars(m.marketId(), BotBrain.HISTORY_BARS));
        }
        return bars;
    }

    private Map<String, Optional<Direction>> allMajorities() {
        Map<String, Optional<Direction>> maj = new HashMap<>();
        for (MarketSnapshot m : exchange.markets()) {
            maj.put(m.marketId(), exchange.previousMajority(m.marketId()));
        }
        return maj;
    }

    private List<BetIntent> decide(BotProfile p, BotAccount a, List<MarketSnapshot> markets) {
        return brain.decide(p, a, markets, allBars(), allMajorities());
    }

    private static BotProfile profile(String id, String persona) {
        return new BotProfile(id, new Coin(), Personas.byId(persona));
    }

    @Test
    void sameInputsGiveTheSameIntents() {
        BotAccount a = exchange.account("bot-1").orElseThrow();
        for (String persona : Personas.ids()) {
            for (int i = 0; i < 25; i++) {
                BotProfile p = profile("bot-" + i, persona);
                BotAccount acc = new BotAccount(p.botId(), a.balance(), LastOutcome.WIN, 2, Set.of());
                List<BetIntent> first = decide(p, acc, exchange.markets());
                List<BetIntent> second = decide(p, acc, exchange.markets());
                assertThat(second).isEqualTo(first);
            }
        }
    }

    @Test
    void theOrderOfTheSnapshotsDoesNotMatter() {
        BotAccount a = exchange.account("bot-1").orElseThrow();
        List<MarketSnapshot> reversed = new ArrayList<>(exchange.markets());
        Collections.reverse(reversed);
        for (String persona : Personas.ids()) {
            for (int i = 0; i < 25; i++) {
                BotProfile p = profile("bot-" + i, persona);
                BotAccount acc = new BotAccount(p.botId(), a.balance(), LastOutcome.NONE, 0, Set.of());
                assertThat(decide(p, acc, reversed)).isEqualTo(decide(p, acc, exchange.markets()));
            }
        }
    }

    @Test
    void decisionsVaryAcrossBotsAndRounds() {
        BotAccount a = exchange.account("bot-1").orElseThrow();
        Set<List<BetIntent>> distinct = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            BotProfile p = profile("bot-" + i, "overtrader");
            distinct.add(decide(p, new BotAccount(p.botId(), a.balance(), LastOutcome.NONE, 0, Set.of()), exchange.markets()).stream()
                    .map(x -> new BetIntent("same", x.marketId(), x.roundId(), x.direction(), x.stake(), x.leverage(), 0, x.fallback()))
                    .toList());
        }
        assertThat(distinct.size()).isGreaterThan(5);
    }

    @Test
    void seedsAreStableAndSeparateTheParts() {
        assertThat(Seeds.of("a", "b", "c")).isEqualTo(Seeds.of("a", "b", "c"));
        assertThat(Seeds.of("ab", "c", "d")).isNotEqualTo(Seeds.of("a", "bc", "d"));
        assertThat(Seeds.of("a", "b", "c")).isNotEqualTo(Seeds.of("a", "b", "d"));
        // pinned: the seed is part of the contract (a replay elsewhere must reproduce a decision)
        assertThat(Seeds.of("bot", "BTC", "BTC-1h-0")).isEqualTo(PINNED_SEED);
    }

    /**
     * 64-bit FNV-1a over "bot", 0x00, "BTC", 0x00, "BTC-1h-0", 0x00 (offset basis 0xcbf29ce484222325, prime 0x100000001b3),
     * computed independently in Python, as a signed long.
     */
    private static final long PINNED_SEED = -4971895384143417297L;

    @Test
    void closedMarketsAndRoundsAlreadyBetAreSkipped() {
        BotProfile p = profile("bot-1", "overtrader");
        for (int i = 0; i < 40; i++) {
            BotProfile q = profile("bot-" + i, "overtrader");
            BotAccount acc = BotAccount.fresh(q.botId(), PlayAmount.ofWholePlay(100_000));
            for (BetIntent b : decide(q, acc, exchange.markets())) {
                assertThat(b.marketId()).isNotEqualTo("SOL"); // closed
            }
            String btcRound = exchange.markets().stream().filter(m -> m.marketId().equals("BTC")).findFirst().orElseThrow().roundId();
            BotAccount betBtc = new BotAccount(q.botId(), acc.balance(), LastOutcome.NONE, 0, Set.of(btcRound));
            for (BetIntent b : decide(q, betBtc, exchange.markets())) {
                assertThat(b.marketId()).isEqualTo("ETH");
            }
        }
        assertThat(p.botId()).isEqualTo("bot-1");
    }

    @Test
    void aSilentRuleSkipsOrFallsBackByPersona() {
        // momentum-15 with no history is silent; disciplined skips, overtrader falls back
        List<MarketSnapshot> markets = exchange.markets();
        MarketSnapshot btc = markets.stream().filter(m -> m.marketId().equals("BTC")).findFirst().orElseThrow();
        BotAccount acc = BotAccount.fresh("bot-1", PlayAmount.ofWholePlay(100_000));
        BotProfile skip = new BotProfile("bot-1", new Momentum(15), Personas.byId("disciplined"));
        BotProfile fall = new BotProfile("bot-1", new Momentum(15), Personas.byId("overtrader"));
        assertThat(brain.decideMarket(skip, acc, btc, List.of(), Optional.empty())).isEmpty();
        Optional<BetIntent> fb = brain.decideMarket(fall, acc, btc, List.of(), Optional.empty());
        assertThat(fb).isPresent();
        assertThat(fb.get().fallback()).isTrue();
        // with a known majority the fallback follows it
        Optional<BetIntent> withCrowd = brain.decideMarket(fall, acc, btc, List.of(), Optional.of(Direction.DOWN));
        assertThat(withCrowd.orElseThrow().direction()).isEqualTo(Direction.DOWN);
    }

    @Test
    void aSpeakingRuleBetsItsDirection() {
        MarketSnapshot btc = exchange.markets().stream().filter(m -> m.marketId().equals("BTC")).findFirst().orElseThrow();
        BotAccount acc = BotAccount.fresh("bot-1", PlayAmount.ofWholePlay(100_000));
        BotProfile p = new BotProfile("bot-1", new Momentum(3), Personas.byId("disciplined"));
        Optional<BetIntent> up = brain.decideMarket(p, acc, btc, TestBars.of(TestBars.ramp(20, 100, 1)), Optional.empty());
        Optional<BetIntent> down = brain.decideMarket(p, acc, btc, TestBars.of(TestBars.ramp(20, 100, -1)), Optional.empty());
        assertThat(up.orElseThrow().direction()).isEqualTo(Direction.UP);
        assertThat(up.get().fallback()).isFalse();
        assertThat(down.orElseThrow().direction()).isEqualTo(Direction.DOWN);
    }

    @Test
    void severalMarketsShareTheBalance() {
        // 150 PLAY: one minimum bet (100 + fees) fits, a second does not; 250 PLAY fits two
        int maxBetsPoor = 0;
        int maxBetsRicher = 0;
        for (int i = 0; i < 40; i++) {
            BotProfile q = profile("bot-" + i, "overtrader");
            List<BetIntent> poor = decide(q, BotAccount.fresh(q.botId(), PlayAmount.ofWholePlay(150)), exchange.markets());
            List<BetIntent> richer = decide(q, BotAccount.fresh(q.botId(), PlayAmount.ofWholePlay(250)), exchange.markets());
            maxBetsPoor = Math.max(maxBetsPoor, poor.size());
            maxBetsRicher = Math.max(maxBetsRicher, richer.size());
            PlayAmount spent = PlayAmount.ZERO;
            for (BetIntent b : richer) {
                MarketSnapshot m = exchange.markets().stream().filter(x -> x.marketId().equals(b.marketId())).findFirst().orElseThrow();
                spent = spent.plus(b.stake()).plus(m.fees(b.leverage(), b.stake()).total());
            }
            assertThat(spent.compareTo(PlayAmount.ofWholePlay(250))).isLessThanOrEqualTo(0);
        }
        assertThat(maxBetsPoor).isEqualTo(1);
        assertThat(maxBetsRicher).isEqualTo(2);
    }

    @Test
    void theRunnerPlacesIntentsThroughThePortsAndNotTwice() {
        BotRunner runner = new BotRunner(exchange, exchange, exchange, brain);
        BotProfile p = null;
        List<BotRunner.Placed> first = List.of();
        for (int i = 0; i < 20 && first.isEmpty(); i++) { // an overtrader sits a round out one time in twenty
            p = new BotProfile("bot-" + i, new Momentum(3), Personas.byId("overtrader"));
            first = runner.runOnce(p);
        }
        assertThat(first).isNotEmpty().allSatisfy(x -> assertThat(x.result().accepted()).as(x.result().reason()).isTrue());
        // everything it could bet on in these rounds is bet now (overtrader plays up to 3 markets, 2 are open)
        List<BotRunner.Placed> again = runner.runOnce(p);
        List<String> firstRounds = first.stream().map(x -> x.intent().roundId()).toList();
        assertThat(again).noneMatch(x -> firstRounds.contains(x.intent().roundId()));
        assertThat(runner.runOnce(new BotProfile("ghost", new Coin(), Personas.byId("overtrader")))).isEmpty();
    }

    @Test
    void aRefusingGameDoesNotBreakTheRunner() {
        BettingPort refusing = r -> crowdvsmarket.bots.api.BetResult.refused("maintenance");
        BotRunner runner = new BotRunner(exchange, refusing, exchange, brain);
        BotProfile p = new BotProfile("bot-1", new Coin(), Personas.byId("maximalist"));
        for (int i = 0; i < 5; i++) {
            runner.runOnce(p).forEach(x -> assertThat(x.result().accepted()).isFalse());
        }
    }
}
