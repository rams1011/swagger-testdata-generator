package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.api.AuthController;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.CreateTenantRequest;
import com.tx.api.test.generator.studio.dto.GrantWorkspaceAccessRequest;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.MemberView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.UpdateSubscriptionRequest;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantIsolationTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;
    private AuthService authService;
    private StudioProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("saas_tenant_" + System.nanoTime()),
                "sa",
                "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        try (Connection connection = dataSource.getConnection();
             Reader schema = new InputStreamReader(
                     new ClassPathResource("ddl/schema.sql").getInputStream(), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, schema);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbcTemplate);
        properties = new StudioProperties();
        properties.setEdition("saas");
        properties.getSaas().setEnabled(true);
        TenantContext.setSaasEnabled(true);
        FixedProvider<TenantStore> stores = new FixedProvider<>(new JdbcTenantStore(jdbcTemplate));
        authService = new AuthService(properties, stores,
                new FixedProvider<>(new EntitlementService(properties, stores)),
                new FixedProvider<>(null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        TenantContext.setSaasEnabled(false);
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void specsAreNotVisibleAcrossWorkspaces() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(acme.tenant().id(), () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            assertEquals(1, storage.listSpecs().size());
        });
        TenantContext.runAs(globex.tenant().id(), () -> {
            assertTrue(storage.listSpecs().isEmpty());
            assertTrue(storage.findSpec("spec-acme").isEmpty());
            storage.saveSpec(spec("spec-globex", "employee-service"));
            assertEquals("employee-service", storage.listSpecs().get(0).getName());
        });
        TenantContext.runAs(acme.tenant().id(), () -> {
            assertEquals(1, storage.listSpecs().size());
            assertEquals("spec-acme", storage.listSpecs().get(0).getSpecId());
            assertTrue(storage.findSpec("spec-globex").isEmpty());
        });
    }

    @Test
    void foreignTenantCannotDeletePlansCasesOrEnvironments() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));
        String planId = "plan-acme";
        String caseId = "case-acme";
        String environmentId = "env-acme";

        TenantContext.runAs(acme.tenant().id(), () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            StudioTestPlan plan = new StudioTestPlan();
            plan.setId(planId);
            plan.setSpecId("spec-acme");
            plan.setName("Default");
            storage.savePlan(plan);
            StudioTestCase testCase = new StudioTestCase();
            testCase.setId(caseId);
            testCase.setSpecId("spec-acme");
            testCase.setPlanId(planId);
            testCase.setOperationKey("GET /employees");
            testCase.setTestName("list employees");
            testCase.setMethod("GET");
            testCase.setRawPath("/employees");
            testCase.setEndpoint("/employees");
            testCase.setStatusCode(200);
            testCase.setEnabled("Y");
            storage.saveTestCase(testCase);
            StoredEnvironment environment = new StoredEnvironment();
            environment.setId(environmentId);
            environment.setSpecId("spec-acme");
            environment.setName("local");
            environment.setDocumentJson("{}");
            storage.saveEnvironment(environment);
        });

        TenantContext.runAs(globex.tenant().id(), () -> {
            assertThrows(NoSuchElementException.class, () -> storage.deleteSpec("spec-acme"));
            assertThrows(NoSuchElementException.class, () -> storage.deletePlan(planId));
            assertThrows(NoSuchElementException.class, () -> storage.deleteTestCase(caseId));
            assertThrows(NoSuchElementException.class, () -> storage.deleteEnvironment(environmentId));
        });

        TenantContext.runAs(acme.tenant().id(), () -> {
            assertTrue(storage.findSpec("spec-acme").isPresent());
            assertTrue(storage.findPlan(planId).isPresent());
            assertTrue(storage.findTestCase(caseId).isPresent());
            assertTrue(storage.findEnvironment(environmentId).isPresent());
        });
    }

    @Test
    void saveTestCaseRecordsAuditFields() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        TenantContext.runAs(owner.tenant().id(), owner.account().id(), owner.account().email(), "OWNER", () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            StudioTestCase testCase = new StudioTestCase();
            testCase.setId("case-audit");
            testCase.setSpecId("spec-acme");
            testCase.setPlanId("plan-audit");
            testCase.setOperationKey("GET /employees");
            testCase.setTestName("list employees");
            testCase.setMethod("GET");
            testCase.setRawPath("/employees");
            testCase.setEndpoint("/employees");
            testCase.setStatusCode(200);
            testCase.setEnabled("Y");
            storage.savePlan(plan("plan-audit", "spec-acme"));
            storage.saveTestCase(testCase);
            StudioTestCase saved = storage.findTestCase("case-audit").orElseThrow();
            assertEquals(owner.account().id(), saved.getCreatedByAccountId());
            assertEquals("qa@acme.test", saved.getCreatedByLabel());
            assertEquals(owner.account().id(), saved.getUpdatedByAccountId());
            assertTrue(saved.getCreatedAt() != null);
            assertTrue(saved.getUpdatedAt() != null);

            saved.setTestName("list employees v2");
            storage.saveTestCase(saved);
            StudioTestCase updated = storage.findTestCase("case-audit").orElseThrow();
            assertEquals("list employees v2", updated.getTestName());
            assertEquals(owner.account().id(), updated.getCreatedByAccountId());
            assertEquals("qa@acme.test", updated.getCreatedByLabel());
            assertEquals(owner.account().id(), updated.getUpdatedByAccountId());
        });
    }

    @Test
    void runHistoryIsNotVisibleAcrossWorkspaces() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));
        String runId = "run-acme";
        String resultId = "result-acme";

        TenantContext.runAs(acme.tenant().id(), () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            storage.saveRun(run(runId, "spec-acme", resultId));
            assertTrue(storage.findRun(runId).isPresent());
            assertTrue(storage.findRunResult(runId, resultId).isPresent());
        });
        TenantContext.runAs(globex.tenant().id(), () -> {
            assertTrue(storage.findRun(runId).isEmpty());
            assertTrue(storage.findRunResult(runId, resultId).isEmpty());
            assertThrows(NoSuchElementException.class, () -> storage.deleteRun(runId));
        });
        TenantContext.runAs(acme.tenant().id(), () -> {
            assertTrue(storage.findRun(runId).isPresent());
            storage.deleteRun(runId);
            assertTrue(storage.findRun(runId).isEmpty());
        });
    }

    @Test
    void healthIsPublicWhenLoginIsRequired() throws Exception {
        TenantFilter filter = new TenantFilter(properties, authService, new EntitlementService(
                properties, new FixedProvider<>(new JdbcTenantStore(new JdbcTemplate(dataSource)))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    @Test
    void loginIsPublicEvenWithExistingSessionToken() throws Exception {
        var session = authService.register(new RegisterRequest("login@example.com", "password1", "Login", "Org", "Ws", null));
        TenantFilter filter = new TenantFilter(properties, authService, new EntitlementService(
                properties, new FixedProvider<>(new JdbcTenantStore(new JdbcTemplate(dataSource)))));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Authorization", "Bearer " + session.token());
        request.addHeader(TenantFilter.WORKSPACE_HEADER, "some-other-workspace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    @Test
    void foreignWorkspaceHeaderIsRejected() throws Exception {
        var session = authService.register(new RegisterRequest("header@example.com", "password1", "Header", "Org", "Ws", null));
        TenantFilter filter = new TenantFilter(properties, authService, new EntitlementService(
                properties, new FixedProvider<>(new JdbcTenantStore(new JdbcTemplate(dataSource)))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/specs");
        request.addHeader("Authorization", "Bearer " + session.token());
        request.addHeader(TenantFilter.TENANT_HEADER, "some-other-workspace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void h2ConsoleIsRejectedWhenLoginIsEnabled() throws Exception {
        TenantFilter filter = new TenantFilter(properties, authService, new EntitlementService(
                properties, new FixedProvider<>(new JdbcTenantStore(new JdbcTemplate(dataSource)))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/h2-console");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void loginReturnsOnlyTheCallersWorkspaces() {
        authService.register(new RegisterRequest("one@example.com", "password1", "One", "One Org", "One", null));
        var two = authService.login(new LoginRequest("one@example.com", "password1", null));
        assertEquals("one", two.tenant().slug());
        assertEquals(1, two.tenants().size());
    }

    @Test
    void addedMemberSeesTheSameWorkspaceSpecs() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(owner.tenant().id(), owner.account().id(), "OWNER", () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            MemberView orgMember = authService.addOrgMember(
                    new AddMemberRequest("dev@acme.test", "Acme Dev", "password1", "MEMBER"));
            MemberView teammate = authService.grantWorkspaceAccess(
                    new GrantWorkspaceAccessRequest(orgMember.accountId(), "MEMBER"));
            assertEquals("dev@acme.test", teammate.email());
            assertEquals("MEMBER", teammate.role());
            assertEquals(2, authService.listMembers().size());
        });
        TenantContext.runAs(globex.tenant().id(), globex.account().id(), "OWNER", () -> {
            storage.saveSpec(spec("spec-globex", "employee-service"));
        });

        var teammate = authService.login(new LoginRequest("dev@acme.test", "password1", owner.tenant().id()));
        assertEquals(owner.tenant().id(), teammate.tenant().id());
        TenantContext.runAs(teammate.tenant().id(), () -> {
            assertEquals(1, storage.listSpecs().size());
            assertEquals("spec-acme", storage.listSpecs().get(0).getSpecId());
            assertTrue(storage.findSpec("spec-globex").isEmpty());
        });
    }

    @Test
    void existingAccountCanJoinAnotherWorkspaceWithoutANewPassword() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(acme.tenant().id(), acme.account().id(), "OWNER", () -> {
            authService.addOrgMember(new AddMemberRequest("qa@globex.test", null, null, "MEMBER"));
            MemberView invited = authService.grantWorkspaceAccess(
                    new GrantWorkspaceAccessRequest(globex.account().id(), "ADMIN"));
            assertEquals(globex.account().id(), invited.accountId());
            assertEquals("ADMIN", invited.role());
        });

        var switched = authService.login(new LoginRequest("qa@globex.test", "password1", acme.tenant().id()));
        assertEquals(acme.tenant().id(), switched.tenant().id());
        assertEquals("ADMIN", switched.tenant().role());
        assertEquals(2, switched.tenants().size());
    }

    @Test
    void memberCannotManageMembersAndLastOwnerCannotBeRemoved() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        TenantContext.runAs(owner.tenant().id(), owner.account().id(), "OWNER", () -> {
            MemberView dev = authService.addOrgMember(
                    new AddMemberRequest("dev@acme.test", "Dev", "password1", "MEMBER"));
            authService.grantWorkspaceAccess(new GrantWorkspaceAccessRequest(dev.accountId(), "MEMBER"));
            ForbiddenException lastOwner = org.junit.jupiter.api.Assertions.assertThrows(
                    ForbiddenException.class, () -> authService.removeMember(owner.account().id()));
            assertTrue(lastOwner.getMessage().contains("at least one owner"));
        });

        var member = authService.login(new LoginRequest("dev@acme.test", "password1", owner.tenant().id()));
        TenantContext.runAs(member.tenant().id(), member.account().id(), "MEMBER", () -> {
            org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, authService::listMembers);
            org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, () ->
                    authService.addOrgMember(new AddMemberRequest("other@acme.test", "Other", "password1", "MEMBER")));
        });
    }

    @Test
    void registerSeparatesOrganizationAndWorkspaceNames() {
        var customer = authService.register(
                new RegisterRequest("split@acme.test", "password1", "Pat", "Acme Corp", "Payment QA", null));
        assertEquals("Acme Corp", customer.organization().name());
        assertEquals("Payment QA", customer.tenant().name());
    }

    @Test
    void legacyRegisterUsesWorkspaceFieldForOrganizationOnly() {
        var customer = authService.register(new RegisterRequest("legacy@acme.test", "password1", "Pat", "Acme"));
        assertEquals("Acme", customer.organization().name());
        assertEquals("Default", customer.tenant().name());
    }

    @Test
    void registerCreatesAnOrganizationTrialThatIsImmediatelyWritable() {
        var customer = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        assertTrue(customer.tenant().ready());
        assertEquals("trial", customer.subscription().plan());
        assertEquals("trial", customer.subscription().status());
        assertTrue(customer.subscription().writable());
        assertEquals(1, customer.subscription().workspaceLimit());
        assertEquals(2, customer.subscription().seatLimit());
        assertEquals(customer.organization().id(), customer.subscription().orgId());
        assertThrows(ForbiddenException.class, () ->
                authService.createWorkspace(customer.token(), new CreateTenantRequest("Second", null)));
    }

    @Test
    void trialSeatLimitAppliesAcrossTheOrganization() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        TenantContext.runAs(owner.tenant().id(), owner.account().id(), "OWNER", () -> {
            authService.addOrgMember(new AddMemberRequest("dev@acme.test", "Dev", "password1", "MEMBER"));
            ForbiddenException denied = assertThrows(ForbiddenException.class, () ->
                    authService.addOrgMember(new AddMemberRequest("other@acme.test", "Other", "password1", "MEMBER")));
            assertTrue(denied.getMessage().contains("seat"));
        });
    }

    @Test
    void saasAdminManagesOrganizationSubscriptions() {
        properties.getSaas().setAdminEmail("saas.admin@local.test");
        properties.getSaas().setAdminPassword("saas-admin-1");
        assertEquals("saas.admin@local.test", authService.ensureSaasAdmin());

        var customer = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        assertFalse(customer.saasAdmin());

        var admin = authService.login(new LoginRequest("saas.admin@local.test", "saas-admin-1", null));
        assertTrue(admin.saasAdmin());
        assertTrue(admin.tenant().ready());

        TenantContext.runAs(admin.tenant().id(), admin.account().id(), "OWNER", () -> {
            var listed = authService.listSubscriptions().stream()
                    .filter(item -> item.orgId().equals(customer.organization().id()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("trial", listed.plan());
            assertEquals("qa@acme.test", listed.ownerEmail());
            var suspended = authService.updateSubscription(
                    customer.organization().id(), new UpdateSubscriptionRequest(false, null, null));
            assertFalse(suspended.writable());
            assertEquals("suspended", suspended.status());
        });

        var locked = authService.login(new LoginRequest("qa@acme.test", "password1", customer.tenant().id()));
        assertFalse(locked.tenant().ready());
        assertFalse(locked.subscription().writable());

        TenantContext.runAs(customer.tenant().id(), customer.account().id(), "OWNER", () -> {
            assertThrows(ForbiddenException.class, authService::listSubscriptions);
        });
    }

    @Test
    void blankEditionIsDatacenter() {
        StudioProperties props = new StudioProperties();
        assertEquals("datacenter", props.editionName());
        assertTrue(props.isAuthEnabled());
        assertFalse(props.isBillingEnabled());
        props.setEdition("saas");
        assertEquals("saas", props.editionName());
        assertTrue(props.isBillingEnabled());
        props.setEdition("local");
        assertEquals("local", props.editionName());
        assertFalse(props.isAuthEnabled());
    }

    @Test
    void datacenterLoginWorksWithoutAPlan() throws Exception {
        properties.setEdition("datacenter");
        properties.getSaas().setEnabled(false);
        properties.getSaas().setAdminEmail("saas.admin@local.test");
        properties.getSaas().setAdminPassword("saas-admin-1");
        TenantStore store = new JdbcTenantStore(new JdbcTemplate(dataSource));
        FixedProvider<TenantStore> stores = new FixedProvider<>(store);
        EntitlementService entitlements = new EntitlementService(properties, stores);
        TenantFilter filter = new TenantFilter(properties, authService, entitlements);
        AuthController controller = new AuthController(properties, authService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/specs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());

        ForbiddenException register = assertThrows(ForbiddenException.class, () ->
                controller.register(new RegisterRequest("qa@acme.test", "password1", "Acme", "Acme")));
        assertTrue(register.getMessage().toLowerCase().contains("saas"));
        ForbiddenException subscriptions = assertThrows(ForbiddenException.class, controller::listSubscriptions);
        assertTrue(subscriptions.getMessage().toLowerCase().contains("saas"));

        assertEquals("saas.admin@local.test", authService.ensureSaasAdmin());
        var admin = controller.login(new LoginRequest("saas.admin@local.test", "saas-admin-1", null));
        assertEquals("datacenter", admin.edition());
        assertFalse(admin.billing());
        assertTrue(admin.saas());
        assertTrue(admin.authenticated());
        assertTrue(admin.saasAdmin());
        assertNull(admin.subscription());
        assertTrue(admin.tenant().ready());

        TenantContext.runAs(admin.tenant().id(), admin.account().id(), "OWNER", () -> {
            MemberView orgMember = authService.addOrgMember(
                    new AddMemberRequest("dev@studio.test", "Dev", "password1", "MEMBER"));
            MemberView member = authService.grantWorkspaceAccess(
                    new GrantWorkspaceAccessRequest(orgMember.accountId(), "MEMBER"));
            assertEquals("dev@studio.test", member.email());
            storage.saveSpec(spec("spec-dc", "employee-service"));
            assertEquals(1, storage.listSpecs().size());
            assertEquals("Second", authService.createWorkspace(
                    admin.token(), new CreateTenantRequest("Second", null)).name());
        });

        var member = authService.login(new LoginRequest("dev@studio.test", "password1", admin.tenant().id()));
        assertTrue(member.tenant().ready());
        assertNull(member.subscription());
        assertFalse(member.billing());
        ForbiddenException denied = assertThrows(ForbiddenException.class, () ->
                authService.createWorkspace(member.token(), new CreateTenantRequest("Sneaky", null)));
        assertTrue(denied.getMessage().toLowerCase().contains("organization"));
    }

    @Test
    void saasAdminCannotDisableThePlatformOrganization() {
        properties.getSaas().setAdminEmail("saas.admin@local.test");
        properties.getSaas().setAdminPassword("saas-admin-1");
        authService.ensureSaasAdmin();
        var admin = authService.login(new LoginRequest("saas.admin@local.test", "saas-admin-1", null));
        TenantContext.runAs(admin.tenant().id(), admin.account().id(), "OWNER", () -> {
            ForbiddenException denied = assertThrows(ForbiddenException.class, () ->
                    authService.updateSubscription(
                            admin.organization().id(), new UpdateSubscriptionRequest(false, null, null)));
            assertTrue(denied.getMessage().contains("cannot be disabled"));
        });
    }

    private static StudioRun run(String runId, String specId, String resultId) {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        StudioRun run = new StudioRun();
        run.setId(runId);
        run.setSpecId(specId);
        run.setTrigger("case");
        run.setPassed(1);
        run.setStartedAt(now);
        StudioRunResult result = new StudioRunResult();
        result.setId(resultId);
        result.setRunId(runId);
        result.setSpecId(specId);
        result.setTrigger("case");
        result.setOutcome("passed");
        result.setCreatedAt(now);
        run.setResults(List.of(result));
        return run;
    }

    private static StoredSpec spec(String id, String name) {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId(id);
        spec.setName(name);
        spec.setTitle(name);
        spec.setLabel("General");
        spec.setBaseUrl("http://localhost:8080");
        return spec;
    }

    private static StudioTestPlan plan(String planId, String specId) {
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId(planId);
        plan.setSpecId(specId);
        plan.setName("Default");
        return plan;
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        private FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfAvailable(Supplier<T> defaultSupplier) {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getIfUnique(Supplier<T> defaultSupplier) {
            return value;
        }

        @Override
        public Stream<T> stream() {
            return Stream.of(value);
        }

        @Override
        public Stream<T> orderedStream() {
            return Stream.of(value);
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            action.accept(value);
        }
    }
}
