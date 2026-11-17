package com.tx.api.test.generator.studio.safety;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Safety enums travel and are stored in lower case ({@code treat_as_read}); unknown values are a 400. */
final class Wire {

    private Wire() {
    }

    static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants()).map(Wire::name).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(field + " must be one of: " + allowed);
    }
}
