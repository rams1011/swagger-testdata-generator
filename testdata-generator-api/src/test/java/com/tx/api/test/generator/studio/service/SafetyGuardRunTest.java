package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentSafety;
import com.tx.api.test.generator.studio.collection.EnvironmentImporter;
import com.tx.api.test.generator.studio.collection.EnvironmentExporter;
import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.SafetyReviewView;
import com.tx.api.test.generator.studio.dto.AddProtectedTargetRequest;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveOperationRuleRequest;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.safety.ApprovalService;
import com.tx.api.test.generator.studio.safety.JdbcSafetyStore;
import com.tx.api.test.generator.studio.safety.SafetyContext;
import com.tx.api.test.generator.studio.safety.SafetyGuard;
import com.tx.api.test.generator.studio.safety.SafetyDecision;
import com.tx.api.test.generator.studio.safety.SafetyPolicyService;
import com.tx.api.test.generator.studio.safety.SafetyReviewService;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.tenant.AuthService;
import com.tx.api.test.generator.studio.tenant.EntitlementService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production safety at send time: every way a request can leave TestRunService goes through SafetyGuard, and a
 * blocked request never reaches the server (the test servers record every request they get).
 */
class SafetyGuardRunTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private HttpServer production;
    private HttpServer staging;
    private JdbcTemplate jdbc;
    private JdbcStudioStorage storage;
    private StudioProperties properties;
    private SafetyPolicyService policies;
    private TestRunService runs;
    private AuthSessionView owner;
    private JdbcSafetyStore safetyStore;
    private SafetyReviewService review;

    @BeforeEach
    void setUp() throws IOException {
        production = server("prod");
        staging = server("staging");
        staging.createContext("/move", exchange -> {
            received.add("staging " + exchange.getRequestMethod() + " /move");
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port(production) + "/users");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("safety_guard_" + System.nanoTime()), "sa", "");
        dataSource.setDriverClassName(H2Database.DRIVER);
        TestSchema.migrate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbc);
        JdbcTenantStore tenantStore = new JdbcTenantStore(jdbc);
        properties = new StudioProperties();
        properties.setEdition("saas");
        properties.getSaas().setEnabled(true);
        properties.getSafety().setEnabled(true);
        properties.getRun().setAllowPrivateNetworks(true);
        TenantContext.setSaasEnabled(true);
        StudioAuditService audit = new StudioAuditService(storage);
        AuthService authService = new AuthService(properties, provider(TenantStore.class, tenantStore),
                provider(EntitlementService.class,
                        new EntitlementService(properties, provider(TenantStore.class, tenantStore))),
                provider(LdapAuthenticator.class, null), new OidcAuthService(properties, tenantStore), audit);
        safetyStore = new JdbcSafetyStore(jdbc);
        policies = new SafetyPolicyService(properties, safetyStore, tenantStore, storage, audit);
        review = new SafetyReviewService(policies, safetyStore, storage);
        SafetyGuard guard = new SafetyGuard(properties, policies, tenantStore, storage, audit,
                new ApprovalService(safetyStore, policies, tenantStore, storage, audit));
        runs = new TestRunService(storage, properties, new OutboundUrlPolicy(properties), audit, guard);
        owner = authService.register(new RegisterRequest("owner@acme.test", "password1234", "Owner", "Acme"));
        TenantContext.set(new TenantContext.Scope(owner.tenant().id(), owner.account().id(), "owner@acme.test",
                "OWNER"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        // A JVM-wide flag: left on, it makes later tests without a workspace fail with "Sign in to continue".
        TenantContext.setSaasEnabled(false);
        production.stop(0);
        staging.stop(0);
    }

    @Test
    void aWriteToAProtectedTargetIsNeverSentButAReadIs() {
        protect("127.0.0.1", port(production));
        enforce();

        RunTestResult write = runs.execute(request("POST", production, "/users"), null, new LinkedHashMap<>());
        assertTrue(write.blocked());
        assertTrue(write.skipped());
        assertFalse(write.passed());
        assertEquals(SafetyGuard.WRITE_TO_PROTECTED_TARGET, write.policy().code());
        assertFalse(write.policy().shadow());
        assertEquals(List.of(), received, "nothing reached the server");

        RunTestResult read = runs.execute(request("GET", production, "/users"), null, new LinkedHashMap<>());
        assertFalse(read.blocked());
        assertNull(read.policy());
        assertEquals(List.of("prod GET /users"), received);

        RunTestResult elsewhere = runs.execute(request("DELETE", staging, "/users"), null, new LinkedHashMap<>());
        assertFalse(elsewhere.blocked(), "an unprotected port isn't checked");
    }

    @Test
    void methodOverridesCountAsWhatTheyAskFor() {
        protect("127.0.0.1", port(production));
        enforce();
        for (String header : List.of("X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override")) {
            StudioTestCase overridden = request("GET", production, "/users");
            overridden.setHeaders(new LinkedHashMap<>(Map.of(header, "DELETE")));
            assertTrue(runs.execute(overridden, null, new LinkedHashMap<>()).blocked(), header);
        }
        StudioTestCase query = request("GET", production, "/users");
        query.setQueryParams(new LinkedHashMap<>(Map.of("_method", "delete")));
        assertTrue(runs.execute(query, null, new LinkedHashMap<>()).blocked(), "_method query parameter");

        StudioTestCase custom = request("PURGE", production, "/cache");
        assertTrue(runs.execute(custom, null, new LinkedHashMap<>()).blocked(), "a custom method is a write");

        StudioTestCase harmless = request("GET", production, "/users");
        harmless.setHeaders(new LinkedHashMap<>(Map.of("X-HTTP-Method-Override", "GET")));
        assertFalse(runs.execute(harmless, null, new LinkedHashMap<>()).blocked());
        assertEquals(List.of("prod GET /users"), received);
    }

    @Test
    void anotherNameForTheSameAddressIsStillProtected() {
        policies.addTarget(new AddProtectedTargetRequest("127.0.0.0/8", List.of(port(production)), "Production network"));
        enforce();
        StudioTestCase swapped = request("POST", production, "/users");
        swapped.setBaseUrl("{{baseUrl}}");
        Map<String, String> variables = new LinkedHashMap<>(Map.of("baseUrl", "http://localhost:" + port(production)));
        RunTestResult result = runs.execute(swapped, null, variables);
        assertTrue(result.blocked(), "localhost resolves into the protected range");
        assertEquals(List.of(), received);
    }

    @Test
    void aProtectedEnvironmentProtectsWhateverItCalls() {
        enforce();
        StoredEnvironment environment = environment("Production", true);
        OutboundUrlPolicy.Scope scope = new OutboundUrlPolicy.Scope(environment.getId(), "Production");
        RunTestResult result = runs.execute(request("PATCH", staging, "/users/1"), null, new LinkedHashMap<>(), null,
                scope);
        assertTrue(result.blocked());
        assertTrue(result.policy().target().contains("Production"), result.policy().target());

        StoredEnvironment open = environment("Staging", false);
        RunTestResult allowed = runs.execute(request("PATCH", staging, "/users/1"), null, new LinkedHashMap<>(), null,
                new OutboundUrlPolicy.Scope(open.getId(), "Staging"));
        assertFalse(allowed.blocked());
        assertEquals(List.of("staging PATCH /users/1"), received);
    }

    @Test
    void aRedirectThatCarriesAWriteIntoProductionIsStopped() {
        protect("127.0.0.1", port(production));
        enforce();
        StudioEnvironmentNetwork follow = new StudioEnvironmentNetwork(true, null, null, null, null);
        RunTestResult result = runs.execute(request("POST", staging, "/move"), null, new LinkedHashMap<>(), follow,
                OutboundUrlPolicy.Scope.NONE);
        assertTrue(result.blocked());
        assertEquals(SafetyGuard.REDIRECT_INTO_PROTECTED, result.policy().code());
        assertEquals(List.of("staging POST /move"), received, "the 307 hop never reached production");
    }

    @Test
    void operationRulesTreatAWriteAsAReadOrBlockARead() {
        protect("127.0.0.1", port(production));
        enforce();
        policies.addRule(new SaveOperationRuleRequest("treat_as_read", "rest", "POST", "/search", null, null,
                "Search only reads data", null));
        policies.addRule(new SaveOperationRuleRequest("block", "rest", "GET", "/admin/{what}", null, null,
                "Admin exports are too heavy", null));

        assertFalse(runs.execute(request("POST", production, "/search"), null, new LinkedHashMap<>()).blocked());
        RunTestResult dotted = runs.execute(request("POST", production, "/search/../users"), null,
                new LinkedHashMap<>());
        assertTrue(dotted.blocked(), "/search/../users is /users, not /search");
        RunTestResult export = runs.execute(request("GET", production, "/admin/export-all"), null,
                new LinkedHashMap<>());
        assertTrue(export.blocked());
        assertEquals(SafetyGuard.BLOCKED_OPERATION, export.policy().code());
        assertNotNull(export.policy().ruleId());
        assertEquals(List.of("prod POST /search"), received);
    }

    @Test
    void shadowModeSendsAndNotesWhatItWouldHaveBlocked() {
        protect("127.0.0.1", port(production));
        RunTestResult result = runs.execute(request("DELETE", production, "/users/1"), null, new LinkedHashMap<>());
        assertFalse(result.blocked());
        assertNotNull(result.policy());
        assertTrue(result.policy().shadow());
        assertEquals(List.of("prod DELETE /users/1"), received);
        assertTrue(storage.listAuditEventsForTenant(20).stream()
                .anyMatch(event -> "WOULD_BLOCK".equals(event.action())));
    }

    @Test
    void theReviewGroupsWhatWasStoppedAndSuggestsTreatAsReadRules() {
        protect("127.0.0.1", port(production));
        StudioTestCase search = request("POST", production, "/search");
        search.setRawPath("/search");
        StudioTestCase remove = request("DELETE", production, "/users/7");
        remove.setRawPath("/users/{{userId}}");
        runs.execute(search, null, new LinkedHashMap<>());
        runs.execute(search, null, new LinkedHashMap<>());
        runs.execute(remove, null, new LinkedHashMap<>(Map.of("userId", "7")));
        enforce();
        runs.execute(remove, null, new LinkedHashMap<>(Map.of("userId", "7")));

        SafetyReviewView view = review.review(14);
        assertEquals(3, view.wouldBlock(), String.valueOf(view.items()));
        assertEquals(1, view.blocked());
        SafetyReviewView.Item first = view.items().get(0);
        assertEquals("POST", first.method());
        assertEquals("/search", first.pathTemplate());
        assertEquals(2, first.count());
        assertTrue(first.shadow());
        assertTrue(first.suggestTreatAsRead());
        assertEquals("127.0.0.1/32", first.target());
        assertNotNull(first.targetId());
        assertTrue(view.items().stream().anyMatch(item -> "/users/{userId}".equals(item.pathTemplate())
                && !item.shadow()), "the case's path, with {{userId}} as a template variable");

        policies.addRule(new SaveOperationRuleRequest("treat_as_read", "rest", "POST", "/search", null, null,
                "Search only reads data", null));
        assertFalse(review.review(14).items().get(0).suggestTreatAsRead(), "a rule covers it now");
        assertThrows(IllegalArgumentException.class, () -> review.review(91));
    }

    @Test
    void theDecisionLogKeepsNinetyDays() {
        String orgId = owner.organization().id();
        safetyStore.insertDecision(new SafetyDecision("old", orgId, java.time.Instant.now().minus(
                java.time.Duration.ofDays(91)), null, null, "POST", "/old", null, "api.acme.com",
                SafetyGuard.WRITE_TO_PROTECTED_TARGET, true));
        policies.recordDecision(new SafetyDecision("new", orgId, java.time.Instant.now(), null, null, "POST", "/new",
                null, "api.acme.com", SafetyGuard.WRITE_TO_PROTECTED_TARGET, true));
        assertEquals(List.of("new"), jdbc.queryForList("SELECT decision_id FROM td_safety_decision", String.class));
    }

    @Test
    void theEnvironmentFileCarriesThePolicyForCiAndImportIgnoresIt() {
        StoredEnvironment production = storage.findEnvironment(environment("Production", true).getId()).orElseThrow();
        settings("off");
        assertTrue(policies.exportBlock(production).isEmpty(), "an organization that is Off exports nothing");
        protect("127.0.0.1", port(this.production));
        policies.addRule(new SaveOperationRuleRequest("treat_as_read", "rest", "POST", "/search", null, null,
                "Search only reads data", null));
        enforce();

        StudioEnvironmentSafety block = policies.exportBlock(production).orElseThrow();
        assertTrue(block.protectedEnvironment());
        assertEquals("enforce", block.mode());
        assertEquals(List.of("127.0.0.1/32"), block.targets().stream().map(StudioEnvironmentSafety.Target::pattern).toList());
        assertEquals(List.of(port(this.production)), block.targets().get(0).ports());
        assertEquals("treat_as_read", block.rules().get(0).effect());
        assertEquals("/search", block.rules().get(0).pathTemplate());
        assertEquals(1, block.limits().maxWriteDataRows());
        assertFalse(policies.exportBlock(environment("Staging", false)).orElseThrow().protectedEnvironment());

        StoredSpec spec = storage.findSpec("spec-env").orElseThrow();
        String file = new String(EnvironmentExporter.export(spec, production, List.of(), block).content(),
                StandardCharsets.UTF_8);
        JsonObject safety = JsonParser.parseString(file).getAsJsonObject().getAsJsonObject("safety");
        assertTrue(safety.get("protected").getAsBoolean());
        assertFalse(file.contains("Search only reads data"), "rule reasons stay in SpecPact");
        assertFalse(file.contains("\"note\""), "target notes stay in SpecPact");

        StoredEnvironment imported = EnvironmentImporter.importInto(storage, "spec-env",
                file.replace("\"name\": \"Production\"", "\"name\": \"Imported\"").getBytes(StandardCharsets.UTF_8));
        assertFalse(imported.isProtectedEnvironment(), "import never changes protection");
        assertFalse(imported.getDocumentJson().contains("safety"), "the block isn't stored");
    }

    @Test
    void offAndTheServerSwitchLetEverythingThrough() {
        protect("127.0.0.1", port(production));
        settings("off");
        assertFalse(runs.execute(request("DELETE", production, "/a"), null, new LinkedHashMap<>()).blocked());
        settings("enforce");
        properties.getSafety().setEnabled(false);
        assertFalse(runs.execute(request("DELETE", production, "/b"), null, new LinkedHashMap<>()).blocked());
        assertEquals(List.of("prod DELETE /a", "prod DELETE /b"), received);
    }

    @Test
    void aRunStopsAtItsProductionRequestLimit() {
        protect("127.0.0.1", port(production));
        policies.updateSettings(new UpdateSafetySettingsRequest("enforce", 2, null, null, null, null,
                policies.settings().revision()));
        SafetyContext run = runs.safetyContext(OutboundUrlPolicy.Scope.NONE);
        List<RunTestResult> results = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            results.add(runs.execute(request("GET", production, "/users"), null, new LinkedHashMap<>(), null,
                    OutboundUrlPolicy.Scope.NONE, run));
        }
        assertFalse(results.get(1).blocked());
        assertTrue(results.get(2).blocked());
        assertEquals(SafetyGuard.LIMIT_REACHED, results.get(2).policy().code());
        assertEquals(2, received.size());
    }

    @Test
    void aPlanSkipsCasesThatNeedABlockedCaseAndNeverRetriesAProductionWrite() {
        protect("127.0.0.1", port(production));
        enforce();
        StudioTestCase create = request("POST", production, "/users");
        create.setId("create");
        create.setTestName("Create user");
        create.setExtracts("userId=$.id");
        StudioTestCase read = request("GET", production, "/users/{{userId}}");
        read.setId("read");
        read.setTestName("Read user");
        Map<String, String> variables = new LinkedHashMap<>();
        SafetyContext run = runs.safetyContext(OutboundUrlPolicy.Scope.NONE);
        PlanRun.Outcome outcome = PlanRun.execute(List.of(create, read), actions(variables, run),
                new PlanRun.Options(false, 0L, 2));
        assertEquals(1, outcome.blocked());
        assertEquals(1, outcome.skipped());
        assertEquals(0, outcome.failed());
        assertTrue(outcome.results().get(1).skipReason().startsWith("Needs blocked case 'Create user'"),
                outcome.results().get(1).skipReason());
        assertEquals(List.of(), received);

        settings("shadow");
        StudioTestCase failing = request("POST", production, "/fail");
        failing.setId("failing");
        SafetyContext shadowRun = runs.safetyContext(OutboundUrlPolicy.Scope.NONE);
        PlanRun.Outcome retried = PlanRun.execute(List.of(failing), actions(new LinkedHashMap<>(), shadowRun),
                new PlanRun.Options(true, 0L, 3));
        assertEquals(1, retried.failed());
        assertEquals(List.of("prod POST /fail"), received, "a write that reached production is sent once");
    }

    @Test
    void aPolicyChangeTakesEffectInTheMiddleOfAPlan() {
        protect("127.0.0.1", port(production));
        StudioTestCase first = request("DELETE", production, "/users/1");
        first.setId("first");
        StudioTestCase second = request("DELETE", production, "/users/2");
        second.setId("second");
        SafetyContext run = runs.safetyContext(OutboundUrlPolicy.Scope.NONE);
        PlanRun.Actions shadowThenEnforce = new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                RunTestResult result = runs.execute(testCase, null, new LinkedHashMap<>(), null,
                        OutboundUrlPolicy.Scope.NONE, run);
                if ("first".equals(testCase.getId())) {
                    enforce();
                }
                return result;
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return runs.skipped(testCase, reason);
            }
        };
        PlanRun.Outcome outcome = PlanRun.execute(List.of(first, second), shadowThenEnforce,
                new PlanRun.Options(true, 0L, 0));
        assertFalse(outcome.results().get(0).blocked(), "sent in Shadow");
        assertTrue(outcome.results().get(1).blocked(), "Enforce applies to the very next request");
        assertEquals(List.of("prod DELETE /users/1"), received);
    }

    @Test
    void everyDataRowIsCheckedAndAllBlockedRowsBlockTheCase() {
        protect("127.0.0.1", port(production));
        enforce();
        StudioTestCase rows = request("POST", production, "/users");
        rows.setDataRows(DataRows.normalize("[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]"));
        SafetyContext run = runs.safetyContext(OutboundUrlPolicy.Scope.NONE);
        RunTestResult summary = DataRowRun.run(rows, DataRows.parse(rows.getDataRows()), new LinkedHashMap<>(),
                (row, rowVariables) -> runs.execute(row, null, rowVariables, null, OutboundUrlPolicy.Scope.NONE, run));
        assertTrue(summary.blocked());
        assertEquals(3, summary.iterations().size());
        assertTrue(summary.iterations().stream().allMatch(RunTestResult::blocked));
        assertEquals(List.of(), received);
    }

    @Test
    void runHistoryStoresTheBlockedOutcome() {
        protect("127.0.0.1", port(production));
        enforce();
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-guard");
        spec.setName("Users");
        spec.setTitle("Users");
        spec.setLabel("General");
        spec.setBaseUrl("http://localhost");
        storage.saveSpec(spec);
        StudioTestCase write = request("POST", production, "/users");
        write.setSpecId("spec-guard");
        RunTestResult result = runs.execute(write, null, new LinkedHashMap<>());
        new RunHistoryService(storage, properties).recordCases(RunHistoryService.TRIGGER_TRY, null, List.of(write),
                List.of(result));
        assertEquals("blocked", jdbc.queryForObject("SELECT outcome FROM td_run_result", String.class));
    }

    private PlanRun.Actions actions(Map<String, String> variables, SafetyContext run) {
        return new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                return runs.execute(testCase, null, variables, null, OutboundUrlPolicy.Scope.NONE, run);
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return runs.skipped(testCase, reason);
            }

            @Override
            public boolean defined(String name) {
                return variables.containsKey(name);
            }

            @Override
            public boolean mayRetry(StudioTestCase testCase) {
                return run.mayRetry(testCase.getId());
            }
        };
    }

    private void protect(String target, int port) {
        policies.addTarget(new AddProtectedTargetRequest(target, List.of(port), "Production"));
    }

    private void enforce() {
        settings("enforce");
    }

    private void settings(String mode) {
        policies.updateSettings(new UpdateSafetySettingsRequest(mode, null, null, null, null, null,
                policies.settings().revision()));
    }

    private StoredEnvironment environment(String name, boolean protectedEnvironment) {
        if (storage.findSpec("spec-env").isEmpty()) {
            StoredSpec spec = new StoredSpec();
            spec.setSpecId("spec-env");
            spec.setName("Env");
            spec.setTitle("Env");
            spec.setLabel("General");
            spec.setBaseUrl("http://localhost");
            storage.saveSpec(spec);
        }
        StoredEnvironment environment = new StoredEnvironment();
        environment.setId(UUID.randomUUID().toString());
        environment.setSpecId("spec-env");
        environment.setName(name);
        environment.setDocumentJson(CollectionDocuments.toJson(
                EnvironmentMapper.merge(null, name, "http://127.0.0.1", "NONE")));
        storage.saveEnvironment(environment);
        storage.setEnvironmentProtected(environment.getId(), protectedEnvironment);
        return environment;
    }

    private static StudioTestCase request(String method, HttpServer server, String endpoint) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setTestName(method + " " + endpoint);
        testCase.setMethod(method);
        testCase.setBaseUrl("http://127.0.0.1:" + port(server));
        testCase.setEndpoint(endpoint);
        testCase.setStatusCode(200);
        testCase.setResponseType("JSON");
        testCase.setMaxResponseTimeMs(5000);
        return testCase;
    }

    private HttpServer server(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.add(name + " " + exchange.getRequestMethod() + " " + path);
            byte[] body = "{\"id\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.equals("/fail") ? 500 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton(type.getSimpleName(), value);
        }
        return factory.getBeanProvider(type);
    }
}
