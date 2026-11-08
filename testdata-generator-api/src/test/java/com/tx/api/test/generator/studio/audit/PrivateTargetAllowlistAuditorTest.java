package com.tx.api.test.generator.studio.audit;

import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateTargetAllowlistAuditorTest {

    private static final String ENVIRONMENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_ID = "33333333-3333-3333-3333-333333333333";

    private StudioStorage storage;
    private StudioProperties properties;
    private PrivateTargetAllowlistAuditor auditor;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("allowlist_" + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        TestSchema.migrate(h2);
        storage = spy(new JdbcStudioStorage(new JdbcTemplate(h2)));
        doReturn(Optional.of(new StudioStorage.EnvironmentOwner(TENANT_ID, "Staging")))
                .when(storage).findEnvironmentOwner(ENVIRONMENT_ID);
        properties = new StudioProperties();
        auditor = new PrivateTargetAllowlistAuditor(properties, storage, new StudioAuditService(storage));
        TenantContext.setSaasEnabled(false);
    }

    private void configure(String... entries) {
        properties.getRun().setPrivateTargetAllowlist(Map.of(ENVIRONMENT_ID, List.of(entries)));
    }

    private List<AuditEventView> events(String tenantId) {
        return TenantContext.callAs(tenantId, null, "OWNER", () -> storage.listAuditEventsForTenant(100));
    }

    @Test
    void nothingIsWrittenWhenThereIsNoAllowlist() {
        auditor.reconcile();
        assertTrue(events(TENANT_ID).isEmpty());
    }

    @Test
    void aNewAllowlistIsAuditedInTheEnvironmentsTenant() {
        configure("10.0.0.5:8080", "orders.internal:8443");

        auditor.reconcile();

        List<AuditEventView> events = events(TENANT_ID);
        assertEquals(1, events.size());
        AuditEventView event = events.get(0);
        assertEquals("ALLOWLIST_CHANGED", event.action().toString());
        assertEquals("OUTBOUND_TARGET", event.resourceType().toString());
        assertEquals(ENVIRONMENT_ID, event.resourceId());
        assertEquals("server configuration", event.actorLabel());
        assertEquals("Private target allowlist for environment Staging changed: added "
                + "[10.0.0.5:8080, orders.internal:8443], removed []", event.detail());
    }

    @Test
    void anUnchangedAllowlistIsNotAuditedAgainEvenIfReorderedOrRecased() {
        configure("10.0.0.5:8080", "orders.internal:8443");
        auditor.reconcile();

        configure(" ORDERS.internal:8443 ", "10.0.0.5:8080");
        auditor.reconcile();

        assertEquals(1, events(TENANT_ID).size());
    }

    @Test
    void addedAndRemovedEntriesAreAudited() {
        configure("10.0.0.5:8080", "orders.internal:8443");
        auditor.reconcile();

        configure("10.0.0.5:8080", "10.0.0.6:8080");
        auditor.reconcile();

        assertTrue(events(TENANT_ID).stream().anyMatch(event -> event.detail()
                .endsWith("added [10.0.0.6:8080], removed [orders.internal:8443]")));
    }

    @Test
    void removingAnEnvironmentFromTheConfigurationIsAuditedAndClearsItsState() {
        configure("10.0.0.5:8080");
        auditor.reconcile();

        properties.getRun().setPrivateTargetAllowlist(Map.of());
        auditor.reconcile();
        auditor.reconcile();

        assertEquals(2, events(TENANT_ID).size());
        assertTrue(events(TENANT_ID).stream().anyMatch(event -> event.detail()
                .endsWith("added [], removed [10.0.0.5:8080]")));
        assertTrue(storage.listPrivateTargetAllowlists().isEmpty());
    }

    @Test
    void anIdWithNoEnvironmentIsAuditedInThePlatformTenant() {
        String unknown = "99999999-9999-9999-9999-999999999999";
        properties.getRun().setPrivateTargetAllowlist(Map.of(unknown, List.of("10.0.0.5:8080")));

        auditor.reconcile();

        List<AuditEventView> events = events(TenantContext.PLATFORM_TENANT_ID);
        assertEquals(1, events.size());
        assertEquals(unknown, events.get(0).resourceId());
    }

    @Test
    void aFailedAuditWriteStopsStartupAndLeavesTheChangePending() {
        StudioStorage failing = mock(StudioStorage.class);
        when(failing.listPrivateTargetAllowlists()).thenReturn(Map.of());
        when(failing.findEnvironmentOwner(anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database down")).when(failing)
                .appendAuditEventForTenant(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        configure("10.0.0.5:8080");

        PrivateTargetAllowlistAuditor failingAuditor =
                new PrivateTargetAllowlistAuditor(properties, failing, new StudioAuditService(failing));

        assertThrows(IllegalStateException.class, failingAuditor::reconcile);
        verify(failing, never()).savePrivateTargetAllowlist(any(), any());
    }

    @Test
    void theAuditEntryIsWrittenBeforeTheStateIsSaved() {
        configure("10.0.0.5:8080");
        auditor.reconcile();
        InOrder order = inOrder(storage);
        order.verify(storage).appendAuditEventForTenant(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        order.verify(storage).savePrivateTargetAllowlist(ENVIRONMENT_ID, "10.0.0.5:8080");
    }
}
