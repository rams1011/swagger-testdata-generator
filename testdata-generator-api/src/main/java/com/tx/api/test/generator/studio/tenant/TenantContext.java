package com.tx.api.test.generator.studio.tenant;

import java.util.function.Supplier;

public final class TenantContext {

    public static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    public static final String LOCAL_SLUG = "local";
    public static final String PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000002";
    public static final String PLATFORM_SLUG = "saas-platform";

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static volatile boolean saasEnabled;

    private TenantContext() {
    }

    public static void setSaasEnabled(boolean enabled) {
        saasEnabled = enabled;
    }

    public static boolean saasEnabled() {
        return saasEnabled;
    }

    public static void set(Scope scope) {
        CURRENT.set(scope);
    }

    public static void runAs(String tenantId, Runnable action) {
        runAs(tenantId, null, "OWNER", action);
    }

    public static void runAs(String tenantId, String accountId, String role, Runnable action) {
        callAs(tenantId, accountId, role, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(String tenantId, String accountId, String role, Supplier<T> action) {
        Scope previous = CURRENT.get();
        try {
            CURRENT.set(new Scope(tenantId, accountId, null, role));
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static String tenantId() {
        Scope scope = CURRENT.get();
        if (scope != null && scope.tenantId() != null && !scope.tenantId().isBlank()) {
            return scope.tenantId();
        }
        if (!saasEnabled) {
            return LOCAL_TENANT_ID;
        }
        throw new UnauthenticatedException("Sign in to continue");
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Scope(String tenantId, String accountId, String email, String role) {
    }
}
