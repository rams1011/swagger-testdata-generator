package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.audit.AuditResourceType;
import org.apache.commons.lang3.StringUtils;

final class StorageCacheKeys {

    private StorageCacheKeys() {
    }

    static String planList(String specId) {
        return "list:" + specId;
    }

    static String planById(String planId) {
        return "by-id:" + planId;
    }

    static String environmentList(String specId) {
        return "list:" + specId;
    }

    static String environmentById(String environmentId) {
        return "by-id:" + environmentId;
    }

    static String environmentByName(String specId, String name) {
        return "by-name:" + specId + "|" + StringUtils.lowerCase(StringUtils.trimToEmpty(name));
    }

    static String caseList(String specId, String planId, String operationKey) {
        return "list:" + specId + "|" + dash(planId) + "|" + dash(operationKey);
    }

    static String caseById(String testCaseId) {
        return "by-id:" + testCaseId;
    }

    static String fileList(String testCaseId) {
        return "list:" + testCaseId;
    }

    static String runList(RunHistoryFilter filter) {
        if (filter == null) {
            return "list:|";
        }
        return "list:" + dash(filter.specId()) + "|" + dash(filter.planId()) + "|" + dash(filter.environmentId())
                + "|" + dash(filter.caseId()) + "|" + dash(filter.outcome()) + "|" + dash(filter.trigger()) + "|"
                + filter.limit();
    }

    static String runById(String runId) {
        return "by-id:" + runId;
    }

    static String runResult(String runId, String resultId) {
        return "result:" + runId + "|" + resultId;
    }

    static String auditByResource(AuditResourceType resourceType, String resourceId, int limit) {
        return "resource:" + resourceType.name() + "|" + resourceId + "|" + limit;
    }

    static String auditForSpec(String specId, int limit) {
        return "spec:" + specId + "|" + limit;
    }

    static String auditForTenant(int limit) {
        return "tenant|" + limit;
    }

    private static String dash(String value) {
        return StringUtils.isBlank(value) ? "-" : value.trim();
    }
}
