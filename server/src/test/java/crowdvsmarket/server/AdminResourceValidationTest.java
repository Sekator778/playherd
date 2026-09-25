package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** Wave-1 findings: {@code AdminResource}'s bearer-token check (constant-time behavior — actual
 *  timing is not asserted here, only correctness, see {@code ConstantTimeTokensTest} for the
 *  compare primitive itself) and generic-not-500 handling of a malformed/mistyped request body. */
@QuarkusTest
class AdminResourceValidationTest {

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @Test
    void statusWithoutTokenIsUnauthorized() {
        given().get("/api/admin/status")
                .then().statusCode(401);
    }

    @Test
    void statusWithWrongTokenIsUnauthorized() {
        given().header("Authorization", "Bearer wrong-token")
                .get("/api/admin/status")
                .then().statusCode(401);
    }

    @Test
    void statusWithCorrectTokenSucceeds() {
        given().header("Authorization", "Bearer " + operatorToken)
                .get("/api/admin/status")
                .then().statusCode(200);
    }

    @Test
    void cancelWithTypeMismatchBodyReturns400NotServerErrorOrJavaClassName() {
        // `reason` is a String — an array can never bind to it (Jackson's own message for this
        // names the target Java DTO class, which must never reach the client).
        Response resp = given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + operatorToken)
                .body("{\"reason\": [1,2,3]}")
                .post("/api/admin/rounds/bogus-round-id/cancel");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.asString()).doesNotContain("crowdvsmarket").doesNotContain("com.fasterxml");
    }

    @Test
    void cancelWithMalformedJsonBodyReturns400NotServerError() {
        Response resp = given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + operatorToken)
                .body("{not valid json")
                .post("/api/admin/rounds/bogus-round-id/cancel");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
    }
}
