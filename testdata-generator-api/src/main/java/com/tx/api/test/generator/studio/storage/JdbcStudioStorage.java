package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
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
                               SET name = ?, label = ?, title = ?, version = ?, description = ?, base_url = ?, source_location = ?,
                                   source_content = ?, operation_count = ?, updated_at = ?
                             WHERE spec_id = ?
                            """,
                    spec.getName(), defaultLabel(spec.getLabel()), spec.getTitle(), spec.getVersion(), spec.getDescription(), spec.getBaseUrl(),
                    spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                    Timestamp.from(spec.getUpdatedAt()), spec.getSpecId());
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_spec (
                            spec_id, name, label, title, version, description, base_url, source_location, source_content,
                            operation_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                spec.getSpecId(), spec.getName(), defaultLabel(spec.getLabel()), spec.getTitle(), spec.getVersion(), spec.getDescription(),
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
        jdbcTemplate.update("DELETE FROM td_run_result WHERE spec_id = ?", specId);
        jdbcTemplate.update("DELETE FROM td_run WHERE spec_id = ?", specId);
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
                                   enabled = ?, test_group = ?, json_path_assertions = ?, extracts = ?, max_response_time_ms = ?,
                                   updated_at = ?
                             WHERE test_case_id = ?
                            """,
                    testCase.getSpecId(), testCase.getPlanId(), testCase.getOperationKey(), testCase.getTestName(),
                    testCase.getMethod(), testCase.getRawPath(), testCase.getEndpoint(), testCase.getBaseUrl(),
                    testCase.getContentType(), JsonMaps.toJson(testCase.getRequestBody()),
                    JsonMaps.toJson(testCase.getResponseJsonSchema()), testCase.getStatusCode(),
                    testCase.getResponseType(), testCase.getExpectedResponse(), testCase.getEnabled(),
                    testCase.getTestGroup(), testCase.getJsonPathAssertions(), testCase.getExtracts(),
                    testCase.getMaxResponseTimeMs(),
                    updated, testCase.getId());
            jdbcTemplate.update("DELETE FROM td_test_case_param WHERE test_case_id = ?", testCase.getId());
        } else {
            jdbcTemplate.update("""
                            INSERT INTO td_test_case (
                                test_case_id, spec_id, plan_id, operation_key, test_name, http_method, raw_path, endpoint,
                                base_url, content_type, request_body_json, response_schema_json, status_code,
                                response_type, expected_response, enabled, test_group, json_path_assertions, extracts,
                                max_response_time_ms, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    testCase.getId(), testCase.getSpecId(), testCase.getPlanId(), testCase.getOperationKey(),
                    testCase.getTestName(), testCase.getMethod(), testCase.getRawPath(), testCase.getEndpoint(),
                    testCase.getBaseUrl(), testCase.getContentType(), JsonMaps.toJson(testCase.getRequestBody()),
                    JsonMaps.toJson(testCase.getResponseJsonSchema()), testCase.getStatusCode(),
                    testCase.getResponseType(), testCase.getExpectedResponse(), testCase.getEnabled(),
                    testCase.getTestGroup(), testCase.getJsonPathAssertions(), testCase.getExtracts(),
                    testCase.getMaxResponseTimeMs(),
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
        sql.append(" ORDER BY created_at, test_name");
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
            spec.setLabel(defaultLabel(rs.getString("label")));
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
            testCase.setExtracts(rs.getString("extracts"));
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

    @Override
    public void saveRun(StudioRun run) {
        jdbcTemplate.update("""
                        INSERT INTO td_run (run_id, spec_id, plan_id, run_trigger, passed, failed, skipped, started_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                run.getId(), run.getSpecId(), run.getPlanId(), run.getTrigger(),
                run.getPassed(), run.getFailed(), run.getSkipped(), Timestamp.from(run.getStartedAt()));
        for (StudioRunResult result : run.getResults()) {
            jdbcTemplate.update("""
                            INSERT INTO td_run_result (
                                result_id, run_id, spec_id, plan_id, case_id, operation_key, test_name, run_trigger, outcome,
                                method, url, expected_status, actual_status, status_matched, elapsed_ms,
                                max_response_time_ms, timed_out, error, request_headers, request_body,
                                request_content_type, request_params, response_headers, response_body,
                                assertions_json, download_path, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    result.getId(), result.getRunId(), result.getSpecId(), result.getPlanId(), result.getCaseId(),
                    result.getOperationKey(), result.getTestName(), result.getTrigger(), result.getOutcome(),
                    result.getMethod(), result.getUrl(), result.getExpectedStatus(), result.getActualStatus(),
                    result.getStatusMatched(), result.getElapsedMs(), result.getMaxResponseTimeMs(),
                    result.getTimedOut(), result.getError(), JsonMaps.toJson(result.getRequestHeaders()),
                    result.getRequestBody(), result.getRequestContentType(), JsonMaps.toJson(result.getRequestParams()),
                    JsonMaps.toJson(result.getResponseHeaders()), result.getResponseBody(),
                    result.getAssertionsJson(), result.getDownloadPath(), Timestamp.from(result.getCreatedAt()));
        }
    }

    @Override
    public Optional<StudioRun> findRun(String runId) {
        List<StudioRun> runs = jdbcTemplate.query(
                "SELECT * FROM td_run WHERE run_id = ?", runMapper(), runId);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        StudioRun run = runs.get(0);
        run.setResults(jdbcTemplate.query(
                "SELECT * FROM td_run_result WHERE run_id = ? ORDER BY created_at", runResultMapper(), runId));
        return Optional.of(run);
    }

    @Override
    public Optional<StudioRunResult> findRunResult(String runId, String resultId) {
        List<StudioRunResult> results = jdbcTemplate.query(
                "SELECT * FROM td_run_result WHERE run_id = ? AND result_id = ?",
                runResultMapper(), runId, resultId);
        return results.stream().findFirst();
    }

    @Override
    public List<StudioRun> listRuns(RunHistoryFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM td_run r WHERE r.spec_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.specId());
        if (hasText(filter.planId())) {
            sql.append(" AND r.plan_id = ?");
            args.add(filter.planId());
        }
        if (hasText(filter.trigger())) {
            sql.append(" AND r.run_trigger = ?");
            args.add(filter.trigger());
        }
        if (hasText(filter.caseId())) {
            sql.append(" AND EXISTS (SELECT 1 FROM td_run_result rr WHERE rr.run_id = r.run_id AND rr.case_id = ?)");
            args.add(filter.caseId());
        }
        if (hasText(filter.outcome())) {
            sql.append(" AND EXISTS (SELECT 1 FROM td_run_result rr WHERE rr.run_id = r.run_id AND rr.outcome = ?)");
            args.add(filter.outcome());
        }
        sql.append(" ORDER BY r.started_at DESC LIMIT ?");
        args.add(filter.limit());
        return jdbcTemplate.query(sql.toString(), runMapper(), args.toArray());
    }

    @Override
    public void deleteRun(String runId) {
        jdbcTemplate.update("DELETE FROM td_run_result WHERE run_id = ?", runId);
        int deleted = jdbcTemplate.update("DELETE FROM td_run WHERE run_id = ?", runId);
        if (deleted == 0) {
            throw new NoSuchElementException("Run not found: " + runId);
        }
    }

    @Override
    public int pruneOldestRuns(String specId, int keep) {
        if (!hasText(specId) || keep < 1) {
            return 0;
        }
        List<String> extra = jdbcTemplate.query("""
                        SELECT run_id FROM (
                            SELECT run_id, ROW_NUMBER() OVER (ORDER BY started_at DESC) AS rn
                            FROM td_run
                            WHERE spec_id = ?
                        ) ranked
                        WHERE rn > ?
                        """,
                (rs, rowNum) -> rs.getString("run_id"), specId, keep);
        if (extra.isEmpty()) {
            return 0;
        }
        for (String runId : extra) {
            jdbcTemplate.update("DELETE FROM td_run_result WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM td_run WHERE run_id = ?", runId);
        }
        return extra.size();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private RowMapper<StudioRun> runMapper() {
        return (rs, rowNum) -> {
            StudioRun run = new StudioRun();
            run.setId(rs.getString("run_id"));
            run.setSpecId(rs.getString("spec_id"));
            run.setPlanId(rs.getString("plan_id"));
            run.setTrigger(rs.getString("run_trigger"));
            run.setPassed(rs.getInt("passed"));
            run.setFailed(rs.getInt("failed"));
            run.setSkipped(rs.getInt("skipped"));
            run.setStartedAt(toInstant(rs, "started_at"));
            return run;
        };
    }

    private RowMapper<StudioRunResult> runResultMapper() {
        return (rs, rowNum) -> {
            StudioRunResult result = new StudioRunResult();
            result.setId(rs.getString("result_id"));
            result.setRunId(rs.getString("run_id"));
            result.setSpecId(rs.getString("spec_id"));
            result.setPlanId(rs.getString("plan_id"));
            result.setCaseId(rs.getString("case_id"));
            result.setOperationKey(rs.getString("operation_key"));
            result.setTestName(rs.getString("test_name"));
            result.setTrigger(rs.getString("run_trigger"));
            result.setOutcome(rs.getString("outcome"));
            result.setMethod(rs.getString("method"));
            result.setUrl(rs.getString("url"));
            int expectedStatus = rs.getInt("expected_status");
            result.setExpectedStatus(rs.wasNull() ? null : expectedStatus);
            int actualStatus = rs.getInt("actual_status");
            result.setActualStatus(rs.wasNull() ? null : actualStatus);
            boolean statusMatched = rs.getBoolean("status_matched");
            result.setStatusMatched(rs.wasNull() ? null : statusMatched);
            long elapsed = rs.getLong("elapsed_ms");
            result.setElapsedMs(rs.wasNull() ? null : elapsed);
            long maxMs = rs.getLong("max_response_time_ms");
            result.setMaxResponseTimeMs(rs.wasNull() ? null : maxMs);
            boolean timedOut = rs.getBoolean("timed_out");
            result.setTimedOut(rs.wasNull() ? null : timedOut);
            result.setError(rs.getString("error"));
            result.setRequestHeaders(JsonMaps.toStringMap(rs.getString("request_headers")));
            result.setRequestBody(rs.getString("request_body"));
            result.setRequestContentType(rs.getString("request_content_type"));
            result.setRequestParams(JsonMaps.toStringMap(rs.getString("request_params")));
            result.setResponseHeaders(JsonMaps.toStringMap(rs.getString("response_headers")));
            result.setResponseBody(rs.getString("response_body"));
            result.setAssertionsJson(rs.getString("assertions_json"));
            result.setDownloadPath(rs.getString("download_path"));
            result.setCreatedAt(toInstant(rs, "created_at"));
            return result;
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

    private static String defaultLabel(String label) {
        if (label == null || label.isBlank()) {
            return StoredSpec.DEFAULT_LABEL;
        }
        return label.trim();
    }
}
