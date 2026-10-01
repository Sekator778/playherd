package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Defensive reading of Gamma's JSON (docs/research/polymarket_data_api.md §2.3): ids come as strings
 * or numbers; {@code outcomes}, {@code outcomePrices} and {@code clobTokenIds} are JSON <i>inside</i>
 * a string (but may one day be real arrays); booleans and numbers are sometimes absent or {@code
 * null}; {@code endDate} is ISO ({@code 2027-01-01T04:59:00Z}) while {@code gameStartTime} is not
 * ({@code 2026-10-01 18:45:00+00}, a space for the {@code T} and {@code +00} without minutes).
 * Anything unreadable becomes {@code null}/empty, never an exception: one odd market must not stop
 * the listing of the others.
 */
final class GammaJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GammaJson() {
    }

    /** A string or number field as trimmed text; {@code null} when absent, null or blank. */
    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** A boolean field (or the text {@code "true"}/{@code "false"}); {@code defaultValue} otherwise. */
    static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return defaultValue;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isTextual()) {
            String s = v.asText().trim();
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return defaultValue;
    }

    /** A number field (or numeric text) as a double; {@code null} when absent or not a number. */
    static Double number(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            if (v.isNumber()) {
                return v.asDouble();
            }
            if (v.isTextual() && !v.asText().isBlank()) {
                double d = Double.parseDouble(v.asText().trim());
                return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** A list of strings that is either an array or JSON text of an array; empty when it is neither. */
    static List<String> stringList(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        try {
            JsonNode array = v.isTextual() ? JSON.readTree(v.asText()) : v;
            if (array != null && array.isArray()) {
                for (JsonNode item : array) {
                    out.add(item.asText());
                }
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    /** ISO instants and offsets, Gamma's space-separated ones, and plain dates (as the start of that UTC day), in ms. */
    static Long millis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not a plain instant
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not an offset date-time with minutes in the offset
        }
        try {
            String iso = s.replace(' ', 'T');
            if (iso.matches(".*[+-]\\d{2}$")) {
                iso += ":00"; // "+00" → "+00:00"
            }
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not Gamma's spelling either
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
