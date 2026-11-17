package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.dto.ApprovalView;
import com.tx.api.test.generator.studio.dto.DecideApprovalRequest;
import com.tx.api.test.generator.studio.dto.EmergencyAccessView;
import com.tx.api.test.generator.studio.dto.GrantEmergencyAccessRequest;
import com.tx.api.test.generator.studio.dto.RequestApprovalRequest;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Production write approvals and emergency access (UI repo docs/PRODUCTION_SAFETY.md §3.5 and "Self-approval in
 * single-admin organizations"). An approval covers one case at one published revision in one environment; a
 * revision change, an expiry or the last run ends it. Statuses that time or a newer version ended are written back
 * when an approval is read, so the inbox shows them as ended.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    static final int MAX_DAYS = 90;
    static final int MAX_RUNS = 100;
    static final Duration SELF_APPROVAL_MAX = Duration.ofDays(7);
    static final int SELF_APPROVAL_MAX_RUNS = 10;
    static final List<Integer> EMERGENCY_MINUTES = List.of(15, 30, 60);

    private final SafetyStore store;
    private final SafetyPolicyService policies;
    private final TenantStore tenantStore;
    private final StudioStorage studioStorage;
    private final StudioAuditService auditService;

    public ApprovalService(SafetyStore store, SafetyPolicyService policies, TenantStore tenantStore,
                           StudioStorage studioStorage, StudioAuditService auditService) {
        this.store = store;
        this.policies = policies;
        this.tenantStore = tenantStore;
        this.studioStorage = studioStorage;
        this.auditService = auditService;
    }

    // --- Requests (any signed-in member of the workspace) ---

    public ApprovalView request(String caseId, RequestApprovalRequest request) {
        SafetyPolicyService.Access access = policies.currentAccess();
        StudioTestCase testCase = studioStorage.findTestCase(caseId)
                .orElseThrow(() -> new NoSuchElementException("Case not found"));
        if (CaseLifecycle.isDraft(testCase)) {
            throw new IllegalArgumentException("Publish the case first: only a published version can be approved");
        }
        if (request == null || StringUtils.isBlank(request.environmentId())) {
            throw new IllegalArgumentException("environmentId is required");
        }
        StoredEnvironment environment = requireEnvironment(request.environmentId());
        if (!StringUtils.equals(environment.getSpecId(), testCase.getSpecId())) {
            throw new IllegalArgumentException("That environment belongs to another collection");
        }
        if (!protectedEnvironment(access.orgId(), environment)) {
            throw new IllegalArgumentException("Writes from " + environment.getName() + " aren't blocked: it isn't "
                    + "protected, so no approval is needed");
        }
        String reason = text(request.reason(), 10, 500, "Give a reason of 10 to 500 characters");
        int days = request.days() == null ? 7 : request.days();
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("Ask for 1 to " + MAX_DAYS + " days");
        }
        Integer maxRuns = request.maxRuns();
        if (maxRuns != null && (maxRuns < 1 || maxRuns > MAX_RUNS)) {
            throw new IllegalArgumentException("Runs must be 1 to " + MAX_RUNS + ", or leave it empty for no limit");
        }
        for (WriteApproval open : refreshed(store.listOpenApprovals(caseId, environment.getId()))) {
            if (WriteApproval.REQUESTED.equals(open.status())) {
                throw new IllegalArgumentException("An approval for this case in " + environment.getName()
                        + " is already waiting for an admin");
            }
            if (WriteApproval.APPROVED.equals(open.status())) {
                throw new IllegalArgumentException("This case is already approved in " + environment.getName());
            }
        }
        Instant now = Instant.now();
        WriteApproval approval = new WriteApproval(UUID.randomUUID().toString(), access.orgId(), caseId,
                testCase.getRevision(), environment.getId(), WriteApproval.REQUESTED, reason, access.accountId(), now,
                null, null, null, false, now.plus(Duration.ofDays(days)), maxRuns, 0);
        store.insertApproval(approval);
        audit(testCase.getSpecId(), AuditResourceType.WRITE_APPROVAL, approval.id(), AuditAction.APPROVAL_REQUESTED,
                "Requested production writes for case " + caseName(testCase) + " in " + environment.getName()
                        + " (version " + testCase.getRevision() + "): " + reason);
        log.info("Approval {} requested for case {} in environment {}", approval.id(), caseId, environment.getId());
        return view(approval);
    }

    public ApprovalView withdraw(String caseId, String approvalId) {
        SafetyPolicyService.Access access = policies.currentAccess();
        WriteApproval approval = store.findApproval(access.orgId(), approvalId)
                .filter(candidate -> candidate.caseId().equals(caseId))
                .orElseThrow(() -> new NoSuchElementException("Approval not found"));
        if (!approval.requestedBy().equals(access.accountId())) {
            throw new ForbiddenException("Only the person who asked can withdraw a request");
        }
        if (!store.setApprovalStatus(approvalId, WriteApproval.REQUESTED, WriteApproval.WITHDRAWN)) {
            throw new IllegalStateException("Only a request still waiting for an admin can be withdrawn");
        }
        audit(null, AuditResourceType.WRITE_APPROVAL, approvalId, AuditAction.WITHDRAWN, "Withdrew approval request");
        return view(approval.withStatus(WriteApproval.WITHDRAWN));
    }

    /** The case's approvals in an environment, newest first, for the case editor. */
    public List<ApprovalView> forCase(String caseId, String environmentId) {
        policies.currentAccess();
        return refreshed(store.listOpenApprovals(caseId, environmentId)).stream().map(this::view).toList();
    }

    // --- Decisions (organization owners and admins) ---

    /** {@code filter}: waiting, active, ended, or blank for all. */
    public List<ApprovalView> list(String filter) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        String wanted = StringUtils.defaultString(filter).trim().toLowerCase(Locale.ROOT);
        List<String> statuses = switch (wanted) {
            case "waiting" -> List.of(WriteApproval.REQUESTED);
            case "active" -> List.of(WriteApproval.APPROVED);
            case "ended" -> List.of(WriteApproval.REJECTED, WriteApproval.REVOKED, WriteApproval.EXPIRED,
                    WriteApproval.ENDED, WriteApproval.WITHDRAWN);
            case "" -> List.of();
            default -> throw new IllegalArgumentException("status must be waiting, active or ended");
        };
        List<WriteApproval> approvals = refreshed(store.listApprovals(access.orgId(), statuses));
        if (!statuses.isEmpty()) {
            approvals = approvals.stream().filter(approval -> statuses.contains(approval.status())).toList();
        }
        return approvals.stream().map(this::view).toList();
    }

    public ApprovalView approve(String approvalId, DecideApprovalRequest request) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        WriteApproval approval = requireApproval(access, approvalId);
        if (!WriteApproval.REQUESTED.equals(approval.status())) {
            throw new IllegalStateException("Only a request still waiting can be approved");
        }
        StudioTestCase testCase = studioStorage.findTestCase(approval.caseId()).orElse(null);
        if (testCase == null || testCase.getRevision() != approval.caseRevision()) {
            store.setApprovalStatus(approvalId, WriteApproval.REQUESTED, WriteApproval.ENDED);
            throw new IllegalStateException("The case changed since this was asked for. Ask for a new approval.");
        }
        SafetyPolicy policy = policies.policy(access.orgId());
        boolean self = approval.requestedBy().equals(access.accountId());
        if (self) {
            if (policy.selfApproval() == SelfApproval.NEVER) {
                throw new ForbiddenException("Your organization doesn't allow approving your own request");
            }
            if (adminCount(access.orgId()) != 1) {
                throw new ForbiddenException("Another organization admin has to approve your request");
            }
        }
        Instant now = Instant.now();
        Duration asked = Duration.between(approval.requestedAt(), approval.expiresAt());
        Instant longest = now.plus(self && asked.compareTo(SELF_APPROVAL_MAX) > 0 ? SELF_APPROVAL_MAX : asked);
        Instant expiresAt = request == null || request.expiresAt() == null ? longest : request.expiresAt();
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("The end date must be in the future");
        }
        if (expiresAt.isAfter(longest.plusSeconds(60))) {
            throw new IllegalArgumentException(self
                    ? "Approving your own request allows at most 7 days and what was asked for"
                    : "You can shorten what was asked for, not lengthen it");
        }
        Integer askedRuns = approval.maxRuns();
        Integer mostRuns = self ? Integer.valueOf(askedRuns == null ? SELF_APPROVAL_MAX_RUNS
                : Math.min(askedRuns, SELF_APPROVAL_MAX_RUNS)) : askedRuns;
        Integer maxRuns = request == null || request.maxRuns() == null ? mostRuns : request.maxRuns();
        if (maxRuns != null && (maxRuns < 1 || (mostRuns != null && maxRuns > mostRuns))) {
            throw new IllegalArgumentException(mostRuns == null ? "Runs must be at least 1"
                    : "Runs must be 1 to " + mostRuns);
        }
        String note = optional(request == null ? null : request.note(), 500, "The note can be at most 500 characters");
        WriteApproval decided = new WriteApproval(approval.id(), approval.orgId(), approval.caseId(),
                approval.caseRevision(), approval.environmentId(), WriteApproval.APPROVED, approval.reason(),
                approval.requestedBy(), approval.requestedAt(), access.accountId(), now, note, self, expiresAt,
                maxRuns, approval.runsUsed());
        if (!store.decideApproval(decided, WriteApproval.REQUESTED)) {
            throw new IllegalStateException("Someone else decided this request first. Reload the list.");
        }
        audit(testCase.getSpecId(), AuditResourceType.WRITE_APPROVAL, approvalId,
                self ? AuditAction.SELF_APPROVED : AuditAction.APPROVED,
                (self ? "Self-approved" : "Approved") + " production writes for case " + caseName(testCase)
                        + " (version " + approval.caseRevision() + ") until " + expiresAt
                        + (maxRuns == null ? "" : ", " + maxRuns + " runs"));
        return view(decided);
    }

    public ApprovalView reject(String approvalId, DecideApprovalRequest request) {
        return close(approvalId, WriteApproval.REQUESTED, WriteApproval.REJECTED, AuditAction.REJECTED,
                request, "Only a request still waiting can be rejected");
    }

    public ApprovalView revoke(String approvalId, DecideApprovalRequest request) {
        return close(approvalId, WriteApproval.APPROVED, WriteApproval.REVOKED, AuditAction.REVOKED,
                request, "Only an approval in use can be revoked");
    }

    // --- Emergency access ---

    public EmergencyAccessView grantEmergency(GrantEmergencyAccessRequest request) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        if (request == null || StringUtils.isBlank(request.environmentId())) {
            throw new IllegalArgumentException("environmentId is required");
        }
        StoredEnvironment environment = requireEnvironment(request.environmentId());
        if (!protectedEnvironment(access.orgId(), environment)) {
            throw new IllegalArgumentException(environment.getName() + " isn't protected, so writes already go through");
        }
        int minutes = request.minutes() == null ? 30 : request.minutes();
        if (!EMERGENCY_MINUTES.contains(minutes)) {
            throw new IllegalArgumentException("Emergency access lasts 15, 30 or 60 minutes");
        }
        Integer maxRequests = request.maxRequests();
        if (maxRequests != null && (maxRequests < 1 || maxRequests > 1000)) {
            throw new IllegalArgumentException("Requests must be 1 to 1000, or leave it empty for no limit");
        }
        String reason = text(request.reason(), 10, 500, "Give a reason of 10 to 500 characters");
        SafetyPolicy policy = policies.policy(access.orgId());
        if (policy.requireSecondAdmin() && adminCount(access.orgId()) < 2) {
            throw new IllegalArgumentException("Your organization requires a second admin to confirm emergency access, "
                    + "and you're its only admin. Add an admin, or turn that setting off.");
        }
        // Whole milliseconds: a database rounding the start up (MySQL DATETIME(3)) would put it in the future.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean running = store.listCurrentEmergencyAccess(access.orgId(), now).stream()
                .anyMatch(current -> current.environmentId().equals(environment.getId()));
        if (running) {
            throw new IllegalArgumentException(environment.getName() + " already has emergency access. End it first.");
        }
        EmergencyAccess granted = new EmergencyAccess(UUID.randomUUID().toString(), access.orgId(), environment.getId(),
                reason, access.accountId(), policy.requireSecondAdmin() ? null : access.accountId(), now,
                now.plus(Duration.ofMinutes(minutes)), maxRequests, 0, null, null);
        store.insertEmergencyAccess(granted);
        audit(environment.getSpecId(), AuditResourceType.EMERGENCY_ACCESS, granted.id(), AuditAction.EMERGENCY_GRANTED,
                "Granted emergency access to " + environment.getName() + " for " + minutes + " minutes"
                        + (maxRequests == null ? "" : ", " + maxRequests + " requests") + ": " + reason);
        log.warn("Emergency access {} granted for environment {} by {}", granted.id(), environment.getId(),
                access.accountId());
        return emergencyView(granted, policy.requireSecondAdmin());
    }

    public EmergencyAccessView confirmEmergency(String accessId) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        EmergencyAccess current = store.findEmergencyAccess(access.orgId(), accessId)
                .orElseThrow(() -> new NoSuchElementException("Emergency access not found"));
        if (current.grantedBy().equals(access.accountId())) {
            throw new ForbiddenException("A second admin has to confirm emergency access");
        }
        if (!store.confirmEmergencyAccess(accessId, access.accountId())) {
            throw new IllegalStateException("It's already confirmed or ended");
        }
        audit(null, AuditResourceType.EMERGENCY_ACCESS, accessId, AuditAction.EMERGENCY_CONFIRMED,
                "Confirmed emergency access");
        return emergencyView(store.findEmergencyAccess(access.orgId(), accessId).orElseThrow(),
                policies.policy(access.orgId()).requireSecondAdmin());
    }

    public EmergencyAccessView endEmergency(String accessId) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        store.findEmergencyAccess(access.orgId(), accessId)
                .orElseThrow(() -> new NoSuchElementException("Emergency access not found"));
        if (!store.endEmergencyAccess(accessId, access.accountId(), Instant.now())) {
            throw new IllegalStateException("It has already ended");
        }
        audit(null, AuditResourceType.EMERGENCY_ACCESS, accessId, AuditAction.EMERGENCY_ENDED, "Ended emergency access");
        return emergencyView(store.findEmergencyAccess(access.orgId(), accessId).orElseThrow(),
                policies.policy(access.orgId()).requireSecondAdmin());
    }

    /** Emergency access that hasn't ended yet, including any waiting for a second admin. */
    public List<EmergencyAccessView> currentEmergency() {
        SafetyPolicyService.Access access = policies.requireAdmin();
        boolean second = policies.policy(access.orgId()).requireSecondAdmin();
        return store.listCurrentEmergencyAccess(access.orgId(), Instant.now()).stream()
                .map(current -> emergencyView(current, second)).toList();
    }

    /** Emergency access running now in one environment, for its banner. Any member of the organization may read it. */
    public List<EmergencyAccessView> currentEmergencyFor(String environmentId) {
        SafetyPolicyService.Access access = policies.currentAccess();
        boolean second = policies.policy(access.orgId()).requireSecondAdmin();
        Instant now = Instant.now();
        return store.listCurrentEmergencyAccess(access.orgId(), now).stream()
                .filter(current -> current.environmentId().equals(environmentId))
                .map(current -> emergencyView(current, second)).toList();
    }

    // --- What SafetyGuard and the previews read ---

    /** What lets a write through: an approval or emergency access, by id; or why not. */
    public record Permission(boolean allowed, String via, String id, String code, String message) {
        static Permission deny(String code, String message) {
            return new Permission(false, null, null, code, message);
        }
    }

    /**
     * Whether a write from this case to this protected environment is allowed, without using anything up.
     * {@code fromEditor}: sent from the editor (unsaved or draft content), which an approval never covers.
     */
    public Permission permission(String orgId, String environmentId, String environmentName, String caseId,
                                 Long caseRevision, boolean draftCase, boolean fromEditor, boolean secondAdmin) {
        if (StringUtils.isBlank(environmentId)) {
            return Permission.deny(SafetyGuard.WRITE_TO_PROTECTED_TARGET, "Choose the environment it belongs to, "
                    + "and ask an admin to approve the case there.");
        }
        Instant now = Instant.now();
        for (EmergencyAccess current : store.listCurrentEmergencyAccess(orgId, now)) {
            if (current.environmentId().equals(environmentId) && current.active(now, secondAdmin)) {
                return new Permission(true, "emergency access", current.id(), null, null);
            }
        }
        if (draftCase || StringUtils.isBlank(caseId) || caseRevision == null) {
            return Permission.deny(SafetyGuard.DRAFT_IN_PROTECTED, "Drafts and unsaved cases never run in "
                    + environmentName + ". Publish the case, then ask an admin to approve it.");
        }
        List<WriteApproval> open = store.listOpenApprovals(caseId, environmentId);
        Permission denial = Permission.deny(SafetyGuard.WRITE_TO_PROTECTED_TARGET, "Ask an organization admin to "
                + "approve this case for " + environmentName + ".");
        for (WriteApproval approval : open) {
            if (WriteApproval.REQUESTED.equals(approval.status())) {
                denial = Permission.deny(SafetyGuard.WRITE_TO_PROTECTED_TARGET, "The approval asked for is still "
                        + "waiting for an admin.");
                continue;
            }
            if (approval.caseRevision() != caseRevision) {
                store.setApprovalStatus(approval.id(), WriteApproval.APPROVED, WriteApproval.ENDED);
                return Permission.deny(SafetyGuard.APPROVAL_VERSION_CHANGED, "The approval covered version "
                        + approval.caseRevision() + " of this case; this is version " + caseRevision
                        + ". Ask for a new approval.");
            }
            if (approval.expiresAt() != null && !approval.expiresAt().isAfter(now)) {
                store.setApprovalStatus(approval.id(), WriteApproval.APPROVED, WriteApproval.EXPIRED);
                return Permission.deny(SafetyGuard.APPROVAL_EXPIRED, "The approval ended on " + approval.expiresAt()
                        + ". Ask for a new one.");
            }
            if (approval.maxRuns() != null && approval.runsUsed() >= approval.maxRuns()) {
                return Permission.deny(SafetyGuard.APPROVAL_USED_UP, "The approval has no runs left. Ask for a new one.");
            }
            if (fromEditor) {
                // An approval covers the stored published version, never what's in the editor.
                return Permission.deny(SafetyGuard.DRAFT_IN_PROTECTED, "This case is approved for " + environmentName
                        + ", but Send sends what's in the editor. Use Send to " + environmentName
                        + ", which sends the published version.");
            }
            return new Permission(true, approval.selfApproved() ? "self-approval" : "approval", approval.id(), null, null);
        }
        return denial;
    }

    /** Uses one run or request of a permission; false when another run took the last one first. */
    public boolean use(Permission permission) {
        if (permission == null || !permission.allowed()) {
            return false;
        }
        Instant now = Instant.now();
        return "emergency access".equals(permission.via())
                ? store.useEmergencyRequest(permission.id(), now)
                : store.useApprovalRun(permission.id(), now);
    }

    // --- Helpers ---

    private ApprovalView close(String approvalId, String from, String to, AuditAction action,
                               DecideApprovalRequest request, String wrongState) {
        SafetyPolicyService.Access access = policies.requireAdmin();
        WriteApproval approval = requireApproval(access, approvalId);
        if (!from.equals(approval.status())) {
            throw new IllegalStateException(wrongState);
        }
        String note = optional(request == null ? null : request.note(), 500, "The note can be at most 500 characters");
        Instant now = Instant.now();
        WriteApproval decided = new WriteApproval(approval.id(), approval.orgId(), approval.caseId(),
                approval.caseRevision(), approval.environmentId(), to, approval.reason(), approval.requestedBy(),
                approval.requestedAt(), access.accountId(), now, note, approval.selfApproved(), approval.expiresAt(),
                approval.maxRuns(), approval.runsUsed());
        if (!store.decideApproval(decided, from)) {
            throw new IllegalStateException("Someone else changed this approval first. Reload the list.");
        }
        audit(null, AuditResourceType.WRITE_APPROVAL, approvalId, action,
                StringUtils.capitalize(to) + " approval" + (note == null ? "" : ": " + note));
        return view(decided);
    }

    private WriteApproval requireApproval(SafetyPolicyService.Access access, String approvalId) {
        return refreshed(store.findApproval(access.orgId(), approvalId).stream().toList()).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Approval not found"));
    }

    /** Writes back the end of approvals that expired or whose case changed. */
    private List<WriteApproval> refreshed(List<WriteApproval> approvals) {
        Instant now = Instant.now();
        List<WriteApproval> out = new ArrayList<>();
        for (WriteApproval approval : approvals) {
            WriteApproval current = approval;
            if (approval.open()) {
                Long revision = studioStorage.findTestCase(approval.caseId()).map(StudioTestCase::getRevision).orElse(null);
                if (revision == null || revision != approval.caseRevision()) {
                    store.setApprovalStatus(approval.id(), approval.status(), WriteApproval.ENDED);
                    current = approval.withStatus(WriteApproval.ENDED);
                } else if (WriteApproval.APPROVED.equals(approval.status()) && approval.expiresAt() != null
                        && !approval.expiresAt().isAfter(now)) {
                    store.setApprovalStatus(approval.id(), WriteApproval.APPROVED, WriteApproval.EXPIRED);
                    current = approval.withStatus(WriteApproval.EXPIRED);
                }
            }
            out.add(current);
        }
        return out;
    }

    private boolean protectedEnvironment(String orgId, StoredEnvironment environment) {
        return environment.isProtectedEnvironment() || policies.matchedTarget(orgId, environment).isPresent();
    }

    private StoredEnvironment requireEnvironment(String environmentId) {
        return studioStorage.findEnvironment(environmentId)
                .orElseThrow(() -> new NoSuchElementException("Environment not found"));
    }

    private int adminCount(String orgId) {
        return (int) tenantStore.listOrgMembers(orgId).stream()
                .filter(member -> "OWNER".equals(member.role()) || "ADMIN".equals(member.role()))
                .count();
    }

    private ApprovalView view(WriteApproval approval) {
        Optional<StudioTestCase> testCase = studioStorage.findTestCase(approval.caseId());
        String environmentName = studioStorage.findEnvironment(approval.environmentId())
                .map(StoredEnvironment::getName).orElse(approval.environmentId());
        return new ApprovalView(approval.id(), approval.caseId(), testCase.map(this::caseName).orElse(null),
                testCase.map(StudioTestCase::getSpecId).orElse(null), approval.caseRevision(),
                testCase.map(found -> found.getRevision() == approval.caseRevision()).orElse(false),
                approval.environmentId(), environmentName, approval.status(), approval.reason(),
                approval.requestedBy(), label(approval.requestedBy()), approval.requestedAt(),
                label(approval.decidedBy()), approval.decidedAt(), approval.decisionNote(), approval.selfApproved(),
                approval.expiresAt(), approval.maxRuns(), approval.runsUsed());
    }

    private EmergencyAccessView emergencyView(EmergencyAccess access, boolean secondAdmin) {
        String environmentName = studioStorage.findEnvironment(access.environmentId())
                .map(StoredEnvironment::getName).orElse(access.environmentId());
        return new EmergencyAccessView(access.id(), access.environmentId(), environmentName, access.reason(),
                access.grantedBy(), label(access.grantedBy()), label(access.confirmedBy()), access.startsAt(),
                access.endsAt(), access.maxRequests(), access.requestsUsed(),
                secondAdmin && access.confirmedBy() == null && access.endedAt() == null,
                access.active(Instant.now(), secondAdmin));
    }

    private String label(String accountId) {
        if (accountId == null) {
            return null;
        }
        return tenantStore.findAccount(accountId).map(account -> account.getEmail()).orElse(accountId);
    }

    private String caseName(StudioTestCase testCase) {
        return StringUtils.defaultIfBlank(testCase.getTestName(), testCase.getOperationKey());
    }

    private static String text(String value, int min, int max, String message) {
        String text = StringUtils.trimToEmpty(value);
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static String optional(String value, int max, String message) {
        String text = StringUtils.trimToNull(value);
        if (text != null && text.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private void audit(String specId, AuditResourceType type, String id, AuditAction action, String detail) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.recordSafetyEvent(specId, type, id, action, detail);
        } catch (RuntimeException exception) {
            log.warn("Could not write audit event: {}", exception.getMessage());
        }
    }
}
