package crowdvsmarket.bots.persona;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The parameter variants used by sweeps ({@code loss-chaser@step/cap}, ...) and the legacy personas; none is in the catalog. */
class PersonaVariantsTest {

    private static final MarketSnapshot SHORT = new MarketSnapshot(MarketSpec.BTC, Interval.HOURLY, "BTC-r1", 0, 180_000, true,
            LedgerBook.MIN_STAKE);

    private static BotAccount account(LastOutcome o, int streak) {
        return new BotAccount("bot", PlayAmount.ofWholePlay(100_000), o, streak, Set.of());
    }

    private static double meanLeverage(Persona p, BotAccount a) {
        double sum = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            sum += p.leverage(SHORT, PlayAmount.ofWholePlay(100), a, new SplittableRandom(i));
        }
        return sum / n;
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        BotAccount lost3 = account(LastOutcome.LOSS, 3);
        assertThat(meanLeverage(Personas.byId("loss-chaser@0.5/3"), lost3)).isEqualTo(meanLeverage(Personas.byId("loss-chaser"), lost3));
        assertThat(meanLeverage(Personas.byId("maximalist@1.0"), lost3)).isEqualTo(1200.0);
    }

    @Test
    void aBiggerStepAndCapRaiseTheLeverageAfterLosses() {
        BotAccount lost3 = account(LastOutcome.LOSS, 3);
        BotAccount lost5 = account(LastOutcome.LOSS, 5);
        double base = meanLeverage(Personas.byId("loss-chaser"), account(LastOutcome.WIN, 4));
        double dflt = meanLeverage(Personas.byId("loss-chaser"), lost3);
        assertThat(dflt / base).isBetween(2.1, 2.6); // 1 + 0.5 * 3 = 2.5, less where the market's cap clamps
        assertThat(meanLeverage(Personas.byId("loss-chaser@1.0/3"), lost3)).isGreaterThan(dflt * 1.4);
        assertThat(meanLeverage(Personas.byId("loss-chaser@0.5/5"), lost5)).isGreaterThan(meanLeverage(Personas.byId("loss-chaser"), lost5));
    }

    @Test
    void houseMoneyVariantRaisesTheStakeAfterAWin() {
        BotAccount won = account(LastOutcome.WIN, 1);
        PlayAmount normal = Personas.byId("house-money").stake(SHORT, won, new SplittableRandom(1));
        PlayAmount doubled = Personas.byId("house-money@0.5/3/2.0").stake(SHORT, won, new SplittableRandom(1));
        assertThat(doubled.mojos() * 3).isEqualTo(normal.mojos() * 4); // 2.0 against 1.5
    }

    @Test
    void aMaximalistShareScalesTheCapAndStaysAboveTwo() {
        BotAccount fresh = account(LastOutcome.NONE, 0);
        PlayAmount hundred = PlayAmount.ofWholePlay(100);
        assertThat(Personas.byId("maximalist@0.5").leverage(SHORT, hundred, fresh, new SplittableRandom(1))).isEqualTo(600);
        assertThat(Personas.byId("maximalist@0.0001").leverage(SHORT, hundred, fresh, new SplittableRandom(1))).isEqualTo(2);
    }

    @Test
    void variantsAndLegacyPersonasAreNotCatalogIds() {
        assertThat(Personas.ids()).noneMatch(id -> id.contains("@") || id.startsWith("legacy"));
        assertThat(Personas.byId("legacy-mixed").noSignalPolicy()).isEqualTo(NoSignalPolicy.FALLBACK);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Personas.byId("loss-chaser@x"));
    }
}
