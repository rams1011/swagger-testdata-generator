package com.tx.api.test.generator.studio.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exactly one Spring profile among {@code local}, {@code docker}, {@code datacenter}, or {@code cloud}
 * must be active for the API process.
 */
public enum HostingProfile {
    LOCAL("local"),
    DOCKER("docker"),
    DATACENTER("datacenter"),
    CLOUD("cloud");

    private static final Set<String> WIRE_VALUES = Set.of(
            LOCAL.wireValue,
            DOCKER.wireValue,
            DATACENTER.wireValue,
            CLOUD.wireValue);

    private final String wireValue;

    HostingProfile(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static HostingProfile requireExactlyOne(String[] activeProfiles) {
        List<HostingProfile> matched = new ArrayList<>();
        if (activeProfiles != null) {
            for (String raw : activeProfiles) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String name = raw.trim().toLowerCase(Locale.ROOT);
                if (!WIRE_VALUES.contains(name)) {
                    continue;
                }
                matched.add(valueOf(name.toUpperCase(Locale.ROOT)));
            }
        }
        if (matched.isEmpty()) {
            throw new IllegalStateException(hostingProfileError(
                    "none active",
                    activeProfiles));
        }
        if (matched.size() > 1) {
            throw new IllegalStateException(hostingProfileError(
                    "multiple active: " + matched,
                    activeProfiles));
        }
        return matched.get(0);
    }

    private static String hostingProfileError(String reason, String[] activeProfiles) {
        String active = activeProfiles == null || activeProfiles.length == 0
                ? "(empty)"
                : String.join(", ", activeProfiles);
        return "Exactly one hosting profile is required (local, docker, datacenter, or cloud) — "
                + reason
                + ". Active Spring profiles: "
                + active
                + ". Set SPRING_PROFILES_ACTIVE to one of: local, docker, datacenter, cloud.";
    }
}
