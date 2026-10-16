package crowdvsmarket.bots.catalog;

import crowdvsmarket.bots.persona.Personas;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The composition of the bot population as data ({@code catalog.json} on the classpath): which bots exist, with which
 * rule, parameters and persona. Loaded and validated up front, so a typo in the file fails at start, not in a round.
 */
public final class Catalog {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final String RESOURCE = "/catalog.json";

    private final List<CatalogEntry> bots;

    private Catalog(List<CatalogEntry> bots) {
        this.bots = List.copyOf(bots);
    }

    /** The catalog shipped in the module. */
    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses and validates a catalog document.
     *
     * @throws IllegalArgumentException on malformed JSON, a wrong shape, a duplicate id or name, an unknown strategy or
     *                                  persona, or parameters the strategy rejects; the message names the entry
     */
    public static Catalog parse(String json) {
        Object root = Json.parse(json);
        Map<String, Object> doc = asObject(root, "catalog");
        if (!Double.valueOf(1).equals(doc.get("version"))) {
            throw new IllegalArgumentException("unsupported catalog version: " + doc.get("version"));
        }
        List<Object> raw = asArray(doc.get("bots"), "bots");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("catalog has no bots");
        }
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> b = asObject(raw.get(i), "bots[" + i + "]");
            String id = string(b, "catalogId", i);
            String where = "bots[" + i + "] (" + id + ")";
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException(where + ": catalogId must match " + ID);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(where + ": duplicate catalogId");
            }
            String name = string(b, "name", i);
            if (name.isBlank() || !names.add(name)) {
                throw new IllegalArgumentException(where + ": name must be non-blank and unique: \"" + name + "\"");
            }
            String persona = string(b, "persona", i);
            if (!Personas.ids().contains(persona)) {
                throw new IllegalArgumentException(where + ": unknown persona " + persona + " (known: " + Personas.ids() + ")");
            }
            Map<String, Double> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : asObject(b.get("params"), where + ".params").entrySet()) {
                if (!(e.getValue() instanceof Double d)) {
                    throw new IllegalArgumentException(where + ": parameter " + e.getKey() + " must be a number");
                }
                params.put(e.getKey(), d);
            }
            CatalogEntry entry = new CatalogEntry(id, name, string(b, "strategy", i), params, persona);
            try {
                entry.signalStrategy();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(where + ": " + ex.getMessage(), ex);
            }
            entries.add(entry);
        }
        return new Catalog(entries);
    }

    public List<CatalogEntry> bots() {
        return bots;
    }

    public Optional<CatalogEntry> byId(String catalogId) {
        return bots.stream().filter(e -> e.catalogId().equals(catalogId)).findFirst();
    }

    private static String string(Map<String, Object> o, String key, int index) {
        if (!(o.get(key) instanceof String s)) {
            throw new IllegalArgumentException("bots[" + index + "]: \"" + key + "\" must be a string");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o, String what) {
        if (!(o instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object o, String what) {
        if (!(o instanceof List<?>)) {
            throw new IllegalArgumentException(what + " must be an array");
        }
        return (List<Object>) o;
    }
}
