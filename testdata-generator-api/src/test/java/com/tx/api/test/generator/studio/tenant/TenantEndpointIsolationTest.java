package com.tx.api.test.generator.studio.tenant;

import com.jayway.jsonpath.JsonPath;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.GrantWorkspaceAccessRequest;
import com.tx.api.test.generator.studio.safety.EmergencyAccess;
import com.tx.api.test.generator.studio.safety.SafetyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every endpoint that takes an ID, called with another workspace's IDs through the real filter and controllers: by
 * another organization, and by a member of a second workspace in the same organization. The call must be refused (or
 * answer an empty list), must not mention the owner's data, and must leave every row of the owner unchanged. The
 * owner then makes the same call, so a refusal means isolation and not a malformed request.
 *
 * {@link #everyEndpointThatTakesAnIdIsCovered} fails when an endpoint is added without an attempt here.
 *
 * Runs on H2, or on a real database named by TESTDATA_TEST_JDBC_URL (with TESTDATA_TEST_JDBC_USER / _PASSWORD).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.profiles.active=local",
                "testdata.edition=saas",
                "testdata.safety.enabled=true",
                "testdata.run.allow-private-networks=true",
                "TESTDATA_H2_FILE=target/tenant-endpoint-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:tenant_endpoints;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class TenantEndpointIsolationTest {

    /** In every name the owner gives, so any of the owner's data in an answer shows. */
    private static final String MARK = "ZQSECRET";
    private static final String PASSWORD = "password1234";
    private static final String DEFINITION = """
            {"openapi":"3.0.3","info":{"title":"ZQSECRET api","version":"1"},"paths":{"/ok":{"post":{"operationId":"ok",
            "summary":"ZQSECRET operation","responses":{"200":{"description":"OK"}}}}}}
            """;

    /** Endpoints with an ID that isn't a workspace's: why each needs no attempt. */
    private static final Map<String, String> NOT_WORKSPACE_DATA = Map.of(
            "PUT /api/saas/subscriptions/{orgId}", "SaaS operators only, for every organization by design (TenantIsolationTest)",
            "POST /api/auth/login", "signing in with another's remembered workspace: TenantIsolationTest",
            "POST /api/auth/oidc/exchange", "a one-time sign-in exchange, before any workspace",
            "POST /api/auth/oidc/register", "a one-time sign-in exchange, before any workspace");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TESTDATA_TEST_JDBC_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv("TESTDATA_TEST_JDBC_USER"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TESTDATA_TEST_JDBC_PASSWORD", ""));
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("testdata.database.require-row-level-security", () -> "true");
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantFilter tenantFilter;
    @Autowired
    private AuthService auth;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SafetyStore safetyStore;

    private MockMvc mvc;

    /** A call: method, path template, path variables, then query or body. */
    private record Attempt(String method, String path, List<String> vars, String query, String body, boolean multipart,
                           boolean mixed, Set<Integer> ownerStatus) {
        Attempt owner(Integer... statuses) {
            return new Attempt(method, path, vars, query, body, multipart, mixed, Set.of(statuses));
        }

        /** Uses the caller's own collection next to the owner's ID; the owner can't make the same call. */
        Attempt mixed(String name) {
            return new Attempt(method, path, vars, query, body, multipart, true, ownerStatus);
        }

        String key() {
            return method + " " + path;
        }
    }

    private static final class Workspace {
        AuthSessionView owner;
        String tenantId;
        String token;
        String memberId;
        String specId;
        String planId;
        String environmentId;
        String caseId;
        String fileId;
        String versionId;
        String runId;
        String resultId;
        String approvalId;
        String accessId;
        String targetId;
        String ruleId;
    }

    @BeforeEach
    void client() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tenantFilter).build();
    }

    @Test
    void everyEndpointThatTakesAnIdIsCovered() {
        Workspace sample = new Workspace();
        Workspace other = new Workspace();
        sample.owner = null;
        Set<String> covered = new TreeSet<>(NOT_WORKSPACE_DATA.keySet());
        attempts(sample, other).forEach(attempt -> covered.add(attempt.key()));
        Set<String> missing = new TreeSet<>();
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods()
                .forEach((info, handler) -> {
                    boolean takesId = Arrays.stream(handler.getMethodParameters()).anyMatch(TenantEndpointIsolationTest::isId);
                    if (takesId) {
                        keys(info).stream().filter(key -> key.contains(" /api/") && !covered.contains(key))
                                .forEach(missing::add);
                    }
                });
        assertEquals(Set.of(), missing, "add an attempt for each of these endpoints");
    }

    @TestFactory
    Stream<DynamicTest> anotherOrganizationGetsNothing() {
        return names().map(index -> DynamicTest.dynamicTest(label(index), () -> check(index, false)));
    }

    @TestFactory
    Stream<DynamicTest> aMemberOfAnotherWorkspaceInTheOrganizationGetsNothing() {
        // The organization's own endpoints are for the whole organization; their roles are tested elsewhere.
        // So is adding one of the organization's people to the workspace you manage.
        return names().filter(index -> !label(index).contains("/organizations/current/")
                        && !label(index).equals("POST /api/tenants/current/members"))
                .map(index -> DynamicTest.dynamicTest(label(index), () -> check(index, true)));
    }

    private Stream<Integer> names() {
        return Stream.iterate(0, index -> index + 1).limit(attempts(new Workspace(), new Workspace()).size());
    }

    private String label(int index) {
        Attempt attempt = attempts(new Workspace(), new Workspace()).get(index);
        return attempt.key() + (attempt.mixed() ? " (own collection)" : "");
    }

    private void check(int index, boolean sameOrganization) throws Exception {
        Workspace acme = workspace(MARK);
        Workspace other = sameOrganization ? secondWorkspace(acme) : workspace("Plain");
        Attempt attempt = attempts(acme, other).get(index);

        String before = fingerprint(acme, !sameOrganization);
        MockHttpServletResponse refused = send(attempt, other.token);
        String body = refused.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(MARK), attempt.key() + " shows the owner's data: " + body);
        boolean empty = refused.getStatus() == 200 && Set.of("[]", "").contains(body)
                || refused.getStatus() == 200 && body.startsWith("{\"items\":[],\"total\":0");
        assertTrue(empty || Set.of(400, 403, 404, 409).contains(refused.getStatus()),
                attempt.key() + " answered " + refused.getStatus() + ": " + body);
        assertEquals(before, fingerprint(acme, !sameOrganization), attempt.key() + " changed the owner's data");

        if (!attempt.mixed()) {
            MockHttpServletResponse own = send(attempt, acme.token);
            assertTrue(own.getStatus() / 100 == 2 || attempt.ownerStatus().contains(own.getStatus()),
                    attempt.key() + ": the owner's own call answered " + own.getStatus() + ": "
                            + own.getContentAsString(StandardCharsets.UTF_8));
        }
    }

    private static List<Attempt> attempts(Workspace o, Workspace mine) {
        List<Attempt> list = new ArrayList<>();
        // Collections
        list.add(get("/api/specs/{specId}", o.specId));
        list.add(put("/api/specs/{specId}", "{\"name\":\"" + MARK + " renamed\",\"label\":\"General\"}", o.specId));
        list.add(delete("/api/specs/{specId}", o.specId));
        list.add(post("/api/specs/{specId}/reload", json("content", DEFINITION), o.specId));
        list.add(upload("/api/specs/{specId}/reload/upload", o.specId));
        list.add(get("/api/specs/{specId}/operations", o.specId));
        list.add(get("/api/specs/{specId}/operations/{operationKey}", o.specId, "ok"));
        list.add(post("/api/specs/{specId}/operations", "{\"method\":\"GET\",\"path\":\"/new\",\"statusCode\":200}",
                o.specId).owner(400));
        list.add(delete("/api/specs/{specId}/operations/{operationKey}", o.specId, "ok").owner(400));
        list.add(query("/api/specs/activity", "ids=" + o.specId));
        list.add(query("/api/specs", "ids=" + o.specId));
        // Plans
        list.add(get("/api/specs/{specId}/plans", o.specId));
        list.add(post("/api/specs/{specId}/plans", "{\"name\":\"More\"}", o.specId));
        list.add(put("/api/specs/{specId}/plans/{planId}", "{\"name\":\"Other\"}", o.specId, o.planId));
        list.add(put("/api/specs/{specId}/plans/{planId}", "{\"name\":\"Other\"}", mine.specId, o.planId).mixed("plan"));
        list.add(delete("/api/specs/{specId}/plans/{planId}", o.specId, o.planId).owner(400, 409));
        list.add(delete("/api/specs/{specId}/plans/{planId}", mine.specId, o.planId).mixed("plan"));
        // Environments
        String environment = "{\"name\":\"Changed\",\"baseUrl\":\"http://127.0.0.1:1\",\"authType\":\"NONE\"}";
        list.add(get("/api/specs/{specId}/environments", o.specId));
        list.add(get("/api/specs/{specId}/environments/{environmentId}", o.specId, o.environmentId));
        list.add(get("/api/specs/{specId}/environments/{environmentId}", mine.specId, o.environmentId).mixed("env"));
        list.add(post("/api/specs/{specId}/environments", environment, o.specId));
        list.add(put("/api/specs/{specId}/environments/{environmentId}", environment, o.specId, o.environmentId));
        list.add(put("/api/specs/{specId}/environments/{environmentId}", environment, mine.specId, o.environmentId).mixed("env"));
        list.add(post("/api/specs/{specId}/environments/{environmentId}/duplicate", null, o.specId, o.environmentId));
        list.add(post("/api/specs/{specId}/environments/{environmentId}/duplicate", null, mine.specId, o.environmentId).mixed("env"));
        list.add(post("/api/specs/{specId}/environments/{environmentId}/apply", null, o.specId, o.environmentId));
        list.add(delete("/api/specs/{specId}/environments/{environmentId}", o.specId, o.environmentId).owner(400));
        list.add(delete("/api/specs/{specId}/environments/{environmentId}", mine.specId, o.environmentId).mixed("env"));
        // Cases
        list.add(query("/api/testdata", "specId=" + o.specId));
        list.add(query("/api/testdata", "specId=" + o.specId + "&planId=" + o.planId));
        list.add(get("/api/testdata/{id}", o.caseId));
        list.add(get("/api/testdata/{id}/audit", o.caseId));
        list.add(get("/api/testdata/{id}/versions", o.caseId));
        list.add(post("/api/testdata/{id}/versions/{versionId}/restore", "{}", o.caseId, o.versionId).owner(409));
        list.add(post("/api/testdata/{id}/versions/{versionId}/restore", "{}", mine.caseId, o.versionId).mixed("version"));
        list.add(post("/api/testdata", testCase(o.specId, o.planId, MARK + " added"), new String[0]));
        list.add(post("/api/testdata", testCase(mine.specId, o.planId, "Mixed")).mixed("plan"));
        list.add(put("/api/testdata/{id}", testCase(o.specId, o.planId, MARK + " case"), o.caseId).owner(409));
        list.add(put("/api/testdata/{id}", testCase(mine.specId, mine.planId, "Taken"), o.caseId).mixed("case"));
        list.add(post("/api/testdata/{id}/run", "{\"environmentId\":\"" + o.environmentId + "\"}", o.caseId));
        list.add(post("/api/testdata/{id}/duplicate", "{\"toEnvironmentId\":\"" + o.environmentId + "\"}", o.caseId));
        list.add(post("/api/testdata/{id}/duplicate", "{\"toEnvironmentId\":\"" + o.environmentId + "\"}", mine.caseId).mixed("env"));
        list.add(delete("/api/testdata/{id}/draft", o.caseId));
        list.add(delete("/api/testdata/{id}", o.caseId));
        list.add(post("/api/testdata/run", "{\"caseId\":\"" + o.caseId + "\"}"));
        list.add(post("/api/testdata/run-plan", plan(o.specId, o.planId, o.environmentId)));
        list.add(post("/api/testdata/run-plan", plan(mine.specId, o.planId, null)).mixed("plan"));
        list.add(post("/api/testdata/validate-plan", plan(o.specId, o.planId, o.environmentId)));
        list.add(post("/api/testdata/validate-plan", plan(mine.specId, o.planId, null)).mixed("plan"));
        list.add(post("/api/testdata/export-collection", plan(o.specId, o.planId, null)));
        list.add(post("/api/testdata/export-collection", plan(mine.specId, o.planId, null)).mixed("plan"));
        list.add(post("/api/testdata/export-environment", plan(o.specId, null, o.environmentId)));
        list.add(post("/api/testdata/export-environment", plan(mine.specId, null, o.environmentId)).mixed("env"));
        list.add(new Attempt("POST", "/api/testdata/import-collection", List.of(), "specId=" + o.specId,
                "{\"format\":\"testdata.studio.collection\",\"version\":1}", false, false, Set.of(400)));
        list.add(new Attempt("POST", "/api/testdata/import-environment", List.of(), "specId=" + o.specId,
                "{\"format\":\"testdata.studio.environment\",\"version\":1}", false, false, Set.of(400)));
        // Files
        list.add(upload("/api/testdata/{caseId}/files", o.caseId));
        list.add(get("/api/testdata/{caseId}/files/{fileId}", o.caseId, o.fileId));
        list.add(get("/api/testdata/{caseId}/files/{fileId}", mine.caseId, o.fileId).mixed("file"));
        // Variables
        list.add(get("/api/variables/collections/{specId}", o.specId));
        list.add(put("/api/variables/collections/{specId}", "{\"variables\":[]}", o.specId));
        list.add(get("/api/variables/collections/{specId}/shared-settings", o.specId));
        list.add(put("/api/variables/collections/{specId}/shared-settings", "{}", o.specId));
        // Runs
        list.add(query("/api/runs", "specId=" + o.specId));
        list.add(query("/api/runs/latest-by-case", "specId=" + o.specId));
        list.add(query("/api/runs/compare", "leftRunId=" + o.runId + "&leftResultId=" + o.resultId + "&rightRunId="
                + o.runId + "&rightResultId=" + o.resultId).owner(400));
        list.add(get("/api/runs/{runId}", o.runId));
        list.add(get("/api/runs/{runId}/results/{resultId}", o.runId, o.resultId));
        list.add(delete("/api/runs/{runId}", o.runId));
        // Audit
        list.add(get("/api/audit/cases/{caseId}", o.caseId));
        list.add(get("/api/audit/specs/{specId}", o.specId));
        // Production safety
        list.add(post("/api/safety/evaluate", "{\"caseId\":\"" + o.caseId + "\",\"environmentId\":\"" + o.environmentId + "\"}"));
        list.add(post("/api/safety/evaluate", "{\"caseId\":\"" + mine.caseId + "\",\"environmentId\":\"" + o.environmentId + "\"}").mixed("env"));
        list.add(post("/api/runs/plan/preflight", plan(o.specId, o.planId, o.environmentId)));
        list.add(post("/api/runs/plan/preflight", plan(mine.specId, mine.planId, o.environmentId)).mixed("env"));
        list.add(query("/api/safety/environments", "specId=" + o.specId));
        list.add(query("/api/safety/emergency", "environmentId=" + o.environmentId));
        list.add(put("/api/environments/{environmentId}/protection", "{\"protected\":false}", o.environmentId));
        String ask = "{\"environmentId\":\"" + o.environmentId + "\",\"reason\":\"Create the smoke test user\",\"days\":7}";
        list.add(post("/api/testdata/{caseId}/approvals", ask, o.caseId).owner(400));
        list.add(post("/api/testdata/{caseId}/approvals", ask, mine.caseId).mixed("env"));
        list.add(new Attempt("GET", "/api/testdata/{caseId}/approvals", vars(o.caseId),
                "environmentId=" + o.environmentId, null, false, false, Set.of()));
        list.add(delete("/api/testdata/{caseId}/approvals/{approvalId}", o.caseId, o.approvalId));
        for (String decision : List.of("approve", "reject", "revoke")) {
            list.add(post("/api/organizations/current/safety/approvals/{approvalId}/" + decision, "{}", o.approvalId)
                    .owner(403, 409));
        }
        list.add(post("/api/organizations/current/safety/emergency", "{\"environmentId\":\"" + o.environmentId
                + "\",\"minutes\":30,\"reason\":\"Fix the broken order\"}").owner(400));
        list.add(post("/api/organizations/current/safety/emergency/{accessId}/confirm", null, o.accessId).owner(403, 409));
        list.add(delete("/api/organizations/current/safety/emergency/{accessId}", o.accessId));
        list.add(delete("/api/organizations/current/safety/targets/{targetId}", o.targetId));
        String rule = "{\"effect\":\"block\",\"protocol\":\"rest\",\"httpMethod\":\"DELETE\",\"pathTemplate\":\"/x\",\"reason\":\""
                + MARK + " rule reason\",\"targetId\":" + (o.targetId == null ? "null" : "\"" + o.targetId + "\"") + "}";
        list.add(post("/api/organizations/current/safety/rules", rule).owner(400, 409));
        list.add(put("/api/organizations/current/safety/rules/{ruleId}", rule, o.ruleId));
        list.add(delete("/api/organizations/current/safety/rules/{ruleId}", o.ruleId));
        // People and workspaces
        String role = "{\"role\":\"ADMIN\"}";
        list.add(put("/api/tenants/current/members/{accountId}", role, o.memberId));
        list.add(delete("/api/tenants/current/members/{accountId}", o.memberId));
        String grant = "{\"accountId\":\"" + o.memberId + "\",\"role\":\"MEMBER\"}";
        list.add(post("/api/tenants/current/members", grant).owner(400));
        list.add(put("/api/organizations/current/members/{accountId}", role, o.memberId));
        list.add(post("/api/organizations/current/members/{accountId}/password-reset", null, o.memberId));
        list.add(delete("/api/organizations/current/members/{accountId}", o.memberId));
        String workspace = "/api/organizations/current/workspaces/{workspaceId}";
        list.add(get(workspace + "/grant-candidates", o.tenantId));
        list.add(get(workspace + "/members", o.tenantId));
        list.add(get(workspace + "/audit", o.tenantId));
        list.add(get(workspace + "/access-requests", o.tenantId));
        list.add(post(workspace + "/members", grant, o.tenantId).owner(400));
        list.add(post(workspace + "/members", grant, mine.tenantId).mixed("account"));
        list.add(put(workspace + "/members/{accountId}", role, o.tenantId, o.memberId));
        list.add(delete(workspace + "/members/{accountId}", o.tenantId, o.memberId));
        list.add(post("/api/organizations/current/access-requests", "{\"workspaceId\":\"" + o.tenantId + "\"}").owner(400, 403));
        list.add(post("/api/auth/switch-tenant", "{\"tenantId\":\"" + o.tenantId + "\"}"));
        list.add(post("/api/auth/switch-workspace", "{\"workspaceId\":\"" + o.tenantId + "\"}"));
        return list;
    }

    // --- The owner's data ---

    private Workspace workspace(String name) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Workspace w = new Workspace();
        w.owner = auth.register(new RegisterRequest(name.toLowerCase() + "-" + id + "@example.test", PASSWORD,
                name + " owner", name + " " + id));
        w.tenantId = w.owner.tenant().id();
        w.token = w.owner.token();
        String orgId = w.owner.organization().id();
        jdbc.update("UPDATE td_subscription SET seat_limit = 10, workspace_limit = 10 WHERE org_id = ?", orgId);
        TenantContext.runAs(w.tenantId, w.owner.account().id(), "OWNER", () -> {
            w.memberId = auth.addOrgMember(new AddMemberRequest(name.toLowerCase() + "-dev-" + id + "@example.test",
                    name + " dev", PASSWORD, "MEMBER")).accountId();
            auth.grantWorkspaceAccess(new GrantWorkspaceAccessRequest(w.memberId, "MEMBER"));
        });
        collection(w, name);

        String protection = "/api/organizations/current/safety";
        ok(send(put(protection, "{\"mode\":\"enforce\",\"expectedRevision\":0}"), w.token));
        String settings = ok(send(post(protection + "/targets", "{\"target\":\"" + name.toLowerCase() + "-" + id
                + ".example.test\",\"note\":\"" + name + " note\"}"), w.token));
        w.targetId = JsonPath.read(settings, "$.targets[0].id");
        settings = ok(send(post(protection + "/rules", "{\"effect\":\"block\",\"protocol\":\"rest\",\"httpMethod\":\"DELETE\","
                + "\"pathTemplate\":\"/orders\",\"reason\":\"" + name + " rule reason\"}"), w.token));
        w.ruleId = JsonPath.read(settings, "$.rules[0].id");
        ok(send(put("/api/environments/{environmentId}/protection", "{\"protected\":true}", w.environmentId), w.token));
        w.approvalId = JsonPath.read(ok(send(post("/api/testdata/{caseId}/approvals", "{\"environmentId\":\""
                + w.environmentId + "\",\"reason\":\"" + name + " approval reason\",\"days\":7}", w.caseId), w.token)), "$.id");
        w.accessId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        safetyStore.insertEmergencyAccess(new EmergencyAccess(w.accessId, orgId, w.environmentId,
                name + " emergency reason", w.owner.account().id(), w.owner.account().id(), now.minusSeconds(5),
                now.plusSeconds(1800), null, 0, null, null));
        return w;
    }

    private void collection(Workspace w, String name) throws Exception {
        w.specId = JsonPath.read(ok(send(post("/api/specs", "{\"name\":\"" + name + " " + UUID.randomUUID()
                + "\",\"baseUrl\":\"http://127.0.0.1:1\",\"content\":" + quoted(DEFINITION.replace(MARK, name)) + "}"),
                w.token)), "$.specId");
        w.planId = JsonPath.read(ok(send(get("/api/specs/{specId}/plans", w.specId), w.token)), "$[0].id");
        w.environmentId = JsonPath.read(ok(send(post("/api/specs/{specId}/environments", "{\"name\":\"" + name
                + " production\",\"baseUrl\":\"http://127.0.0.1:1\",\"authType\":\"NONE\",\"variables\":[{\"key\":\"v\","
                + "\"value\":\"" + name + " value\",\"secret\":false}]}", w.specId), w.token)), "$.id");
        w.caseId = JsonPath.read(ok(send(post("/api/testdata", testCase(w.specId, w.planId, name + " case")), w.token)), "$.id");
        w.versionId = JsonPath.read(ok(send(get("/api/testdata/{id}/versions", w.caseId), w.token)), "$[0].id");
        w.fileId = JsonPath.read(ok(send(upload("/api/testdata/{caseId}/files", w.caseId), w.token)), "$.fileId");
        ok(send(put("/api/variables/collections/{specId}", "{\"variables\":[{\"key\":\"c\",\"value\":\"" + name
                + " variable\",\"secret\":false}]}", w.specId), w.token));
        ok(send(put("/api/variables/collections/{specId}/shared-settings", "{\"headers\":{\"X-Shared\":\"" + name
                + "\"}}", w.specId), w.token));
        ok(send(put("/api/variables/globals", "{\"variables\":[{\"key\":\"g\",\"value\":\"" + name + " global\",\"secret\":false}]}"), w.token));
        ok(send(put("/api/testdata/{id}", testCase(w.specId, w.planId, name + " draft").replace("READY", "DRAFT"),
                w.caseId), w.token));
        ok(send(post("/api/testdata/{id}/run", "{\"environmentId\":\"" + w.environmentId + "\"}", w.caseId), w.token));
        String runs = ok(send(query("/api/runs", "specId=" + w.specId), w.token));
        w.runId = JsonPath.read(runs, "$[0].runId");
        w.resultId = JsonPath.read(ok(send(get("/api/runs/{runId}", w.runId), w.token)), "$.results[0].resultId");
    }

    /** A second workspace of the owner's organization, with a member who isn't in the first. */
    private Workspace secondWorkspace(Workspace acme) throws Exception {
        Workspace w = new Workspace();
        w.tenantId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO td_tenant (tenant_id, name, slug, org_id, ready) VALUES (?, 'Second', ?, ?, TRUE)",
                w.tenantId, "second-" + w.tenantId.substring(0, 8), acme.owner.organization().id());
        String email = "second-" + w.tenantId.substring(0, 8) + "@example.test";
        TenantContext.runAs(acme.tenantId, acme.owner.account().id(), "OWNER", () -> w.memberId = auth.addOrgMember(
                new AddMemberRequest(email, "Second dev", PASSWORD, "MEMBER")).accountId());
        TenantContext.runAs(w.tenantId, acme.owner.account().id(), "OWNER",
                () -> auth.grantWorkspaceAccess(new GrantWorkspaceAccessRequest(w.memberId, "ADMIN")));
        w.token = auth.login(new LoginRequest(email, PASSWORD, w.tenantId)).token();
        collection(w, "Plain");
        return w;
    }

    /** Every row of the owner's workspace, and of its organization when the caller is from another one. */
    private String fingerprint(Workspace w, boolean organization) {
        String orgId = w.owner.organization().id();
        return DatabaseTenant.acrossTenants(() -> {
            StringBuilder out = new StringBuilder();
            for (String table : List.of("td_spec", "td_test_plan", "td_environment", "td_collection_variables",
                    "td_collection_settings", "td_test_case", "td_test_case_param", "td_request_file",
                    "td_test_case_draft", "td_test_case_version", "td_test_case_environment", "td_run",
                    "td_run_result", "td_global_variables", "td_audit_event", "td_membership")) {
                rows(out, table, "SELECT * FROM " + table + " WHERE tenant_id = ?", w.tenantId);
            }
            for (String table : List.of("td_safety_policy", "td_protected_target", "td_operation_rule",
                    "td_write_approval", "td_emergency_access", "td_org_membership", "td_usage_month")) {
                if (!organization && table.equals("td_usage_month")) {
                    continue; // Runs count against the organization, whichever workspace starts them.
                }
                rows(out, table, "SELECT * FROM " + table + " WHERE org_id = ?", orgId);
            }
            rows(out, "td_account", "SELECT * FROM td_account WHERE account_id IN (SELECT account_id FROM"
                    + " td_membership WHERE tenant_id = ?)", w.tenantId);
            return out.toString();
        });
    }

    private void rows(StringBuilder out, String table, String sql, String id) {
        List<String> rows = new ArrayList<>();
        jdbc.queryForList(sql, id).forEach(row -> {
            row.replaceAll((column, value) -> value instanceof byte[] bytes ? "bytes " + bytes.length : value);
            rows.add(row.toString());
        });
        rows.sort(String::compareTo);
        out.append(table).append(rows).append('\n');
    }

    // --- Calls ---

    private MockHttpServletResponse send(Attempt attempt, String token) throws Exception {
        Object[] vars = attempt.vars().toArray();
        String path = attempt.path() + (attempt.query() == null ? "" : "?" + attempt.query());
        AbstractMockHttpServletRequestBuilder<?> request;
        if (attempt.multipart()) {
            request = MockMvcRequestBuilders.multipart(path, vars).file(new MockMultipartFile("file", "api.json",
                    "application/json", DEFINITION.getBytes(StandardCharsets.UTF_8)));
        } else {
            request = switch (attempt.method()) {
                case "GET" -> MockMvcRequestBuilders.get(path, vars);
                case "PUT" -> MockMvcRequestBuilders.put(path, vars);
                case "DELETE" -> MockMvcRequestBuilders.delete(path, vars);
                default -> MockMvcRequestBuilders.post(path, vars);
            };
            if (attempt.body() != null) {
                request.contentType("application/json").content(attempt.body());
            }
        }
        return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private static String ok(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(response.getStatus() / 100 == 2, response.getStatus() + ": " + body);
        return body;
    }

    private static Attempt get(String path, String... vars) {
        return new Attempt("GET", path, vars(vars), null, null, false, false, Set.of());
    }

    private static Attempt query(String path, String query) {
        return new Attempt("GET", path, List.of(), query, null, false, false, Set.of());
    }

    private static Attempt delete(String path, String... vars) {
        return new Attempt("DELETE", path, vars(vars), null, null, false, false, Set.of());
    }

    private static Attempt put(String path, String body, String... vars) {
        return new Attempt("PUT", path, vars(vars), null, body, false, false, Set.of());
    }

    private static Attempt post(String path, String body, String... vars) {
        return new Attempt("POST", path, vars(vars), null, body, false, false, Set.of());
    }

    private static Attempt upload(String path, String... vars) {
        return new Attempt("POST", path, vars(vars), null, null, true, false, Set.of());
    }

    private static List<String> vars(String... values) {
        return Arrays.stream(values).map(value -> value == null ? "-" : value).toList();
    }

    private static String testCase(String specId, String planId, String name) {
        return "{\"specId\":\"" + specId + "\",\"planId\":\"" + planId + "\",\"operationKey\":\"ok\",\"testName\":\"" + name
                + "\",\"baseUrl\":\"{{baseUrl}}\",\"statusCode\":200,\"responseType\":\"JSON\",\"enabled\":\"Y\","
                + "\"lifecycle\":\"READY\",\"queryParams\":{\"q\":\"1\"},\"maxResponseTimeMs\":2000}";
    }

    private static String plan(String specId, String planId, String environmentId) {
        return "{\"specId\":\"" + specId + "\"" + (planId == null ? "" : ",\"planId\":\"" + planId + "\"")
                + (environmentId == null ? "" : ",\"environmentId\":\"" + environmentId + "\"") + "}";
    }

    private static String json(String key, String value) {
        return "{\"" + key + "\":" + quoted(value) + "}";
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    // --- Which endpoints take an ID ---

    private static List<String> keys(RequestMappingInfo info) {
        List<String> keys = new ArrayList<>();
        info.getMethodsCondition().getMethods().forEach(method -> info.getPatternValues()
                .forEach(pattern -> keys.add(method + " " + pattern)));
        return keys;
    }

    private static boolean isId(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return true;
        }
        if (parameter.hasParameterAnnotation(RequestParam.class)) {
            return isIdName(parameter.getParameter().getName());
        }
        return parameter.hasParameterAnnotation(RequestBody.class) && parameter.getParameterType().isRecord()
                && Arrays.stream(parameter.getParameterType().getRecordComponents())
                .anyMatch(component -> isIdName(component.getName()));
    }

    private static boolean isIdName(String name) {
        return name.equals("ids") || name.endsWith("Id") || name.endsWith("Ids");
    }
}
