package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostingProfileTest {

    @Test
    void acceptsSingleHostingProfile() {
        assertEquals(HostingProfile.LOCAL, HostingProfile.requireExactlyOne(new String[] { "local" }));
        assertEquals(HostingProfile.CLOUD, HostingProfile.requireExactlyOne(new String[] { "cloud", "hosted" }));
    }

    @Test
    void rejectsNone() {
        assertThrows(IllegalStateException.class, () -> HostingProfile.requireExactlyOne(new String[] { "test" }));
        assertThrows(IllegalStateException.class, () -> HostingProfile.requireExactlyOne(new String[] {}));
    }

    @Test
    void rejectsMultipleHostingProfiles() {
        assertThrows(IllegalStateException.class,
                () -> HostingProfile.requireExactlyOne(new String[] { "local", "docker" }));
    }
}
