package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.studio.collection.EnvironmentScope;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.ApprovalView;
import com.tx.api.test.generator.studio.dto.EvaluateSafetyRequest;
import com.tx.api.test.generator.studio.dto.PlanPreflightView;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.SafetyEvaluateView;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.safety.ApprovalService;
import com.tx.api.test.generator.studio.safety.OperationRule;
import com.tx.api.test.generator.studio.safety.ProtectedTarget;
import com.tx.api.test.generator.studio.safety.RequestClassifier;
import com.tx.api.test.generator.studio.safety.RuleEffect;
import com.tx.api.test.generator.studio.safety.SafetyGuard;
import com.tx.api.test.generator.studio.safety.SafetyMode;
import com.tx.api.test.generator.studio.safety.SafetyPolicy;
import com.tx.api.test.generator.studio.safety.SafetyPolicyService;
import com.tx.api.test.generator.studio.safety.TargetPatterns;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * What production safety would do, without sending anything: for one stored case ({@link #evaluate}) and for a plan
 * ({@link #preflight}). It classifies the stored case as saved (variables unresolved, shared headers not applied),
 * so the send-time check in SafetyGuard stays the one that decides.
 */
@Service
public class SafetyPreviewService {

    private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();

    private final StudioProperties properties;
    private final SafetyPolicyService policies;
    private final ApprovalService approvals;
    private final StudioStorage studioStorage;

    public SafetyPreviewService(StudioProperties properties, SafetyPolicyService policies, ApprovalService approvals,
                                StudioStorage studioStorage) {
        this.properties = properties;
        this.policies = policies;
        this.approvals = approvals;
        this.studioStorage = studioStorage;
    }

    public SafetyEvaluateView evaluate(EvaluateSafetyRequest request) {
        if (request == null || StringUtils.isBlank(request.caseId())) {
            throw new IllegalArgumentException("caseId is required");
        }
        StudioTestCase testCase = studioStorage.findTestCase(request.caseId())
                .orElseThrow(() -> new NoSuchElementException("Case not found"));
        StoredEnvironment environment = StringUtils.isBlank(request.environmentId()) ? null
                : studioStorage.findEnvironment(request.environmentId())
                .orElseThrow(() -> new NoSuchElementException("Environment not found"));
        return evaluate(testCase, environment);
    }

    SafetyEvaluateView evaluate(StudioTestCase testCase, StoredEnvironment environment) {
        Optional<String> orgId = policies.currentOrgId();
        String confirmation = environment == null ? null : environment.getName();
        if (orgId.isEmpty()) {
            return new SafetyEvaluateView(false, "off", false, null, false, null, "off", null, null, null, null, null);
        }
        SafetyPolicy policy = policies.policy(orgId.get());
        String environmentUrl = environment == null ? null : baseUrlOf(environment);
        String url = requestUrl(testCase, environmentUrl);
        Optional<ProtectedTarget> target = TargetPatterns.matchUrl(policy.targets(), url);
        if (target.isEmpty() && environmentUrl != null) {
            target = TargetPatterns.matchUrl(policy.targets(), environmentUrl);
        }
        boolean protectedEnvironment = (environment != null && environment.isProtectedEnvironment()) || target.isPresent();
        String label = target.map(ProtectedTarget::pattern).orElse(environment == null ? null
                : "the protected environment " + environment.getName());
        RequestClassifier.Classification kind = RequestClassifier.classify(testCase.getMethod(), url,
                testCase.getHeaders(), body(testCase), testCase.getContentType());
        OperationRule rule = SafetyGuard.matchingRule(policy, kind, url, target.orElse(null));
        boolean write = kind.write() && (rule == null || rule.effect() != RuleEffect.TREAT_AS_READ);
        String mode = policy.mode().wire();
        ApprovalView approval = environment == null || StringUtils.isBlank(testCase.getId()) ? null
                : approvals.forCase(testCase.getId(), environment.getId()).stream().findFirst().orElse(null);
        if (policy.mode() == SafetyMode.OFF) {
            return view(mode, false, label, kind, write, "off", null, null, null, approval, confirmation);
        }
        if (!protectedEnvironment) {
            return view(mode, false, label, kind, write, "unprotected", null, null, null, approval, confirmation);
        }
        if (rule != null && rule.effect() == RuleEffect.BLOCK) {
            return view(mode, true, label, kind, write, policy.mode() == SafetyMode.SHADOW ? "shadow" : "blocked",
                    SafetyGuard.BLOCKED_OPERATION, "An admin blocked this operation in protected environments: "
                            + rule.reason(), null, approval, confirmation);
        }
        if (!write) {
            return view(mode, true, label, kind, false, "read", null, null, null, approval, confirmation);
        }
        if (policy.mode() == SafetyMode.SHADOW) {
            return view(mode, true, label, kind, true, "shadow", SafetyGuard.WRITE_TO_PROTECTED_TARGET,
                    "Shadow mode: this write is sent and recorded as would-be blocked.", null, approval, confirmation);
        }
        boolean draft = CaseLifecycle.isDraft(testCase);
        ApprovalService.Permission permission = approvals.permission(orgId.get(),
                environment == null ? null : environment.getId(), environment == null ? label : environment.getName(),
                testCase.getId(), testCase.getRevision(), draft, false, policy.requireSecondAdmin());
        if (permission.allowed()) {
            return view(mode, true, label, kind, true, "approved", null, null, permission.via(), approval, confirmation);
        }
        String state = draft ? "draft"
                : approval != null && "requested".equals(approval.status()) ? "waiting" : "blocked";
        return view(mode, true, label, kind, true, state, permission.code(), permission.message(), null, approval,
                confirmation);
    }

    /** The plan's cases as Run plan (or Run selected) would take them, each with what production safety would do. */
    public PlanPreflightView preflight(RunPlanRequest request) {
        if (request == null || StringUtils.isBlank(request.specId()) || StringUtils.isBlank(request.planId())) {
            throw new IllegalArgumentException("specId and planId are required");
        }
        StoredEnvironment environment = StringUtils.isBlank(request.environmentId()) ? null
                : studioStorage.findEnvironment(request.environmentId())
                .orElseThrow(() -> new NoSuchElementException("Environment not found"));
        List<StudioTestCase> ordered = CaseVariables.orderForChain(
                studioStorage.listTestCases(request.specId(), request.planId(), null));
        List<StudioTestCase> cases = chosen(ordered, request.caseIds());
        String environmentName = environment == null ? null : environment.getName();
        List<PlanPreflightView.Entry> confirm = new ArrayList<>();
        List<PlanPreflightView.Entry> blocked = new ArrayList<>();
        List<PlanPreflightView.Entry> skipped = new ArrayList<>();
        LinkedHashMap<String, String> blockedProduces = new LinkedHashMap<>();
        int willRun = 0;
        boolean enabled = false;
        String mode = "off";
        boolean protectedEnvironment = false;
        for (StudioTestCase testCase : cases) {
            if (CaseLifecycle.isDraft(testCase) || "N".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getEnabled()))
                    || !EnvironmentScope.visible(testCase.getEnvironments(), environmentName)) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(testCase.getTestName(), testCase.getOperationKey());
            String needs = CaseVariables.placeholderNames(testCase).stream()
                    .filter(blockedProduces::containsKey).findFirst().orElse(null);
            if (needs != null) {
                skipped.add(new PlanPreflightView.Entry(testCase.getId(), name, null,
                        "Needs blocked case '" + blockedProduces.get(needs) + "' for {{" + needs + "}}"));
                CaseVariables.producedNames(testCase.getExtracts()).forEach(produced -> blockedProduces.put(produced, name));
                continue;
            }
            SafetyEvaluateView view = evaluate(testCase, environment);
            enabled = view.enabled();
            mode = view.mode();
            protectedEnvironment = protectedEnvironment || view.protectedEnvironment();
            switch (view.state()) {
                case "blocked", "waiting", "draft" -> {
                    blocked.add(new PlanPreflightView.Entry(testCase.getId(), name, view.code(), view.message()));
                    CaseVariables.producedNames(testCase.getExtracts()).forEach(produced -> blockedProduces.put(produced, name));
                }
                case "approved" -> {
                    confirm.add(new PlanPreflightView.Entry(testCase.getId(), name, null, view.via()));
                    willRun++;
                }
                default -> willRun++;
            }
        }
        if (!enabled) {
            enabled = policies.currentOrgId().isPresent();
            mode = enabled ? policies.policy(policies.currentOrgId().get()).mode().wire() : "off";
        }
        return new PlanPreflightView(enabled, mode, protectedEnvironment, environmentName, willRun, confirm, blocked,
                skipped);
    }

    private static List<StudioTestCase> chosen(List<StudioTestCase> ordered, List<String> caseIds) {
        if (caseIds == null) {
            return ordered;
        }
        Set<String> keep = new HashSet<>();
        caseIds.stream().filter(StringUtils::isNotBlank).forEach(keep::add);
        for (StudioTestCase testCase : ordered.stream().filter(candidate -> keep.contains(candidate.getId())).toList()) {
            CaseVariables.prerequisites(ordered, testCase).forEach(prior -> keep.add(prior.getId()));
        }
        return ordered.stream().filter(testCase -> keep.contains(testCase.getId())).toList();
    }

    private static SafetyEvaluateView view(String mode, boolean protectedEnvironment, String label,
                                           RequestClassifier.Classification kind, boolean write, String state,
                                           String code, String message, String via, ApprovalView approval,
                                           String confirmation) {
        return new SafetyEvaluateView(true, mode, protectedEnvironment, label, write, kind.reason(), state, code,
                message, via, approval, confirmation);
    }

    /** The case's URL with {{baseUrl}} filled from the environment; other variables stay as they are. */
    private static String requestUrl(StudioTestCase testCase, String environmentUrl) {
        String base = StringUtils.defaultString(testCase.getBaseUrl());
        if (environmentUrl != null) {
            base = base.replace("{{baseUrl}}", environmentUrl).replace("{{env:baseUrl}}", environmentUrl);
        }
        try {
            return TestRunService.buildUrl(base, testCase.getEndpoint(), testCase.getQueryParams());
        } catch (RuntimeException unreadable) {
            return base + StringUtils.defaultString(testCase.getEndpoint());
        }
    }

    private static String baseUrlOf(StoredEnvironment environment) {
        try {
            return com.tx.api.test.generator.studio.collection.EnvironmentMapper.baseUrl(
                    com.tx.api.test.generator.studio.collection.CollectionDocuments.parseEnvironment(
                            environment.getDocumentJson()));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String body(StudioTestCase testCase) {
        Object body = testCase.getRequestBody();
        if (body == null) {
            return null;
        }
        return body instanceof String text ? text : JSON.toJson(body);
    }
}
