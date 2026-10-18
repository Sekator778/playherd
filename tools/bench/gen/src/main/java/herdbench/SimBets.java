package herdbench;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.util.concurrent.atomic.AtomicLong;

import static herdbench.Common.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/** S-A, bet intake (design §3): open model, one placeBet per arriving virtual user, a different (player, row) pair each time.
 *  bench.shape=spread  A1: rows round-robin over all markets x series, N per row stays small
 *  bench.shape=hot     A2: every bet on one row (bench.hotrow, default BTC:HOURLY), both sides 50/50
 *  bench.shape=hourly  A1 on the 18 HOURLY rows only (the 15-minute rows get no bets, so their settlement burst is absent)
 *  bench.shape=other   S-B probe: spread over every row except bench.hotrow (the settling one)
 *  bench.ladder="rate:sec,rate:sec" (default 100:60); bench.warmup seconds of the first step are for the report to drop (informational).
 *  bench.players = how many seeded sessions to use (default all in the file up to 1e6); stake bench.stake (PLAY), leverage bench.leverage.
 *  Output: simulation.log of Gatling (per-request start/end for HdrHistogram, SimLogHdr) and <rd>.counts.tsv with results by class and second. */
public class SimBets extends Simulation {
    private static final String SHAPE = prop("shape", "spread");
    private static final String LADDER = prop("ladder", "100:60");
    private static final String STAKE = prop("stake", "100.000");
    private static final int LEVERAGE = Integer.parseInt(prop("leverage", "2"));
    private static final String[] COOKIES = loadCookies(Integer.parseInt(prop("players", "1000000")));
    private static final String HOT = prop("hotrow", "BTC:HOURLY");
    private static final AtomicLong COUNTER = new AtomicLong();

    /** index of the rows this shape bets on, in the probe's current row array */
    private static int[] eligibleRows() {
        Row[] rows = Rounds.rows;
        int[] tmp = new int[rows.length];
        int n = 0;
        for (int i = 0; i < rows.length; i++) {
            boolean hot = rows[i].key().equals(HOT);
            if (SHAPE.equals("hot") ? hot : SHAPE.equals("other") ? !hot : SHAPE.equals("hourly") ? rows[i].interval().equals("HOURLY") : true) tmp[n++] = i;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }
    private static volatile int[] eligible = new int[0];
    private static volatile Row[] eligibleFor = null;

    private final ChainBuilder bet = exec(session -> {
        // eligible rows are recomputed when the probe has replaced the row array
        Row[] rows = Rounds.rows;
        int[] el = eligible;
        if (rows != eligibleFor) { el = eligibleRows(); eligible = el; eligibleFor = rows; }
        if (el.length == 0) return session.set("skip", true);
        long c = COUNTER.getAndIncrement();
        int slot = (int) (c % el.length);
        int rowIdx = el[slot];
        if (rowIdx >= Rounds.roundIds.length || rowIdx >= Rounds.closesMs.length) return session.set("skip", true); // arrays replaced by the probe a moment ago
        long pairIndex = c / el.length;
        String cookie = COOKIES[(int) (pairIndex % COOKIES.length)];
        long h = c * 0x9E3779B97F4A7C15L;
        String dir = ((h >>> 40) & 1) == 0 ? "UP" : "DOWN";
        String body = "{\"args\":[{\"roundId\":\"" + Rounds.idAt(rowIdx, System.currentTimeMillis()) + "\",\"direction\":\"" + dir + "\",\"leverage\":" + LEVERAGE
                + ",\"stake\":\"" + STAKE + "\"}]}";
        Stats.arrived();
        return session.set("skip", false).set("cookie", "herd_session=" + cookie).set("pb", body);
    }).doIf(session -> !session.getBoolean("skip")).then(
            exec(http("placeBet").post("/api/rpc/placeBet").header("Cookie", "#{cookie}").body(StringBody("#{pb}"))
                    .check(responseTimeInMillis().saveAs("rt"), status().saveAs("st"), bodyString().saveAs("rb"), status().is(200)))
                    .exec(session -> {
                        Stats.record(Stats.classify(session.getInt("st"), session.getString("rb")), session.getInt("rt"));
                        return session;
                    })
    ).doIf(session -> session.getBoolean("skip")).then(exec(session -> { Stats.record("skipped_no_row", 0); return session; }));

    private final ScenarioBuilder bets = scenario("bets").exec(bet);
    private final ScenarioBuilder probe = scenario("probe").exec(probeChain());

    {
        int total = ladderSeconds(LADDER);
        setUp(
                probe.injectOpen(constantUsersPerSec(1).during(total + 10)),
                bets.injectOpen(ladder(LADDER))
        ).protocols(protocol());
    }

    @Override
    public void before() { Stats.t0Ms = System.currentTimeMillis(); }

    @Override
    public void after() { Stats.write(prop("rf", "/results"), prop("rd", "simbets")); }
}
