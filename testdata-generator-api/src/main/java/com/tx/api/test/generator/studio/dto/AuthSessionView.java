package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record AuthSessionView(
        boolean saas,
        boolean authenticated,
        String token,
        AccountView account,
        TenantView tenant,
        List<TenantView> tenants,
        boolean saasAdmin,
        OrganizationView organization,
        SubscriptionView subscription
) {
    public static AuthSessionView local(TenantView tenant) {
        return new AuthSessionView(false, true, null, null, tenant, List.of(tenant), false, null, null);
    }

    public static AuthSessionView anonymous() {
        return new AuthSessionView(true, false, null, null, null, List.of(), false, null, null);
    }
}
