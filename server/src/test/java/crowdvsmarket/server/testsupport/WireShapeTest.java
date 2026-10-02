package crowdvsmarket.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How two JSON values are compared by shape — the rule the wire fixtures are guarded by ({@link WireShape}): the
 * same keys, recursively, and the same JSON type per key; {@code null} matches anything; an array is the union of
 * its elements; values never matter.
 */
class WireShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static List<String> differences(String committed, String live) {
        return WireShape.differences(WireShape.of(json(committed)), WireShape.of(json(live)));
    }

    @Test
    void sameKeysAndTypesWithOtherValuesAreTheSameShape() {
        assertThat(differences("""
                {"id": "a1", "atMs": 1759400000000, "price": "2500.000", "ok": true, "pct": 0.5,
                 "who": {"name": "Ann", "tags": ["x", "y"]}}""", """
                {"id": "zz", "atMs": 7, "price": "0.1", "ok": false, "pct": 12,
                 "who": {"name": "Bob", "tags": ["only"]}}""")).isEmpty();
    }

    @Test
    void anIntegerAndADecimalAreBothNumbers() {
        assertThat(differences("{\"a\": 1}", "{\"a\": 1.5}")).isEmpty();
    }

    @Test
    void aKeyOnOneSideOnlyIsADifferenceSaidWithItsPath() {
        assertThat(differences("{\"a\": {\"b\": 1, \"c\": 2}}", "{\"a\": {\"b\": 1}}"))
                .containsExactly("$.a.c: in the committed file, not in the live answer");
        assertThat(differences("{\"a\": {\"b\": 1}}", "{\"a\": {\"b\": 1, \"d\": \"new\"}}"))
                .containsExactly("$.a.d: in the live answer, not in the committed file");
    }

    @Test
    void aDifferentTypeIsADifference() {
        assertThat(differences("{\"a\": \"1\"}", "{\"a\": 1}"))
                .containsExactly("$.a: the committed file has string, the live answer has number");
        assertThat(differences("{\"a\": {\"b\": 1}}", "{\"a\": [1]}"))
                .containsExactly("$.a: the committed file has object, the live answer has array");
        assertThat(differences("{\"a\": true}", "{\"a\": \"true\"}")).hasSize(1);
    }

    @Test
    void nullMatchesAnythingOnEitherSide() {
        assertThat(differences("{\"a\": null, \"b\": \"x\"}", "{\"a\": \"now a string\", \"b\": null}")).isEmpty();
        assertThat(differences("{\"a\": null}", "{\"a\": {\"deep\": [1, 2]}}")).isEmpty();
        assertThat(differences("{\"a\": [null, \"x\"]}", "{\"a\": [\"y\"]}")).as("a nullable string element").isEmpty();
    }

    @Test
    void anArrayIsTheUnionOfItsElements() {
        // some elements have the key, others do not: the key is one of the merged shape
        assertThat(differences("{\"xs\": [{\"a\": 1}, {\"a\": 2, \"b\": \"x\"}]}",
                "{\"xs\": [{\"a\": 9, \"b\": \"y\"}]}")).isEmpty();
        // a difference inside an element is found, at the path of the element
        assertThat(differences("{\"xs\": [{\"a\": 1}]}", "{\"xs\": [{\"a\": \"one\"}]}"))
                .containsExactly("$.xs[].a: the committed file has number, the live answer has string");
        assertThat(differences("{\"xs\": [{\"a\": 1}]}", "{\"xs\": [{\"a\": 1, \"z\": 0}]}"))
                .containsExactly("$.xs[].z: in the live answer, not in the committed file");
        // arrays of arrays
        assertThat(differences("[[1, 2], [3]]", "[[\"a\"]]")).containsExactly("$[][]: the committed file has number, "
                + "the live answer has string");
    }

    @Test
    void anEmptyArraySaysNothingAndMatchesAnyArray() {
        assertThat(differences("{\"xs\": []}", "{\"xs\": [{\"a\": 1}]}")).isEmpty();
        assertThat(differences("{\"xs\": [{\"a\": 1}]}", "{\"xs\": []}")).isEmpty();
        assertThat(differences("{\"xs\": []}", "{\"xs\": {\"a\": 1}}"))
                .containsExactly("$.xs: the committed file has array, the live answer has object");
    }

    @Test
    void theRootIsComparedToo() {
        assertThat(differences("[1]", "{\"a\": 1}")).containsExactly("$: the committed file has array, the live "
                + "answer has object");
        assertThat(differences("{}", "{}")).isEmpty();
        assertThat(differences("\"text\"", "\"other\"")).isEmpty();
    }

    @Test
    void everyDifferenceIsReportedNotOnlyTheFirst() {
        List<String> differences = differences("{\"a\": 1, \"b\": \"x\", \"c\": {\"d\": 1}}",
                "{\"a\": \"1\", \"c\": {\"e\": 1}, \"f\": true}");

        assertThat(differences).containsExactlyInAnyOrder(
                "$.a: the committed file has number, the live answer has string",
                "$.b: in the committed file, not in the live answer",
                "$.c.d: in the committed file, not in the live answer",
                "$.c.e: in the live answer, not in the committed file",
                "$.f: in the live answer, not in the committed file");
    }

    @Test
    void theArraysAndNullsThatHaveNoExampleAreListed() {
        WireShape shape = WireShape.of(json("""
                {"full": [{"a": 1}], "empty": [], "nested": [{"inner": [], "n": null, "s": "x"}], "nothing": null}"""));

        assertThat(shape.arraysWithoutAnExample()).containsExactlyInAnyOrder("$.empty", "$.nested[].inner");
        assertThat(shape.nullOnlyValues()).containsExactlyInAnyOrder("$.nested[].n", "$.nothing");
    }
}
