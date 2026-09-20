package com.tx.api.test.generator.studio.collection;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnvironmentScope {

    public static final String EXTENSION = "x-testdata-environments";

    private EnvironmentScope() {
    }

    public static boolean visible(List<String> environments, String selectedName) {
        if (environments == null || environments.isEmpty()) {
            return true;
        }
        if (StringUtils.isBlank(selectedName)) {
            return true;
        }
        String needle = selectedName.trim();
        for (String name : environments) {
            if (name != null && needle.equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    public static List<String> rename(List<String> names, String from, String to) {
        if (names == null || names.isEmpty() || StringUtils.isBlank(from) || StringUtils.isBlank(to)) {
            return normalize(names);
        }
        List<String> next = new ArrayList<>();
        for (String name : names) {
            next.add(matchesIgnoreCase(name, from) ? to.trim() : name);
        }
        return normalize(next);
    }

    public static List<String> normalize(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            if (StringUtils.isNotBlank(name)) {
                unique.putIfAbsent(key(name), name.trim());
            }
        }
        return unique.isEmpty() ? null : List.copyOf(unique.values());
    }

    public static List<String> fromExtension(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.isNotBlank(item.toString())) {
                    names.add(item.toString().trim());
                }
            }
            return normalize(names);
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("[")) {
            return normalize(List.of(text.replace("[", "").replace("]", "").split(",")));
        }
        return normalize(List.of(text));
    }

    public static String label(List<String> environments) {
        List<String> names = normalize(environments);
        if (names == null) {
            return "all environments";
        }
        return String.join(", ", names);
    }

    public static boolean matchesIgnoreCase(String left, String right) {
        return StringUtils.isNotBlank(left)
                && StringUtils.isNotBlank(right)
                && left.trim().equalsIgnoreCase(right.trim());
    }

    public static String key(String name) {
        return StringUtils.defaultString(name).trim().toLowerCase(Locale.ROOT);
    }
}
