package com.tx.api.test.generator.studio.collection;

import com.google.gson.Gson;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class StudioVariables {

    public static final int MAX_VARIABLES = 200;
    public static final int MAX_VALUE_LENGTH = 10_000;

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$");

    private static final Gson GSON = new Gson();

    private record Document(List<StudioVariable> variables) {
    }

    private StudioVariables() {
    }

    /** Reads a stored {@code {"variables": [...]}} document (global or collection variables). */
    public static List<StudioVariable> fromDocument(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        Document document = GSON.fromJson(json, Document.class);
        return document == null || document.variables() == null ? List.of() : document.variables();
    }

    public static String toDocument(List<StudioVariable> variables) {
        return GSON.toJson(new Document(variables == null ? List.of() : variables));
    }

    /** {@code base} with each of {@code incoming} added, or replacing the row with the same key in place. */
    public static List<StudioVariable> merge(List<StudioVariable> base, List<StudioVariable> incoming) {
        Map<String, StudioVariable> byKey = new LinkedHashMap<>();
        for (List<StudioVariable> rows : List.of(base == null ? List.<StudioVariable>of() : base,
                incoming == null ? List.<StudioVariable>of() : incoming)) {
            for (StudioVariable variable : rows) {
                if (variable != null && StringUtils.isNotBlank(variable.key())) {
                    byKey.put(variable.key(), variable);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Trims keys, drops blank rows, and clears secret values so they are never stored.
     * A secret may keep a {@code ${ENV_VAR}} placeholder (from an imported file); any other secret value is rejected.
     */
    public static List<StudioVariable> normalize(List<StudioVariable> variables, String scope) {
        if (variables == null || variables.isEmpty()) {
            return new ArrayList<>();
        }
        List<StudioVariable> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StudioVariable variable : variables) {
            if (variable == null) {
                continue;
            }
            String key = StringUtils.trimToEmpty(variable.key());
            String value = StringUtils.defaultString(variable.value());
            if (key.isEmpty()) {
                if (StringUtils.isNotBlank(value)) {
                    throw new CollectionFormatException(scope + " variable needs a name");
                }
                continue;
            }
            if (!KEY.matcher(key).matches()) {
                throw new CollectionFormatException(scope + " variable '" + key
                        + "' must start with a letter or _ and use only letters, digits, and _");
            }
            if (!seen.add(key)) {
                throw new CollectionFormatException(scope + " variable '" + key + "' is defined more than once");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new CollectionFormatException(scope + " variable '" + key + "' is longer than "
                        + MAX_VALUE_LENGTH + " characters");
            }
            if (variable.secret()) {
                if (StringUtils.isNotBlank(value) && !ENV_PLACEHOLDER.matcher(value.trim()).matches()) {
                    throw new CollectionFormatException(scope + " variable '" + key
                            + "' is secret; its value must be a ${ENV_VAR} placeholder, not the secret itself");
                }
                result.add(new StudioVariable(key, null, true));
            } else {
                result.add(new StudioVariable(key, value, false));
            }
        }
        if (result.size() > MAX_VARIABLES) {
            throw new CollectionFormatException(scope + " has more than " + MAX_VARIABLES + " variables");
        }
        return result;
    }

    /** Values a run may use without browser input: non-secret variables only. */
    public static Map<String, String> storedValues(List<StudioVariable> variables) {
        Map<String, String> values = new LinkedHashMap<>();
        if (variables == null) {
            return values;
        }
        for (StudioVariable variable : variables) {
            if (variable != null && !variable.secret() && StringUtils.isNotBlank(variable.key())) {
                values.put(variable.key(), StringUtils.defaultString(variable.value()));
            }
        }
        return values;
    }

    public static Set<String> secretKeys(List<StudioVariable> variables) {
        Set<String> keys = new LinkedHashSet<>();
        if (variables == null) {
            return keys;
        }
        for (StudioVariable variable : variables) {
            if (variable != null && variable.secret() && StringUtils.isNotBlank(variable.key())) {
                keys.add(variable.key());
            }
        }
        return keys;
    }

    public static Set<String> keys(List<StudioVariable> variables) {
        Set<String> keys = new LinkedHashSet<>();
        if (variables == null) {
            return keys;
        }
        for (StudioVariable variable : variables) {
            if (variable != null && StringUtils.isNotBlank(variable.key())) {
                keys.add(variable.key());
            }
        }
        return keys;
    }

    /** Secret values become {@code ${STUDIO_VAR_NAME}} so CI reads them from its own environment. */
    public static List<StudioVariable> forExport(List<StudioVariable> variables) {
        List<StudioVariable> result = new ArrayList<>();
        if (variables == null) {
            return result;
        }
        for (StudioVariable variable : variables) {
            if (variable == null) {
                continue;
            }
            result.add(variable.secret()
                    ? new StudioVariable(variable.key(), envPlaceholder(variable.key()), true)
                    : variable);
        }
        return result;
    }

    public static String envPlaceholder(String key) {
        return "${STUDIO_VAR_" + StringUtils.defaultString(key).toUpperCase(Locale.ROOT) + "}";
    }
}
