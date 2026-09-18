package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record AuthSessionView(
        boolean saas,
        boolean authenticated,
        String token,
        AccountView account,
        TenantView tenant,
        List<TenantView> tenants,
        boolean saasAdmin
) {
    public static AuthSessionView local(TenantView tenant) {
        return new AuthSessionView(false, true, null, null, tenant, List.of(tenant), false);
    }

    public static AuthSessionView anonymous() {
        return new AuthSessionView(true, false, null, null, null, List.of(), false);
    }
}
