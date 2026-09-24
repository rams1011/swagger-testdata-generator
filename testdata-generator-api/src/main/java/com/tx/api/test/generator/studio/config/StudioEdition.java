package com.tx.api.test.generator.studio.config;

import java.util.Locale;

public enum StudioEdition {
    LOCAL,
    DATACENTER,
    SAAS;

    public static StudioEdition from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "local", "desktop", "offline" -> LOCAL;
            case "datacenter", "data-center", "dc", "onprem", "on-prem", "enterprise" -> DATACENTER;
            case "saas", "hosted", "cloud" -> SAAS;
            default -> throw new IllegalArgumentException(
                    "Unknown testdata.edition '" + raw + "'. Use local, datacenter, or saas.");
        };
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
