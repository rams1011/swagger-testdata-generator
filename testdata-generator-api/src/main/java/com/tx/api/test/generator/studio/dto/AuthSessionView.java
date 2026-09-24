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
        SubscriptionView subscription,
        String edition,
        boolean billing
) {
    public static AuthSessionView local(TenantView tenant) {
        return new AuthSessionView(false, true, null, null, tenant, List.of(tenant), false, null, null, "local", false);
    }

    public static AuthSessionView anonymous() {
        return anonymous("saas", true);
    }

    public static AuthSessionView anonymous(String edition, boolean billing) {
        return new AuthSessionView(true, false, null, null, null, List.of(), false, null, null, edition, billing);
    }
}
