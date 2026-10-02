package crowdvsmarket.server.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The SHAPE of a JSON value, and how two shapes are compared: what the wire fixtures ({@code
 * web/src/api/wire/*.json}, {@link WireFixtures}) are held against the live answers of the server by. Values are
 * not compared — ids, addresses and timestamps change from run to run — only what a client can depend on: which keys
 * an object has and which JSON type each value is.
 *
 * <ul>
 *   <li>An object's shape is its set of keys, each with the shape of its value; two objects have the same shape when
 *       they have exactly the same keys (recursively) — a key on one side only is a difference.
 *   <li>A scalar's shape is its type: {@code boolean}, {@code number} (an integer and a decimal are both numbers),
 *       {@code string}.
 *   <li><b>{@code null} matches anything.</b> A value that is {@code null} on either side is not compared: the
 *       server writes an absent value as {@code null}, so a field that is a string in one answer and {@code null} in
 *       the next is the same field (contract.ts: "an optional field may be absent OR JSON null").
 *   <li><b>An array's shape is the union of the shapes of its elements</b>: all elements are merged into one shape
 *       (an object key that some elements have and others do not is a key of the merged shape; a value that is a
 *       string in one element and {@code null} in another is a nullable string). An empty array has no element
 *       shape and matches any array — it says nothing, which is why {@link #arraysWithoutAnExample} lists the
 *       arrays of a fixture that are empty everywhere: nothing guards the elements of those.
 * </ul>
 */
public final class WireShape {

    private enum Kind {
        NULL, BOOLEAN, NUMBER, STRING, OBJECT, ARRAY
    }

    private final Set<Kind> kinds = EnumSet.noneOf(Kind.class);
    private final Map<String, WireShape> fields = new TreeMap<>();
    /** The merged shape of the elements; {@code null} while no element has been seen. */
    private WireShape items;

    private WireShape() {
    }

    /** The shape of {@code node}. */
    public static WireShape of(JsonNode node) {
        WireShape shape = new WireShape();
        if (node == null || node.isNull() || node.isMissingNode()) {
            shape.kinds.add(Kind.NULL);
        } else if (node.isBoolean()) {
            shape.kinds.add(Kind.BOOLEAN);
        } else if (node.isNumber()) {
            shape.kinds.add(Kind.NUMBER);
        } else if (node.isTextual()) {
            shape.kinds.add(Kind.STRING);
        } else if (node.isObject()) {
            shape.kinds.add(Kind.OBJECT);
            node.fields().forEachRemaining(entry -> shape.fields.put(entry.getKey(), of(entry.getValue())));
        } else if (node.isArray()) {
            shape.kinds.add(Kind.ARRAY);
            for (JsonNode element : node) {
                WireShape elementShape = of(element);
                shape.items = shape.items == null ? elementShape : merge(shape.items, elementShape);
            }
        } else {
            throw new IllegalArgumentException("not a JSON value: " + node);
        }
        return shape;
    }

    /** The two shapes as one: what a value that may be either of them looks like. */
    static WireShape merge(WireShape a, WireShape b) {
        WireShape merged = new WireShape();
        merged.kinds.addAll(a.kinds);
        merged.kinds.addAll(b.kinds);
        for (Map<String, WireShape> source : List.of(a.fields, b.fields)) {
            source.forEach((key, shape) -> merged.fields.merge(key, shape, WireShape::merge));
        }
        if (a.items != null && b.items != null) {
            merged.items = merge(a.items, b.items);
        } else {
            merged.items = a.items != null ? a.items : b.items;
        }
        return merged;
    }

    private Set<Kind> nonNullKinds() {
        Set<Kind> nonNull = EnumSet.noneOf(Kind.class);
        nonNull.addAll(kinds);
        nonNull.remove(Kind.NULL);
        return nonNull;
    }

    private static String describe(Set<Kind> kinds) {
        List<String> names = new ArrayList<>();
        for (Kind kind : kinds) {
            names.add(kind.name().toLowerCase(java.util.Locale.ROOT));
        }
        return String.join(" or ", names);
    }

    /**
     * What differs between the {@code committed} shape (the fixture file) and the {@code live} one (the server's
     * answer now), one line per difference, each starting with where it is ({@code $.positions[].player.isBot}):
     * a key on one side only, a different JSON type, an object where the other has a scalar. Empty when the two
     * agree.
     */
    public static List<String> differences(WireShape committed, WireShape live) {
        List<String> out = new ArrayList<>();
        compare(committed, live, "$", out);
        return out;
    }

    private static void compare(WireShape committed, WireShape live, String path, List<String> out) {
        Set<Kind> committedKinds = committed.nonNullKinds();
        Set<Kind> liveKinds = live.nonNullKinds();
        if (committedKinds.isEmpty() || liveKinds.isEmpty()) {
            return; // null on a side: it matches whatever the other side has
        }
        if (!committedKinds.equals(liveKinds)) {
            out.add(path + ": the committed file has " + describe(committedKinds) + ", the live answer has "
                    + describe(liveKinds));
            return;
        }
        if (committedKinds.contains(Kind.OBJECT)) {
            Set<String> keys = new TreeSet<>(committed.fields.keySet());
            keys.addAll(live.fields.keySet());
            for (String key : keys) {
                WireShape c = committed.fields.get(key);
                WireShape l = live.fields.get(key);
                if (c == null) {
                    out.add(path + "." + key + ": in the live answer, not in the committed file");
                } else if (l == null) {
                    out.add(path + "." + key + ": in the committed file, not in the live answer");
                } else {
                    compare(c, l, path + "." + key, out);
                }
            }
        }
        if (committedKinds.contains(Kind.ARRAY) && committed.items != null && live.items != null) {
            compare(committed.items, live.items, path + "[]", out);
        }
    }

    /** The paths of the arrays that have no element anywhere (merged over the elements of the arrays around them). */
    public List<String> arraysWithoutAnExample() {
        List<String> out = new ArrayList<>();
        collectEmptyArrays(this, "$", out);
        return out;
    }

    private static void collectEmptyArrays(WireShape shape, String path, List<String> out) {
        if (shape.kinds.contains(Kind.ARRAY) && shape.items == null) {
            out.add(path);
        }
        shape.fields.forEach((key, value) -> collectEmptyArrays(value, path + "." + key, out));
        if (shape.items != null) {
            collectEmptyArrays(shape.items, path + "[]", out);
        }
    }

    /** The paths of the values that are {@code null} everywhere in this shape: no example of what they hold. */
    public List<String> nullOnlyValues() {
        List<String> out = new ArrayList<>();
        collectNullOnly(this, "$", out);
        return out;
    }

    private static void collectNullOnly(WireShape shape, String path, List<String> out) {
        if (shape.nonNullKinds().isEmpty()) {
            out.add(path);
            return;
        }
        shape.fields.forEach((key, value) -> collectNullOnly(value, path + "." + key, out));
        if (shape.items != null) {
            collectNullOnly(shape.items, path + "[]", out);
        }
    }
}
