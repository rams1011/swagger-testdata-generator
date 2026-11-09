package com.tx.api.test.generator.studio.collection;

import com.google.gson.Gson;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.service.CaseAuth;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Auth and headers a collection, and each case group in it, hands down to its cases (like Postman collection and
 * folder settings). Auth: the case's own, else its group's, else the collection's, else the environment's run auth
 * ({@code null} at a level means "inherit"; type {@code NONE} sends none). Headers: collection, then group, then the
 * case's own; a header the case sets wins, whatever the letter case.
 */
public record SharedSettings(RunAuth auth, Map<String, String> headers, Map<String, Level> groups) {

    public static final int MAX_HEADERS = 100;
    public static final int MAX_GROUPS = 200;
    public static final int MAX_VALUE_LENGTH = 10_000;

    private static final Gson GSON = new Gson();

    /** One level's settings: a group's. */
    public record Level(RunAuth auth, Map<String, String> headers) {
        boolean empty() {
            return auth == null && (headers == null || headers.isEmpty());
        }
    }

    public static SharedSettings empty() {
        return new SharedSettings(null, Map.of(), Map.of());
    }

    public Map<String, String> headerMap() {
        return headers == null ? Map.of() : headers;
    }

    public Map<String, Level> groupMap() {
        return groups == null ? Map.of() : groups;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return auth == null && headerMap().isEmpty() && groupMap().isEmpty();
    }

    /** Checked copy: secret auth fields must be {{variables}}, header names valid, empty groups dropped. */
    public static SharedSettings normalize(SharedSettings settings) {
        if (settings == null) {
            return empty();
        }
        Map<String, Level> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        settings.groupMap().forEach((name, level) -> {
            String group = StringUtils.trimToEmpty(name);
            if (group.isEmpty() || level == null) {
                return;
            }
            Level normalized = new Level(auth("Group '" + group + "'", level.auth()),
                    headers("Group '" + group + "'", level.headers()));
            if (!normalized.empty()) {
                if (groups.containsKey(group)) {
                    throw new CollectionFormatException("Group '" + group + "' has settings more than once");
                }
                groups.put(group, normalized);
            }
        });
        if (groups.size() > MAX_GROUPS) {
            throw new CollectionFormatException("Settings for more than " + MAX_GROUPS + " groups");
        }
        return new SharedSettings(auth("Collection", settings.auth()), headers("Collection", settings.headers()),
                new LinkedHashMap<>(groups));
    }

    private static RunAuth auth(String where, RunAuth auth) {
        try {
            return CaseAuth.normalize(auth);
        } catch (IllegalArgumentException exception) {
            throw new CollectionFormatException(where + " auth: " + exception.getMessage());
        }
    }

    private static Map<String, String> headers(String where, Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        Map<String, Boolean> seen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((rawName, rawValue) -> {
            String name = StringUtils.trimToEmpty(rawName);
            if (name.isEmpty()) {
                return;
            }
            String problem = HeaderRules.problem(name, rawValue);
            if (problem != null) {
                throw new CollectionFormatException(where + " " + problem);
            }
            if (seen.put(name, true) != null) {
                throw new CollectionFormatException(where + " header '" + name + "' is set more than once");
            }
            String value = StringUtils.defaultString(rawValue);
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new CollectionFormatException(where + " header '" + name + "' is longer than "
                        + MAX_VALUE_LENGTH + " characters");
            }
            result.put(name, value);
        });
        if (result.size() > MAX_HEADERS) {
            throw new CollectionFormatException(where + " has more than " + MAX_HEADERS + " headers");
        }
        return result;
    }

    /** The settings of {@code group}, matched without regard to letter case; null when it has none. */
    public Level group(String group) {
        if (StringUtils.isBlank(group)) {
            return null;
        }
        String wanted = group.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Level> entry : groupMap().entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The case as it runs: inherited auth when it has none of its own, shared headers under its own. */
    public StudioTestCase applyTo(StudioTestCase testCase) {
        if (testCase == null || isEmpty()) {
            return testCase;
        }
        Level group = group(testCase.getTestGroup());
        RunAuth inherited = group != null && group.auth() != null ? group.auth() : auth;
        Map<String, String> shared = new LinkedHashMap<>(headerMap());
        if (group != null && group.headers() != null) {
            group.headers().forEach((name, value) -> {
                shared.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
                shared.put(name, value);
            });
        }
        Map<String, String> own = testCase.getHeaders() == null ? Map.of() : testCase.getHeaders();
        own.keySet().forEach(name -> shared.keySet().removeIf(existing -> existing.equalsIgnoreCase(name)));
        boolean addsAuth = testCase.getAuth() == null && inherited != null;
        if (!addsAuth && shared.isEmpty()) {
            return testCase;
        }
        StudioTestCase copy = EnvironmentOverlay.copy(testCase);
        if (addsAuth) {
            copy.setAuth(inherited);
        }
        if (!shared.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(shared);
            merged.putAll(own);
            copy.setHeaders(merged);
        }
        return copy;
    }

    /**
     * {@code base} with what {@code incoming} sets: its collection auth and headers when present, and its groups by
     * name. Used by collection import, so importing one plan doesn't wipe other groups' settings.
     */
    public static SharedSettings merge(SharedSettings base, SharedSettings incoming) {
        if (incoming == null) {
            return base == null ? empty() : base;
        }
        SharedSettings current = base == null ? empty() : base;
        Map<String, Level> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        groups.putAll(current.groupMap());
        groups.putAll(incoming.groupMap());
        return new SharedSettings(
                incoming.auth() != null ? incoming.auth() : current.auth(),
                incoming.headerMap().isEmpty() ? current.headerMap() : incoming.headerMap(),
                new LinkedHashMap<>(groups));
    }

    public static SharedSettings fromDocument(String json) {
        if (StringUtils.isBlank(json)) {
            return empty();
        }
        SharedSettings parsed = GSON.fromJson(json, SharedSettings.class);
        return parsed == null ? empty() : parsed;
    }

    public String toDocument() {
        return GSON.toJson(this);
    }
}
