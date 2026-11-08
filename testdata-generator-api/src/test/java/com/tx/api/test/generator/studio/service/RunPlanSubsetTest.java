package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunPlanResult;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run plan with chosen cases runs those, plus the earlier cases they need values from, and nothing else. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_RUN_ALLOW_PRIVATE_NETWORKS=true",
                "TESTDATA_H2_FILE=target/run-plan-subset-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:run_plan_subset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class RunPlanSubsetTest {

    private static final String DEFINITION = """
            {"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{"/ok":{"post":{"operationId":"ok",
            "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
            "responses":{"200":{"description":"OK"}}}}}}
            """;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static HttpServer target;

    @Autowired
    private TestDataStudioService studio;
    @Autowired
    private SpecCatalogService catalog;

    private String specId;
    private String planId;
    private String environmentId;
    private TestCaseView producer;
    private TestCaseView consumer;
    private TestCaseView standalone;

    @BeforeAll
    static void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            REQUESTS.incrementAndGet();
            byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    @BeforeEach
    void newCollection() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null,
                "http://127.0.0.1:" + target.getAddress().getPort(), "Subset " + UUID.randomUUID(), null, null, null, null))
                .specId();
        planId = studio.listPlans(specId).get(0).id();
        environmentId = studio.listEnvironments(specId).get(0).id();
        producer = studio.create(save("Producer", "userId=$.id", null));
        consumer = studio.create(save("Consumer", "NA", "{{userId}}"));
        standalone = studio.create(save("Standalone", "NA", null));
        REQUESTS.set(0);
    }

    @Test
    void runsAChosenCaseAndTheCaseItNeedsAValueFrom() {
        RunPlanResult result = run(List.of(consumer.id()));

        assertEquals(List.of("Producer", "Consumer"), names(result));
        assertEquals(2, result.passed());
        assertTrue(result.allPassed());
        assertEquals(2, REQUESTS.get(), "the standalone case isn't sent");
    }

    @Test
    void runsOnlyTheChosenCaseWhenItNeedsNothing() {
        RunPlanResult result = run(List.of(standalone.id()));

        assertEquals(List.of("Standalone"), names(result));
        assertEquals(1, REQUESTS.get());
    }

    @Test
    void keepsRunOrderWhateverOrderTheyWereChosenIn() {
        RunPlanResult result = run(List.of(standalone.id(), consumer.id(), producer.id()));

        assertEquals(3, result.results().size());
        assertTrue(names(result).indexOf("Producer") < names(result).indexOf("Consumer"));
    }

    @Test
    void noChoiceRunsTheWholePlan() {
        assertEquals(3, run(null).results().size());
    }

    @Test
    void anEmptyOrUnknownChoiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> run(List.of()));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> run(List.of("not-a-case")));
        assertEquals("None of the chosen cases are in this plan", unknown.getMessage());
        assertEquals(0, REQUESTS.get());
    }

    private RunPlanResult run(List<String> caseIds) {
        return studio.runPlan(new RunPlanRequest(specId, planId, null, environmentId, null, null, null, null, caseIds));
    }

    private static List<String> names(RunPlanResult result) {
        return result.results().stream().map(RunTestResult::testName).toList();
    }

    private SaveTestDataRequest save(String name, String extracts, String headerValue) {
        Map<String, String> headers = headerValue == null ? Map.of() : Map.of("X-User", headerValue);
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), headers, Map.of(),
                new HashMap<>(Map.of("a", 1)), 200, "JSON", null, null, null, null, "Y", "READY", null, null, extracts,
                5000L, "application/json", environmentId, null, null, null, null, null);
    }
}
