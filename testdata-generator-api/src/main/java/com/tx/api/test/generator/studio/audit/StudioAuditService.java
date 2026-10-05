package com.tx.api.test.generator.studio.audit;

import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.dto.ReloadSpecResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class StudioAuditService {

    private final StudioStorage studioStorage;

    public StudioAuditService(StudioStorage studioStorage) {
        this.studioStorage = studioStorage;
    }

    public void recordCaseCreated(StudioTestCase testCase) {
        if (testCase == null) {
            return;
        }
        append(testCase.getSpecId(), AuditResourceType.CASE, testCase.getId(), AuditAction.CREATED,
                summary(testCase.getTestName(), "created"));
    }

    public void recordCaseUpdated(StudioTestCase testCase) {
        if (testCase == null) {
            return;
        }
        append(testCase.getSpecId(), AuditResourceType.CASE, testCase.getId(), AuditAction.UPDATED,
                summary(testCase.getTestName(), "updated"));
    }

    public void recordCaseDeleted(String specId, String caseId, String testName) {
        append(specId, AuditResourceType.CASE, caseId, AuditAction.DELETED,
                summary(testName, "deleted"));
    }

    public void recordSpecRenamed(String specId, String name) {
        append(specId, AuditResourceType.SPEC, specId, AuditAction.RENAMED,
                summary(name, "settings saved"));
    }

    public void recordSpecReloaded(String specId, ReloadSpecResult result) {
        String detail = result == null
                ? "Spec reloaded"
                : "Reloaded: +" + result.addedOperations().size() + " / -" + result.removedOperations().size()
                + " operations, " + result.orphanedCaseCount() + " orphaned cases";
        append(specId, AuditResourceType.SPEC, specId, AuditAction.RELOADED, detail);
    }

    public void recordSpecDeleted(String specId, String name) {
        append(specId, AuditResourceType.SPEC, specId, AuditAction.DELETED,
                summary(name, "deleted"));
    }

    public List<AuditEventView> listForCase(String caseId, int limit) {
        return studioStorage.listAuditEvents(AuditResourceType.CASE, caseId, clamp(limit));
    }

    public List<AuditEventView> listForSpec(String specId, int limit) {
        return studioStorage.listAuditEventsForSpec(specId, clamp(limit));
    }

    private void append(String specId, AuditResourceType resourceType, String resourceId,
                        AuditAction action, String detail) {
        TenantContext.Scope scope = TenantContext.current();
        String actorId = scope == null ? null : scope.accountId();
        String actorLabel = scope == null ? null : StringUtils.defaultIfBlank(scope.email(), scope.accountId());
        studioStorage.appendAuditEvent(
                UUID.randomUUID().toString(),
                specId,
                resourceType,
                resourceId,
                action,
                actorId,
                actorLabel,
                StringUtils.abbreviate(StringUtils.trimToEmpty(detail), 512),
                Instant.now()
        );
    }

    private static String summary(String name, String verb) {
        if (StringUtils.isBlank(name)) {
            return "Test case " + verb;
        }
        return "Test case \"" + name.trim() + "\" " + verb;
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
