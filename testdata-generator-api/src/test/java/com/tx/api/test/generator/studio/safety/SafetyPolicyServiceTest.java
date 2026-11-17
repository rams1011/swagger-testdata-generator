package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.api.ConflictException;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.AddProtectedTargetRequest;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.EnvironmentProtectionView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.SafetySettingsView;
import com.tx.api.test.generator.studio.dto.SaveOperationRuleRequest;
import com.tx.api.test.generator.studio.dto.SetEnvironmentProtectionRequest;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.tenant.AuthService;
import com.tx.api.test.generator.studio.tenant.EntitlementService;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.JdbcTenantStore;
import com.tx.api.test.generator.studio.tenant.LdapAuthenticator;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import com.tx.api.test.generator.studio.tenant.oidc.OidcAuthService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyPolicyServiceTest {

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcStudioStorage storage;
    private StudioProperties properties;
    private SafetyPolicyService safety;
    private AuthSessionView owner;
    private String memberId;

    @BeforeEach
    void setUp() {
        dataSource = memoryDatabase("safety_policy_");
        TestSchema.migrate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbc);
        JdbcTenantStore tenantStore = new JdbcTenantStore(jdbc);
        properties = new StudioProperties();
        properties.setEdition("saas");
        properties.getSaas().setEnabled(true);
        properties.getSafety().setEnabled(true);
        TenantContext.setSaasEnabled(true);
        StudioAuditService audit = new StudioAuditService(storage);
        AuthService authService = new AuthService(properties, provider(TenantStore.class, tenantStore),
                provider(EntitlementService.class,
                        new EntitlementService(properties, provider(TenantStore.class, tenantStore))),
                provider(LdapAuthenticator.class, null),
                new OidcAuthService(properties, tenantStore), audit);
        safety = new SafetyPolicyService(properties, new JdbcSafetyStore(jdbc), tenantStore, storage, audit);

        owner = authService.register(new RegisterRequest("owner@acme.test", "password1234", "Owner", "Acme"));
        memberId = asOwner(() -> authService.addOrgMember(
                new AddMemberRequest("dev@acme.test", "Dev", "password1234", "MEMBER")).accountId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        // A JVM-wide flag: left on, it makes later tests without a workspace fail with "Sign in to continue".
        TenantContext.setSaasEnabled(false);
    }

    @Test
    void organizationsThatExistBeforeTheMigrationStartOff() {
        DriverManagerDataSource upgraded = memoryDatabase("safety_upgrade_");
        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("7").load().migrate();
        new JdbcTemplate(upgraded).update(
                "INSERT INTO td_organization (org_id, slug, name) VALUES ('org-before', 'before', 'Before')");
        TestSchema.migrate(upgraded);
        SafetyPolicy existing = new JdbcSafetyStore(new JdbcTemplate(upgraded)).findPolicy("org-before").orElseThrow();
        assertEquals(SafetyMode.OFF, existing.mode());
        assertEquals(200, existing.maxRequestsPerRun());
        assertEquals(1, existing.maxWriteDataRows());
        assertEquals(SelfApproval.ALLOWED, existing.selfApproval());
    }

    @Test
    void aNewOrganizationStartsInShadow() {
        assertTrue(owner.safetyEnabled(), "the session tells the UI production safety is on");
        asOwner(() -> {
            var summary = safety.summary();
            assertTrue(summary.enabled());
            assertEquals(SafetyMode.SHADOW, summary.mode());
            assertTrue(summary.canManage());
            SafetySettingsView settings = safety.settings();
            assertEquals(0, settings.revision());
            assertEquals(60, settings.maxRequestsPerMinute());
        });
    }

    @Test
    void theFirstTargetMovesAnOrganizationFromOffToShadow() {
        startOff();
        asOwner(() -> {
            assertEquals(SafetyMode.OFF, safety.summary().mode());
            SafetySettingsView first = safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, "Public API"));
            assertEquals(SafetyMode.SHADOW, first.mode());
            assertEquals(SafetyPolicyService.SHADOW_NOTICE, first.notice());

            SafetySettingsView second = safety.addTarget(new AddProtectedTargetRequest("10.20.0.0/16", null, null));
            assertNull(second.notice());

            SafetySettingsView off = safety.updateSettings(new UpdateSafetySettingsRequest(
                    "off", null, null, null, null, null, second.revision()));
            assertEquals(SafetyMode.OFF, off.mode());
            SafetySettingsView third = safety.addTarget(new AddProtectedTargetRequest("*.prod.acme.com", List.of(443), null));
            assertEquals(SafetyMode.OFF, third.mode(), "only the very first target switches Shadow on");
            assertNull(third.notice());
        });
    }

    @Test
    void onlyOrganizationAdminsChangeProductionSafety() {
        String environmentId = asOwner(() -> environment("Production", "https://api.acme.com").getId());
        asMember(() -> {
            assertFalse(safety.summary().canManage());
            assertThrows(ForbiddenException.class, safety::settings);
            assertThrows(ForbiddenException.class,
                    () -> safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, null)));
            assertThrows(ForbiddenException.class, () -> safety.updateSettings(
                    new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, 0L)));
            assertThrows(ForbiddenException.class, () -> safety.setEnvironmentProtection(environmentId,
                    new SetEnvironmentProtectionRequest(true)));
        });
    }

    @Test
    void withTheSwitchOffNothingIsReachable() {
        properties.getSafety().setEnabled(false);
        asOwner(() -> {
            assertFalse(safety.summary().enabled());
            assertThrows(NoSuchElementException.class, safety::settings);
            assertThrows(NoSuchElementException.class,
                    () -> safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, null)));
        });
        properties.getSafety().setEnabled(true);
        properties.setEdition("local");
        assertFalse(safety.summary().enabled(), "safety needs sign-in");
    }

    @Test
    void settingsNeedTheCurrentRevisionAndValidValues() {
        asOwner(() -> {
            assertThrows(IllegalArgumentException.class, () -> safety.updateSettings(
                    new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, null)));
            SafetySettingsView saved = safety.updateSettings(
                    new UpdateSafetySettingsRequest("enforce", 100, 30, 2, true, "never", 0L));
            assertEquals(1, saved.revision());
            assertEquals(SafetyMode.ENFORCE, saved.mode());
            assertEquals(100, saved.maxRequestsPerRun());
            assertEquals(SelfApproval.NEVER, saved.selfApproval());
            assertTrue(saved.requireSecondAdmin());

            assertThrows(ConflictException.class, () -> safety.updateSettings(
                    new UpdateSafetySettingsRequest("shadow", null, null, null, null, null, 0L)));
            for (UpdateSafetySettingsRequest bad : List.of(
                    new UpdateSafetySettingsRequest("loud", null, null, null, null, null, 1L),
                    new UpdateSafetySettingsRequest(null, 0, null, null, null, null, 1L),
                    new UpdateSafetySettingsRequest(null, null, 601, null, null, null, 1L),
                    new UpdateSafetySettingsRequest(null, null, null, 51, null, null, 1L),
                    new UpdateSafetySettingsRequest(null, null, null, null, null, "sometimes", 1L))) {
                assertThrows(IllegalArgumentException.class, () -> safety.updateSettings(bad));
            }
            assertEquals(1, safety.settings().revision(), "refused changes save nothing");
        });
    }

    @Test
    void targetsAndRulesAreChecked() {
        asOwner(() -> {
            SafetySettingsView withTarget = safety.addTarget(
                    new AddProtectedTargetRequest("API.acme.com.", List.of(8443, 443), "Public API"));
            ProtectedTarget target = withTarget.targets().get(0);
            assertEquals("api.acme.com", target.pattern());
            assertEquals(List.of(443, 8443), target.ports());
            assertThrows(IllegalArgumentException.class,
                    () -> safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, null)));
            assertThrows(IllegalArgumentException.class,
                    () -> safety.addTarget(new AddProtectedTargetRequest("https://api.acme.com", null, null)));
            assertThrows(IllegalArgumentException.class,
                    () -> safety.addTarget(new AddProtectedTargetRequest("x.acme.com", null, "n".repeat(201))));

            SafetySettingsView withRule = safety.addRule(rule("treat_as_read", "rest", "post", "/search/", null,
                    target.id(), "Search only reads data", null));
            OperationRule rule = withRule.rules().get(0);
            assertEquals("POST", rule.httpMethod());
            assertEquals("/search", rule.pathTemplate());
            assertEquals(RuleEffect.TREAT_AS_READ, rule.effect());

            for (SaveOperationRuleRequest bad : List.of(
                    rule("treat_as_read", "rest", "POST", "/search", null, target.id(), "Duplicate of the rule", null),
                    rule("block", "rest", "GET", "/export", null, null, "short", null),
                    rule("block", "rest", "GET", "export", null, null, "Heavy export of everything", null),
                    rule("block", "rest", "GET", "/export?all=1", null, null, "Heavy export of everything", null),
                    rule("block", "rest", "GET", "/export", null, "no-such-target", "Heavy export of everything", null),
                    rule("block", "rest", "GET", "/export", null, null, "Heavy export of everything",
                            Instant.now().minus(1, ChronoUnit.DAYS)),
                    rule("treat_as_read", "graphql", null, null, "List Orders", null, "Persisted read query", null),
                    rule("treat_as_read", "grpc", null, null, "ListOrders", null, "Reads the order list", null),
                    rule("allow", "rest", "GET", "/x", null, null, "Unknown effect value", null))) {
                assertThrows(IllegalArgumentException.class, () -> safety.addRule(bad), String.valueOf(bad));
            }
            SafetySettingsView grpc = safety.addRule(rule("treat_as_read", "grpc", null, null,
                    "acme.orders.Orders/ListOrders", null, "Reads the order list", null));
            String grpcId = grpc.rules().stream().filter(r -> r.protocol() == RuleProtocol.GRPC)
                    .findFirst().orElseThrow().id();
            SafetySettingsView changed = safety.updateRule(grpcId, rule("block", "grpc", null, null,
                    "acme.orders.Orders/ListOrders", null, "Too heavy for production", null));
            assertEquals(RuleEffect.BLOCK, changed.rules().stream()
                    .filter(r -> r.id().equals(grpcId)).findFirst().orElseThrow().effect());
            assertEquals(1, safety.deleteRule(grpcId).rules().size());

            SafetySettingsView noTarget = safety.deleteTarget(target.id());
            assertTrue(noTarget.targets().isEmpty());
            assertTrue(noTarget.rules().isEmpty(), "a target's rules go with it");
            assertThrows(NoSuchElementException.class, () -> safety.deleteTarget(target.id()));
        });
    }

    @Test
    void environmentProtectionFollowsTheSwitchAndTheTargets() {
        asOwner(() -> {
            StoredEnvironment production = environment("Production", "https://api.acme.com/v1");
            StoredEnvironment staging = environment("Staging", "https://staging.acme.com");
            String specId = production.getSpecId();

            EnvironmentProtectionView on = safety.setEnvironmentProtection(staging.getId(),
                    new SetEnvironmentProtectionRequest(true));
            assertTrue(on.protectedEnvironment());
            assertTrue(on.effective());
            assertTrue(storage.findEnvironment(staging.getId()).orElseThrow().isProtectedEnvironment());

            safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, null));
            EnvironmentProtectionView matched = view(safety.environmentProtection(specId), production.getId());
            assertFalse(matched.protectedEnvironment());
            assertEquals("api.acme.com", matched.matchedTarget());
            assertTrue(matched.effective());
            assertThrows(IllegalStateException.class, () -> safety.setEnvironmentProtection(production.getId(),
                    new SetEnvironmentProtectionRequest(false)));

            EnvironmentProtectionView off = safety.setEnvironmentProtection(staging.getId(),
                    new SetEnvironmentProtectionRequest(false));
            assertFalse(off.effective());
            assertThrows(IllegalArgumentException.class, () -> safety.setEnvironmentProtection(staging.getId(),
                    new SetEnvironmentProtectionRequest(null)));
            assertThrows(NoSuchElementException.class, () -> safety.setEnvironmentProtection("missing",
                    new SetEnvironmentProtectionRequest(true)));
            return null;
        });
        asMember(() -> assertFalse(safety.summary().canManage()));
    }

    @Test
    void everyChangeIsAudited() {
        asOwner(() -> {
            StoredEnvironment production = environment("Production", "https://api.acme.com");
            safety.updateSettings(new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, 0L));
            SafetySettingsView added = safety.addTarget(new AddProtectedTargetRequest("api.acme.com", null, null));
            safety.addRule(rule("block", "rest", "GET", "/admin/export-all", null, null, "Heavy export of everything", null));
            safety.deleteTarget(added.targets().get(0).id());
            safety.setEnvironmentProtection(production.getId(), new SetEnvironmentProtectionRequest(true));

            List<AuditEventView> events = storage.listAuditEventsForTenant(100);
            for (String type : List.of("SAFETY_POLICY", "PROTECTED_TARGET", "OPERATION_RULE", "ENVIRONMENT")) {
                assertTrue(events.stream().anyMatch(event -> type.equals(event.resourceType())), type);
            }
            AuditEventView mode = events.stream().filter(event -> "SAFETY_POLICY".equals(event.resourceType()))
                    .findFirst().orElseThrow();
            assertTrue(mode.detail().contains("shadow → enforce"), mode.detail());
            assertNotNull(mode.actorLabel());
            return null;
        });
    }

    private void startOff() {
        jdbc.update("""
                INSERT INTO td_safety_policy (org_id, mode, max_requests_per_run, max_requests_per_minute,
                                              max_write_data_rows, require_second_admin, self_approval, revision)
                VALUES (?, 'off', 200, 60, 1, FALSE, 'allowed', 1)
                """, owner.organization().id());
    }

    private StoredEnvironment environment(String name, String baseUrl) {
        String specId = "spec-safety";
        if (storage.findSpec(specId).isEmpty()) {
            StoredSpec spec = new StoredSpec();
            spec.setSpecId(specId);
            spec.setName("Orders");
            spec.setTitle("Orders");
            spec.setLabel("General");
            spec.setBaseUrl("http://localhost:8080");
            storage.saveSpec(spec);
        }
        StoredEnvironment environment = new StoredEnvironment();
        environment.setId(UUID.randomUUID().toString());
        environment.setSpecId(specId);
        environment.setName(name);
        environment.setDocumentJson(CollectionDocuments.toJson(EnvironmentMapper.merge(null, name, baseUrl, "NONE")));
        storage.saveEnvironment(environment);
        return storage.findEnvironment(environment.getId()).orElseThrow();
    }

    private static EnvironmentProtectionView view(List<EnvironmentProtectionView> views, String environmentId) {
        return views.stream().filter(view -> view.environmentId().equals(environmentId)).findFirst().orElseThrow();
    }

    private static SaveOperationRuleRequest rule(String effect, String protocol, String method, String path,
                                                 String operation, String targetId, String reason, Instant expiresAt) {
        return new SaveOperationRuleRequest(effect, protocol, method, path, operation, targetId, reason, expiresAt);
    }

    private <T> T asOwner(Supplier<T> action) {
        return TenantContext.callAs(owner.tenant().id(), owner.account().id(), "owner@acme.test", "OWNER", action);
    }

    private void asOwner(Runnable action) {
        asOwner(() -> {
            action.run();
            return null;
        });
    }

    private void asMember(Runnable action) {
        TenantContext.runAs(owner.tenant().id(), memberId, "dev@acme.test", "MEMBER", action);
    }

    private static DriverManagerDataSource memoryDatabase(String prefix) {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl(prefix + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        return h2;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton(type.getSimpleName(), value);
        }
        return factory.getBeanProvider(type);
    }
}
