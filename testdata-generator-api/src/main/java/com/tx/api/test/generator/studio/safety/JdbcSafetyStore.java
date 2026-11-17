package com.tx.api.test.generator.studio.safety;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSafetyStore implements SafetyStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSafetyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SafetyPolicy> findPolicy(String orgId) {
        return jdbcTemplate.query("SELECT * FROM td_safety_policy WHERE org_id = ?", policyMapper(), orgId)
                .stream().findFirst();
    }

    @Override
    public void insertPolicy(SafetyPolicy policy) {
        jdbcTemplate.update("""
                        INSERT INTO td_safety_policy (org_id, mode, max_requests_per_run, max_requests_per_minute,
                                                      max_write_data_rows, require_second_admin, self_approval,
                                                      revision, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                policy.orgId(), policy.mode().wire(), policy.maxRequestsPerRun(), policy.maxRequestsPerMinute(),
                policy.maxWriteDataRows(), policy.requireSecondAdmin(), policy.selfApproval().wire(),
                policy.revision(), policy.updatedBy(), timestamp(policy.updatedAt()));
    }

    @Override
    public boolean updatePolicy(SafetyPolicy policy, long expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE td_safety_policy
                           SET mode = ?, max_requests_per_run = ?, max_requests_per_minute = ?,
                               max_write_data_rows = ?, require_second_admin = ?, self_approval = ?,
                               revision = revision + 1, updated_by = ?, updated_at = ?
                         WHERE org_id = ? AND revision = ?
                        """,
                policy.mode().wire(), policy.maxRequestsPerRun(), policy.maxRequestsPerMinute(),
                policy.maxWriteDataRows(), policy.requireSecondAdmin(), policy.selfApproval().wire(),
                policy.updatedBy(), timestamp(policy.updatedAt()), policy.orgId(), expectedRevision) == 1;
    }

    @Override
    public List<ProtectedTarget> listTargets(String orgId) {
        return jdbcTemplate.query(
                "SELECT * FROM td_protected_target WHERE org_id = ? ORDER BY pattern",
                (rs, rowNum) -> new ProtectedTarget(
                        rs.getString("target_id"),
                        TargetKind.from(rs.getString("kind")),
                        rs.getString("pattern"),
                        TargetPatterns.portsFromText(rs.getString("ports")),
                        rs.getString("note"),
                        rs.getString("created_by"),
                        instant(rs, "created_at")),
                orgId);
    }

    @Override
    public void insertTarget(String orgId, ProtectedTarget target) {
        jdbcTemplate.update("""
                        INSERT INTO td_protected_target (target_id, org_id, kind, pattern, ports, note, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                target.id(), orgId, target.kind().wire(), target.pattern(), TargetPatterns.portsText(target.ports()),
                target.note(), target.createdBy(), timestamp(target.createdAt()));
    }

    @Override
    public boolean deleteTarget(String orgId, String targetId) {
        return jdbcTemplate.update("DELETE FROM td_protected_target WHERE org_id = ? AND target_id = ?",
                orgId, targetId) == 1;
    }

    @Override
    public List<OperationRule> listRules(String orgId) {
        return jdbcTemplate.query(
                "SELECT * FROM td_operation_rule WHERE org_id = ? ORDER BY created_at, rule_id",
                (rs, rowNum) -> new OperationRule(
                        rs.getString("rule_id"),
                        RuleEffect.from(rs.getString("effect")),
                        RuleProtocol.from(rs.getString("protocol")),
                        rs.getString("http_method"),
                        rs.getString("path_template"),
                        rs.getString("operation_name"),
                        rs.getString("target_id"),
                        rs.getString("reason"),
                        instant(rs, "expires_at"),
                        rs.getString("created_by"),
                        instant(rs, "created_at")),
                orgId);
    }

    @Override
    public void insertRule(String orgId, OperationRule rule) {
        jdbcTemplate.update("""
                        INSERT INTO td_operation_rule (rule_id, org_id, effect, protocol, http_method, path_template,
                                                       operation_name, target_id, reason, expires_at, created_by,
                                                       created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                rule.id(), orgId, rule.effect().wire(), rule.protocol().wire(), rule.httpMethod(), rule.pathTemplate(),
                rule.operationName(), rule.targetId(), rule.reason(), timestamp(rule.expiresAt()), rule.createdBy(),
                timestamp(rule.createdAt()));
    }

    @Override
    public boolean updateRule(String orgId, OperationRule rule) {
        return jdbcTemplate.update("""
                        UPDATE td_operation_rule
                           SET effect = ?, protocol = ?, http_method = ?, path_template = ?, operation_name = ?,
                               target_id = ?, reason = ?, expires_at = ?
                         WHERE org_id = ? AND rule_id = ?
                        """,
                rule.effect().wire(), rule.protocol().wire(), rule.httpMethod(), rule.pathTemplate(),
                rule.operationName(), rule.targetId(), rule.reason(), timestamp(rule.expiresAt()), orgId,
                rule.id()) == 1;
    }

    @Override
    public boolean deleteRule(String orgId, String ruleId) {
        return jdbcTemplate.update("DELETE FROM td_operation_rule WHERE org_id = ? AND rule_id = ?",
                orgId, ruleId) == 1;
    }

    private static RowMapper<SafetyPolicy> policyMapper() {
        return (rs, rowNum) -> new SafetyPolicy(
                rs.getString("org_id"),
                SafetyMode.from(rs.getString("mode")),
                rs.getInt("max_requests_per_run"),
                rs.getInt("max_requests_per_minute"),
                rs.getInt("max_write_data_rows"),
                rs.getBoolean("require_second_admin"),
                SelfApproval.from(rs.getString("self_approval")),
                rs.getLong("revision"),
                rs.getString("updated_by"),
                instant(rs, "updated_at"),
                true,
                List.of(),
                List.of());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
