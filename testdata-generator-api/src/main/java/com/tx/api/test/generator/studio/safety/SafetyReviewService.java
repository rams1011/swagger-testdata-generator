package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.dto.SafetyReviewView;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The review on the settings page (docs/PRODUCTION_SAFETY.md §3.4, PR 6): what production safety stopped, or would
 * have stopped in Shadow mode, over the last days, grouped by operation, target and reason, with a suggestion to add
 * a Treat-as-read rule where a write was stopped and no such rule exists yet. Organization owners and admins only.
 */
@Service
public class SafetyReviewService {

    static final int MAX_ITEMS = 200;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern PATH_TEMPLATE = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/{}-]*");

    private final SafetyPolicyService policies;
    private final SafetyStore store;
    private final StudioStorage studioStorage;

    public SafetyReviewService(SafetyPolicyService policies, SafetyStore store, StudioStorage studioStorage) {
        this.policies = policies;
        this.store = store;
        this.studioStorage = studioStorage;
    }

    /** {@code days}: 1 to 90, default 14. */
    public SafetyReviewView review(Integer days) {
        int span = days == null ? 14 : days;
        if (span < 1 || span > 90) {
            throw new IllegalArgumentException("Review 1 to 90 days");
        }
        SafetyPolicyService.Access access = policies.requireAdmin();
        SafetyPolicy policy = policies.policy(access.orgId());
        Instant now = Instant.now();
        List<SafetyDecision.Group> groups = store.decisionGroups(access.orgId(), now.minus(Duration.ofDays(span)));
        long wouldBlock = 0;
        long blocked = 0;
        for (SafetyDecision.Group group : groups) {
            if (group.shadow()) {
                wouldBlock += group.count();
            } else {
                blocked += group.count();
            }
        }
        Map<String, Optional<StudioTestCase>> cases = new HashMap<>();
        List<SafetyReviewView.Item> items = new ArrayList<>();
        for (SafetyDecision.Group group : groups.stream().limit(MAX_ITEMS).toList()) {
            String template = template(group.pathTemplate());
            Optional<StudioTestCase> example = group.exampleCaseId() == null ? Optional.empty()
                    : cases.computeIfAbsent(group.exampleCaseId(), this::findCase);
            items.add(new SafetyReviewView.Item(group.method(), template, group.targetId(), group.targetLabel(),
                    group.code(), group.shadow(), group.count(), group.lastSeen(), group.exampleCaseId(),
                    example.map(StudioTestCase::getTestName).orElse(null),
                    example.map(StudioTestCase::getSpecId).orElse(null),
                    suggestTreatAsRead(policy, group, template, now)));
        }
        return new SafetyReviewView(span, policy.mode().wire(), wouldBlock, blocked, items,
                groups.size() > MAX_ITEMS);
    }

    private Optional<StudioTestCase> findCase(String caseId) {
        try {
            return studioStorage.findTestCase(caseId);
        } catch (RuntimeException otherWorkspace) {
            return Optional.empty();
        }
    }

    /** {@code /users/{{userId}}} as the rule template {@code /users/{userId}}; a path that can't be one stays null. */
    static String template(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        String template = VARIABLE.matcher(path.trim()).replaceAll(match -> "{" + match.group(1).replaceAll(
                "[^A-Za-z0-9_]", "_") + "}");
        int query = template.indexOf('?');
        if (query >= 0) {
            template = template.substring(0, query);
        }
        return template.length() <= 500 && PATH_TEMPLATE.matcher(template).matches() ? template : null;
    }

    /** A write stopped for being a write, on a REST path, with no Treat-as-read rule for it yet. */
    private static boolean suggestTreatAsRead(SafetyPolicy policy, SafetyDecision.Group group, String template,
                                              Instant now) {
        if (!SafetyGuard.WRITE_TO_PROTECTED_TARGET.equals(group.code()) || template == null) {
            return false;
        }
        return policy.rules().stream().noneMatch(rule -> rule.effect() == RuleEffect.TREAT_AS_READ
                && rule.protocol() == RuleProtocol.REST && !rule.expired(now)
                && group.method().equalsIgnoreCase(StringUtils.defaultString(rule.httpMethod()))
                && template.equals(rule.pathTemplate())
                && (rule.targetId() == null || rule.targetId().equals(group.targetId())));
    }
}
