package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.CollectionImporter;
import com.tx.api.test.generator.studio.collection.CollectionMapper;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.ApprovalView;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.DecideApprovalRequest;
import com.tx.api.test.generator.studio.dto.EmergencyAccessView;
import com.tx.api.test.generator.studio.dto.EvaluateSafetyRequest;
import com.tx.api.test.generator.studio.dto.GrantEmergencyAccessRequest;
import com.tx.api.test.generator.studio.dto.PlanPreflightView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.RequestApprovalRequest;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.safety.ApprovalService;
import com.tx.api.test.generator.studio.safety.JdbcSafetyStore;
import com.tx.api.test.generator.studio.safety.SafetyContext;
import com.tx.api.test.generator.studio.safety.SafetyGuard;
import com.tx.api.test.generator.studio.safety.SafetyPolicyService;
import com.tx.api.test.generator.studio.safety.WriteApproval;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.tenant.AuthService;
import com.tx.api.test.generator.studio.tenant.EntitlementService;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.JdbcTenantStore;
import com.tx.api.test.generator.studio.tenant.LdapAuthenticator;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import com.tx.api.test.generator.studio.tenant.oidc.OidcAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approvals and emergency access let a write reach production deliberately: only the approved published version,
 * in the approved environment, with the environment's name typed, within the runs and rows allowed. The test
 * server records every request it gets, so "not sent" is checked, not assumed.
 */
class ProductionApprovalsTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private HttpServer production;
    private JdbcTemplate jdbc;
    private JdbcStudioStorage storage;
    private AuthService auth;
    private SafetyPolicyService policies;
    private ApprovalService approvals;
    private TestRunService runs;
    private SafetyPreviewService previews;
    private JdbcSafetyStore safetyStore;
    private AuthSessionView owner;
    private String adminId;
    private String memberId;
    private StoredEnvironment environment;
    private StudioTestCase create;

    @BeforeEach
    void setUp() throws IOException {
        production = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        production.createContext("/", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] body = "{\"id\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        production.start();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("approvals_" + System.nanoTime()), "sa", "");
        dataSource.setDriverClassName(H2Database.DRIVER);
        TestSchema.migrate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbc);
        JdbcTenantStore tenantStore = new JdbcTenantStore(jdbc);
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");
        properties.getSaas().setEnabled(true);
        properties.getSafety().setEnabled(true);
        properties.getRun().setAllowPrivateNetworks(true);
        TenantContext.setSaasEnabled(true);
        StudioAuditService audit = new StudioAuditService(storage);
        auth = new AuthService(properties, provider(TenantStore.class, tenantStore),
                provider(EntitlementService.class,
                        new EntitlementService(properties, provider(TenantStore.class, tenantStore))),
                provider(LdapAuthenticator.class, null), new OidcAuthService(properties, tenantStore), audit);
        safetyStore = new JdbcSafetyStore(jdbc);
        policies = new SafetyPolicyService(properties, safetyStore, tenantStore, storage, audit);
        approvals = new ApprovalService(safetyStore, policies, tenantStore, storage, audit);
        SafetyGuard guard = new SafetyGuard(properties, policies, tenantStore, storage, audit, approvals);
        runs = new TestRunService(storage, properties, new OutboundUrlPolicy(properties), audit, guard);
        previews = new SafetyPreviewService(properties, policies, approvals, storage);

        owner = auth.register(new RegisterRequest("owner@acme.test", "password1234", "Owner", "Acme"));
        // The trial plan allows two seats; this organization needs an owner, a second admin and a member.
        jdbc.update("UPDATE td_subscription SET seat_limit = 10 WHERE org_id = ?", owner.organization().id());
        as(owner.account().id());
        adminId = auth.addOrgMember(new AddMemberRequest("admin@acme.test", "Admin", "password1234", "ADMIN")).accountId();
        memberId = auth.addOrgMember(new AddMemberRequest("dev@acme.test", "Dev", "password1234", "MEMBER")).accountId();
        policies.updateSettings(new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, 0L));

        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-p");
        spec.setName("Users");
        spec.setTitle("Users");
        spec.setLabel("General");
        spec.setBaseUrl(url());
        storage.saveSpec(spec);
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId("plan-p");
        plan.setSpecId("spec-p");
        plan.setName("Default");
        storage.savePlan(plan);
        environment = environment("Production", true);
        create = saved(testCase("create", "Create user", "POST", "/users", "userId=$.id"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        TenantContext.setSaasEnabled(false);
        production.stop(0);
    }

    @Test
    void aRequestNeedsAPublishedCaseAProtectedEnvironmentAndAReason() {
        as(memberId);
        assertThrows(IllegalArgumentException.class, () -> approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "short", 7, 5)));
        StoredEnvironment staging = environment("Staging", false);
        assertThrows(IllegalArgumentException.class, () -> approvals.request(create.getId(),
                new RequestApprovalRequest(staging.getId(), "Clean up the smoke test user", 7, 5)));
        StudioTestCase draft = testCase("draft", "Draft case", "POST", "/users", null);
        draft.setLifecycle("DRAFT");
        StudioTestCase savedDraft = saved(draft);
        assertThrows(IllegalArgumentException.class, () -> approvals.request(savedDraft.getId(),
                new RequestApprovalRequest(environment.getId(), "Clean up the smoke test user", 7, 5)));

        ApprovalView asked = approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Clean up the smoke test user", 7, 5));
        assertEquals(WriteApproval.REQUESTED, asked.status());
        assertEquals("dev@acme.test", asked.requestedByLabel());
        assertThrows(IllegalArgumentException.class, () -> approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Clean up the smoke test user", 7, 5)),
                "one open request per case and environment");
        assertEquals(WriteApproval.WITHDRAWN, approvals.withdraw(create.getId(), asked.id()).status());
    }

    @Test
    void anotherAdminApprovesAndCanOnlyShortenWhatWasAsked() {
        as(owner.account().id());
        ApprovalView asked = approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Create the smoke test user", 7, 5));
        assertThrows(ForbiddenException.class, () -> approvals.approve(asked.id(), null),
                "a requester can't approve their own request when there's another admin");
        as(memberId);
        assertThrows(ForbiddenException.class, () -> approvals.approve(asked.id(), null), "members can't approve");
        as(adminId);
        assertThrows(IllegalArgumentException.class, () -> approvals.approve(asked.id(),
                new DecideApprovalRequest(Instant.now().plus(Duration.ofDays(30)), null, null)));
        assertThrows(IllegalArgumentException.class, () -> approvals.approve(asked.id(),
                new DecideApprovalRequest(null, 6, null)));
        ApprovalView approved = approvals.approve(asked.id(), new DecideApprovalRequest(null, 2, "Fine for today"));
        assertEquals(WriteApproval.APPROVED, approved.status());
        assertEquals(2, approved.maxRuns());
        assertFalse(approved.selfApproved());
        assertEquals("admin@acme.test", approved.decidedByLabel());
        assertTrue(approvals.list("active").stream().anyMatch(item -> item.id().equals(asked.id())));
        assertEquals(WriteApproval.REVOKED, approvals.revoke(asked.id(), null).status());
        assertTrue(approvals.list("ended").stream().anyMatch(item -> item.id().equals(asked.id())));
    }

    @Test
    void theOnlyAdminMaySelfApproveWithTighterLimitsUnlessTurnedOff() {
        AuthSessionView solo = auth.register(new RegisterRequest("solo@solo.test", "password1234", "Solo", "Solo"));
        TenantContext.set(new TenantContext.Scope(solo.tenant().id(), solo.account().id(), "solo@solo.test", "OWNER"));
        policies.updateSettings(new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, 0L));
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-solo");
        spec.setName("Solo");
        spec.setTitle("Solo");
        spec.setLabel("General");
        spec.setBaseUrl(url());
        storage.saveSpec(spec);
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId("plan-solo");
        plan.setSpecId("spec-solo");
        plan.setName("Default");
        storage.savePlan(plan);
        StoredEnvironment soloProduction = environment("spec-solo", "Production", true);
        StudioTestCase soloCase = testCase("solo-case", "Create", "POST", "/users", null);
        soloCase.setSpecId("spec-solo");
        soloCase.setPlanId("plan-solo");
        storage.saveTestCase(soloCase);

        ApprovalView asked = approvals.request("solo-case",
                new RequestApprovalRequest(soloProduction.getId(), "Seed the production smoke user", 30, null));
        ApprovalView self = approvals.approve(asked.id(), null);
        assertTrue(self.selfApproved());
        assertEquals(10, self.maxRuns());
        assertTrue(self.expiresAt().isBefore(Instant.now().plus(Duration.ofDays(7)).plusSeconds(60)));
        assertTrue(storage.listAuditEventsForTenant(50).stream().anyMatch(event -> "SELF_APPROVED".equals(event.action())));

        approvals.revoke(asked.id(), null);
        policies.updateSettings(new UpdateSafetySettingsRequest(null, null, null, null, null, "never",
                policies.settings().revision()));
        ApprovalView again = approvals.request("solo-case",
                new RequestApprovalRequest(soloProduction.getId(), "Seed the production smoke user", 7, null));
        assertThrows(ForbiddenException.class, () -> approvals.approve(again.id(), null));
        assertThrows(IllegalArgumentException.class, () -> {
            policies.updateSettings(new UpdateSafetySettingsRequest(null, null, null, null, true, null,
                    policies.settings().revision()));
            approvals.grantEmergency(new GrantEmergencyAccessRequest(soloProduction.getId(), 15, null,
                    "Fix the production outage now"));
        }, "a second admin can't be required in a one-admin organization");
    }

    @Test
    void anApprovedWriteIsSentOnlyWithTheEnvironmentNameTypedAndWithinItsRuns() {
        approve(2);
        RunTestResult unconfirmed = run(create, null, false);
        assertTrue(unconfirmed.blocked());
        assertEquals(SafetyGuard.CONFIRMATION_REQUIRED, unconfirmed.policy().code());
        RunTestResult fromEditor = run(create, "Production", true);
        assertEquals(SafetyGuard.DRAFT_IN_PROTECTED, fromEditor.policy().code());
        assertEquals(List.of(), received, "nothing sent yet");

        RunTestResult sent = run(create, " production ", false);
        assertFalse(sent.blocked(), String.valueOf(sent.policy()));
        assertEquals(List.of("POST /users"), received);
        assertTrue(storage.listAuditEventsForTenant(50).stream()
                .anyMatch(event -> "PROTECTED_WRITE_SENT".equals(event.action())));
        assertFalse(run(create, "Production", false).blocked());
        RunTestResult usedUp = run(create, "Production", false);
        assertEquals(SafetyGuard.APPROVAL_USED_UP, usedUp.policy().code());
        assertEquals(2, received.size());
    }

    @Test
    void sendingFromTheEditorExplainsWhatIsMissing() {
        assertEquals(SafetyGuard.WRITE_TO_PROTECTED_TARGET, run(create, "Production", true).policy().code(),
                "no approval yet: ask an admin, whatever was sent");
        approve(2);
        RunTestResult approved = run(create, "Production", true);
        assertEquals(SafetyGuard.DRAFT_IN_PROTECTED, approved.policy().code());
        assertTrue(approved.policy().message().contains("Use Send to Production"), approved.policy().message());
        assertEquals(List.of(), received);
    }

    @Test
    void publishingANewVersionEndsTheApproval() {
        approve(5);
        assertFalse(run(create, "Production", false).blocked());
        create.setTestName("Create user, changed");
        storage.saveTestCase(create);
        StudioTestCase republished = storage.findTestCase(create.getId()).orElseThrow();
        RunTestResult result = run(republished, "Production", false);
        assertEquals(SafetyGuard.APPROVAL_VERSION_CHANGED, result.policy().code());
        assertEquals(1, received.size());
        as(adminId);
        assertEquals(WriteApproval.ENDED, approvals.list("ended").get(0).status());
    }

    @Test
    void twoRunsCannotBothTakeTheLastRun() {
        ApprovalView approved = approve(1);
        assertTrue(safetyStore.useApprovalRun(approved.id(), Instant.now()));
        assertFalse(safetyStore.useApprovalRun(approved.id(), Instant.now()));
    }

    @Test
    void emergencyAccessNeedsTheSecondAdminWhenRequiredAndCanBeEnded() {
        as(owner.account().id());
        policies.updateSettings(new UpdateSafetySettingsRequest(null, null, null, null, true, null,
                policies.settings().revision()));
        EmergencyAccessView granted = approvals.grantEmergency(new GrantEmergencyAccessRequest(environment.getId(), 15,
                3, "Production outage: fix the stuck order"));
        assertTrue(granted.waitingForSecondAdmin());
        assertFalse(granted.active());
        assertTrue(run(create, "Production", true).blocked(), "not confirmed yet");
        assertThrows(ForbiddenException.class, () -> approvals.confirmEmergency(granted.id()));
        as(adminId);
        assertTrue(approvals.confirmEmergency(granted.id()).active(), emergencyState());
        RunTestResult sent = run(create, "Production", true);
        assertFalse(sent.blocked(), "emergency access covers every write from the environment");
        assertEquals(List.of("POST /users"), received);
        approvals.endEmergency(granted.id());
        assertTrue(run(create, "Production", true).blocked());
        assertEquals(1, received.size());
    }

    @Test
    void onlyTheAllowedNumberOfDataRowsOfAWriteCaseRun() {
        create.setDataRows(DataRows.normalize("[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]"));
        storage.saveTestCase(create);
        ApprovalView approved = approve(null);
        assertNotNull(approved);
        StudioTestCase rows = storage.findTestCase(create.getId()).orElseThrow();
        SafetyContext run = context("Production", false);
        RunTestResult summary = DataRowRun.run(rows, DataRows.parse(rows.getDataRows()), new LinkedHashMap<>(),
                (row, variables) -> runs.execute(row, null, variables, null, scope(), run));
        assertEquals(1, received.size(), "the default limit is one row");
        assertFalse(summary.iterations().get(0).blocked());
        assertEquals(SafetyGuard.LIMIT_REACHED, summary.iterations().get(1).policy().code());
    }

    @Test
    void thePreviewsSayWhatWillHappenWithoutSendingAnything() {
        StudioTestCase read = saved(testCase("read", "Read user", "GET", "/users/{{userId}}", null));
        StudioTestCase list = saved(testCase("list", "List users", "GET", "/users", null));
        assertEquals("blocked", previews.evaluate(new EvaluateSafetyRequest(create.getId(), environment.getId())).state());
        assertEquals("read", previews.evaluate(new EvaluateSafetyRequest(list.getId(), environment.getId())).state());
        StoredEnvironment staging = environment("Staging", false);
        assertEquals("unprotected", previews.evaluate(new EvaluateSafetyRequest(create.getId(), staging.getId())).state());

        PlanPreflightView plan = previews.preflight(new RunPlanRequest("spec-p", "plan-p", null, environment.getId()));
        assertEquals(List.of("Create user"), plan.blocked().stream().map(PlanPreflightView.Entry::name).toList());
        assertEquals("blocked", plan.blocked().get(0).state());
        assertEquals("skipped", plan.skipped().get(0).state());
        assertEquals(List.of("Read user"), plan.skipped().stream().map(PlanPreflightView.Entry::name).toList());
        assertEquals(1, plan.willRun());
        assertEquals("Production", plan.confirmationText());

        as(memberId);
        approvals.request(create.getId(), new RequestApprovalRequest(environment.getId(), "Create the smoke test user", 7, 3));
        assertEquals("waiting", previews.evaluate(new EvaluateSafetyRequest(create.getId(), environment.getId())).state());
        assertEquals("waiting", previews.preflight(new RunPlanRequest("spec-p", "plan-p", null, environment.getId()))
                .blocked().get(0).state());
        as(adminId);
        approvals.approve(approvals.list("waiting").get(0).id(), null);
        var approved = previews.evaluate(new EvaluateSafetyRequest(create.getId(), environment.getId()));
        assertEquals("approved", approved.state());
        assertEquals("Production", approved.confirmationText());
        PlanPreflightView withApproval = previews.preflight(new RunPlanRequest("spec-p", "plan-p", null,
                environment.getId()));
        assertEquals(List.of("Create user"), withApproval.needsConfirmation().stream()
                .map(PlanPreflightView.Entry::name).toList());
        assertTrue(withApproval.skipped().isEmpty());
        assertEquals(List.of(), received);
        assertNotNull(read);
    }

    @Test
    void restoringAnEarlierVersionEndsTheApproval() {
        String original = create.getTestName();
        create.setTestName("Create user, second version");
        storage.saveTestCase(create);
        create = storage.findTestCase(create.getId()).orElseThrow();
        approve(5);
        assertFalse(run(create, "Production", false).blocked());
        // A restore publishes the earlier content as a new version: same content as before, new revision.
        create.setTestName(original);
        storage.saveTestCase(create);
        StudioTestCase restored = storage.findTestCase(create.getId()).orElseThrow();
        assertEquals(SafetyGuard.APPROVAL_VERSION_CHANGED, run(restored, "Production", false).policy().code());
        assertEquals(1, received.size());
    }

    @Test
    void importingAChangedCaseEndsTheApprovalButAnUnchangedOneDoesNot() {
        String exported = CollectionDocuments.toJson(CollectionMapper.readFrom(storage, "spec-p", "plan-p"));
        // The collection as the studio itself stores it after an import; importing it again changes nothing.
        CollectionImporter.importInto(storage, exported.getBytes(StandardCharsets.UTF_8), "first.json", "spec-p");
        create = storage.findTestCase(create.getId()).orElseThrow();
        approve(5);
        CollectionImporter.importInto(storage, exported.getBytes(StandardCharsets.UTF_8), "same.json", "spec-p");
        StudioTestCase same = storage.findTestCase(create.getId()).orElseThrow();
        assertEquals(create.getRevision(), same.getRevision(), "an unchanged case isn't saved again");
        RunTestResult kept = run(withBaseUrl(same), "Production", false);
        assertFalse(kept.blocked(), String.valueOf(kept.policy()));
        assertEquals(List.of("POST /users"), received);

        String changed = exported.replace("\"Create user\"", "\"Create user, imported\"");
        CollectionImporter.importInto(storage, changed.getBytes(StandardCharsets.UTF_8), "changed.json", "spec-p");
        StudioTestCase imported = storage.findTestCase(create.getId()).orElseThrow();
        assertEquals("Create user, imported", imported.getTestName());
        assertEquals(SafetyGuard.APPROVAL_VERSION_CHANGED,
                run(withBaseUrl(imported), "Production", false).policy().code());
        assertEquals(1, received.size());
    }

    private String emergencyState() {
        return "now=" + Instant.now() + " zone=" + java.util.TimeZone.getDefault().getID() + " stored="
                + jdbc.queryForList("SELECT starts_at, ends_at, confirmed_by, ended_at, max_requests, requests_used "
                + "FROM td_emergency_access");
    }

    /** Import stores the collection's base URL as {{baseUrl}}; these runs pass no environment values, so fill it in. */
    private StudioTestCase withBaseUrl(StudioTestCase testCase) {
        testCase.setBaseUrl(url());
        return testCase;
    }

    @Test
    void anApprovalPastItsDateStopsAndIsRecordedAsExpired() {
        ApprovalView approved = approve(5);
        jdbc.update("UPDATE td_write_approval SET expires_at = ? WHERE approval_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), approved.id());
        RunTestResult result = run(create, "Production", false);
        assertEquals(SafetyGuard.APPROVAL_EXPIRED, result.policy().code());
        assertEquals(List.of(), received);
        as(adminId);
        ApprovalView ended = approvals.list("ended").stream().filter(item -> item.id().equals(approved.id()))
                .findFirst().orElseThrow();
        assertEquals(WriteApproval.EXPIRED, ended.status());
        assertTrue(approvals.list("active").isEmpty());
    }

    @Test
    void emergencyAccessStopsOnItsOwnWhenItsTimeIsUp() {
        as(owner.account().id());
        EmergencyAccessView granted = approvals.grantEmergency(new GrantEmergencyAccessRequest(environment.getId(), 15,
                null, "Production outage: fix the stuck order"));
        assertTrue(granted.active());
        as(memberId);
        assertEquals(List.of(granted.id()), approvals.currentEmergencyFor(environment.getId()).stream()
                .map(EmergencyAccessView::id).toList(), "every member sees the banner");
        assertTrue(approvals.currentEmergencyFor(environment("Staging", false).getId()).isEmpty());
        RunTestResult sent = run(create, "Production", true);
        assertFalse(sent.blocked(), sent.policy() + " " + emergencyState());
        as(owner.account().id());
        jdbc.update("UPDATE td_emergency_access SET starts_at = ?, ends_at = ? WHERE access_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))),
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(5))), granted.id());
        assertTrue(approvals.currentEmergency().stream().noneMatch(EmergencyAccessView::active));
        assertTrue(run(create, "Production", true).blocked());
        assertEquals(1, received.size());
    }

    @Test
    void aSelfApprovalStaysValidAfterASecondAdminJoinsButNoNewOneIsAllowed() {
        AuthSessionView solo = auth.register(new RegisterRequest("solo@solo.test", "password1234", "Solo", "Solo"));
        TenantContext.set(new TenantContext.Scope(solo.tenant().id(), solo.account().id(), "solo@solo.test", "OWNER"));
        policies.updateSettings(new UpdateSafetySettingsRequest("enforce", null, null, null, null, null, 0L));
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-solo");
        spec.setName("Solo");
        spec.setTitle("Solo");
        spec.setLabel("General");
        spec.setBaseUrl(url());
        storage.saveSpec(spec);
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId("plan-solo");
        plan.setSpecId("spec-solo");
        plan.setName("Default");
        storage.savePlan(plan);
        StoredEnvironment soloProduction = environment("spec-solo", "Production", true);
        StudioTestCase first = testCase("solo-first", "Create", "POST", "/users", null);
        first.setSpecId("spec-solo");
        first.setPlanId("plan-solo");
        StudioTestCase second = testCase("solo-second", "Update", "PUT", "/users/1", null);
        second.setSpecId("spec-solo");
        second.setPlanId("plan-solo");
        storage.saveTestCase(first);
        storage.saveTestCase(second);

        ApprovalView self = approvals.approve(approvals.request("solo-first",
                new RequestApprovalRequest(soloProduction.getId(), "Seed the production smoke user", 7, 5)).id(), null);
        assertTrue(self.selfApproved());
        auth.addOrgMember(new AddMemberRequest("second@solo.test", "Second", "password1234", "ADMIN"));

        OutboundUrlPolicy.Scope soloScope = new OutboundUrlPolicy.Scope(soloProduction.getId(), "Production");
        RunTestResult sent = runs.execute(storage.findTestCase("solo-first").orElseThrow(), null,
                new LinkedHashMap<>(), null, soloScope, runs.safetyContext(soloScope, "Production", false));
        assertFalse(sent.blocked(), String.valueOf(sent.policy()));
        assertEquals(List.of("POST /users"), received);

        ApprovalView asked = approvals.request("solo-second",
                new RequestApprovalRequest(soloProduction.getId(), "Fix the smoke user's name", 7, 5));
        assertThrows(ForbiddenException.class, () -> approvals.approve(asked.id(), null),
                "with two admins, the other one has to approve");
    }

    @Test
    void anotherOrganizationCanNeitherReadNorEndApprovals() {
        as(memberId);
        ApprovalView asked = approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Create the smoke test user", 7, 5));
        AuthSessionView stranger = auth.register(
                new RegisterRequest("owner@globex.test", "password1234", "Globex", "Globex"));
        TenantContext.runAs(stranger.tenant().id(), stranger.account().id(), "OWNER", () -> {
            assertThrows(NoSuchElementException.class,
                    () -> approvals.forCase(create.getId(), environment.getId()));
            assertThrows(NoSuchElementException.class, () -> approvals.withdraw(create.getId(), asked.id()));
            assertThrows(NoSuchElementException.class, () -> approvals.approve(asked.id(), null));
            assertThrows(NoSuchElementException.class, () -> approvals.reject(asked.id(), null));
            assertTrue(approvals.list("").isEmpty());
        });
        assertEquals(WriteApproval.REQUESTED, storedStatus(asked.id()));
    }

    @Test
    void theInboxInAnotherWorkspaceOfTheOrganizationKeepsApprovalsOpenAndCanDecide() {
        as(memberId);
        ApprovalView asked = approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Create the smoke test user", 7, 5));
        String second = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO td_tenant (tenant_id, name, slug, org_id, ready, created_at)
                VALUES (?, 'Second', 'acme-second', ?, TRUE, CURRENT_TIMESTAMP)
                """, second, owner.organization().id());
        TenantContext.runAs(second, memberId, "MEMBER", () -> assertThrows(NoSuchElementException.class,
                () -> approvals.forCase(create.getId(), environment.getId())));
        TenantContext.runAs(second, adminId, "ADMIN", () -> {
            ApprovalView waiting = approvals.list("waiting").get(0);
            assertEquals("Create user", waiting.caseName());
            assertEquals("Production", waiting.environmentName());
            assertEquals(WriteApproval.APPROVED, approvals.approve(asked.id(), null).status());
            assertEquals(1, approvals.list("active").size());
            assertEquals(second, TenantContext.tenantId());
        });
        assertEquals(WriteApproval.APPROVED, storedStatus(asked.id()));
        assertEquals(owner.tenant().id(), jdbc.queryForObject(
                "SELECT tenant_id FROM td_audit_event WHERE resource_id = ? AND action = 'APPROVED'",
                String.class, asked.id()));
    }

    private String storedStatus(String approvalId) {
        return jdbc.queryForObject("SELECT status FROM td_write_approval WHERE approval_id = ?", String.class, approvalId);
    }

    private ApprovalView approve(Integer maxRuns) {
        as(memberId);
        approvals.forCase(create.getId(), environment.getId()).stream()
                .filter(item -> WriteApproval.REQUESTED.equals(item.status()))
                .forEach(item -> approvals.withdraw(create.getId(), item.id()));
        ApprovalView asked = approvals.request(create.getId(),
                new RequestApprovalRequest(environment.getId(), "Create the smoke test user", 7, maxRuns));
        as(adminId);
        ApprovalView approved = approvals.approve(asked.id(), null);
        as(memberId);
        return approved;
    }

    private RunTestResult run(StudioTestCase testCase, String confirmation, boolean fromEditor) {
        return runs.execute(testCase, null, new LinkedHashMap<>(), null, scope(), context(confirmation, fromEditor));
    }

    private SafetyContext context(String confirmation, boolean fromEditor) {
        return runs.safetyContext(scope(), confirmation, fromEditor);
    }

    private OutboundUrlPolicy.Scope scope() {
        return new OutboundUrlPolicy.Scope(environment.getId(), environment.getName());
    }

    private void as(String accountId) {
        TenantContext.set(new TenantContext.Scope(owner.tenant().id(), accountId, accountId, "MEMBER"));
    }

    private StoredEnvironment environment(String name, boolean protectedEnvironment) {
        return environment("spec-p", name, protectedEnvironment);
    }

    private StoredEnvironment environment(String specId, String name, boolean protectedEnvironment) {
        StoredEnvironment stored = new StoredEnvironment();
        stored.setId(UUID.randomUUID().toString());
        stored.setSpecId(specId);
        stored.setName(name);
        stored.setDocumentJson(CollectionDocuments.toJson(EnvironmentMapper.merge(null, name, url(), "NONE")));
        storage.saveEnvironment(stored);
        storage.setEnvironmentProtected(stored.getId(), protectedEnvironment);
        return storage.findEnvironment(stored.getId()).orElseThrow();
    }

    private StudioTestCase saved(StudioTestCase testCase) {
        storage.saveTestCase(testCase);
        return storage.findTestCase(testCase.getId()).orElseThrow();
    }

    private StudioTestCase testCase(String id, String name, String method, String endpoint, String extracts) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(id);
        testCase.setSpecId("spec-p");
        testCase.setPlanId("plan-p");
        testCase.setOperationKey(method + " " + endpoint);
        testCase.setTestName(name);
        testCase.setMethod(method);
        testCase.setBaseUrl(url());
        testCase.setRawPath(endpoint);
        testCase.setEndpoint(endpoint);
        testCase.setStatusCode(201);
        testCase.setResponseType("JSON");
        testCase.setMaxResponseTimeMs(5000);
        testCase.setEnabled("Y");
        testCase.setLifecycle("READY");
        testCase.setExtracts(extracts);
        return testCase;
    }

    private String url() {
        return "http://127.0.0.1:" + production.getAddress().getPort();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton(type.getSimpleName(), value);
        }
        return factory.getBeanProvider(type);
    }
}
