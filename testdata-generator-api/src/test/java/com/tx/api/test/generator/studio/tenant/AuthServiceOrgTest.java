package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.UpdateMemberRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceOrgTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void addOrgMemberDoesNotGrantWorkspaceAccess() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspaceA = tenant("ws-a", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspaceA));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findAccountByEmail("new@example.com")).thenReturn(Optional.empty());
        when(store.findOrgRole(eq("org-1"), eq("admin-1"))).thenReturn(Optional.of("OWNER"));
        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "admin-1", "admin@example.com", "OWNER"));

        authService.addOrgMember(new AddMemberRequest("new@example.com", "New User", "password123", "MEMBER"));

        verify(store).saveOrgMembership(eq("org-1"), any(String.class), eq("MEMBER"));
        verify(store, never()).saveMembership(any(Membership.class));
    }

    @Test
    void removeOrgMemberRequiresOrgAdmin() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");

        Tenant workspace = tenant("ws-a", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspace));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "member-1")).thenReturn(Optional.of("MEMBER"));

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "member-1", "member@example.com", "MEMBER"));

        assertThrows(ForbiddenException.class, () -> authService.removeOrgMember("other-1"));
        verify(store, never()).deleteOrgMembership(any(), any());
    }

    @Test
    void listOrgWorkspacesAllowsSignedInUserWithoutOrgMembership() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspace = tenant("ws-a", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspace));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "member-1")).thenReturn(Optional.empty());
        when(store.listTenantsForOrg("org-1")).thenReturn(List.of(workspace));
        when(store.findMembership("ws-a", "member-1")).thenReturn(Optional.empty());

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "member-1", "member@example.com", null));

        var views = authService.listOrgWorkspaces();
        assertEquals(1, views.size());
        assertEquals("ws-a", views.get(0).id());
        assertEquals(null, views.get(0).role());
    }

    @Test
    void workspaceMemberDoesNotSeeSiblingWorkspaces() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspaceA = tenant("ws-a", "org-1");
        Tenant workspaceB = tenant("ws-b", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspaceA));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "member-1")).thenReturn(Optional.of("MEMBER"));
        when(store.listTenantsForOrg("org-1")).thenReturn(List.of(workspaceA, workspaceB));
        when(store.findMembership("ws-a", "member-1")).thenReturn(Optional.of(new Membership("ws-a", "member-1", "MEMBER")));
        when(store.findMembership("ws-b", "member-1")).thenReturn(Optional.empty());

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "member-1", "member@example.com", "MEMBER"));

        var views = authService.listOrgWorkspaces();
        assertEquals(1, views.size());
        assertEquals("ws-a", views.get(0).id());
    }

    @Test
    void workspaceAdminCannotListTheOrganizationRoster() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspace = tenant("ws-a", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspace));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "admin-1")).thenReturn(Optional.of("MEMBER"));

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "admin-1", "admin@example.com", "ADMIN"));

        assertThrows(ForbiddenException.class, () -> authService.listOrgMembers());
    }

    @Test
    void orgAdminCanListOrganizationAccessRequestsAcrossWorkspaces() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspaceA = tenant("ws-a", "org-1");
        Tenant workspaceB = tenant("ws-b", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspaceA));
        when(store.findTenant("ws-b")).thenReturn(Optional.of(workspaceB));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "admin-1")).thenReturn(Optional.of("ADMIN"));
        when(store.listTenantsForOrg("org-1")).thenReturn(List.of(workspaceA, workspaceB));
        when(store.listPendingWorkspaceAccessRequests("ws-a")).thenReturn(List.of());
        when(store.listPendingWorkspaceAccessRequests("ws-b")).thenReturn(List.of(
                new TenantStore.WorkspaceAccessRequestRecord(
                        "req-1", "ws-b", "user-1", "user@example.com", "User",
                        "Please add me", Instant.now())));

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "admin-1", "admin@example.com", "MEMBER"));

        assertEquals(1, authService.listOrganizationAccessRequests().size());
    }

    @Test
    void orgAdminCanListWorkspaceMembersWithoutMembership() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspaceA = tenant("ws-a", "org-1");
        Tenant workspaceB = tenant("ws-b", "org-1");
        Organization organization = organization("org-1");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspaceA));
        when(store.findTenant("ws-b")).thenReturn(Optional.of(workspaceB));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "admin-1")).thenReturn(Optional.of("ADMIN"));
        when(store.findMembership("ws-b", "admin-1")).thenReturn(Optional.empty());
        when(store.listMembers("ws-b")).thenReturn(List.of());

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "admin-1", "admin@example.com", "MEMBER"));

        assertEquals(0, authService.listWorkspaceMembers("ws-b").size());
    }

    @Test
    void updateOrgMemberChangesRole() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("saas");

        Tenant workspace = tenant("ws-a", "org-1");
        Organization organization = organization("org-1");
        Account target = account("user-2", "user@example.com");

        TenantStore store = mock(TenantStore.class);
        when(store.findTenant("ws-a")).thenReturn(Optional.of(workspace));
        when(store.findOrganization("org-1")).thenReturn(Optional.of(organization));
        when(store.findOrgRole("org-1", "admin-1")).thenReturn(Optional.of("OWNER"));
        when(store.findOrgRole("org-1", "user-2")).thenReturn(Optional.of("MEMBER"));
        when(store.countOrgOwners("org-1")).thenReturn(1);
        when(store.findAccount("user-2")).thenReturn(Optional.of(target));

        AuthService authService = authService(properties, store);
        TenantContext.setSaasEnabled(true);
        TenantContext.set(new TenantContext.Scope("ws-a", "admin-1", "admin@example.com", "OWNER"));

        var view = authService.updateOrgMember("user-2", new UpdateMemberRequest("ADMIN"));
        assertEquals("ADMIN", view.role());
        verify(store).updateOrgMembershipRole("org-1", "user-2", "ADMIN");
    }

    private static AuthService authService(StudioProperties properties, TenantStore store) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantStore> tenantStores = mock(ObjectProvider.class);
        when(tenantStores.getIfAvailable()).thenReturn(store);
        EntitlementService entitlementService = mock(EntitlementService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EntitlementService> entitlements = mock(ObjectProvider.class);
        when(entitlements.getIfAvailable()).thenReturn(entitlementService);
        @SuppressWarnings("unchecked")
        ObjectProvider<LdapAuthenticator> ldap = mock(ObjectProvider.class);
        return new AuthService(properties, tenantStores, entitlements, ldap);
    }

    private static Tenant tenant(String id, String orgId) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setOrgId(orgId);
        tenant.setName(id);
        tenant.setSlug(id);
        tenant.setReady(true);
        tenant.setCreatedAt(Instant.now());
        return tenant;
    }

    private static Organization organization(String id) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName("Org");
        organization.setSlug("org");
        organization.setCreatedAt(Instant.now());
        return organization;
    }

    private static Account account(String id, String email) {
        Account account = new Account();
        account.setId(id);
        account.setEmail(email);
        account.setDisplayName("User");
        account.setCreatedAt(Instant.now());
        return account;
    }
}
