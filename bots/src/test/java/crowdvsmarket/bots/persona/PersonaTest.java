package crowdvsmarket.bots.persona;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Personas keep the game's rules (min stake, balance, leverage cap, stake tiers) and behave as documented. */
class PersonaTest {

    private static final MarketSpec BTC = MarketSpec.BTC;
    private static final MarketSnapshot SHORT = snapshot(BTC, Interval.HOURLY); // cap 1200
    private static final MarketSnapshot LONG = snapshot(BTC, Interval.DAILY); // cap 500
    private static final MarketSpec LOW = MarketSpec.of("LOW", 4).withEconomy(4, 4, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));

    /** A bot id whose constant stake is 100 PLAY (so 1.5x is still in the full-cap tier). */
    private static final String BOT_100 = botWithStake(100);

    private static String botWithStake(long play) {
        for (int i = 0; ; i++) {
            String id = "bot" + i;
            if (100 + 50L * Math.floorMod(id.hashCode(), 4) == play) {
                return id;
            }
        }
    }

    private static MarketSnapshot snapshot(MarketSpec spec, Interval interval) {
        return new MarketSnapshot(spec, interval, spec.id() + "-r1", 0, 180_000, true, LedgerBook.MIN_STAKE);
    }

    private static BotAccount account(String id, long balancePlay, LastOutcome o, int streak) {
        return new BotAccount(id, PlayAmount.ofWholePlay(balancePlay), o, streak, Set.of());
    }

    @Test
    void everyPersonaKeepsTheRulesOverManyDraws() {
        for (String id : Personas.ids()) {
            Persona p = Personas.byId(id);
            for (MarketSnapshot m : List.of(SHORT, LONG, snapshot(LOW, Interval.HOURLY))) {
                for (long balance : new long[] {105, 150, 400, 1_000, 5_000, 1_000_000}) {
                    for (LastOutcome o : LastOutcome.values()) {
                        for (long seed = 0; seed < 60; seed++) {
                            BotAccount a = account("bot" + seed, balance, o, o == LastOutcome.NONE ? 0 : 1 + (int) (seed % 5));
                            Optional<Sizing> s = p.size(m, a, new SplittableRandom(seed));
                            if (s.isEmpty()) {
                                // only allowed when even the minimum stake with fees does not fit
                                PlayAmount min = m.minStake();
                                assertThat(min.plus(m.fees(2, min).total()).compareTo(a.balance())).as(id).isGreaterThan(0);
                                continue;
                            }
                            Sizing z = s.get();
                            assertThat(z.stake().compareTo(m.minStake())).as(id + " stake >= min").isGreaterThanOrEqualTo(0);
                            assertThat(z.stake().mojos() % PlayAmount.ofWholePlay(10).mojos()).as(id + " round stake").isZero();
                            assertThat(z.leverage()).as(id + " leverage").isBetween(2, m.maxLeverage(z.stake()));
                            PlayAmount total = z.stake().plus(m.fees(z.leverage(), z.stake()).total());
                            assertThat(total.compareTo(a.balance())).as(id + " stake + fees within balance")
                                    .isLessThanOrEqualTo(0);
                            EconomyRules.requireLeverageAllowed(m.spec(), m.interval(), z.leverage(), z.stake());
                        }
                    }
                }
            }
        }
    }

    @Test
    void aBotThatCannotAffordTheMinimumDoesNotBet() {
        for (String id : Personas.ids()) {
            assertThat(Personas.byId(id).size(SHORT, account("b", 100, LastOutcome.NONE, 0), new SplittableRandom(1))).as(id).isEmpty();
        }
    }

    @Test
    void largerStakesFaceLowerLeverageCaps() {
        // house money after a win stakes 1.5x: 250 -> 375 falls into the cap/2 tier (250 < stake <= 500)
        Persona p = Personas.byId("house-money");
        for (long seed = 0; seed < 200; seed++) {
            BotAccount a = account("bot-" + seed, 100_000, LastOutcome.WIN, 3);
            Sizing z = p.size(SHORT, a, new SplittableRandom(seed)).orElseThrow();
            assertThat(z.leverage()).isLessThanOrEqualTo(EconomyRules.maxLeverage(BTC, Interval.HOURLY, z.stake()));
            if (z.stake().compareTo(PlayAmount.ofWholePlay(250)) > 0) {
                assertThat(z.leverage()).isLessThanOrEqualTo(600);
            }
        }
    }

    @Test
    void maximalistTakesTheCapOfItsTier() {
        Persona p = Personas.byId("maximalist");
        Sizing z = p.size(SHORT, account("m", 100_000, LastOutcome.NONE, 0), new SplittableRandom(3)).orElseThrow();
        assertThat(z.leverage()).isEqualTo(SHORT.maxLeverage(z.stake())).isEqualTo(1200);
        Sizing l = p.size(LONG, account("m", 100_000, LastOutcome.NONE, 0), new SplittableRandom(3)).orElseThrow();
        assertThat(l.leverage()).isEqualTo(500);
    }

    @Test
    void cautiousStaysBetween2And5() {
        Persona p = Personas.byId("cautious");
        for (long seed = 0; seed < 300; seed++) {
            Sizing z = p.size(SHORT, account("c", 100_000, LastOutcome.NONE, 0), new SplittableRandom(seed)).orElseThrow();
            assertThat(z.leverage()).isBetween(2, 5);
            assertThat(z.stake().compareTo(PlayAmount.ofWholePlay(100))).isGreaterThanOrEqualTo(0);
            assertThat(z.stake().compareTo(PlayAmount.ofWholePlay(200))).isLessThanOrEqualTo(0);
        }
    }

    @Test
    void lossChaserRaisesLeverageAfterALossHouseMoneyAfterAWin() {
        assertThat(meanLeverage("loss-chaser", LastOutcome.LOSS, 3)).isGreaterThan(meanLeverage("loss-chaser", LastOutcome.WIN, 3) * 1.3);
        assertThat(meanLeverage("loss-chaser", LastOutcome.LOSS, 3)).isGreaterThan(meanLeverage("loss-chaser", LastOutcome.NONE, 0) * 1.3);
        assertThat(meanLeverage("house-money", LastOutcome.WIN, 3)).isGreaterThan(meanLeverage("house-money", LastOutcome.LOSS, 3) * 1.3);
        // and the other way round: no effect
        assertThat(meanLeverage("house-money", LastOutcome.LOSS, 3)).isBetween(
                meanLeverage("house-money", LastOutcome.NONE, 0) * 0.7, meanLeverage("house-money", LastOutcome.NONE, 0) * 1.3);
    }

    @Test
    void houseMoneyStakesMoreAfterAWin() {
        Persona p = Personas.byId("house-money");
        BotAccount base = account(BOT_100, 100_000, LastOutcome.NONE, 0);
        BotAccount won = account(BOT_100, 100_000, LastOutcome.WIN, 1);
        PlayAmount usual = p.size(SHORT, base, new SplittableRandom(1)).orElseThrow().stake();
        PlayAmount after = p.size(SHORT, won, new SplittableRandom(1)).orElseThrow().stake();
        assertThat(after.mojos()).isEqualTo(usual.mojos() * 3 / 2);
    }

    @Test
    void marketPickingFollowsThePersona() {
        List<MarketCandidate> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MarketSpec spec = MarketSpec.of("M" + i, 40);
            five.add(new MarketCandidate(snapshot(spec, Interval.HOURLY), i * 0.001));
        }
        BotAccount a = account("x", 10_000, LastOutcome.NONE, 0);
        int[] rounds = new int[1];
        for (String id : Personas.ids()) {
            AbstractPersona p = (AbstractPersona) Personas.byId(id);
            int played = 0;
            for (long seed = 0; seed < 2000; seed++) {
                List<MarketCandidate> picked = p.pickMarkets(five, a, new SplittableRandom(seed));
                assertThat(picked.size()).isLessThanOrEqualTo(p.marketsPerRound());
                assertThat(picked.stream().map(c -> c.market().marketId()).distinct().count()).isEqualTo(picked.size());
                if (!picked.isEmpty()) {
                    played++;
                    assertThat(picked.size()).isEqualTo(p.marketsPerRound());
                }
            }
            assertThat(played / 2000.0).as(id + " bet probability").isBetween(p.betProbability() - 0.05, p.betProbability() + 0.05);
            assertThat(p.pickMarkets(List.of(), a, new SplittableRandom(1))).isEmpty();
            rounds[0]++;
        }
        assertThat(rounds[0]).isEqualTo(7);
        assertThat(((AbstractPersona) Personas.byId("overtrader")).marketsPerRound()).isEqualTo(3);
        assertThat(((AbstractPersona) Personas.byId("occasional")).marketsPerRound()).isEqualTo(1);
    }

    @Test
    void movingMarketsArePickedMoreOften() {
        MarketCandidate calm = new MarketCandidate(snapshot(MarketSpec.of("CALM", 40), Interval.HOURLY), 0.0);
        MarketCandidate wild = new MarketCandidate(snapshot(MarketSpec.of("WILD", 40), Interval.HOURLY), 0.01);
        Persona p = Personas.byId("occasional");
        BotAccount a = account("x", 10_000, LastOutcome.NONE, 0);
        int wildPicks = 0;
        int calmPicks = 0;
        for (long seed = 0; seed < 20_000; seed++) {
            for (MarketCandidate c : p.pickMarkets(List.of(calm, wild), a, new SplittableRandom(seed))) {
                if (c == wild) {
                    wildPicks++;
                } else {
                    calmPicks++;
                }
            }
        }
        // weights 4 : 1
        assertThat((double) wildPicks / calmPicks).isBetween(3.4, 4.7);
    }

    @Test
    void silentRulePolicies() {
        assertThat(Personas.byId("disciplined").noSignalPolicy()).isEqualTo(NoSignalPolicy.SKIP);
        assertThat(Personas.byId("overtrader").noSignalPolicy()).isEqualTo(NoSignalPolicy.FALLBACK);
    }

    private static double meanLeverage(String persona, LastOutcome o, int streak) {
        Persona p = Personas.byId(persona);
        double sum = 0;
        int n = 4000;
        for (long seed = 0; seed < n; seed++) {
            BotAccount a = account(BOT_100, 10_000_000, o, streak);
            sum += p.size(SHORT, a, new SplittableRandom(seed)).orElseThrow().leverage();
        }
        return sum / n;
    }
}
