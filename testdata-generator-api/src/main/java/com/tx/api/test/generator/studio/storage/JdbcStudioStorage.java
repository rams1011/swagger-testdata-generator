package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

public class JdbcStudioStorage implements StudioStorage {

    private final JdbcTemplate jdbcTemplate;

    public JdbcStudioStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveSpec(StoredSpec spec) {
        Instant now = Instant.now();
        Optional<StoredSpec> existing = findSpec(spec.getSpecId());
        spec.setCreatedAt(existing.map(StoredSpec::getCreatedAt).orElse(now));
        spec.setUpdatedAt(now);
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                            UPDATE td_spec
                               SET name = ?, title = ?, version = ?, description = ?, base_url = ?, source_location = ?,
                                   source_content = ?, operation_count = ?, updated_at = ?
                             WHERE spec_id = ?
                            """,
                    spec.getName(), spec.getTitle(), spec.getVersion(), spec.getDescription(), spec.getBaseUrl(),
                    spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                    Timestamp.from(spec.getUpdatedAt()), spec.getSpecId());
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_spec (
                            spec_id, name, title, version, description, base_url, source_location, source_content,
                            operation_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                spec.getSpecId(), spec.getName(), spec.getTitle(), spec.getVersion(), spec.getDescription(),
                spec.getBaseUrl(), spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                Timestamp.from(spec.getCreatedAt()), Timestamp.from(spec.getUpdatedAt()));
    }

    @Override
    public Optional<StoredSpec> findSpec(String specId) {
        List<StoredSpec> specs = jdbcTemplate.query(
                "SELECT * FROM td_spec WHERE spec_id = ?", specMapper(), specId);
        return specs.stream().findFirst();
    }

    @Override
    public List<StoredSpec> listSpecs() {
        return jdbcTemplate.query("SELECT * FROM td_spec ORDER BY name, title", specMapper());
    }

    @Override
    public void deleteSpec(String specId) {
        jdbcTemplate.update("DELETE FROM td_test_case_param WHERE test_case_id IN (SELECT test_case_id FROM td_test_case WHERE spec_id = ?)",
                specId);
        jdbcTemplate.update(
                "DELETE FROM td_request_file WHERE test_case_id IN (SELECT test_case_id FROM td_test_case WHERE spec_id = ?)",
                specId);
        jdbcTemplate.update("DELETE FROM td_test_case WHERE spec_id = ?", specId);
        jdbcTemplate.update("DELETE FROM td_test_plan WHERE spec_id = ?", specId);
        int deleted = jdbcTemplate.update("DELETE FROM td_spec WHERE spec_id = ?", specId);
        if (deleted == 0) {
            throw new NoSuchElementException("Spec not found: " + specId);
        }
    }

    @Override
    public void ensureSpecNameUniqueIndex() {
        try {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_td_spec_name ON td_spec (LOWER(name))");
        } catch (RuntimeException ignored) {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_td_spec_name ON td_spec (name)");
        }
    }

    @Override
    public void savePlan(StudioTestPlan plan) {
        Instant now = Instant.now();
        Optional<StudioTestPlan> existing = findPlan(plan.getId());
        plan.setCreatedAt(existing.map(StudioTestPlan::getCreatedAt).orElse(now));
        plan.setUpdatedAt(now);
        Timestamp updated = Timestamp.from(plan.getUpdatedAt());
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                            UPDATE td_test_plan
                               SET spec_id = ?, name = ?, description = ?, updated_at = ?
                             WHERE plan_id = ?
                            """,
                    plan.getSpecId(), plan.getName(), plan.getDescription(), updated, plan.getId());
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_test_plan (plan_id, spec_id, name, description, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                plan.getId(), plan.getSpecId(), plan.getName(), plan.getDescription(),
                Timestamp.from(plan.getCreatedAt()), updated);
    }

    @Override
    public void deletePlan(String planId) {
        jdbcTemplate.update(
                "DELETE FROM td_request_file WHERE test_case_id IN (SELECT test_case_id FROM td_test_case WHERE plan_id = ?)",
                planId);
        jdbcTemplate.update("DELETE FROM td_test_case WHERE plan_id = ?", planId);
        int deleted = jdbcTemplate.update("DELETE FROM td_test_plan WHERE plan_id = ?", planId);
        if (deleted == 0) {
            throw new NoSuchElementException("Test plan not found: " + planId);
        }
    }

    @Override
    public Optional<StudioTestPlan> findPlan(String planId) {
        List<StudioTestPlan> plans = jdbcTemplate.query(
                "SELECT * FROM td_test_plan WHERE plan_id = ?", planMapper(), planId);
        return plans.stream().findFirst();
    }

    @Override
    public List<StudioTestPlan> listPlans(String specId) {
        return jdbcTemplate.query(
                "SELECT * FROM td_test_plan WHERE spec_id = ? ORDER BY name", planMapper(), specId);
    }

    @Override
    public void saveTestCase(StudioTestCase testCase) {
        boolean exists = findTestCase(testCase.getId()).isPresent();
        Instant now = Instant.now();
        Timestamp updated = Timestamp.from(now);
        if (exists) {
            jdbcTemplate.update("""
                            UPDATE td_test_case
                               SET spec_id = ?, plan_id = ?, operation_key = ?, test_name = ?, http_method = ?, raw_path = ?,
                                   endpoint = ?, base_url = ?, content_type = ?, request_body_json = ?,
                                   response_schema_json = ?, status_code = ?, response_type = ?, expected_response = ?,
                                   enabled = ?, test_group = ?, json_path_assertions = ?, max_response_time_ms = ?,
                                   updated_at = ?
                             WHERE test_case_id = ?
                            """,
                    testCase.getSpecId(), testCase.getPlanId(), testCase.getOperationKey(), testCase.getTestName(),
                    testCase.getMethod(), testCase.getRawPath(), testCase.getEndpoint(), testCase.getBaseUrl(),
                    testCase.getContentType(), JsonMaps.toJson(testCase.getRequestBody()),
                    JsonMaps.toJson(testCase.getResponseJsonSchema()), testCase.getStatusCode(),
                    testCase.getResponseType(), testCase.getExpectedResponse(), testCase.getEnabled(),
                    testCase.getTestGroup(), testCase.getJsonPathAssertions(), testCase.getMaxResponseTimeMs(),
                    updated, testCase.getId());
            jdbcTemplate.update("DELETE FROM td_test_case_param WHERE test_case_id = ?", testCase.getId());
        } else {
            jdbcTemplate.update("""
                            INSERT INTO td_test_case (
                                test_case_id, spec_id, plan_id, operation_key, test_name, http_method, raw_path, endpoint,
                                base_url, content_type, request_body_json, response_schema_json, status_code,
                                response_type, expected_response, enabled, test_group, json_path_assertions,
                                max_response_time_ms, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    testCase.getId(), testCase.getSpecId(), testCase.getPlanId(), testCase.getOperationKey(),
                    testCase.getTestName(), testCase.getMethod(), testCase.getRawPath(), testCase.getEndpoint(),
                    testCase.getBaseUrl(), testCase.getContentType(), JsonMaps.toJson(testCase.getRequestBody()),
                    JsonMaps.toJson(testCase.getResponseJsonSchema()), testCase.getStatusCode(),
                    testCase.getResponseType(), testCase.getExpectedResponse(), testCase.getEnabled(),
                    testCase.getTestGroup(), testCase.getJsonPathAssertions(), testCase.getMaxResponseTimeMs(),
                    updated, updated);
        }
        insertParams(testCase);
    }

    @Override
    public void deleteTestCase(String testCaseId) {
        jdbcTemplate.update("DELETE FROM td_request_file WHERE test_case_id = ?", testCaseId);
        int deleted = jdbcTemplate.update("DELETE FROM td_test_case WHERE test_case_id = ?", testCaseId);
        if (deleted == 0) {
            throw new NoSuchElementException("Test case not found: " + testCaseId);
        }
    }

    @Override
    public Optional<StudioTestCase> findTestCase(String testCaseId) {
        List<StudioTestCase> cases = jdbcTemplate.query(
                "SELECT * FROM td_test_case WHERE test_case_id = ?", caseMapper(), testCaseId);
        if (cases.isEmpty()) {
            return Optional.empty();
        }
        StudioTestCase testCase = cases.get(0);
        attachParams(List.of(testCase));
        return Optional.of(testCase);
    }

    @Override
    public List<StudioTestCase> listTestCases(String specId, String planId, String operationKey) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM td_test_case WHERE 1=1");
        if (specId != null) {
            sql.append(" AND spec_id = ?");
            args.add(specId);
        }
        if (planId != null) {
            sql.append(" AND plan_id = ?");
            args.add(planId);
        }
        if (operationKey != null) {
            sql.append(" AND operation_key = ?");
            args.add(operationKey);
        }
        sql.append(" ORDER BY test_name");
        List<StudioTestCase> cases = jdbcTemplate.query(sql.toString(), caseMapper(), args.toArray());
        attachParams(cases);
        return cases;
    }

    @Override
    public void saveRequestFile(StudioRequestFile file) {
        Instant now = Instant.now();
        file.setCreatedAt(file.getCreatedAt() == null ? now : file.getCreatedAt());
        jdbcTemplate.update("""
                        INSERT INTO td_request_file (
                            file_id, test_case_id, file_name, content_type, size_bytes, content, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                file.getFileId(), file.getTestCaseId(), file.getFileName(), file.getContentType(),
                file.getSizeBytes(), file.getContent(), Timestamp.from(file.getCreatedAt()));
    }

    @Override
    public Optional<StudioRequestFile> findRequestFile(String testCaseId, String fileId) {
        List<StudioRequestFile> files = jdbcTemplate.query(
                "SELECT * FROM td_request_file WHERE test_case_id = ? AND file_id = ?",
                requestFileMapper(true), testCaseId, fileId);
        return files.stream().findFirst();
    }

    @Override
    public void deleteRequestFile(String testCaseId, String fileId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM td_request_file WHERE test_case_id = ? AND file_id = ?", testCaseId, fileId);
        if (deleted == 0) {
            throw new NoSuchElementException("Request file not found: " + fileId);
        }
    }

    @Override
    public List<StudioRequestFile> listRequestFiles(String testCaseId) {
        return jdbcTemplate.query(
                """
                SELECT file_id, test_case_id, file_name, content_type, size_bytes, created_at
                  FROM td_request_file
                 WHERE test_case_id = ?
                 ORDER BY created_at
                """,
                requestFileMapper(false), testCaseId);
    }

    @Override
    public void assignRequestFiles(String testCaseId, List<String> fileIds) {
        List<String> ids = fileIds == null ? List.of() : fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        for (String fileId : ids) {
            jdbcTemplate.update(
                    "UPDATE td_request_file SET test_case_id = ? WHERE file_id = ?",
                    testCaseId, fileId);
        }
        if (ids.isEmpty()) {
            jdbcTemplate.update("DELETE FROM td_request_file WHERE test_case_id = ?", testCaseId);
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(testCaseId);
        args.addAll(ids);
        jdbcTemplate.update(
                "DELETE FROM td_request_file WHERE test_case_id = ? AND file_id NOT IN (" + placeholders + ")",
                args.toArray());
    }

    @Override
    public void deleteRequestFilesForCase(String testCaseId) {
        jdbcTemplate.update("DELETE FROM td_request_file WHERE test_case_id = ?", testCaseId);
    }

    private void insertParams(StudioTestCase testCase) {
        insertParamGroup(testCase.getId(), "path", testCase.getPathParams());
        insertParamGroup(testCase.getId(), "query", testCase.getQueryParams());
        insertParamGroup(testCase.getId(), "header", testCase.getHeaders());
        insertParamGroup(testCase.getId(), "cookie", testCase.getCookieParams());
    }

    private void insertParamGroup(String testCaseId, String in, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((name, value) -> jdbcTemplate.update(
                "INSERT INTO td_test_case_param (test_case_id, param_in, param_name, param_value) VALUES (?, ?, ?, ?)",
                testCaseId, in, name, value));
    }

    private void attachParams(List<StudioTestCase> cases) {
        if (cases.isEmpty()) {
            return;
        }
        Map<String, StudioTestCase> byId = new LinkedHashMap<>();
        cases.forEach(testCase -> byId.put(testCase.getId(), testCase));
        String placeholders = String.join(",", cases.stream().map(testCase -> "?").toList());
        jdbcTemplate.query(
                "SELECT test_case_id, param_in, param_name, param_value FROM td_test_case_param WHERE test_case_id IN ("
                        + placeholders + ")",
                rs -> {
                    StudioTestCase testCase = byId.get(rs.getString("test_case_id"));
                    if (testCase == null) {
                        return;
                    }
                    String in = rs.getString("param_in");
                    String name = rs.getString("param_name");
                    String value = rs.getString("param_value");
                    if ("path".equalsIgnoreCase(in)) {
                        testCase.getPathParams().put(name, value);
                    } else if ("header".equalsIgnoreCase(in)) {
                        testCase.getHeaders().put(name, value);
                    } else if ("cookie".equalsIgnoreCase(in)) {
                        testCase.getCookieParams().put(name, value);
                    } else {
                        testCase.getQueryParams().put(name, value);
                    }
                },
                cases.stream().map(StudioTestCase::getId).toArray());
    }

    private RowMapper<StoredSpec> specMapper() {
        return (rs, rowNum) -> {
            StoredSpec spec = new StoredSpec();
            spec.setSpecId(rs.getString("spec_id"));
            spec.setName(rs.getString("name"));
            spec.setTitle(rs.getString("title"));
            spec.setVersion(rs.getString("version"));
            spec.setDescription(rs.getString("description"));
            spec.setBaseUrl(rs.getString("base_url"));
            spec.setSourceLocation(rs.getString("source_location"));
            spec.setSourceContent(rs.getString("source_content"));
            spec.setOperationCount(rs.getInt("operation_count"));
            spec.setCreatedAt(toInstant(rs, "created_at"));
            spec.setUpdatedAt(toInstant(rs, "updated_at"));
            return spec;
        };
    }

    private RowMapper<StudioTestCase> caseMapper() {
        return (rs, rowNum) -> {
            StudioTestCase testCase = new StudioTestCase();
            testCase.setId(rs.getString("test_case_id"));
            testCase.setSpecId(rs.getString("spec_id"));
            testCase.setPlanId(rs.getString("plan_id"));
            testCase.setOperationKey(rs.getString("operation_key"));
            testCase.setTestName(rs.getString("test_name"));
            testCase.setMethod(rs.getString("http_method"));
            testCase.setRawPath(rs.getString("raw_path"));
            testCase.setEndpoint(rs.getString("endpoint"));
            testCase.setBaseUrl(rs.getString("base_url"));
            testCase.setContentType(rs.getString("content_type"));
            testCase.setRequestBody(JsonMaps.toObject(rs.getString("request_body_json")));
            testCase.setResponseJsonSchema(JsonMaps.toObjectMap(rs.getString("response_schema_json")));
            testCase.setStatusCode(rs.getInt("status_code"));
            testCase.setResponseType(rs.getString("response_type"));
            testCase.setExpectedResponse(rs.getString("expected_response"));
            testCase.setEnabled(rs.getString("enabled"));
            testCase.setTestGroup(rs.getString("test_group"));
            testCase.setJsonPathAssertions(rs.getString("json_path_assertions"));
            long maxTime = rs.getLong("max_response_time_ms");
            testCase.setMaxResponseTimeMs(rs.wasNull() ? 3000L : maxTime);
            return testCase;
        };
    }

    private RowMapper<StudioTestPlan> planMapper() {
        return (rs, rowNum) -> {
            StudioTestPlan plan = new StudioTestPlan();
            plan.setId(rs.getString("plan_id"));
            plan.setSpecId(rs.getString("spec_id"));
            plan.setName(rs.getString("name"));
            plan.setDescription(rs.getString("description"));
            plan.setCreatedAt(toInstant(rs, "created_at"));
            plan.setUpdatedAt(toInstant(rs, "updated_at"));
            return plan;
        };
    }

    private RowMapper<StudioRequestFile> requestFileMapper(boolean includeContent) {
        return (rs, rowNum) -> {
            StudioRequestFile file = new StudioRequestFile();
            file.setFileId(rs.getString("file_id"));
            file.setTestCaseId(rs.getString("test_case_id"));
            file.setFileName(rs.getString("file_name"));
            file.setContentType(rs.getString("content_type"));
            file.setSizeBytes(rs.getLong("size_bytes"));
            file.setCreatedAt(toInstant(rs, "created_at"));
            if (includeContent) {
                file.setContent(rs.getBytes("content"));
            }
            return file;
        };
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }
}
