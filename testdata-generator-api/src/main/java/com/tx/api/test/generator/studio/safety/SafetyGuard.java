package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.SafetyPolicyNote;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import com.tx.api.test.generator.studio.tenant.TenantStore;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The send-time production safety check (UI repo docs/PRODUCTION_SAFETY.md, "Decision order"). Every request
 * {@code TestRunService} sends goes through {@link #check}: a single run, each prerequisite, each data row, each
 * retry and each redirect hop, after variables, auth and headers are applied and the address is pinned.
 * Approvals and emergency access come later (PR 4), so for now every write to a protected target is stopped.
 */
@Service
public class SafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(SafetyGuard.class);

    public static final String WRITE_TO_PROTECTED_TARGET = "WRITE_TO_PROTECTED_TARGET";
    public static final String BLOCKED_OPERATION = "BLOCKED_OPERATION";
    public static final String LIMIT_REACHED = "LIMIT_REACHED";
    public static final String REDIRECT_INTO_PROTECTED = "REDIRECT_INTO_PROTECTED";

    private final StudioProperties properties;
    private final SafetyPolicyService policies;
    private final TenantStore tenantStore;
    private final StudioStorage studioStorage;
    private final StudioAuditService auditService;
    /** Request times per organization and protected target, for the per-minute limit. On this server only. */
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public SafetyGuard(StudioProperties properties, SafetyPolicyService policies, TenantStore tenantStore,
                       StudioStorage studioStorage, StudioAuditService auditService) {
        this.properties = properties;
        this.policies = policies;
        this.tenantStore = tenantStore;
        this.studioStorage = studioStorage;
        this.auditService = auditService;
    }

    /** One request about to be sent. {@code address} and {@code port} are where it will connect. */
    public record Attempt(String caseId, String method, String url, Map<String, String> headers, String body,
                          String contentType, String host, InetAddress address, int port, boolean redirectHop) {
    }

    /** {@code send} false: stop, nothing goes out. A note with {@code send} true is a Shadow-mode would-block. */
    public record Decision(boolean send, SafetyPolicyNote note) {
        static final Decision ALLOW = new Decision(true, null);
    }

    /** The run's context: the organization of the current workspace and the selected environment's protection. */
    public SafetyContext newContext(String environmentId, String environmentName) {
        if (!properties.isSafetyEnabled()) {
            return SafetyContext.none();
        }
        String tenantId = TenantContext.current() == null ? null : TenantContext.current().tenantId();
        String orgId = tenantId == null ? null
                : tenantStore.findTenant(tenantId).map(tenant -> StringUtils.trimToNull(tenant.getOrgId())).orElse(null);
        if (orgId == null) {
            return SafetyContext.none();
        }
        boolean protectedEnvironment = StringUtils.isNotBlank(environmentId)
                && studioStorage.findEnvironment(environmentId).map(StoredEnvironment::isProtectedEnvironment)
                .orElse(false);
        return new SafetyContext(orgId, environmentId, environmentName, protectedEnvironment);
    }

    public Decision check(SafetyContext context, Attempt attempt) {
        if (context == null || context.orgId() == null || !properties.isSafetyEnabled()) {
            return Decision.ALLOW;
        }
        SafetyPolicy policy = policies.policy(context.orgId());
        if (policy.mode() == SafetyMode.OFF) {
            return Decision.ALLOW;
        }
        Optional<ProtectedTarget> target = TargetPatterns.match(policy.targets(), attempt.host(), attempt.address(),
                attempt.port());
        if (target.isEmpty() && !context.environmentProtected()) {
            return Decision.ALLOW;
        }
        String label = target.map(ProtectedTarget::pattern)
                .orElseGet(() -> "the protected environment " + StringUtils.defaultIfBlank(context.environmentName(),
                        "selected"));
        RequestClassifier.Classification kind = RequestClassifier.classify(attempt.method(), attempt.url(),
                attempt.headers(), attempt.body(), attempt.contentType());
        OperationRule rule = matchingRule(policy, kind, attempt.url(), target.orElse(null));
        if (rule != null && rule.effect() == RuleEffect.BLOCK) {
            return decide(policy, context, attempt, BLOCKED_OPERATION,
                    "An admin blocked this operation in protected environments: " + rule.reason(), rule, label);
        }
        boolean write = kind.write() && (rule == null || rule.effect() != RuleEffect.TREAT_AS_READ);
        String targetKey = target.map(ProtectedTarget::id).orElse(context.environmentId());
        if (write) {
            Decision decision = attempt.redirectHop()
                    ? decide(policy, context, attempt, REDIRECT_INTO_PROTECTED, "The server redirected a write ("
                    + kind.reason() + ") to " + label + ", so Specforge stopped before sending it there.", null, label)
                    : decide(policy, context, attempt, WRITE_TO_PROTECTED_TARGET, "Production safety stopped this "
                    + "request: " + kind.reason() + ", and " + label + " only accepts reads. An organization admin "
                    + "can change the rules in Settings → Production safety.", null, label);
            if (decision.send()) {
                // Shadow mode sends it, so it counts toward this run's and this minute's requests.
                context.countProtectedRequest();
                withinMinuteLimit(context.orgId(), targetKey, Integer.MAX_VALUE);
            }
            return decision;
        }
        if (context.countProtectedRequest() > policy.maxRequestsPerRun()) {
            return decide(policy, context, attempt, LIMIT_REACHED, "This run reached the production limit of "
                    + policy.maxRequestsPerRun() + " requests, so the rest weren't sent.", null, label);
        }
        if (!withinMinuteLimit(context.orgId(), targetKey, policy.maxRequestsPerMinute())) {
            return decide(policy, context, attempt, LIMIT_REACHED, "More than " + policy.maxRequestsPerMinute()
                    + " requests a minute went to " + label + ". Wait a minute and run it again.", null, label);
        }
        return Decision.ALLOW;
    }

    private static OperationRule matchingRule(SafetyPolicy policy, RequestClassifier.Classification kind, String url,
                                              ProtectedTarget target) {
        Instant now = Instant.now();
        for (OperationRule rule : policy.rules()) {
            if (rule.expired(now) || rule.protocol() != RuleProtocol.REST) {
                continue;
            }
            if (rule.targetId() != null && (target == null || !rule.targetId().equals(target.id()))) {
                continue;
            }
            boolean methodMatches = rule.httpMethod().equals(kind.effectiveMethod())
                    || rule.httpMethod().equals(kind.method());
            if (methodMatches && PathTemplates.matches(rule.pathTemplate(), url)) {
                return rule;
            }
        }
        return null;
    }

    private Decision decide(SafetyPolicy policy, SafetyContext context, Attempt attempt, String code, String message,
                            OperationRule rule, String label) {
        boolean shadow = policy.mode() == SafetyMode.SHADOW;
        SafetyPolicyNote note = new SafetyPolicyNote(code, message, rule == null ? null : rule.id(), label,
                context.environmentId(), shadow);
        String request = attempt.method() + " " + urlForAudit(attempt.url());
        if (shadow) {
            context.markNoRetry(attempt.caseId());
            audit(context, AuditAction.WOULD_BLOCK, "Would have blocked " + request + " (" + code + ", " + label + ")");
            log.info("Production safety (shadow) would have blocked {} ({})", request, code);
            return new Decision(true, note);
        }
        audit(context, AuditAction.BLOCKED, "Blocked " + request + " (" + code + ", " + label + ")");
        log.info("Production safety blocked {} ({})", request, code);
        return new Decision(false, note);
    }

    private boolean withinMinuteLimit(String orgId, String targetKey, int perMinute) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(orgId + "|" + StringUtils.defaultString(targetKey),
                ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < now - 60_000L) {
                times.pollFirst();
            }
            if (times.size() >= perMinute) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private void audit(SafetyContext context, AuditAction action, String detail) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.recordSafetyDecision(context.environmentId(), action, detail);
        } catch (RuntimeException exception) {
            log.warn("Could not write audit event: {}", exception.getMessage());
        }
    }

    /** Scheme, host and path only: a query can carry tokens. */
    static String urlForAudit(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
