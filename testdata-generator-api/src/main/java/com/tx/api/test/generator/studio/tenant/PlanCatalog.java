package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

public final class PlanCatalog {

    public static final String TRIAL = "trial";
    public static final String TEAM = "team";
    public static final String ENTERPRISE = "enterprise";

    public static final String STATUS_TRIAL = "trial";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAST_DUE = "past_due";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_CANCELED = "canceled";

    private static final Set<String> PLANS = Set.of(TRIAL, TEAM, ENTERPRISE);
    private static final Set<String> STATUSES = Set.of(
            STATUS_TRIAL, STATUS_ACTIVE, STATUS_PAST_DUE, STATUS_SUSPENDED, STATUS_CANCELED);

    private PlanCatalog() {
    }

    public static Limits limits(String plan) {
        return switch (normalizePlan(plan)) {
            case TEAM -> new Limits(TEAM, 10, 5, 50, 5000, null);
            case ENTERPRISE -> new Limits(ENTERPRISE, 1000, 100, 10_000, 1_000_000, null);
            default -> new Limits(TRIAL, 2, 1, 3, 100, 14);
        };
    }

    public static String normalizePlan(String plan) {
        String value = plan == null || plan.isBlank() ? TRIAL : plan.trim().toLowerCase(Locale.ROOT);
        if (!PLANS.contains(value)) {
            throw new IllegalArgumentException("Plan must be trial, team, or enterprise");
        }
        return value;
    }

    public static String normalizeStatus(String status) {
        String value = status == null || status.isBlank()
                ? STATUS_TRIAL
                : status.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new IllegalArgumentException("Status must be trial, active, past_due, suspended, or canceled");
        }
        return value;
    }

    public static boolean isReservedOrg(String orgId) {
        return TenantContext.LOCAL_TENANT_ID.equals(orgId)
                || TenantContext.PLATFORM_TENANT_ID.equals(orgId);
    }

    public static boolean isWritable(Subscription subscription, Instant now) {
        if (subscription == null) {
            return false;
        }
        if (isReservedOrg(subscription.getOrgId())) {
            return true;
        }
        String status = subscription.getStatus();
        if (STATUS_ACTIVE.equals(status)) {
            return true;
        }
        if (STATUS_TRIAL.equals(status)) {
            Instant ends = trialEndsAt(subscription);
            return ends == null || !now.isAfter(ends);
        }
        return false;
    }

    public static Instant trialEndsAt(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        if (subscription.getTrialEndsAt() != null) {
            return subscription.getTrialEndsAt();
        }
        if (STATUS_TRIAL.equals(subscription.getStatus()) && subscription.getCreatedAt() != null) {
            return subscription.getCreatedAt().plus(14, ChronoUnit.DAYS);
        }
        return null;
    }

    public static Instant trialEndFrom(Instant createdAt) {
        return createdAt.plus(14, ChronoUnit.DAYS);
    }

    public static void applyLimits(Subscription subscription, Limits limits) {
        subscription.setPlanCode(limits.planCode());
        subscription.setSeatLimit(limits.seats());
        subscription.setWorkspaceLimit(limits.workspaces());
        subscription.setSpecLimit(limits.specs());
        subscription.setRunLimitMonth(limits.runsPerMonth());
    }

    public record Limits(String planCode, int seats, int workspaces, int specs, int runsPerMonth, Integer trialDays) {
    }
}
