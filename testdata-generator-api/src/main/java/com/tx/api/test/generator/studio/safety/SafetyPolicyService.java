package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.api.ConflictException;
import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddProtectedTargetRequest;
import com.tx.api.test.generator.studio.dto.EnvironmentProtectionView;
import com.tx.api.test.generator.studio.dto.SafetySettingsView;
import com.tx.api.test.generator.studio.dto.SafetySummaryView;
import com.tx.api.test.generator.studio.dto.SaveOperationRuleRequest;
import com.tx.api.test.generator.studio.dto.SetEnvironmentProtectionRequest;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.Tenant;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import com.tx.api.test.generator.studio.tenant.UnauthenticatedException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Production safety settings, protected targets, operation rules and environment protection
 * (UI repo docs/PRODUCTION_SAFETY.md). Organization owners and admins change them; any signed-in member reads the
 * summary. With {@code testdata.safety.enabled} off every call but {@link #summary()} answers 404.
 */
@Service
public class SafetyPolicyService {

    private static final Logger log = LoggerFactory.getLogger(SafetyPolicyService.class);

    /** A change applies to the next request within this long on another node; this node clears its copy at once. */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    static final String SHADOW_NOTICE = "Production safety is now in Shadow: Specforge records requests that would "
            + "be blocked and blocks nothing yet. Review them before you switch to Enforce.";

    private static final Pattern HTTP_METHOD = Pattern.compile("[A-Z][A-Z0-9_-]{0,15}");
    private static final Pattern PATH_TEMPLATE = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/{}-]*");
    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]{0,254}");
    private static final Pattern GRPC_METHOD = Pattern.compile("([A-Za-z_]\\w*\\.)*[A-Za-z_]\\w*/[A-Za-z_]\\w*");

    private final StudioProperties properties;
    private final SafetyStore store;
    private final TenantStore tenantStore;
    private final StudioStorage studioStorage;
    private final StudioAuditService auditService;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SafetyPolicyService(StudioProperties properties, SafetyStore store, TenantStore tenantStore,
                               StudioStorage studioStorage, StudioAuditService auditService) {
        this.properties = properties;
        this.store = store;
        this.tenantStore = tenantStore;
        this.studioStorage = studioStorage;
        this.auditService = auditService;
    }

    private record Cached(SafetyPolicy policy, Instant loadedAt) {
    }

    /** The signed-in account, its organization and its role there; ApprovalService uses the same check. */
    record Access(String orgId, String accountId, String orgRole) {
        boolean admin() {
            return "OWNER".equals(orgRole) || "ADMIN".equals(orgRole);
        }
    }

    // --- Reading ---

    /** An organization's policy with its targets and rules, cached for at most {@link #CACHE_TTL}. */
    public SafetyPolicy policy(String orgId) {
        Cached cached = cache.get(orgId);
        if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.policy();
        }
        SafetyPolicy fresh = load(orgId);
        cache.put(orgId, new Cached(fresh, Instant.now()));
        return fresh;
    }

    public SafetySummaryView summary() {
        if (!properties.safetyOn()) {
            return new SafetySummaryView(false, SafetyMode.OFF, SafetyPolicy.DEFAULT_MAX_REQUESTS_PER_RUN,
                    SafetyPolicy.DEFAULT_MAX_REQUESTS_PER_MINUTE, SafetyPolicy.DEFAULT_MAX_WRITE_DATA_ROWS, 0, false);
        }
        Access access = currentAccess();
        SafetyPolicy policy = policy(access.orgId());
        return new SafetySummaryView(true, policy.mode(), policy.maxRequestsPerRun(), policy.maxRequestsPerMinute(),
                policy.maxWriteDataRows(), policy.targets().size(), access.admin());
    }

    public SafetySettingsView settings() {
        Access access = requireAdmin();
        return view(policy(access.orgId()), null);
    }

    /** Every environment of a collection with its protection. Any signed-in member of the workspace may read it. */
    public List<EnvironmentProtectionView> environmentProtection(String specId) {
        Access access = currentAccess();
        List<ProtectedTarget> targets = policy(access.orgId()).targets();
        List<EnvironmentProtectionView> views = new ArrayList<>();
        for (StoredEnvironment environment : studioStorage.listEnvironments(specId)) {
            views.add(protectionView(environment, targets));
        }
        return views;
    }

    /** The current workspace's organization when production safety is on and someone is signed in; else empty. */
    public Optional<String> currentOrgId() {
        if (!properties.safetyOn()) {
            return Optional.empty();
        }
        try {
            return Optional.of(currentAccess().orgId());
        } catch (RuntimeException notSignedIn) {
            return Optional.empty();
        }
    }

    /** The protected target an environment's base URL falls under, if any. */
    public Optional<ProtectedTarget> matchedTarget(String orgId, StoredEnvironment environment) {
        return TargetPatterns.matchUrl(policy(orgId).targets(), baseUrl(environment));
    }

    // --- Settings ---

    public SafetySettingsView updateSettings(UpdateSafetySettingsRequest request) {
        Access access = requireAdmin();
        if (request == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("expectedRevision is required");
        }
        SafetyPolicy current = load(access.orgId());
        if (current.revision() != request.expectedRevision()) {
            throw new ConflictException("Someone else changed production safety. Reload and try again.");
        }
        SafetyMode mode = request.mode() == null ? current.mode() : SafetyMode.from(request.mode());
        int perRun = limit(request.maxRequestsPerRun(), current.maxRequestsPerRun(), 1, 5000, "Requests per run");
        int perMinute = limit(request.maxRequestsPerMinute(), current.maxRequestsPerMinute(), 1, 600,
                "Requests per minute per target");
        int writeRows = limit(request.maxWriteDataRows(), current.maxWriteDataRows(), 1, 50,
                "Data rows for write cases");
        boolean secondAdmin = request.requireSecondAdmin() == null
                ? current.requireSecondAdmin() : request.requireSecondAdmin();
        SelfApproval selfApproval = request.selfApproval() == null
                ? current.selfApproval() : SelfApproval.from(request.selfApproval());
        SafetyPolicy next = current.withSettings(mode, perRun, perMinute, writeRows, secondAdmin, selfApproval);
        String changes = describeChanges(current, next);
        if (!changes.isEmpty()) {
            saveSettings(current, next, access);
            audit(() -> auditService.recordSafetySettingsChanged(access.orgId(), "Production safety: " + changes));
            log.info("Production safety settings for organization {} changed: {}", access.orgId(), changes);
        }
        return view(load(access.orgId()), null);
    }

    // --- Protected targets ---

    public SafetySettingsView addTarget(AddProtectedTargetRequest request) {
        Access access = requireAdmin();
        TargetPatterns.Parsed parsed = TargetPatterns.parse(request == null ? null : request.target());
        List<Integer> ports = TargetPatterns.normalizePorts(request.ports());
        String note = optionalText(request.note(), 200, "Note");
        SafetyPolicy current = load(access.orgId());
        if (current.targets().size() >= SafetyPolicy.MAX_TARGETS) {
            throw new IllegalArgumentException("An organization can have at most " + SafetyPolicy.MAX_TARGETS
                    + " protected targets");
        }
        if (current.targets().stream().anyMatch(target -> target.pattern().equals(parsed.pattern()))) {
            throw new IllegalArgumentException(parsed.pattern() + " is already protected");
        }
        ProtectedTarget target = new ProtectedTarget(UUID.randomUUID().toString(), parsed.kind(), parsed.pattern(),
                ports, note, access.accountId(), Instant.now());
        try {
            store.insertTarget(access.orgId(), target);
        } catch (DuplicateKeyException race) {
            throw new IllegalArgumentException(parsed.pattern() + " is already protected");
        }
        invalidate(access.orgId());
        audit(() -> auditService.recordProtectedTargetAdded(target.id(), target.pattern()));
        String notice = null;
        if (current.mode() == SafetyMode.OFF && current.targets().isEmpty()) {
            // The first target starts recording what would be blocked; only an admin ever chooses Enforce.
            SafetyPolicy fresh = load(access.orgId());
            saveSettings(fresh, fresh.withSettings(SafetyMode.SHADOW, fresh.maxRequestsPerRun(),
                    fresh.maxRequestsPerMinute(), fresh.maxWriteDataRows(), fresh.requireSecondAdmin(),
                    fresh.selfApproval()), access);
            audit(() -> auditService.recordSafetySettingsChanged(access.orgId(),
                    "Production safety: mode off → shadow (first protected target added)"));
            notice = SHADOW_NOTICE;
        }
        log.info("Protected target {} added for organization {}", target.pattern(), access.orgId());
        return view(load(access.orgId()), notice);
    }

    public SafetySettingsView deleteTarget(String targetId) {
        Access access = requireAdmin();
        ProtectedTarget target = load(access.orgId()).targets().stream()
                .filter(candidate -> candidate.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Protected target not found"));
        if (!store.deleteTarget(access.orgId(), targetId)) {
            throw new NoSuchElementException("Protected target not found");
        }
        invalidate(access.orgId());
        audit(() -> auditService.recordProtectedTargetRemoved(target.id(), target.pattern()));
        log.info("Protected target {} removed for organization {}", target.pattern(), access.orgId());
        return view(load(access.orgId()), null);
    }

    // --- Operation rules ---

    public SafetySettingsView addRule(SaveOperationRuleRequest request) {
        Access access = requireAdmin();
        SafetyPolicy current = load(access.orgId());
        if (current.rules().size() >= SafetyPolicy.MAX_RULES) {
            throw new IllegalArgumentException("An organization can have at most " + SafetyPolicy.MAX_RULES + " rules");
        }
        OperationRule rule = validRule(UUID.randomUUID().toString(), request, current, access, Instant.now());
        store.insertRule(access.orgId(), rule);
        invalidate(access.orgId());
        audit(() -> auditService.recordOperationRuleChanged(rule.id(), AuditAction.CREATED,
                "Added rule: " + describe(rule)));
        return view(load(access.orgId()), null);
    }

    public SafetySettingsView updateRule(String ruleId, SaveOperationRuleRequest request) {
        Access access = requireAdmin();
        SafetyPolicy current = load(access.orgId());
        OperationRule existing = current.rules().stream()
                .filter(candidate -> candidate.id().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Rule not found"));
        OperationRule rule = validRule(ruleId, request, current, access, existing.createdAt());
        if (!store.updateRule(access.orgId(), rule)) {
            throw new NoSuchElementException("Rule not found");
        }
        invalidate(access.orgId());
        audit(() -> auditService.recordOperationRuleChanged(rule.id(), AuditAction.UPDATED,
                "Changed rule: " + describe(existing) + " → " + describe(rule)));
        return view(load(access.orgId()), null);
    }

    public SafetySettingsView deleteRule(String ruleId) {
        Access access = requireAdmin();
        OperationRule existing = load(access.orgId()).rules().stream()
                .filter(candidate -> candidate.id().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Rule not found"));
        if (!store.deleteRule(access.orgId(), ruleId)) {
            throw new NoSuchElementException("Rule not found");
        }
        invalidate(access.orgId());
        audit(() -> auditService.recordOperationRuleChanged(ruleId, AuditAction.DELETED,
                "Removed rule: " + describe(existing)));
        return view(load(access.orgId()), null);
    }

    // --- Environment protection ---

    public EnvironmentProtectionView setEnvironmentProtection(String environmentId,
                                                              SetEnvironmentProtectionRequest request) {
        Access access = requireAdmin();
        if (request == null || request.protectedEnvironment() == null) {
            throw new IllegalArgumentException("protected is required");
        }
        StoredEnvironment environment = studioStorage.findEnvironment(environmentId)
                .orElseThrow(() -> new NoSuchElementException("Environment not found"));
        boolean value = request.protectedEnvironment();
        List<ProtectedTarget> targets = policy(access.orgId()).targets();
        Optional<ProtectedTarget> matched = TargetPatterns.matchUrl(targets, baseUrl(environment));
        if (!value && matched.isPresent()) {
            throw new IllegalStateException("This environment's base URL is under the protected target "
                    + matched.get().pattern() + ", so it stays protected. Remove that target first.");
        }
        if (environment.isProtectedEnvironment() != value) {
            studioStorage.setEnvironmentProtected(environmentId, value);
            environment.setProtectedEnvironment(value);
            audit(() -> auditService.recordEnvironmentProtectionChanged(environment.getSpecId(), environmentId,
                    environment.getName(), value));
            log.info("Environment {} protection set to {} in organization {}", environmentId, value, access.orgId());
        }
        return protectionView(environment, targets);
    }

    // --- Helpers ---

    private SafetyPolicy load(String orgId) {
        SafetyPolicy settings = store.findPolicy(orgId).orElseGet(() -> SafetyPolicy.defaults(orgId));
        return settings.withTargetsAndRules(store.listTargets(orgId), store.listRules(orgId));
    }

    private void invalidate(String orgId) {
        cache.remove(orgId);
    }

    private void saveSettings(SafetyPolicy current, SafetyPolicy next, Access access) {
        Instant now = Instant.now();
        SafetyPolicy stamped = new SafetyPolicy(next.orgId(), next.mode(), next.maxRequestsPerRun(),
                next.maxRequestsPerMinute(), next.maxWriteDataRows(), next.requireSecondAdmin(), next.selfApproval(),
                current.revision() + 1, access.accountId(), now, true, next.targets(), next.rules());
        if (!current.stored()) {
            try {
                store.insertPolicy(stamped);
            } catch (DuplicateKeyException race) {
                throw new ConflictException("Someone else changed production safety. Reload and try again.");
            }
        } else if (!store.updatePolicy(stamped, current.revision())) {
            throw new ConflictException("Someone else changed production safety. Reload and try again.");
        }
        invalidate(next.orgId());
    }

    Access currentAccess() {
        if (!properties.safetyOn()) {
            throw new NoSuchElementException("Not found");
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
        Tenant tenant = tenantStore.findTenant(scope.tenantId())
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        if (StringUtils.isBlank(tenant.getOrgId())) {
            throw new ForbiddenException("This workspace is not attached to an organization");
        }
        String orgRole = tenantStore.findOrgRole(tenant.getOrgId(), scope.accountId()).orElse(null);
        return new Access(tenant.getOrgId(), scope.accountId(), orgRole);
    }

    Access requireAdmin() {
        Access access = currentAccess();
        if (!access.admin()) {
            throw new ForbiddenException("Only organization owners and admins can change production safety");
        }
        return access;
    }

    private OperationRule validRule(String id, SaveOperationRuleRequest request, SafetyPolicy current, Access access,
                                    Instant createdAt) {
        if (request == null) {
            throw new IllegalArgumentException("A rule is required");
        }
        RuleEffect effect = RuleEffect.from(request.effect());
        RuleProtocol protocol = RuleProtocol.from(request.protocol());
        String method = null;
        String path = null;
        String operation = null;
        if (protocol == RuleProtocol.REST) {
            method = StringUtils.trimToEmpty(request.httpMethod()).toUpperCase(Locale.ROOT);
            if (!HTTP_METHOD.matcher(method).matches()) {
                throw new IllegalArgumentException("Enter an HTTP method, such as POST");
            }
            path = StringUtils.trimToEmpty(request.pathTemplate());
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.length() > 500 || !PATH_TEMPLATE.matcher(path).matches()) {
                throw new IllegalArgumentException(
                        "Enter a path template starting with /, such as /users/{id}, without a query or #");
            }
        } else {
            operation = StringUtils.trimToEmpty(request.operationName());
            Pattern expected = protocol == RuleProtocol.GRAPHQL ? GRAPHQL_NAME : GRPC_METHOD;
            if (!expected.matcher(operation).matches()) {
                throw new IllegalArgumentException(protocol == RuleProtocol.GRAPHQL
                        ? "Enter a GraphQL operation name, such as ListOrders"
                        : "Enter a gRPC method as package.Service/Method, such as acme.orders.Orders/ListOrders");
            }
        }
        String targetId = StringUtils.trimToNull(request.targetId());
        if (targetId != null && current.targets().stream().noneMatch(target -> target.id().equals(targetId))) {
            throw new IllegalArgumentException("That protected target doesn't exist");
        }
        String reason = StringUtils.trimToEmpty(request.reason());
        if (reason.length() < 10 || reason.length() > 300) {
            throw new IllegalArgumentException("Give a reason of 10 to 300 characters");
        }
        Instant expiresAt = request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("The expiry date must be in the future");
        }
        OperationRule rule = new OperationRule(id, effect, protocol, method, path, operation, targetId, reason,
                expiresAt, access.accountId(), createdAt);
        boolean duplicate = current.rules().stream()
                .anyMatch(other -> !other.id().equals(id) && other.sameMatch(rule));
        if (duplicate) {
            throw new IllegalArgumentException("A rule for this operation and target already exists; change that one");
        }
        return rule;
    }

    private EnvironmentProtectionView protectionView(StoredEnvironment environment, List<ProtectedTarget> targets) {
        Optional<ProtectedTarget> matched = TargetPatterns.matchUrl(targets, baseUrl(environment));
        return new EnvironmentProtectionView(environment.getId(), environment.getName(),
                environment.isProtectedEnvironment(), matched.map(ProtectedTarget::pattern).orElse(null),
                environment.isProtectedEnvironment() || matched.isPresent());
    }

    static String baseUrl(StoredEnvironment environment) {
        if (environment == null || environment.getDocumentJson() == null) {
            return null;
        }
        return EnvironmentMapper.baseUrl(CollectionDocuments.parseEnvironment(environment.getDocumentJson()));
    }

    private static SafetySettingsView view(SafetyPolicy policy, String notice) {
        return new SafetySettingsView(policy.orgId(), policy.mode(), policy.maxRequestsPerRun(),
                policy.maxRequestsPerMinute(), policy.maxWriteDataRows(), policy.requireSecondAdmin(),
                policy.selfApproval(), policy.revision(), policy.updatedAt(), policy.targets(), policy.rules(), notice);
    }

    private static int limit(Integer requested, int current, int min, int max, String label) {
        if (requested == null) {
            return current;
        }
        if (requested < min || requested > max) {
            throw new IllegalArgumentException(label + " must be " + min + " to " + max);
        }
        return requested;
    }

    private static String optionalText(String value, int max, String label) {
        String text = StringUtils.trimToNull(value);
        if (text != null && text.length() > max) {
            throw new IllegalArgumentException(label + " can be at most " + max + " characters");
        }
        return text;
    }

    private static String describeChanges(SafetyPolicy before, SafetyPolicy after) {
        List<String> changes = new ArrayList<>();
        if (before.mode() != after.mode()) {
            changes.add("mode " + before.mode().wire() + " → " + after.mode().wire());
        }
        if (before.maxRequestsPerRun() != after.maxRequestsPerRun()) {
            changes.add("requests per run " + before.maxRequestsPerRun() + " → " + after.maxRequestsPerRun());
        }
        if (before.maxRequestsPerMinute() != after.maxRequestsPerMinute()) {
            changes.add("requests per minute " + before.maxRequestsPerMinute() + " → " + after.maxRequestsPerMinute());
        }
        if (before.maxWriteDataRows() != after.maxWriteDataRows()) {
            changes.add("write data rows " + before.maxWriteDataRows() + " → " + after.maxWriteDataRows());
        }
        if (before.requireSecondAdmin() != after.requireSecondAdmin()) {
            changes.add("second admin for emergency access " + (after.requireSecondAdmin() ? "required" : "not required"));
        }
        if (before.selfApproval() != after.selfApproval()) {
            changes.add("self-approval " + before.selfApproval().wire() + " → " + after.selfApproval().wire());
        }
        return String.join("; ", changes);
    }

    private static String describe(OperationRule rule) {
        String match = rule.protocol() == RuleProtocol.REST
                ? rule.httpMethod() + " " + rule.pathTemplate()
                : rule.protocol().wire() + " " + rule.operationName();
        String effect = rule.effect() == RuleEffect.TREAT_AS_READ ? "treat as read" : "block";
        return effect + " " + match;
    }

    private void audit(Runnable action) {
        if (auditService == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Could not write audit event: {}", exception.getMessage());
        }
    }
}
