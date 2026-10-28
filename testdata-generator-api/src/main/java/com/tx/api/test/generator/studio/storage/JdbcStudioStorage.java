package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.audit.CaseAudit;
import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.dto.AuditEventView;
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
        spec.setUpdatedAt(com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency.toStorageInstant(now));
        String tenantId = tenantId();
        if (existing.isPresent()) {
            Instant expected = spec.getExpectedUpdatedAt();
            boolean optimistic = expected != null && !spec.isForceOverwrite();
            String sql = """
                            UPDATE td_spec
                               SET name = ?, label = ?, title = ?, version = ?, description = ?, base_url = ?, source_location = ?,
                                   source_content = ?, operation_count = ?, updated_at = ?
                             WHERE spec_id = ? AND tenant_id = ?
                            """ + (optimistic ? " AND updated_at = ?" : "");
            Object[] args = optimistic
                    ? new Object[]{
                    spec.getName(), defaultLabel(spec.getLabel()), spec.getTitle(), spec.getVersion(), spec.getDescription(),
                    spec.getBaseUrl(), spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                    Timestamp.from(spec.getUpdatedAt()), spec.getSpecId(), tenantId, Timestamp.from(expected)
            }
                    : new Object[]{
                    spec.getName(), defaultLabel(spec.getLabel()), spec.getTitle(), spec.getVersion(), spec.getDescription(),
                    spec.getBaseUrl(), spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                    Timestamp.from(spec.getUpdatedAt()), spec.getSpecId(), tenantId
            };
            int rows = jdbcTemplate.update(sql, args);
            if (rows == 0) {
                if (optimistic) {
                    throw new com.tx.api.test.generator.studio.concurrency.StaleRevisionException();
                }
                throw new NoSuchElementException("Spec not found: " + spec.getSpecId());
            }
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_spec (
                            spec_id, tenant_id, name, label, title, version, description, base_url, source_location, source_content,
                            operation_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                spec.getSpecId(), tenantId, spec.getName(), defaultLabel(spec.getLabel()), spec.getTitle(), spec.getVersion(), spec.getDescription(),
                spec.getBaseUrl(), spec.getSourceLocation(), spec.getSourceContent(), spec.getOperationCount(),
                Timestamp.from(spec.getCreatedAt()), Timestamp.from(spec.getUpdatedAt()));
    }

    @Override
    public Optional<StoredSpec> findSpec(String specId) {
        List<StoredSpec> specs = jdbcTemplate.query(
                "SELECT * FROM td_spec WHERE spec_id = ? AND tenant_id = ?", specMapper(), specId, tenantId());
        return specs.stream().findFirst();
    }

    @Override
    public List<StoredSpec> listSpecs() {
        return jdbcTemplate.query(
                "SELECT * FROM td_spec WHERE tenant_id = ? ORDER BY name, title", specMapper(), tenantId());
    }

    @Override
    public void deleteSpec(String specId) {
        requireOwnedSpec(specId);
        String tenant = tenantId();
        jdbcTemplate.update("""
                DELETE FROM td_run_result
                 WHERE spec_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_run
                 WHERE spec_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_case_param
                 WHERE test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.spec_id = ? AND s.tenant_id = ?
                 )
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_request_file
                 WHERE test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.spec_id = ? AND s.tenant_id = ?
                 )
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_case
                 WHERE spec_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_plan
                 WHERE spec_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, specId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_environment
                 WHERE spec_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, specId, tenant);
        int deleted = jdbcTemplate.update("DELETE FROM td_spec WHERE spec_id = ? AND tenant_id = ?", specId, tenant);
        if (deleted == 0) {
            throw new NoSuchElementException("Spec not found: " + specId);
        }
    }

    @Override
    public void ensureSpecNameUniqueIndex() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_td_spec_name");
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_td_spec_tenant_name ON td_spec (tenant_id, name)");
    }

    @Override
    public void savePlan(StudioTestPlan plan) {
        requireOwnedSpec(plan.getSpecId());
        Instant now = Instant.now();
        Optional<StudioTestPlan> existing = findPlan(plan.getId());
        plan.setCreatedAt(existing.map(StudioTestPlan::getCreatedAt).orElse(now));
        plan.setUpdatedAt(now);
        Timestamp updatedAt = Timestamp.from(plan.getUpdatedAt());
        if (existing.isPresent()) {
            int rows = jdbcTemplate.update("""
                            UPDATE td_test_plan
                               SET spec_id = ?, name = ?, description = ?, updated_at = ?
                             WHERE plan_id = ?
                               AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                            """,
                    plan.getSpecId(), plan.getName(), plan.getDescription(), updatedAt, plan.getId(), tenantId());
            if (rows == 0) {
                throw new NoSuchElementException("Test plan not found: " + plan.getId());
            }
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_test_plan (plan_id, spec_id, name, description, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                plan.getId(), plan.getSpecId(), plan.getName(), plan.getDescription(),
                Timestamp.from(plan.getCreatedAt()), updatedAt);
    }

    @Override
    public void deletePlan(String planId) {
        requireOwnedPlan(planId);
        String tenant = tenantId();
        jdbcTemplate.update("""
                DELETE FROM td_request_file
                 WHERE test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.plan_id = ? AND s.tenant_id = ?
                 )
                """, planId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_case_param
                 WHERE test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.plan_id = ? AND s.tenant_id = ?
                 )
                """, planId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_case
                 WHERE plan_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, planId, tenant);
        int deleted = jdbcTemplate.update("""
                DELETE FROM td_test_plan
                 WHERE plan_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, planId, tenant);
        if (deleted == 0) {
            throw new NoSuchElementException("Test plan not found: " + planId);
        }
    }

    @Override
    public Optional<StudioTestPlan> findPlan(String planId) {
        List<StudioTestPlan> plans = jdbcTemplate.query(
                """
                SELECT p.* FROM td_test_plan p
                  JOIN td_spec s ON s.spec_id = p.spec_id
                 WHERE p.plan_id = ? AND s.tenant_id = ?
                """,
                planMapper(), planId, tenantId());
        return plans.stream().findFirst();
    }

    @Override
    public List<StudioTestPlan> listPlans(String specId) {
        return jdbcTemplate.query(
                """
                SELECT p.* FROM td_test_plan p
                  JOIN td_spec s ON s.spec_id = p.spec_id
                 WHERE p.spec_id = ? AND s.tenant_id = ?
                 ORDER BY p.name
                """,
                planMapper(), specId, tenantId());
    }

    @Override
    public List<StoredEnvironment> listEnvironments(String specId) {
        return jdbcTemplate.query(
                """
                SELECT e.* FROM td_environment e
                  JOIN td_spec s ON s.spec_id = e.spec_id
                 WHERE e.spec_id = ? AND s.tenant_id = ?
                 ORDER BY e.name
                """,
                environmentMapper(), specId, tenantId());
    }

    @Override
    public Optional<StoredEnvironment> findEnvironment(String environmentId) {
        List<StoredEnvironment> environments = jdbcTemplate.query(
                """
                SELECT e.* FROM td_environment e
                  JOIN td_spec s ON s.spec_id = e.spec_id
                 WHERE e.environment_id = ? AND s.tenant_id = ?
                """,
                environmentMapper(), environmentId, tenantId());
        return environments.stream().findFirst();
    }

    @Override
    public Optional<StoredEnvironment> findEnvironmentByName(String specId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<StoredEnvironment> environments = jdbcTemplate.query(
                """
                SELECT e.* FROM td_environment e
                  JOIN td_spec s ON s.spec_id = e.spec_id
                 WHERE e.spec_id = ? AND LOWER(e.name) = LOWER(?) AND s.tenant_id = ?
                """,
                environmentMapper(), specId, name.trim(), tenantId());
        return environments.stream().findFirst();
    }

    @Override
    public void saveEnvironment(StoredEnvironment environment) {
        requireOwnedSpec(environment.getSpecId());
        Instant now = Instant.now();
        Optional<StoredEnvironment> existing = findEnvironment(environment.getId());
        environment.setCreatedAt(existing.map(StoredEnvironment::getCreatedAt).orElse(now));
        environment.setUpdatedAt(now);
        Timestamp updatedAt = Timestamp.from(environment.getUpdatedAt());
        if (existing.isPresent()) {
            int rows = jdbcTemplate.update("""
                            UPDATE td_environment
                               SET spec_id = ?, name = ?, document_json = ?, updated_at = ?
                             WHERE environment_id = ?
                               AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                            """,
                    environment.getSpecId(), environment.getName(), environment.getDocumentJson(),
                    updatedAt, environment.getId(), tenantId());
            if (rows == 0) {
                throw new NoSuchElementException("Environment not found: " + environment.getId());
            }
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO td_environment (environment_id, spec_id, name, document_json, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                environment.getId(), environment.getSpecId(), environment.getName(), environment.getDocumentJson(),
                Timestamp.from(environment.getCreatedAt()), updatedAt);
    }

    @Override
    public void deleteEnvironment(String environmentId) {
        requireOwnedEnvironment(environmentId);
        int deleted = jdbcTemplate.update("""
                DELETE FROM td_environment
                 WHERE environment_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, environmentId, tenantId());
        if (deleted == 0) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
    }

    @Override
    public void saveTestCase(StudioTestCase testCase) {
        requireOwnedSpec(testCase.getSpecId());
        boolean exists = findTestCase(testCase.getId()).isPresent();
        Instant now = Instant.now();
        if (exists) {
            CaseAudit.stampUpdate(testCase, now);
            Timestamp updatedAt = Timestamp.from(
                    com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency.toStorageInstant(
                            testCase.getUpdatedAt()));
            Instant expected = testCase.getExpectedUpdatedAt();
            boolean optimistic = expected != null && !testCase.isForceOverwrite();
            String sql = """
                            UPDATE td_test_case
                               SET spec_id = ?, plan_id = ?, operation_key = ?, test_name = ?, http_method = ?, raw_path = ?,
                                   endpoint = ?, base_url = ?, content_type = ?, request_body_json = ?,
                                   response_schema_json = ?, status_code = ?, response_type = ?, expected_response = ?,
                                   enabled = ?, lifecycle = ?, test_group = ?, json_path_assertions = ?, extracts = ?, max_response_time_ms = ?,
                                   environment_names = ?, updated_at = ?, updated_by_account_id = ?, updated_by_label = ?
                             WHERE test_case_id = ?
                               AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                            """ + (optimistic ? " AND updated_at = ?" : "");
            List<Object> args = new ArrayList<>();
            args.add(testCase.getSpecId());
            args.add(testCase.getPlanId());
            args.add(testCase.getOperationKey());
            args.add(testCase.getTestName());
            args.add(testCase.getMethod());
            args.add(testCase.getRawPath());
            args.add(testCase.getEndpoint());
            args.add(testCase.getBaseUrl());
            args.add(testCase.getContentType());
            args.add(JsonMaps.toJson(testCase.getRequestBody()));
            args.add(JsonMaps.toJson(testCase.getResponseJsonSchema()));
            args.add(testCase.getStatusCode());
            args.add(testCase.getResponseType());
            args.add(testCase.getExpectedResponse());
            args.add(testCase.getEnabled());
            args.add(com.tx.api.test.generator.studio.model.CaseLifecycle.normalizeOr(testCase.getLifecycle(),
                    com.tx.api.test.generator.studio.model.CaseLifecycle.READY));
            args.add(testCase.getTestGroup());
            args.add(testCase.getJsonPathAssertions());
            args.add(testCase.getExtracts());
            args.add(testCase.getMaxResponseTimeMs());
            args.add(JsonMaps.toJson(testCase.getEnvironments()));
            args.add(updatedAt);
            args.add(testCase.getUpdatedByAccountId());
            args.add(testCase.getUpdatedByLabel());
            args.add(testCase.getId());
            args.add(tenantId());
            if (optimistic) {
                args.add(Timestamp.from(
                        com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency.toStorageInstant(
                                expected)));
            }
            int rows = jdbcTemplate.update(sql, args.toArray());
            if (rows == 0) {
                if (optimistic) {
                    throw new com.tx.api.test.generator.studio.concurrency.StaleRevisionException();
                }
                throw new NoSuchElementException("Test case not found: " + testCase.getId());
            }
            jdbcTemplate.update("""
                    DELETE FROM td_test_case_param
                     WHERE test_case_id = ?
                       AND test_case_id IN (
                           SELECT c.test_case_id FROM td_test_case c
                             JOIN td_spec s ON s.spec_id = c.spec_id
                            WHERE c.test_case_id = ? AND s.tenant_id = ?
                       )
                    """, testCase.getId(), testCase.getId(), tenantId());
        } else {
            CaseAudit.stampCreate(testCase, now);
            Timestamp createdAt = Timestamp.from(testCase.getCreatedAt());
            Timestamp updatedAt = Timestamp.from(testCase.getUpdatedAt());
            jdbcTemplate.update("""
                            INSERT INTO td_test_case (
                                test_case_id, spec_id, plan_id, operation_key, test_name, http_method, raw_path, endpoint,
                                base_url, content_type, request_body_json, response_schema_json, status_code,
                                response_type, expected_response, enabled, lifecycle, test_group, json_path_assertions, extracts,
                                max_response_time_ms, environment_names, created_at, updated_at,
                                created_by_account_id, created_by_label, updated_by_account_id, updated_by_label)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    testCase.getId(), testCase.getSpecId(), testCase.getPlanId(), testCase.getOperationKey(),
                    testCase.getTestName(), testCase.getMethod(), testCase.getRawPath(), testCase.getEndpoint(),
                    testCase.getBaseUrl(), testCase.getContentType(), JsonMaps.toJson(testCase.getRequestBody()),
                    JsonMaps.toJson(testCase.getResponseJsonSchema()), testCase.getStatusCode(),
                    testCase.getResponseType(), testCase.getExpectedResponse(), testCase.getEnabled(),
                    com.tx.api.test.generator.studio.model.CaseLifecycle.normalizeOr(testCase.getLifecycle(),
                            com.tx.api.test.generator.studio.model.CaseLifecycle.READY),
                    testCase.getTestGroup(), testCase.getJsonPathAssertions(), testCase.getExtracts(),
                    testCase.getMaxResponseTimeMs(), JsonMaps.toJson(testCase.getEnvironments()),
                    createdAt, updatedAt, testCase.getCreatedByAccountId(), testCase.getCreatedByLabel(),
                    testCase.getUpdatedByAccountId(), testCase.getUpdatedByLabel());
        }
        insertParams(testCase);
    }

    @Override
    public void updateResponseSchema(String testCaseId, Map<String, Object> schema) {
        int rows = jdbcTemplate.update("""
                        UPDATE td_test_case
                           SET response_schema_json = ?
                         WHERE test_case_id = ?
                           AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                        """, JsonMaps.toJson(schema), testCaseId, tenantId());
        if (rows == 0) {
            throw new NoSuchElementException("Test case not found: " + testCaseId);
        }
    }

    @Override
    public void deleteTestCase(String testCaseId) {
        requireOwnedTestCase(testCaseId);
        String tenant = tenantId();
        jdbcTemplate.update("""
                DELETE FROM td_request_file
                 WHERE test_case_id = ?
                   AND test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.test_case_id = ? AND s.tenant_id = ?
                   )
                """, testCaseId, testCaseId, tenant);
        jdbcTemplate.update("""
                DELETE FROM td_test_case_param
                 WHERE test_case_id = ?
                   AND test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.test_case_id = ? AND s.tenant_id = ?
                   )
                """, testCaseId, testCaseId, tenant);
        int deleted = jdbcTemplate.update("""
                DELETE FROM td_test_case
                 WHERE test_case_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, testCaseId, tenant);
        if (deleted == 0) {
            throw new NoSuchElementException("Test case not found: " + testCaseId);
        }
    }

    @Override
    public Optional<StudioTestCase> findTestCase(String testCaseId) {
        List<StudioTestCase> cases = jdbcTemplate.query(
                """
                SELECT c.* FROM td_test_case c
                  JOIN td_spec s ON s.spec_id = c.spec_id
                 WHERE c.test_case_id = ? AND s.tenant_id = ?
                """,
                caseMapper(), testCaseId, tenantId());
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
        StringBuilder sql = new StringBuilder("""
                SELECT c.* FROM td_test_case c
                  JOIN td_spec s ON s.spec_id = c.spec_id
                 WHERE s.tenant_id = ?
                """);
        args.add(tenantId());
        if (specId != null) {
            sql.append(" AND c.spec_id = ?");
            args.add(specId);
        }
        if (planId != null) {
            sql.append(" AND c.plan_id = ?");
            args.add(planId);
        }
        if (operationKey != null) {
            sql.append(" AND c.operation_key = ?");
            args.add(operationKey);
        }
        sql.append(" ORDER BY c.created_at, c.test_name");
        List<StudioTestCase> cases = jdbcTemplate.query(sql.toString(), caseMapper(), args.toArray());
        attachParams(cases);
        return cases;
    }

    @Override
    public void saveRequestFile(StudioRequestFile file) {
        requireOwnedTestCase(file.getTestCaseId());
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
                """
                SELECT f.* FROM td_request_file f
                  JOIN td_test_case c ON c.test_case_id = f.test_case_id
                  JOIN td_spec s ON s.spec_id = c.spec_id
                 WHERE f.test_case_id = ? AND f.file_id = ? AND s.tenant_id = ?
                """,
                requestFileMapper(true), testCaseId, fileId, tenantId());
        return files.stream().findFirst();
    }

    @Override
    public void deleteRequestFile(String testCaseId, String fileId) {
        if (findRequestFile(testCaseId, fileId).isEmpty()) {
            throw new NoSuchElementException("Request file not found: " + fileId);
        }
        int deleted = jdbcTemplate.update("""
                DELETE FROM td_request_file
                 WHERE test_case_id = ? AND file_id = ?
                   AND test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.test_case_id = ? AND s.tenant_id = ?
                   )
                """, testCaseId, fileId, testCaseId, tenantId());
        if (deleted == 0) {
            throw new NoSuchElementException("Request file not found: " + fileId);
        }
    }

    @Override
    public List<StudioRequestFile> listRequestFiles(String testCaseId) {
        return jdbcTemplate.query(
                """
                SELECT f.file_id, f.test_case_id, f.file_name, f.content_type, f.size_bytes, f.created_at
                  FROM td_request_file f
                  JOIN td_test_case c ON c.test_case_id = f.test_case_id
                  JOIN td_spec s ON s.spec_id = c.spec_id
                 WHERE f.test_case_id = ? AND s.tenant_id = ?
                 ORDER BY f.created_at
                """,
                requestFileMapper(false), testCaseId, tenantId());
    }

    @Override
    public void assignRequestFiles(String testCaseId, List<String> fileIds) {
        requireOwnedTestCase(testCaseId);
        List<String> ids = fileIds == null ? List.of() : fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        String tenant = tenantId();
        for (String fileId : ids) {
            jdbcTemplate.update("""
                    UPDATE td_request_file
                       SET test_case_id = ?
                     WHERE file_id = ?
                       AND (test_case_id IS NULL OR test_case_id IN (
                           SELECT c.test_case_id FROM td_test_case c
                             JOIN td_spec s ON s.spec_id = c.spec_id
                            WHERE c.test_case_id = ? AND s.tenant_id = ?
                       ))
                    """, testCaseId, fileId, testCaseId, tenant);
        }
        if (ids.isEmpty()) {
            jdbcTemplate.update("""
                    DELETE FROM td_request_file
                     WHERE test_case_id = ?
                       AND test_case_id IN (
                           SELECT c.test_case_id FROM td_test_case c
                             JOIN td_spec s ON s.spec_id = c.spec_id
                            WHERE c.test_case_id = ? AND s.tenant_id = ?
                       )
                    """, testCaseId, testCaseId, tenant);
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(testCaseId);
        args.add(testCaseId);
        args.add(tenant);
        args.addAll(ids);
        jdbcTemplate.update(
                """
                DELETE FROM td_request_file
                 WHERE test_case_id = ?
                   AND test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.test_case_id = ? AND s.tenant_id = ?
                   )
                   AND file_id NOT IN (""" + placeholders + ")",
                args.toArray());
    }

    @Override
    public void deleteRequestFilesForCase(String testCaseId) {
        requireOwnedTestCase(testCaseId);
        jdbcTemplate.update("""
                DELETE FROM td_request_file
                 WHERE test_case_id = ?
                   AND test_case_id IN (
                       SELECT c.test_case_id FROM td_test_case c
                         JOIN td_spec s ON s.spec_id = c.spec_id
                        WHERE c.test_case_id = ? AND s.tenant_id = ?
                   )
                """, testCaseId, testCaseId, tenantId());
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
            testCase.setLifecycle(com.tx.api.test.generator.studio.model.CaseLifecycle.normalizeOr(
                    rs.getString("lifecycle"), com.tx.api.test.generator.studio.model.CaseLifecycle.READY));
            testCase.setTestGroup(rs.getString("test_group"));
            testCase.setJsonPathAssertions(rs.getString("json_path_assertions"));
            testCase.setExtracts(rs.getString("extracts"));
            long maxTime = rs.getLong("max_response_time_ms");
            testCase.setMaxResponseTimeMs(rs.wasNull() ? 3000L : maxTime);
            testCase.setEnvironments(JsonMaps.toStringList(rs.getString("environment_names")));
            testCase.setCreatedAt(toInstant(rs, "created_at"));
            testCase.setUpdatedAt(toInstant(rs, "updated_at"));
            testCase.setCreatedByAccountId(rs.getString("created_by_account_id"));
            testCase.setCreatedByLabel(rs.getString("created_by_label"));
            testCase.setUpdatedByAccountId(rs.getString("updated_by_account_id"));
            testCase.setUpdatedByLabel(rs.getString("updated_by_label"));
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

    private RowMapper<StoredEnvironment> environmentMapper() {
        return (rs, rowNum) -> {
            StoredEnvironment environment = new StoredEnvironment();
            environment.setId(rs.getString("environment_id"));
            environment.setSpecId(rs.getString("spec_id"));
            environment.setName(rs.getString("name"));
            environment.setDocumentJson(rs.getString("document_json"));
            environment.setCreatedAt(toInstant(rs, "created_at"));
            environment.setUpdatedAt(toInstant(rs, "updated_at"));
            return environment;
        };
    }

    @Override
    public void saveRun(StudioRun run) {
        requireOwnedSpec(run.getSpecId());
        jdbcTemplate.update("""
                        INSERT INTO td_run (run_id, spec_id, plan_id, environment_id, run_trigger, passed, failed, skipped, started_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                run.getId(), run.getSpecId(), run.getPlanId(), run.getEnvironmentId(), run.getTrigger(),
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
                """
                SELECT r.* FROM td_run r
                  JOIN td_spec s ON s.spec_id = r.spec_id
                 WHERE r.run_id = ? AND s.tenant_id = ?
                """,
                runMapper(), runId, tenantId());
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
                """
                SELECT rr.* FROM td_run_result rr
                  JOIN td_run r ON r.run_id = rr.run_id
                  JOIN td_spec s ON s.spec_id = r.spec_id
                 WHERE rr.run_id = ? AND rr.result_id = ? AND s.tenant_id = ?
                """,
                runResultMapper(), runId, resultId, tenantId());
        return results.stream().findFirst();
    }

    @Override
    public List<StudioRun> listRuns(RunHistoryFilter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.* FROM td_run r
                  JOIN td_spec s ON s.spec_id = r.spec_id
                 WHERE r.spec_id = ? AND s.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(filter.specId());
        args.add(tenantId());
        if (hasText(filter.planId())) {
            sql.append(" AND r.plan_id = ?");
            args.add(filter.planId());
        }
        if (hasText(filter.environmentId())) {
            sql.append(" AND r.environment_id = ?");
            args.add(filter.environmentId());
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
        jdbcTemplate.update("""
                DELETE FROM td_run_result
                 WHERE run_id = ?
                   AND run_id IN (
                       SELECT r.run_id FROM td_run r
                         JOIN td_spec s ON s.spec_id = r.spec_id
                        WHERE r.run_id = ? AND s.tenant_id = ?
                   )
                """, runId, runId, tenantId());
        int deleted = jdbcTemplate.update("""
                DELETE FROM td_run
                 WHERE run_id = ?
                   AND spec_id IN (SELECT spec_id FROM td_spec WHERE tenant_id = ?)
                """, runId, tenantId());
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
                            SELECT r.run_id, ROW_NUMBER() OVER (ORDER BY r.started_at DESC) AS rn
                            FROM td_run r
                              JOIN td_spec s ON s.spec_id = r.spec_id
                            WHERE r.spec_id = ? AND s.tenant_id = ?
                        ) ranked
                        WHERE rn > ?
                        """,
                (rs, rowNum) -> rs.getString("run_id"), specId, tenantId(), keep);
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

    private String tenantId() {
        return com.tx.api.test.generator.studio.tenant.TenantContext.tenantId();
    }

    private void requireOwnedSpec(String specId) {
        if (specId == null || findSpec(specId).isEmpty()) {
            throw new NoSuchElementException("Spec not found: " + specId);
        }
    }

    private void requireOwnedPlan(String planId) {
        if (planId == null || findPlan(planId).isEmpty()) {
            throw new NoSuchElementException("Test plan not found: " + planId);
        }
    }

    private void requireOwnedTestCase(String testCaseId) {
        if (testCaseId == null || findTestCase(testCaseId).isEmpty()) {
            throw new NoSuchElementException("Test case not found: " + testCaseId);
        }
    }

    private void requireOwnedEnvironment(String environmentId) {
        if (environmentId == null || findEnvironment(environmentId).isEmpty()) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
    }

    private RowMapper<StudioRun> runMapper() {
        return (rs, rowNum) -> {
            StudioRun run = new StudioRun();
            run.setId(rs.getString("run_id"));
            run.setSpecId(rs.getString("spec_id"));
            run.setPlanId(rs.getString("plan_id"));
            run.setEnvironmentId(rs.getString("environment_id"));
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
        if (timestamp == null) {
            return com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency.toStorageInstant(Instant.now());
        }
        return com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency.toStorageInstant(timestamp.toInstant());
    }

    private static String defaultLabel(String label) {
        if (label == null || label.isBlank()) {
            return StoredSpec.DEFAULT_LABEL;
        }
        return label.trim();
    }

    @Override
    public void appendAuditEvent(String eventId, String specId, AuditResourceType resourceType, String resourceId,
                                 AuditAction action, String actorAccountId, String actorLabel, String detail,
                                 Instant occurredAt) {
        jdbcTemplate.update("""
                        INSERT INTO td_audit_event (
                            event_id, tenant_id, spec_id, resource_type, resource_id, action,
                            actor_account_id, actor_label, detail, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                eventId, tenantId(), specId, resourceType.name(), resourceId, action.name(),
                actorAccountId, actorLabel, detail, Timestamp.from(occurredAt));
    }

    @Override
    public List<AuditEventView> listAuditEvents(AuditResourceType resourceType, String resourceId, int limit) {
        return jdbcTemplate.query("""
                        SELECT event_id, spec_id, resource_type, resource_id, action, actor_label, detail, occurred_at
                          FROM td_audit_event
                         WHERE tenant_id = ?
                           AND resource_type = ?
                           AND resource_id = ?
                         ORDER BY occurred_at DESC
                         LIMIT ?
                        """,
                auditEventMapper(), tenantId(), resourceType.name(), resourceId, limit);
    }

    @Override
    public List<AuditEventView> listAuditEventsForSpec(String specId, int limit) {
        return jdbcTemplate.query("""
                        SELECT event_id, spec_id, resource_type, resource_id, action, actor_label, detail, occurred_at
                          FROM td_audit_event
                         WHERE tenant_id = ?
                           AND spec_id = ?
                         ORDER BY occurred_at DESC
                         LIMIT ?
                        """,
                auditEventMapper(), tenantId(), specId, limit);
    }

    @Override
    public void appendAuditEventForTenant(String tenantId, String eventId, String specId,
                                          AuditResourceType resourceType, String resourceId,
                                          AuditAction action, String actorAccountId, String actorLabel,
                                          String detail, Instant occurredAt) {
        jdbcTemplate.update("""
                        INSERT INTO td_audit_event (
                            event_id, tenant_id, spec_id, resource_type, resource_id, action,
                            actor_account_id, actor_label, detail, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                eventId, tenantId, specId, resourceType.name(), resourceId, action.name(),
                actorAccountId, actorLabel, detail, Timestamp.from(occurredAt));
    }

    @Override
    public List<AuditEventView> listAuditEventsForTenant(int limit) {
        return jdbcTemplate.query("""
                        SELECT event_id, spec_id, resource_type, resource_id, action, actor_label, detail, occurred_at
                          FROM td_audit_event
                         WHERE tenant_id = ?
                         ORDER BY occurred_at DESC
                         LIMIT ?
                        """,
                auditEventMapper(), tenantId(), limit);
    }

    private RowMapper<AuditEventView> auditEventMapper() {
        return (rs, rowNum) -> new AuditEventView(
                rs.getString("event_id"),
                rs.getString("spec_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("action"),
                rs.getString("actor_label"),
                toInstant(rs, "occurred_at"),
                rs.getString("detail")
        );
    }
}
