package com.tx.api.test.generator.studio.tenant;

import java.util.function.Supplier;

/** The workspace a database connection works for, as PostgreSQL row-level security sees it. */
public final class DatabaseTenant {

    /** Every workspace. Only through {@link #acrossTenants}. */
    static final String ALL = "*";
    /** No workspace: row-level security hides every row. */
    static final String NONE = "";

    private static final ThreadLocal<Boolean> ACROSS = new ThreadLocal<>();

    private DatabaseTenant() {
    }

    /**
     * Runs a query that has to look across workspaces, such as counting an organization's collections. The query
     * must limit what it returns itself: row-level security doesn't.
     */
    public static <T> T acrossTenants(Supplier<T> query) {
        Boolean previous = ACROSS.get();
        ACROSS.set(Boolean.TRUE);
        try {
            return query.get();
        } finally {
            if (previous == null) {
                ACROSS.remove();
            }
        }
    }

    static String current() {
        if (ACROSS.get() != null) {
            return ALL;
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope != null && scope.tenantId() != null && !scope.tenantId().isBlank()) {
            return scope.tenantId();
        }
        return TenantContext.saasEnabled() ? NONE : TenantContext.LOCAL_TENANT_ID;
    }
}
