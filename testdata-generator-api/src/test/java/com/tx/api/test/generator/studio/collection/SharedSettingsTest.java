package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSettingsTest {

    private static RunAuth bearer(String token) {
        return new RunAuth("BEARER", null, null, token, null, null, null, null, null, null, null, null);
    }

    private static final RunAuth NONE = new RunAuth("NONE", null, null, null, null, null, null, null, null, null, null, null);

    private static SharedSettings settings() {
        Map<String, String> collectionHeaders = new LinkedHashMap<>();
        collectionHeaders.put("Accept", "application/json");
        collectionHeaders.put("X-Tenant", "acme");
        return SharedSettings.normalize(new SharedSettings(bearer("{{userToken}}"), collectionHeaders, Map.of(
                "Admin", new SharedSettings.Level(bearer("{{adminToken}}"), Map.of("x-tenant", "admin-tenant")),
                "Public", new SharedSettings.Level(NONE, null))));
    }

    private static StudioTestCase testCase(String group, RunAuth auth, Map<String, String> headers) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId("c1");
        testCase.setTestGroup(group);
        testCase.setAuth(auth);
        testCase.setHeaders(headers);
        return testCase;
    }

    @Test
    void aCaseInheritsFromItsGroupThenTheCollection() {
        SharedSettings settings = settings();

        assertEquals("{{adminToken}}", settings.applyTo(testCase("admin", null, Map.of())).getAuth().token(),
                "the group wins, matched without regard to letter case");
        assertEquals("{{userToken}}", settings.applyTo(testCase("Orders", null, Map.of())).getAuth().token());
        assertEquals("NONE", settings.applyTo(testCase("Public", null, Map.of())).getAuth().type(),
                "a group can turn auth off");
        RunAuth own = bearer("{{mine}}");
        assertSame(own, settings.applyTo(testCase("Admin", own, Map.of())).getAuth(), "the case's own auth wins");
    }

    @Test
    void headersStackCollectionGroupThenCase() {
        StudioTestCase applied = settings().applyTo(testCase("Admin", null, Map.of("ACCEPT", "text/plain")));

        assertEquals(Map.of("x-tenant", "admin-tenant", "ACCEPT", "text/plain"), applied.getHeaders(),
                "the group replaces X-Tenant; the case's Accept replaces the collection's");
        StudioTestCase original = testCase("Admin", null, Map.of());
        settings().applyTo(original);
        assertTrue(original.getHeaders().isEmpty(), "the stored case is unchanged");
        assertNull(original.getAuth());
    }

    @Test
    void refusesSecretsAndBadHeaders() {
        CollectionFormatException secret = assertThrows(CollectionFormatException.class, () -> SharedSettings.normalize(
                new SharedSettings(bearer("real-token"), null, null)));
        assertTrue(secret.getMessage().startsWith("Collection auth:"), secret.getMessage());
        assertThrows(CollectionFormatException.class, () -> SharedSettings.normalize(
                new SharedSettings(null, Map.of("Bad Header", "x"), null)));
        assertThrows(CollectionFormatException.class, () -> SharedSettings.normalize(
                new SharedSettings(null, Map.of("X-A", "1", "x-a", "2"), null)));
    }

    @Test
    void dropsEmptyGroupsAndRoundTripsAsADocument() {
        SharedSettings normalized = SharedSettings.normalize(new SharedSettings(null, null,
                Map.of("Empty", new SharedSettings.Level(null, Map.of()), "Admin", new SharedSettings.Level(NONE, null))));
        assertEquals(java.util.Set.of("Admin"), normalized.groupMap().keySet());
        assertEquals(normalized, SharedSettings.fromDocument(normalized.toDocument()));
    }

    @Test
    void importMergesGroupsByNameAndKeepsWhatTheFileLeavesOut() {
        SharedSettings current = settings();
        SharedSettings incoming = SharedSettings.normalize(new SharedSettings(null, null,
                Map.of("Public", new SharedSettings.Level(bearer("{{publicToken}}"), null))));

        SharedSettings merged = SharedSettings.merge(current, incoming);

        assertEquals("{{userToken}}", merged.auth().token());
        assertEquals("{{publicToken}}", merged.group("public").auth().token());
        assertEquals("{{adminToken}}", merged.group("Admin").auth().token());
        assertEquals("acme", merged.headerMap().get("X-Tenant"));
    }
}
