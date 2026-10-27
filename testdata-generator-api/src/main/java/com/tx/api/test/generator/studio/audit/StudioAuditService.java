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

    public void recordPublished(StudioTestCase testCase) {
        if (testCase == null) {
            return;
        }
        append(testCase.getSpecId(), AuditResourceType.CASE, testCase.getId(), AuditAction.PUBLISHED,
                summary(testCase.getTestName(), "published"));
    }

    public void recordExported(String specId, String kind, String fileName, int count) {
        String noun = count == 1 ? "row" : "rows";
        append(specId, AuditResourceType.SPEC, specId, AuditAction.EXPORTED,
                kind + " export " + StringUtils.defaultString(fileName) + " (" + count + " " + noun + ")");
    }

    public void recordLogin(String tenantId, String accountId, String email) {
        appendForTenant(tenantId, accountId, email, null, AuditResourceType.ACCOUNT, accountId,
                AuditAction.LOGGED_IN, "Signed in");
    }

    public void recordLoginFailed(String tenantId, String email) {
        appendForTenant(tenantId, null, email, null, AuditResourceType.ACCOUNT, "unknown",
                AuditAction.LOGIN_FAILED, "Sign-in failed");
    }

    public void recordLogout(String tenantId, String accountId, String email) {
        appendForTenant(tenantId, accountId, email, null, AuditResourceType.ACCOUNT, accountId,
                AuditAction.LOGGED_OUT, "Signed out");
    }

    public void recordSwitched(String tenantId, String accountId, String email, String workspaceName) {
        appendForTenant(tenantId, accountId, email, null, AuditResourceType.WORKSPACE, tenantId,
                AuditAction.SWITCHED, "Switched to workspace " + StringUtils.defaultString(workspaceName));
    }

    public void recordRegistered(String tenantId, String accountId, String email) {
        appendForTenant(tenantId, accountId, email, null, AuditResourceType.ACCOUNT, accountId,
                AuditAction.CREATED, "Registered an account");
    }

    public void recordWorkspaceCreated(String tenantId, String accountId, String email, String workspaceName) {
        appendForTenant(tenantId, accountId, email, null, AuditResourceType.WORKSPACE, tenantId,
                AuditAction.CREATED, "Created workspace " + StringUtils.defaultString(workspaceName));
    }

    public void recordGrant(String accountId, String email, String role) {
        append(null, AuditResourceType.ACCOUNT, accountId, AuditAction.GRANTED,
                "Granted " + role + " to " + StringUtils.defaultString(email));
    }

    public void recordRoleChanged(String accountId, String email, String fromRole, String toRole, String scope) {
        append(null, AuditResourceType.ACCOUNT, accountId, AuditAction.ROLE_CHANGED,
                scope + " role for " + StringUtils.defaultString(email) + " changed from " + fromRole + " to " + toRole);
    }

    public void recordRevoked(String accountId, String email, String scope) {
        append(null, AuditResourceType.ACCOUNT, accountId, AuditAction.REVOKED,
                "Removed " + StringUtils.defaultString(email) + " from the " + scope);
    }

    public void recordPasswordChanged(String accountId, String email) {
        append(null, AuditResourceType.ACCOUNT, accountId, AuditAction.PASSWORD_CHANGED,
                "Changed password for " + StringUtils.defaultString(email));
    }

    public void recordPasswordResetRequired(String accountId, String email) {
        append(null, AuditResourceType.ACCOUNT, accountId, AuditAction.PASSWORD_CHANGED,
                "Required a password change for " + StringUtils.defaultString(email));
    }

    public void recordAccessRequested(String workspaceId, String accountId, String email, String workspaceName) {
        appendForTenant(workspaceId, accountId, email, null, AuditResourceType.WORKSPACE, workspaceId,
                AuditAction.ACCESS_REQUESTED,
                "Requested access to " + StringUtils.defaultString(workspaceName));
    }

    public List<AuditEventView> listForCase(String caseId, int limit) {
        return studioStorage.listAuditEvents(AuditResourceType.CASE, caseId, clamp(limit));
    }

    public List<AuditEventView> listForSpec(String specId, int limit) {
        return studioStorage.listAuditEventsForSpec(specId, clamp(limit));
    }

    public List<AuditEventView> listForWorkspace(int limit) {
        return studioStorage.listAuditEventsForTenant(clamp(limit));
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

    private void appendForTenant(String tenantId, String actorId, String actorLabel, String specId,
                                 AuditResourceType resourceType, String resourceId,
                                 AuditAction action, String detail) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(resourceId)) {
            return;
        }
        String resource = resourceId.length() > 36 ? resourceId.substring(0, 36) : resourceId;
        studioStorage.appendAuditEventForTenant(
                tenantId,
                UUID.randomUUID().toString(),
                StringUtils.trimToNull(specId),
                resourceType,
                resource,
                action,
                StringUtils.trimToNull(actorId),
                StringUtils.abbreviate(StringUtils.trimToEmpty(actorLabel), 320),
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
