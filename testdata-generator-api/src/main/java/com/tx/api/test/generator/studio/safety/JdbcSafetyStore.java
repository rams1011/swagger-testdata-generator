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
    public boolean updateTargetPorts(String orgId, String targetId, List<Integer> ports) {
        return jdbcTemplate.update("UPDATE td_protected_target SET ports = ? WHERE org_id = ? AND target_id = ?",
                TargetPatterns.portsText(ports), orgId, targetId) == 1;
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

    @Override
    public void insertApproval(WriteApproval approval) {
        jdbcTemplate.update("""
                        INSERT INTO td_write_approval (approval_id, org_id, case_id, case_revision, environment_id, status,
                                                       reason, requested_by, requested_at, self_approved, expires_at,
                                                       max_runs, runs_used)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                approval.id(), approval.orgId(), approval.caseId(), approval.caseRevision(), approval.environmentId(),
                approval.status(), approval.reason(), approval.requestedBy(), timestamp(approval.requestedAt()),
                approval.selfApproved(), timestamp(approval.expiresAt()), approval.maxRuns());
    }

    @Override
    public Optional<WriteApproval> findApproval(String orgId, String approvalId) {
        return jdbcTemplate.query("SELECT * FROM td_write_approval WHERE org_id = ? AND approval_id = ?",
                approvalMapper(), orgId, approvalId).stream().findFirst();
    }

    @Override
    public List<WriteApproval> listApprovals(String orgId, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return jdbcTemplate.query(
                    "SELECT * FROM td_write_approval WHERE org_id = ? ORDER BY requested_at DESC, approval_id",
                    approvalMapper(), orgId);
        }
        String marks = String.join(", ", java.util.Collections.nCopies(statuses.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        args.addAll(statuses);
        return jdbcTemplate.query("SELECT * FROM td_write_approval WHERE org_id = ? AND status IN (" + marks + ")"
                + " ORDER BY requested_at DESC, approval_id", approvalMapper(), args.toArray());
    }

    @Override
    public List<WriteApproval> listOpenApprovals(String caseId, String environmentId) {
        return jdbcTemplate.query("""
                        SELECT * FROM td_write_approval
                         WHERE case_id = ? AND environment_id = ? AND status IN ('requested', 'approved')
                         ORDER BY requested_at DESC, approval_id
                        """, approvalMapper(), caseId, environmentId);
    }

    @Override
    public boolean decideApproval(WriteApproval decided, String fromStatus) {
        return jdbcTemplate.update("""
                        UPDATE td_write_approval
                           SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?, self_approved = ?,
                               expires_at = ?, max_runs = ?
                         WHERE approval_id = ? AND status = ?
                        """,
                decided.status(), decided.decidedBy(), timestamp(decided.decidedAt()), decided.decisionNote(),
                decided.selfApproved(), timestamp(decided.expiresAt()), decided.maxRuns(), decided.id(),
                fromStatus) == 1;
    }

    @Override
    public boolean setApprovalStatus(String approvalId, String fromStatus, String toStatus) {
        return jdbcTemplate.update("UPDATE td_write_approval SET status = ? WHERE approval_id = ? AND status = ?",
                toStatus, approvalId, fromStatus) == 1;
    }

    @Override
    public boolean useApprovalRun(String approvalId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE td_write_approval SET runs_used = runs_used + 1
                         WHERE approval_id = ? AND status = 'approved'
                           AND (max_runs IS NULL OR runs_used < max_runs)
                           AND (expires_at IS NULL OR expires_at > ?)
                        """, approvalId, timestamp(now)) == 1;
    }

    @Override
    public void insertEmergencyAccess(EmergencyAccess access) {
        jdbcTemplate.update("""
                        INSERT INTO td_emergency_access (access_id, org_id, environment_id, reason, granted_by,
                                                         confirmed_by, starts_at, ends_at, max_requests, requests_used)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                access.id(), access.orgId(), access.environmentId(), access.reason(), access.grantedBy(),
                access.confirmedBy(), timestamp(access.startsAt()), timestamp(access.endsAt()), access.maxRequests());
    }

    @Override
    public Optional<EmergencyAccess> findEmergencyAccess(String orgId, String accessId) {
        return jdbcTemplate.query("SELECT * FROM td_emergency_access WHERE org_id = ? AND access_id = ?",
                emergencyMapper(), orgId, accessId).stream().findFirst();
    }

    @Override
    public List<EmergencyAccess> listCurrentEmergencyAccess(String orgId, Instant now) {
        return jdbcTemplate.query("""
                        SELECT * FROM td_emergency_access
                         WHERE org_id = ? AND ended_at IS NULL AND ends_at > ?
                         ORDER BY starts_at DESC, access_id
                        """, emergencyMapper(), orgId, timestamp(now));
    }

    @Override
    public boolean confirmEmergencyAccess(String accessId, String confirmedBy) {
        return jdbcTemplate.update("""
                        UPDATE td_emergency_access SET confirmed_by = ?
                         WHERE access_id = ? AND confirmed_by IS NULL AND ended_at IS NULL
                        """, confirmedBy, accessId) == 1;
    }

    @Override
    public boolean endEmergencyAccess(String accessId, String endedBy, Instant at) {
        return jdbcTemplate.update("""
                        UPDATE td_emergency_access SET ended_by = ?, ended_at = ?
                         WHERE access_id = ? AND ended_at IS NULL
                        """, endedBy, timestamp(at), accessId) == 1;
    }

    @Override
    public boolean useEmergencyRequest(String accessId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE td_emergency_access SET requests_used = requests_used + 1
                         WHERE access_id = ? AND ended_at IS NULL AND ends_at > ? AND starts_at <= ?
                           AND (max_requests IS NULL OR requests_used < max_requests)
                        """, accessId, timestamp(now), timestamp(now)) == 1;
    }

    @Override
    public void insertDecision(SafetyDecision decision) {
        jdbcTemplate.update("""
                        INSERT INTO td_safety_decision (decision_id, org_id, occurred_at, environment_id, case_id,
                                                        http_method, path_template, target_id, target_label,
                                                        block_code, shadow)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                decision.id(), decision.orgId(), timestamp(decision.occurredAt()), decision.environmentId(),
                decision.caseId(), decision.method(), decision.pathTemplate(), decision.targetId(),
                decision.targetLabel(), decision.code(), decision.shadow());
    }

    @Override
    public int pruneDecisions(String orgId, Instant before) {
        return jdbcTemplate.update("DELETE FROM td_safety_decision WHERE org_id = ? AND occurred_at < ?", orgId,
                timestamp(before));
    }

    @Override
    public List<SafetyDecision.Group> decisionGroups(String orgId, Instant since) {
        return jdbcTemplate.query("""
                        SELECT http_method, path_template, target_id, target_label, block_code, shadow,
                               COUNT(*) AS hits, MAX(occurred_at) AS last_seen, MAX(case_id) AS example_case
                          FROM td_safety_decision
                         WHERE org_id = ? AND occurred_at >= ?
                         GROUP BY http_method, path_template, target_id, target_label, block_code, shadow
                         ORDER BY hits DESC, last_seen DESC
                        """,
                (rs, rowNum) -> new SafetyDecision.Group(
                        rs.getString("http_method"),
                        rs.getString("path_template"),
                        rs.getString("target_id"),
                        rs.getString("target_label"),
                        rs.getString("block_code"),
                        rs.getBoolean("shadow"),
                        rs.getLong("hits"),
                        instant(rs, "last_seen"),
                        rs.getString("example_case")),
                orgId, timestamp(since));
    }

    private static RowMapper<WriteApproval> approvalMapper() {
        return (rs, rowNum) -> new WriteApproval(
                rs.getString("approval_id"),
                rs.getString("org_id"),
                rs.getString("case_id"),
                rs.getLong("case_revision"),
                rs.getString("environment_id"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("requested_by"),
                instant(rs, "requested_at"),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("decision_note"),
                rs.getBoolean("self_approved"),
                instant(rs, "expires_at"),
                (Integer) rs.getObject("max_runs", Integer.class),
                rs.getInt("runs_used"));
    }

    private static RowMapper<EmergencyAccess> emergencyMapper() {
        return (rs, rowNum) -> new EmergencyAccess(
                rs.getString("access_id"),
                rs.getString("org_id"),
                rs.getString("environment_id"),
                rs.getString("reason"),
                rs.getString("granted_by"),
                rs.getString("confirmed_by"),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"),
                (Integer) rs.getObject("max_requests", Integer.class),
                rs.getInt("requests_used"),
                rs.getString("ended_by"),
                instant(rs, "ended_at"));
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
