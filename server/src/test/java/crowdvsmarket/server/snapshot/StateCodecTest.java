package crowdvsmarket.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerRoster;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F5: the codec carries everything a replay builds. A seeded random game is played through the real applier; its
 * state is encoded, decoded into a fresh hub, and encoded again - the same text, the same digest - and then both hubs play the same
 * further events and must stay the same (a restored state is not only equal, it behaves alike).
 */
class StateCodecTest {

    private static EngineHub freshLike(RandomGame game) {
        EngineHub hub = new EngineHub();
        game.listed.forEach(hub::install);
        return hub;
    }

    @Test
    void aRestoredStateEqualsTheOneItWasTakenFromAndBehavesAlike() {
        int withBets = 0;
        for (long seed = 1; seed <= 40; seed++) {
            RandomGame game = new RandomGame(seed, new EngineHub(), new BetFeed());
            game.play(150 + (int) seed * 12);

            String text = StateCodec.encode(game.hub, game.feed);
            EngineHub restored = freshLike(game);
            BetFeed restoredFeed = new BetFeed();
            StateCodec.decode(text, restored, restoredFeed);

            assertThat(StateCodec.encode(restored, restoredFeed)).as("seed %d: encode(decode(encode(s)))", seed).isEqualTo(text);
            assertThat(StateDigest.of(restored, restoredFeed)).isEqualTo(StateDigest.of(game.hub, game.feed));
            assertThat(restored.oracleSeq()).isEqualTo(game.hub.oracleSeq());
            assertThat(restored.ledger().lastSequence()).isEqualTo(game.hub.ledger().lastSequence());
            if (text.contains("\nbet\t")) {
                withBets++;
            }

            // the same further play on both
            int from = game.log.size();
            game.play(80);
            EventApplier twin = EventApplier.over(restored, restoredFeed);
            for (GameEvent event : game.log.subList(from, game.log.size())) {
                try {
                    twin.apply(event);
                } catch (RuntimeException refused) {
                    // refused by the original too
                }
            }
            assertThat(StateCodec.encode(restored, restoredFeed)).as("seed %d after further play", seed)
                    .isEqualTo(StateCodec.encode(game.hub, game.feed));
        }
        assertThat(withBets).as("the random games leave bets open in most seeds").isGreaterThan(20);
    }

    @Test
    void theTextCarriesTheOddCornersOfAState() {
        // the seeds are fixed: these facts hold for them, and a codec that dropped one would lose it in the round trip above
        String all = "";
        for (long seed = 1; seed <= 40; seed++) {
            RandomGame game = new RandomGame(seed, new EngineHub(), new BetFeed());
            game.play(300);
            all += StateCodec.encode(game.hub, game.feed);
        }
        for (String kind : new String[] {"account", "season", "withdrawal", "deposit", "correction", "pack", "feesPaid", "retired",
                "treasuryDay", "roster", "crowdRound", "hold", "anti", "bet", "window", "closed", "latestEnd", "feedEntry",
                "betRequest"}) {
            assertThat(all).as("a random game reaches a line of kind " + kind).contains("\n" + kind + "\t");
        }
        assertThat(all).contains("\\t").contains("\\n").contains("\\\\").contains("\\N");
        assertThat(all).as("a fixed-payout bet and a bet with stored liquidation parameters").contains("\tFIXED\n");
    }

    @Test
    void anEmptyStateRoundTrips() {
        EngineHub hub = new EngineHub();
        BetFeed feed = new BetFeed();
        String text = StateCodec.encode(hub, feed);
        EngineHub restored = new EngineHub();
        BetFeed restoredFeed = new BetFeed();
        StateCodec.decode(text, restored, restoredFeed);
        assertThat(StateCodec.encode(restored, restoredFeed)).isEqualTo(text);
    }

    @Test
    void aDigestSeesEveryFactAndTheJournalDeterminedOneLeavesTheRosterOut() {
        RandomGame game = new RandomGame(7, new EngineHub(), new BetFeed());
        game.play(200);
        String before = StateCodec.encode(game.hub, game.feed);
        String digest = StateDigest.sha256(before);

        game.hub.roster().register(new PlayerRoster.Entry("p-extra", "xch1extra", "Extra", "HUMAN", null, null));
        String withRoster = StateCodec.encode(game.hub, game.feed);
        assertThat(StateDigest.sha256(withRoster)).as("the roster is part of the digest").isNotEqualTo(digest);
        assertThat(StateDigest.ofJournalDetermined(withRoster, true)).as("but not of the journal-determined one")
                .isEqualTo(StateDigest.ofJournalDetermined(before, true));

        game.hub.raiseOracleSeq(game.hub.oracleSeq() + 1);
        assertThat(StateDigest.sha256(StateCodec.encode(game.hub, game.feed))).as("the oracle counter").isNotEqualTo(StateDigest.sha256(withRoster));
    }

    @Test
    void textThatIsNotAStateOfThisFormatIsRefused() {
        RandomGame game = new RandomGame(3, new EngineHub(), new BetFeed());
        game.play(120);
        String text = StateCodec.encode(game.hub, game.feed);

        assertThatThrownBy(() -> decode(game, text.replaceFirst("format\t1", "format\t2"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> decode(game, text.substring(0, text.lastIndexOf("end\t")))).as("no end line")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode(game, text.replaceFirst("end\t\\d+", "end\t1"))).as("a wrong line count")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("end line");
        assertThatThrownBy(() -> decode(game, text.replace("\nmarket\tT_RA\n", "\nmarket\tGONE\n"))).as("a market the hub lacks")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GONE");
        assertThatThrownBy(() -> decode(game, text.replaceFirst("\noracleSeq", "\nmystery\t1\noracleSeq"))).as("an unknown line")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown");
        assertThatThrownBy(() -> decode(game, text.replaceFirst("\naccount\t", "\naccount\tx\\"))).as("a lone backslash")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode(game, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode(game, "format\t1\n")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void decode(RandomGame game, String text) {
        StateCodec.decode(text, freshLike(game), new BetFeed());
    }

    @Test
    void everyIntervalIsCoveredByTheGame() {
        RandomGame game = new RandomGame(11, new EngineHub(), new BetFeed());
        game.play(400);
        String text = StateCodec.encode(game.hub, game.feed);
        for (Interval interval : Interval.values()) {
            assertThat(text).contains("\t" + interval + "\t");
        }
    }
}
