package herdbench;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.util.concurrent.ThreadLocalRandom;

import static herdbench.Common.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/** S-C, clients polling (design §3): open model, one request per arriving virtual user, as a tab that polls every ~2 s would
 *  send it: the mix of bench.methods (default markets / priceSeries / myPositions in equal thirds), each with one of the seeded sessions.
 *  bench.sessions = how many distinct seeded sessions take part (1e3 ... 1e6, the file must hold that many);
 *  bench.ladder="rate:sec,..." total requests per second (1500..2000 per 1000 tabs). For rates above what one Gatling
 *  process holds under 60 % of its cores, wrk2 (wrk/herd.lua) does the same mix. Counts by class and second go to <rd>.counts.tsv. */
public class SimPoll extends Simulation {
    private static final String LADDER = prop("ladder", "1000:60");
    private static final String[] COOKIES = loadCookies(Integer.parseInt(prop("sessions", "1000")));
    private static final String[] METHODS = prop("methods", "markets,priceSeries,myPositions").split(","); // serverInfo alone = the generator self-check

    private final ScenarioBuilder poll = scenario("poll")
            .exec(session -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                int m = r.nextInt(METHODS.length);
                String body = METHODS[m].equals("priceSeries") ? "{\"args\":[\"BTC\"]}" : "{\"args\":[]}";
                Stats.arrived();
                return session.set("meth", METHODS[m]).set("cookie", "herd_session=" + COOKIES[r.nextInt(COOKIES.length)]).set("pb", body);
            })
            .exec(http("poll_#{meth}").post("/api/rpc/#{meth}").header("Cookie", "#{cookie}").body(StringBody("#{pb}"))
                    .check(responseTimeInMillis().saveAs("rt"), status().saveAs("st"), status().is(200)))
            .exec(session -> { Stats.record(Stats.classify(session.getInt("st"), null) + "_" + session.getString("meth"), session.getInt("rt")); return session; });
    private final ScenarioBuilder probe = scenario("probe").exec(probeChain());

    {
        int total = ladderSeconds(LADDER);
        setUp(
                probe.injectOpen(constantUsersPerSec(1).during(total + 10)),
                poll.injectOpen(ladder(LADDER))
        ).protocols(protocol());
    }

    @Override
    public void before() { Stats.t0Ms = System.currentTimeMillis(); }

    @Override
    public void after() { Stats.write(prop("rf", "/results"), prop("rd", "simpoll")); }
}
