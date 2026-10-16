package crowdvsmarket.bots.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader (RFC 8259 subset: objects, arrays, strings with the usual escapes, numbers, true/false/null),
 * enough for {@code catalog.json} so the module needs no library. Objects become {@code LinkedHashMap<String, Object>},
 * arrays {@code List<Object>}, numbers {@code Double}, strings {@code String}, booleans {@code Boolean}, null {@code null}.
 */
final class Json {

    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException on malformed input, with the position */
    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("trailing content");
        }
        return v;
    }

    private Object value() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        pos++;
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            if (m.put(key, value()) != null) {
                throw error("duplicate key " + key);
            }
            skipWs();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> l = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            skipWs();
            l.add(value());
            skipWs();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return l;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw error("unexpected character '" + s.charAt(pos) + "'");
        }
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("bad number");
        }
    }

    private Object literal(String word, Object result) {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return result;
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at position " + pos);
    }
}
