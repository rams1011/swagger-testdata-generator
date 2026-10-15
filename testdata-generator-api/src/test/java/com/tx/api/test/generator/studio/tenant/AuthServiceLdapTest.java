package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceLdapTest {

    @Test
    void addMemberRejectsNewAccountWhenLdapIsOn() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        properties.getAuth().getLdap().setUrl("ldaps://ldap.example.com");
        properties.getAuth().getLdap().setDomain("example.com");

        Tenant tenant = new Tenant();
        tenant.setId("tenant-1");
        tenant.setOrgId("org-1");
        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("tenant-1")).thenReturn(Optional.of(tenant));
        when(store.findAccountByEmail("new@example.com")).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        ObjectProvider<TenantStore> tenantStores = mock(ObjectProvider.class);
        when(tenantStores.getIfAvailable()).thenReturn(store);
        EntitlementService entitlementService = mock(EntitlementService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EntitlementService> entitlements = mock(ObjectProvider.class);
        when(entitlements.getIfAvailable()).thenReturn(entitlementService);
        @SuppressWarnings("unchecked")
        ObjectProvider<LdapAuthenticator> ldap = mock(ObjectProvider.class);

        AuthService authService = new AuthService(properties, tenantStores, entitlements, ldap);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("tenant-1", "admin-1", "admin@example.com", "OWNER"));
        try {
            assertThrows(IllegalArgumentException.class, () -> authService.addMember(
                    new AddMemberRequest("new@example.com", "New User", null, "MEMBER")));
        } finally {
            TenantContext.clear();
        }
    }
}
