package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The operator's page and the server info during an outage (review of the core, finding 2). {@code
 * /api/admin/status} read the ledger corrections from the engine and answered 503 {@code ENGINE_NOT_READY} exactly
 * when the operator needed it; nothing said whether the engine was ready; and {@code serverInfo} gained a database
 * read that made it, and the status, answer 500 while the database did not. Now the status answers, with the
 * corrections absent while the engine is not ready and {@code server.engineReady} saying so, and both pages answer
 * while the database does not.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class AdminStatusOutageTest extends LiveGameTestBase {

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @InjectSpy
    EventStore eventStoreSpy;
    @InjectSpy
    Db dbSpy;

    @AfterEach
    void heal() {
        Mockito.reset(eventStoreSpy, dbSpy);
        rebuild.rebuild();
    }

    private Response status() {
        return given().header("Authorization", "Bearer " + operatorToken).get("/api/admin/status");
    }

    private Response serverInfo() {
        return given().contentType("application/json").body("{\"args\":[]}").post("/api/rpc/serverInfo");
    }

    private void theEngineIsNotReady() {
        doThrow(new IllegalStateException("db flapping")).when(eventStoreSpy).replayAfter(anyLong(), any());
        gameService.runExclusively(() -> rebuild.recoverAfterFailure(new IllegalStateException("the settlement failed")));
        assertThat(rebuild.isReady()).isFalse();
    }

    @Test
    void theStatusAnswersWhileTheEngineIsNotReadyWithoutTheCorrectionsAndSaysWhy() {
        Response ready = status();
        assertThat(ready.statusCode()).as(ready.asString()).isEqualTo(200);
        assertThat(ready.jsonPath().getBoolean("server.engineReady")).isTrue();
        assertThat(ready.jsonPath().getList("recentLedgerCorrections")).isNotNull();

        theEngineIsNotReady();

        Response outage = status();
        assertThat(outage.statusCode()).as(outage.asString()).isEqualTo(200);
        assertThat(outage.jsonPath().getBoolean("server.engineReady")).isFalse();
        assertThat(outage.jsonPath().getList("recentLedgerCorrections"))
                .as("read from the engine, so absent while it is not ready").isNull();
        assertThat(outage.jsonPath().getList("recentCancellations")).as("from the database: still there").isNotNull();
        assertThat(outage.jsonPath().getString("server.mode")).isEqualTo("live");
    }

    @Test
    void serverInfoSaysWhetherTheEngineIsReady() {
        assertThat(serverInfo().jsonPath().getBoolean("engineReady")).isTrue();

        theEngineIsNotReady();

        Response outage = serverInfo();
        assertThat(outage.statusCode()).isEqualTo(200);
        assertThat(outage.jsonPath().getBoolean("engineReady")).isFalse();

        Mockito.reset(eventStoreSpy);
        rebuild.rebuild();
        assertThat(serverInfo().jsonPath().getBoolean("engineReady")).isTrue();
    }

    @Test
    void bothPagesAnswerWhileTheDatabaseDoesNot() {
        doThrow(new RuntimeException("db connection failure")).when(dbSpy).read(any());

        Response info = serverInfo();
        Response status = status();

        assertThat(info.statusCode()).as(info.asString()).isEqualTo(200);
        assertThat(info.jsonPath().getString("mode")).isEqualTo("live");
        assertThat(info.jsonPath().getBoolean("engineReady")).isTrue();
        assertThat(status.statusCode()).as(status.asString()).isEqualTo(200);
        assertThat(status.jsonPath().getList("recentCancellations")).as("the database does not answer").isNull();
        assertThat(status.jsonPath().getList("recentLedgerCorrections")).as("the engine does").isNotNull();
    }
}
