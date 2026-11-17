package com.tx.api.test.generator.studio.safety;

import java.util.List;
import java.util.Optional;

/** Production safety tables (migration V8). Every call names the organization; nothing reads TenantContext. */
public interface SafetyStore {

    /** The settings row only; targets and rules are read separately. Empty for an organization with no row. */
    Optional<SafetyPolicy> findPolicy(String orgId);

    void insertPolicy(SafetyPolicy policy);

    /** Saves the settings when the stored revision is still {@code expectedRevision}; false when someone saved first. */
    boolean updatePolicy(SafetyPolicy policy, long expectedRevision);

    List<ProtectedTarget> listTargets(String orgId);

    void insertTarget(String orgId, ProtectedTarget target);

    boolean deleteTarget(String orgId, String targetId);

    List<OperationRule> listRules(String orgId);

    void insertRule(String orgId, OperationRule rule);

    boolean updateRule(String orgId, OperationRule rule);

    boolean deleteRule(String orgId, String ruleId);

    void insertApproval(WriteApproval approval);

    java.util.Optional<WriteApproval> findApproval(String orgId, String approvalId);

    /** Newest first; {@code statuses} empty = all. */
    List<WriteApproval> listApprovals(String orgId, List<String> statuses);

    /** The open (requested or approved) approvals of one case in one environment, newest first. */
    List<WriteApproval> listOpenApprovals(String caseId, String environmentId);

    /** Moves an approval from {@code fromStatus} to the decided state; false when its status had changed. */
    boolean decideApproval(WriteApproval decided, String fromStatus);

    /** Sets a status when the current one is {@code fromStatus}; false otherwise. */
    boolean setApprovalStatus(String approvalId, String fromStatus, String toStatus);

    /** Uses one run of an approval if it still has one, in one statement, so two runs can't both take the last. */
    boolean useApprovalRun(String approvalId, java.time.Instant now);

    void insertEmergencyAccess(EmergencyAccess access);

    java.util.Optional<EmergencyAccess> findEmergencyAccess(String orgId, String accessId);

    /** Not ended and not past their end; newest first. */
    List<EmergencyAccess> listCurrentEmergencyAccess(String orgId, java.time.Instant now);

    boolean confirmEmergencyAccess(String accessId, String confirmedBy);

    boolean endEmergencyAccess(String accessId, String endedBy, java.time.Instant at);

    /** Uses one request of an emergency access if it still has one, in one statement. */
    boolean useEmergencyRequest(String accessId, java.time.Instant now);

    // --- Decision log (review of Shadow and Enforce blocks) ---

    void insertDecision(SafetyDecision decision);

    /** Removes an organization's log rows older than {@code before}; returns how many. */
    int pruneDecisions(String orgId, java.time.Instant before);

    /** The organization's log since {@code since}, grouped, most frequent first. */
    List<SafetyDecision.Group> decisionGroups(String orgId, java.time.Instant since);
}
