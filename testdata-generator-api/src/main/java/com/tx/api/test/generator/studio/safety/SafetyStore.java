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
}
