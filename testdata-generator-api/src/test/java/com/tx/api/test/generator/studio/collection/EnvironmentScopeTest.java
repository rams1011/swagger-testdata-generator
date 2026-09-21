package com.tx.api.test.generator.studio.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentScopeTest {

    @Test
    void emptyMembershipIsVisibleEverywhere() {
        assertTrue(EnvironmentScope.visible(null, "int"));
        assertTrue(EnvironmentScope.visible(List.of(), "prod"));
        assertFalse(EnvironmentScope.visible(List.of("int"), null));
        assertFalse(EnvironmentScope.visible(List.of("int"), " "));
        assertTrue(EnvironmentScope.assigned(List.of("int")));
        assertFalse(EnvironmentScope.assigned(List.of()));
    }

    @Test
    void namedMembershipIsCaseInsensitive() {
        assertTrue(EnvironmentScope.visible(List.of("int"), "INT"));
        assertFalse(EnvironmentScope.visible(List.of("int"), "prod"));
    }

    @Test
    void normalizeDropsBlanksAndDuplicates() {
        assertNull(EnvironmentScope.normalize(null));
        assertNull(EnvironmentScope.normalize(List.of(" ", "")));
        assertEquals(List.of("int", "prod"), EnvironmentScope.normalize(List.of(" int ", "prod", "INT")));
        assertEquals("all environments", EnvironmentScope.label(null));
        assertEquals("int, prod", EnvironmentScope.label(List.of("int", "prod")));
    }

    @Test
    void renameMovesMembershipToTheNewName() {
        assertEquals(List.of("dev"), EnvironmentScope.rename(List.of("local"), "local", "dev"));
        assertEquals(List.of("dev", "prod"), EnvironmentScope.rename(List.of("LOCAL", "prod"), "local", "dev"));
        assertEquals(List.of("int"), EnvironmentScope.rename(List.of("int"), "local", "dev"));
        assertNull(EnvironmentScope.rename(null, "local", "dev"));
        assertEquals(List.of("local"), EnvironmentScope.rename(List.of("local"), "local", " "));
    }
}
