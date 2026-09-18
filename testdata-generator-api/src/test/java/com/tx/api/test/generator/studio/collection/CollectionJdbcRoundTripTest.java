package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionJdbcRoundTripTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("collection_phase0_" + System.nanoTime()),
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
        // H2 2.4 CHECK IN (...) can keep a closed session and reject later param inserts.
        jdbcTemplate.execute("ALTER TABLE td_test_case_param DROP CONSTRAINT IF EXISTS ck_td_test_case_param_in");
        storage = new JdbcStudioStorage(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void roundTripsPlanThroughCollectionJsonAndH2() throws Exception {
        StudioCollection original = CollectionDocuments.parseCollection(readGolden());
        CollectionMapper.writeTo(storage, original);

        assertEquals("create", storage.findTestCase("case-create-role").orElseThrow().getOperationKey());
        assertEquals("{{roleId}}", storage.findTestCase("case-get-role").orElseThrow().getPathParams().get("id"));

        storage.deleteSpec("spec-employee-service");

        CollectionMapper.writeTo(storage, original);
        StudioCollection reloaded = CollectionMapper.readFrom(storage, "spec-employee-service", "plan-default");

        assertEquals("case-create-role", reloaded.cases().get(0).id());
        assertEquals("case-get-role", reloaded.cases().get(1).id());
        assertEquals("roleId=$.roleId", reloaded.cases().get(0).extracts());
        assertEquals("{{roleId}}", reloaded.cases().get(1).pathParams().get("id"));
        assertEquals("ADMIN", ((Map<?, ?>) reloaded.cases().get(0).body()).get("code"));
        assertEquals(CollectionDocuments.parseTree(CollectionDocuments.toJson(original)),
                CollectionDocuments.parseTree(CollectionDocuments.toJson(reloaded)));

        StudioTestCase create = storage.findTestCase("case-create-role").orElseThrow();
        StudioTestCase get = storage.findTestCase("case-get-role").orElseThrow();
        assertEquals("spec-employee-service", create.getSpecId());
        assertEquals("plan-default", get.getPlanId());
        assertEquals("/api/roles/{id}", get.getRawPath());
        assertEquals("/api/roles/{{roleId}}", get.getEndpoint());
        assertEquals("http://localhost:8080/employee-service", create.getBaseUrl());
        assertEquals("{{baseUrl}}", reloaded.cases().get(0).baseUrl());
    }

    @Test
    void exportReordersCreatedAtSoGetDoesNotLeadTheFile() {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-employee-service");
        spec.setName("employee-service");
        spec.setTitle("Employee Service");
        spec.setBaseUrl("http://localhost:8080/employee-service");
        storage.saveSpec(spec);

        StudioTestPlan plan = new StudioTestPlan();
        plan.setId("plan-default");
        plan.setSpecId(spec.getSpecId());
        plan.setName("Default");
        storage.savePlan(plan);

        StudioTestCase get = new StudioTestCase();
        get.setId("case-get-role");
        get.setSpecId(spec.getSpecId());
        get.setPlanId(plan.getId());
        get.setOperationKey("get");
        get.setTestName("get — 200");
        get.setMethod("GET");
        get.setRawPath("/api/roles/{id}");
        get.setEndpoint("/api/roles/{{roleId}}");
        get.setPathParams(Map.of("id", "{{roleId}}"));
        get.setStatusCode(200);
        get.setEnabled("Y");
        storage.saveTestCase(get);
        new JdbcTemplate(dataSource).update(
                "UPDATE td_test_case SET created_at = DATEADD('SECOND', -1, created_at) WHERE test_case_id = ?",
                get.getId());

        StudioTestCase create = new StudioTestCase();
        create.setId("case-create-role");
        create.setSpecId(spec.getSpecId());
        create.setPlanId(plan.getId());
        create.setOperationKey("create");
        create.setTestName("create — 200");
        create.setMethod("POST");
        create.setRawPath("/api/roles");
        create.setEndpoint("/api/roles");
        create.setRequestBody(Map.of("name", "Admin"));
        create.setExtracts("roleId=$.roleId");
        create.setStatusCode(200);
        create.setEnabled("Y");
        storage.saveTestCase(create);

        assertEquals("case-get-role", storage.listTestCases(spec.getSpecId(), plan.getId(), null).get(0).getId());

        StudioCollection exported = CollectionMapper.readFrom(storage, spec.getSpecId(), plan.getId());
        assertEquals("case-create-role", exported.cases().get(0).id());
        assertEquals("case-get-role", exported.cases().get(1).id());
    }

    private static String readGolden() throws Exception {
        try (var stream = CollectionJdbcRoundTripTest.class
                .getResourceAsStream("/collection/employee-service.Default.collection.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
