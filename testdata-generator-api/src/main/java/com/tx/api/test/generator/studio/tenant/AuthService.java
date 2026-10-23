package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AccountView;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.CreateTenantRequest;
import com.tx.api.test.generator.studio.dto.CreateWorkspaceAccessRequest;
import com.tx.api.test.generator.studio.dto.GrantWorkspaceAccessRequest;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.MemberView;
import com.tx.api.test.generator.studio.dto.OrganizationView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.SubscriptionView;
import com.tx.api.test.generator.studio.dto.TenantView;
import com.tx.api.test.generator.studio.dto.UpdateMemberRequest;
import com.tx.api.test.generator.studio.dto.UpdateSubscriptionRequest;
import com.tx.api.test.generator.studio.dto.WorkspaceAccessRequestView;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AuthService {

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final StudioProperties properties;
    private final ObjectProvider<TenantStore> tenantStores;
    private final ObjectProvider<EntitlementService> entitlements;
    private final ObjectProvider<LdapAuthenticator> ldapAuthenticators;

    public AuthService(StudioProperties properties, ObjectProvider<TenantStore> tenantStores,
                       ObjectProvider<EntitlementService> entitlements,
                       ObjectProvider<LdapAuthenticator> ldapAuthenticators) {
        this.properties = properties;
        this.tenantStores = tenantStores;
        this.entitlements = entitlements;
        this.ldapAuthenticators = ldapAuthenticators;
    }

    public AuthSessionView localSession() {
        return AuthSessionView.local(new TenantView(
                TenantContext.LOCAL_TENANT_ID, TenantContext.LOCAL_SLUG, "Local", "OWNER", true));
    }

    public AuthSessionView register(RegisterRequest request) {
        TenantStore store = requireStore();
        String email = normalizeEmail(request == null ? null : request.email());
        String password = request == null ? null : request.password();
        requirePassword(password);
        if (store.findAccountByEmail(email).isPresent()) {
            throw new IllegalArgumentException("An account with that email already exists");
        }
        Instant now = Instant.now();
        Account account = new Account();
        account.setId(UUID.randomUUID().toString());
        account.setEmail(email);
        account.setPasswordHash(PasswordHasher.hash(password));
        account.setDisplayName(displayName(request == null ? null : request.name(), email));
        account.setCreatedAt(now);
        account.setPlatformRole("USER");
        store.saveAccount(account);

        ResolvedRegistration names = resolveRegistrationNames(request, email);
        Organization organization = newOrganization(store, names.organizationName(), email, now);
        provisionTrial(store, organization, now);
        Tenant tenant = newWorkspace(store, organization, names.workspaceName(), email, now);
        store.saveOrgMembership(organization.getId(), account.getId(), "OWNER");
        store.saveMembership(new Membership(tenant.getId(), account.getId(), "OWNER"));
        return issueSession(store, account, tenant, "OWNER");
    }

    public AuthSessionView login(LoginRequest request) {
        if (properties.isLdapAuth()) {
            return loginWithDirectory(request);
        }
        return loginWithLocalPassword(request);
    }

    private AuthSessionView loginWithLocalPassword(LoginRequest request) {
        TenantStore store = requireStore();
        String email = normalizeEmail(request == null ? null : request.email());
        Account account = store.findAccountByEmail(email)
                .orElseThrow(() -> new UnauthenticatedException("Email or password is incorrect"));
        if (!PasswordHasher.matches(request == null ? null : request.password(), account.getPasswordHash())) {
            throw new UnauthenticatedException("Email or password is incorrect");
        }
        return issueSessionForAccount(store, account, request == null ? null : request.tenantId());
    }

    private AuthSessionView loginWithDirectory(LoginRequest request) {
        String rawLogin = request == null ? null : request.email();
        String password = request == null ? null : request.password();
        if (properties.getAuth().isLocalAdminFallback()) {
            AuthSessionView adminSession = tryLocalAdminFallback(rawLogin, password, request);
            if (adminSession != null) {
                return adminSession;
            }
        }
        LdapAuthenticator ldap = ldapAuthenticators.getIfAvailable();
        if (ldap == null) {
            throw new IllegalStateException("LDAP sign-in is not configured");
        }
        LdapUserProfile profile = ldap.authenticate(rawLogin, password);
        TenantStore store = requireStore();
        String email = profile.studioEmail();
        Account account = store.findAccountByEmail(email).orElse(null);
        Instant now = Instant.now();
        if (account == null) {
            account = new Account();
            account.setId(UUID.randomUUID().toString());
            account.setEmail(email);
            account.setPasswordHash(LdapAccounts.EXTERNAL_PASSWORD_MARKER);
            account.setDisplayName(profile.displayName());
            account.setCreatedAt(now);
            account.setPlatformRole("USER");
            store.saveAccount(account);
        } else if (StringUtils.isNotBlank(profile.displayName())
                && !profile.displayName().equals(account.getDisplayName())) {
            account.setDisplayName(profile.displayName());
            store.saveAccount(account);
        }
        Tenant contextWorkspace = requireCompanyWorkspace(store);
        List<Tenant> workspaces = store.listTenantsForAccount(account.getId());
        if (workspaces.isEmpty()) {
            return issueSessionWithoutWorkspaceAccess(store, account, contextWorkspace);
        }
        return issueSessionForAccount(store, account, request == null ? null : request.tenantId());
    }

    private AuthSessionView tryLocalAdminFallback(String rawLogin, String password, LoginRequest request) {
        String adminEmail = properties.getSaas().normalizedAdminEmail();
        if (adminEmail == null || StringUtils.isBlank(rawLogin) || StringUtils.isBlank(password)) {
            return null;
        }
        try {
            String email = normalizeEmail(rawLogin);
            if (!adminEmail.equals(email)) {
                return null;
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        TenantStore store = requireStore();
        Account account = store.findAccountByEmail(adminEmail).orElse(null);
        if (account == null || LdapAccounts.isDirectoryManaged(account.getPasswordHash())) {
            return null;
        }
        if (!PasswordHasher.matches(password, account.getPasswordHash())) {
            return null;
        }
        return issueSessionForAccount(store, account, request == null ? null : request.tenantId());
    }

    private AuthSessionView issueSessionForAccount(TenantStore store, Account account, String requestedTenantId) {
        List<Tenant> workspaces = store.listTenantsForAccount(account.getId());
        if (workspaces.isEmpty()) {
            if (isSaasAdmin(account)) {
                Tenant platform = requirePlatformTenant(store);
                store.saveMembership(new Membership(platform.getId(), account.getId(), "OWNER"));
                if (store.findOrgRole(platform.getOrgId(), account.getId()).isEmpty()) {
                    store.saveOrgMembership(platform.getOrgId(), account.getId(), "OWNER");
                }
                return issueSession(store, account, platform, "OWNER");
            }
            Tenant context = contextWorkspaceForAccount(store, account);
            if (context != null) {
                return issueSessionWithoutWorkspaceAccess(store, account, context);
            }
            throw new ForbiddenException(
                    "This account has no workspace access. Ask an administrator or request access after sign-in.");
        }
        Tenant tenant = selectTenant(workspaces, requestedTenantId);
        String role = store.findMembership(tenant.getId(), account.getId())
                .map(Membership::role)
                .orElseThrow(() -> new ForbiddenException("You are not a member of that workspace"));
        return issueSession(store, account, tenant, role);
    }

    private Tenant contextWorkspaceForAccount(TenantStore store, Account account) {
        List<Tenant> memberWorkspaces = store.listTenantsForAccount(account.getId());
        if (!memberWorkspaces.isEmpty()) {
            return memberWorkspaces.get(0);
        }
        List<Organization> organizations = store.listOrganizationsForAccount(account.getId());
        for (Organization organization : organizations) {
            List<Tenant> orgWorkspaces = store.listTenantsForOrg(organization.getId());
            if (!orgWorkspaces.isEmpty()) {
                return orgWorkspaces.get(0);
            }
        }
        if (properties.isLdapAuth() && !properties.isBillingEnabled()) {
            return requireCompanyWorkspace(store);
        }
        return null;
    }

    private AuthSessionView issueSessionWithoutWorkspaceAccess(TenantStore store, Account account,
                                                               Tenant contextWorkspace) {
        String token = newToken();
        Instant now = Instant.now();
        StoredSession session = new StoredSession();
        session.setTokenHash(hashToken(token));
        session.setAccountId(account.getId());
        session.setTenantId(contextWorkspace.getId());
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(properties.getSaas().resolvedSessionDays(), ChronoUnit.DAYS));
        store.saveSession(session);
        return toView(token, account, contextWorkspace, null);
    }

    private String resolveDirectoryRole(LdapUserProfile profile) {
        StudioProperties.GroupRole mapping = properties.getAuth().getLdap().getGroupRole();
        if (!properties.getAuth().getLdap().hasGroupRoleMapping()) {
            return null;
        }
        List<String> groups = profile.groupDns();
        if (StringUtils.isNotBlank(mapping.getOwner())
                && LdapGroupMatcher.matchesAny(groups, mapping.getOwner())) {
            return "OWNER";
        }
        if (StringUtils.isNotBlank(mapping.getAdmin())
                && LdapGroupMatcher.matchesAny(groups, mapping.getAdmin())) {
            return "ADMIN";
        }
        if (StringUtils.isNotBlank(mapping.getMember())
                && LdapGroupMatcher.matchesAny(groups, mapping.getMember())) {
            return "MEMBER";
        }
        return null;
    }

    public AuthSessionView session(String rawToken) {
        if (!properties.isAuthEnabled()) {
            return localSession();
        }
        if (StringUtils.isBlank(rawToken)) {
            return AuthSessionView.anonymous(properties.editionName(), properties.isBillingEnabled());
        }
        ResolvedSession resolved = resolve(rawToken).orElse(null);
        if (resolved == null) {
            return AuthSessionView.anonymous(properties.editionName(), properties.isBillingEnabled());
        }
        return toView(null, resolved.account(), resolved.tenant(), resolved.role());
    }

    public AuthSessionView switchTenant(String rawToken, String tenantId) {
        TenantStore store = requireStore();
        ResolvedSession resolved = resolve(rawToken)
                .orElseThrow(() -> new UnauthenticatedException("Sign in to continue"));
        Membership membership = store.findMembership(tenantId, resolved.account().getId())
                .orElseThrow(() -> new ForbiddenException("You are not a member of that workspace"));
        Tenant tenant = store.findTenant(tenantId)
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        store.switchSessionTenant(hashToken(rawToken), tenantId);
        return toView(rawToken, resolved.account(), tenant, membership.role());
    }

    public TenantView createWorkspace(String rawToken, CreateTenantRequest request) {
        TenantStore store = requireStore();
        ResolvedSession resolved = resolve(rawToken)
                .orElseThrow(() -> new UnauthenticatedException("Sign in to continue"));
        Organization organization = requireOrganization(store, resolved.tenant());
        entitlements().requireWorkspace(organization.getId());
        String name = StringUtils.defaultIfBlank(request == null ? null : request.name(),
                displayName(null, resolved.account().getEmail()) + " workspace").trim();
        Instant now = Instant.now();
        Tenant tenant = newWorkspace(store, organization, name, resolved.account().getEmail(), now);
        store.saveMembership(new Membership(tenant.getId(), resolved.account().getId(), "OWNER"));
        if (store.findOrgRole(organization.getId(), resolved.account().getId()).isEmpty()) {
            store.saveOrgMembership(organization.getId(), resolved.account().getId(), "OWNER");
        }
        store.switchSessionTenant(hashToken(rawToken), tenant.getId());
        return toTenantView(store, tenant, "OWNER");
    }

    public void logout(String rawToken) {
        if (StringUtils.isBlank(rawToken) || tenantStores.getIfAvailable() == null) {
            return;
        }
        tenantStores.getObject().deleteSession(hashToken(rawToken));
    }

    public Optional<ResolvedSession> resolve(String rawToken) {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null || StringUtils.isBlank(rawToken)) {
            return Optional.empty();
        }
        store.deleteExpiredSessions(Instant.now());
        StoredSession session = store.findSession(hashToken(rawToken)).orElse(null);
        if (session == null || session.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        Account account = store.findAccount(session.getAccountId()).orElse(null);
        Tenant tenant = store.findTenant(session.getTenantId()).orElse(null);
        if (account == null || tenant == null) {
            return Optional.empty();
        }
        String role = store.findMembership(tenant.getId(), account.getId()).map(Membership::role).orElse(null);
        return Optional.of(new ResolvedSession(session, account, tenant, role));
    }

    public Optional<Membership> membership(String tenantId, String accountId) {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return Optional.empty();
        }
        return store.findMembership(tenantId, accountId);
    }

    public Optional<Tenant> findTenant(String tenantId) {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null || StringUtils.isBlank(tenantId)) {
            return Optional.empty();
        }
        return store.findTenant(tenantId);
    }

    public TenantView requireTenant(String tenantId) {
        TenantStore store = requireStore();
        Tenant tenant = store.findTenant(tenantId)
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        return toTenantView(store, tenant, null);
    }

    public List<MemberView> listMembers() {
        requireManager();
        return requireStore().listMembers(TenantContext.tenantId()).stream()
                .map(AuthService::toMemberView)
                .toList();
    }

    public List<MemberView> listWorkspaceMembers(String workspaceId) {
        return runWithWorkspaceManagement(workspaceId, this::listMembers);
    }

    public MemberView grantWorkspaceAccessForWorkspace(String workspaceId, GrantWorkspaceAccessRequest request) {
        return runWithWorkspaceManagement(workspaceId, () -> grantWorkspaceAccess(request));
    }

    public MemberView updateWorkspaceMember(String workspaceId, String accountId, UpdateMemberRequest request) {
        return runWithWorkspaceManagement(workspaceId, () -> updateMember(accountId, request));
    }

    public void removeWorkspaceMember(String workspaceId, String accountId) {
        runWithWorkspaceManagement(workspaceId, () -> {
            removeMember(accountId);
            return null;
        });
    }

    public List<WorkspaceAccessRequestView> listWorkspaceAccessRequestsForWorkspace(String workspaceId) {
        return runWithWorkspaceManagement(workspaceId, this::listWorkspaceAccessRequests);
    }

    public List<MemberView> listOrgMembers() {
        requireOrgManagerOrWorkspaceManager();
        Organization organization = requireCurrentOrganization(requireStore());
        return requireStore().listOrgMemberDetails(organization.getId()).stream()
                .map(AuthService::toMemberView)
                .toList();
    }

    public MemberView addOrgMember(AddMemberRequest request) {
        requireOrgAdmin();
        TenantStore store = requireStore();
        Organization organization = requireCurrentOrganization(store);
        Tenant tenant = store.findTenant(TenantContext.tenantId())
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        entitlements().requireWritable(tenant);
        String email = normalizeEmail(request == null ? null : request.email());
        String role = normalizeOrgRole(request == null ? null : request.role());
        assertCanAssignOrgRole(role);

        Account account = store.findAccountByEmail(email).orElse(null);
        if (account != null) {
            if (store.findOrgRole(organization.getId(), account.getId()).isPresent()) {
                throw new IllegalArgumentException("That person is already a member of this organization");
            }
        } else if (properties.isLdapAuth()) {
            throw new IllegalArgumentException(
                    "That person must sign in with the company directory before you can add them to this organization");
        } else {
            requirePassword(request == null ? null : request.password());
            Instant now = Instant.now();
            account = new Account();
            account.setId(UUID.randomUUID().toString());
            account.setEmail(email);
            account.setPasswordHash(PasswordHasher.hash(request.password()));
            account.setDisplayName(displayName(request.name(), email));
            account.setCreatedAt(now);
            store.saveAccount(account);
        }
        entitlements().requireSeat(organization.getId(), account.getId());
        store.saveOrgMembership(organization.getId(), account.getId(), role);
        return new MemberView(account.getId(), account.getEmail(), account.getDisplayName(), role);
    }

    public MemberView updateOrgMember(String accountId, UpdateMemberRequest request) {
        requireOrgAdmin();
        TenantStore store = requireStore();
        Organization organization = requireCurrentOrganization(store);
        String role = normalizeOrgRole(request == null ? null : request.role());
        String currentRole = store.findOrgRole(organization.getId(), accountId)
                .orElseThrow(() -> new NoSuchElementException("Organization member not found"));
        assertCanAssignOrgRole(role);
        assertCanManageOrgTarget(currentRole);
        if ("OWNER".equals(currentRole) && !"OWNER".equals(role) && store.countOrgOwners(organization.getId()) <= 1) {
            throw new ForbiddenException("The organization needs at least one owner");
        }
        store.updateOrgMembershipRole(organization.getId(), accountId, role);
        Account account = store.findAccount(accountId)
                .orElseThrow(() -> new NoSuchElementException("Organization member not found"));
        return new MemberView(account.getId(), account.getEmail(), account.getDisplayName(), role);
    }

    public void removeOrgMember(String accountId) {
        requireOrgAdmin();
        TenantStore store = requireStore();
        Organization organization = requireCurrentOrganization(store);
        String currentRole = store.findOrgRole(organization.getId(), accountId)
                .orElseThrow(() -> new NoSuchElementException("Organization member not found"));
        assertCanManageOrgTarget(currentRole);
        if ("OWNER".equals(currentRole) && store.countOrgOwners(organization.getId()) <= 1) {
            throw new ForbiddenException("The organization needs at least one owner");
        }
        store.deleteWorkspaceMembershipsForOrg(organization.getId(), accountId);
        store.deleteOrgMembership(organization.getId(), accountId);
    }

    public List<TenantView> listOrgWorkspaces() {
        requireAuthenticatedScope();
        TenantStore store = requireStore();
        Organization organization = organizationForContextTenant(store);
        String accountId = actorAccountId();
        return store.listTenantsForOrg(organization.getId()).stream()
                .map(workspace -> {
                    String role = store.findMembership(workspace.getId(), accountId)
                            .map(Membership::role)
                            .orElse(null);
                    return toTenantView(store, workspace, role);
                })
                .toList();
    }

    public MemberView grantWorkspaceAccess(GrantWorkspaceAccessRequest request) {
        requireManager();
        TenantStore store = requireStore();
        String tenantId = TenantContext.tenantId();
        Tenant tenant = store.findTenant(tenantId)
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        entitlements().requireWritable(tenant);
        String accountId = request == null ? null : StringUtils.trimToNull(request.accountId());
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        String role = normalizeAssignableRole(request == null ? null : request.role());
        assertCanAssign(role);

        Account account = store.findAccount(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (store.findMembership(tenantId, accountId).isPresent()) {
            throw new IllegalArgumentException("That person already has access to this workspace");
        }
        String orgId = tenant.getOrgId();
        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("This workspace is not attached to an organization");
        }
        if (store.findOrgRole(orgId, accountId).isEmpty()) {
            entitlements().requireSeat(orgId, accountId);
            store.saveOrgMembership(orgId, accountId, "MEMBER");
        }
        store.saveMembership(new Membership(tenantId, accountId, role));
        store.findPendingWorkspaceAccessRequest(tenantId, accountId).ifPresent(pending ->
                store.resolveWorkspaceAccessRequest(pending.requestId(), "GRANTED"));
        return new MemberView(account.getId(), account.getEmail(), account.getDisplayName(), role);
    }

    public WorkspaceAccessRequestView requestWorkspaceAccess(CreateWorkspaceAccessRequest request) {
        requireAuthenticatedScope();
        TenantStore store = requireStore();
        String accountId = actorAccountId();
        String workspaceId = request == null ? null : StringUtils.trimToNull(request.workspaceId());
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        Tenant workspace = store.findTenant(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));
        Organization contextOrg = organizationForContextTenant(store);
        if (StringUtils.isBlank(workspace.getOrgId())
                || !workspace.getOrgId().equals(contextOrg.getId())) {
            throw new ForbiddenException("That workspace is not in your organization");
        }
        if (store.findMembership(workspaceId, accountId).isPresent()) {
            throw new IllegalArgumentException("You already have access to that workspace");
        }
        if (store.findPendingWorkspaceAccessRequest(workspaceId, accountId).isPresent()) {
            throw new IllegalArgumentException("You already requested access to that workspace");
        }
        String message = StringUtils.trimToNull(request == null ? null : request.message());
        Instant now = Instant.now();
        String requestId = UUID.randomUUID().toString();
        store.saveWorkspaceAccessRequest(requestId, workspaceId, accountId, message, now);
        Account account = store.findAccount(accountId)
                .orElseThrow(() -> new UnauthenticatedException("Sign in to continue"));
        return new WorkspaceAccessRequestView(
                requestId,
                workspaceId,
                workspace.getName(),
                accountId,
                account.getEmail(),
                account.getDisplayName(),
                message,
                now);
    }

    public List<WorkspaceAccessRequestView> listWorkspaceAccessRequests() {
        requireManager();
        TenantStore store = requireStore();
        String tenantId = TenantContext.tenantId();
        return store.listPendingWorkspaceAccessRequests(tenantId).stream()
                .map(item -> toWorkspaceAccessRequestView(store, item))
                .toList();
    }

    public List<WorkspaceAccessRequestView> listOrganizationAccessRequests() {
        requireAuthenticatedScope();
        TenantStore store = requireStore();
        Organization organization = organizationForContextTenant(store);
        String accountId = actorAccountId();
        String orgRole = store.findOrgRole(organization.getId(), accountId).orElse(null);
        boolean orgManager = isManager(orgRole);
        return store.listTenantsForOrg(organization.getId()).stream()
                .filter(workspace -> {
                    if (orgManager) {
                        return true;
                    }
                    return store.findMembership(workspace.getId(), accountId)
                            .map(Membership::role)
                            .map(AuthService::isManager)
                            .orElse(false);
                })
                .flatMap(workspace -> store.listPendingWorkspaceAccessRequests(workspace.getId()).stream()
                        .map(item -> toWorkspaceAccessRequestView(store, item)))
                .sorted(Comparator.comparing(WorkspaceAccessRequestView::createdAt))
                .toList();
    }

    private static WorkspaceAccessRequestView toWorkspaceAccessRequestView(
            TenantStore store, TenantStore.WorkspaceAccessRequestRecord item) {
        Tenant workspace = store.findTenant(item.tenantId()).orElse(null);
        String workspaceName = workspace == null ? item.tenantId() : workspace.getName();
        return new WorkspaceAccessRequestView(
                item.requestId(),
                item.tenantId(),
                workspaceName,
                item.accountId(),
                item.email(),
                item.name(),
                item.message(),
                item.createdAt());
    }

    public MemberView updateMember(String accountId, UpdateMemberRequest request) {
        requireManager();
        TenantStore store = requireStore();
        String tenantId = TenantContext.tenantId();
        String role = normalizeAssignableRole(request == null ? null : request.role());
        Membership current = store.findMembership(tenantId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Member not found"));
        assertCanManageTarget(current.role());
        assertCanAssign(role);
        if ("OWNER".equals(current.role()) && !"OWNER".equals(role) && store.countOwners(tenantId) <= 1) {
            throw new ForbiddenException("The workspace needs at least one owner");
        }
        store.updateMembershipRole(tenantId, accountId, role);
        Account account = store.findAccount(accountId)
                .orElseThrow(() -> new NoSuchElementException("Member not found"));
        return new MemberView(account.getId(), account.getEmail(), account.getDisplayName(), role);
    }

    public void removeMember(String accountId) {
        requireManager();
        TenantStore store = requireStore();
        String tenantId = TenantContext.tenantId();
        Membership current = store.findMembership(tenantId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Member not found"));
        assertCanManageTarget(current.role());
        if ("OWNER".equals(current.role()) && store.countOwners(tenantId) <= 1) {
            throw new ForbiddenException("The workspace needs at least one owner");
        }
        store.deleteMembership(tenantId, accountId);
    }

    public String ensureSaasAdmin() {
        if (!properties.isAuthEnabled()) {
            return null;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return null;
        }
        Tenant home = properties.isBillingEnabled()
                ? requirePlatformTenant(store)
                : requireCompanyWorkspace(store);
        String email = properties.getSaas().normalizedAdminEmail();
        if (email == null) {
            log.warn("No testdata.saas.admin-email is set — nobody can administer Studio");
            return null;
        }
        Account account = store.findAccountByEmail(email).orElse(null);
        if (account == null) {
            String password = properties.getSaas().getAdminPassword();
            requirePassword(password);
            Instant now = Instant.now();
            account = new Account();
            account.setId(UUID.randomUUID().toString());
            account.setEmail(email);
            account.setPasswordHash(PasswordHasher.hash(password));
            account.setDisplayName(properties.isDatacenter() ? "Studio admin" : "SaaS admin");
            account.setCreatedAt(now);
            account.setPlatformRole("SAAS_ADMIN");
            store.saveAccount(account);
        } else if (!isSaasAdmin(account)) {
            store.updatePlatformRole(account.getId(), "SAAS_ADMIN");
            account.setPlatformRole("SAAS_ADMIN");
        }
        if (store.findMembership(home.getId(), account.getId()).isEmpty()) {
            store.saveMembership(new Membership(home.getId(), account.getId(), "OWNER"));
        }
        if (store.findOrgRole(home.getOrgId(), account.getId()).isEmpty()) {
            store.saveOrgMembership(home.getOrgId(), account.getId(), "OWNER");
        }
        return email;
    }

    public List<SubscriptionView> listSubscriptions() {
        requireSaasAdmin();
        TenantStore store = requireStore();
        return store.listOrgSubscriptions().stream()
                .map(item -> toSubscriptionView(store, item))
                .toList();
    }

    public SubscriptionView currentSubscription() {
        TenantStore store = requireStore();
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
        Tenant tenant = store.findTenant(scope.tenantId())
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        Organization organization = requireOrganization(store, tenant);
        Subscription subscription = store.findSubscriptionByOrg(organization.getId())
                .orElseThrow(() -> new NoSuchElementException("Subscription not found"));
        return toSubscriptionView(store, organization, subscription);
    }

    public SubscriptionView updateSubscription(String orgId, UpdateSubscriptionRequest request) {
        requireSaasAdmin();
        if (request == null || (request.ready() == null && request.plan() == null && request.status() == null)) {
            throw new IllegalArgumentException("plan, status, or ready is required");
        }
        TenantStore store = requireStore();
        Organization organization = store.findOrganization(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found"));
        if (PlanCatalog.isReservedOrg(organization.getId())
                || TenantContext.PLATFORM_SLUG.equals(organization.getSlug())) {
            throw new ForbiddenException("The platform organization cannot be disabled");
        }
        Subscription subscription = store.findSubscriptionByOrg(orgId)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found"));
        if (request.plan() != null) {
            PlanCatalog.Limits limits = PlanCatalog.limits(request.plan());
            PlanCatalog.applyLimits(subscription, limits);
            if (PlanCatalog.TRIAL.equals(limits.planCode()) && subscription.getTrialEndsAt() == null) {
                subscription.setTrialEndsAt(PlanCatalog.trialEndFrom(
                        subscription.getCreatedAt() == null ? Instant.now() : subscription.getCreatedAt()));
            }
            if (!PlanCatalog.TRIAL.equals(limits.planCode())) {
                subscription.setTrialEndsAt(null);
            }
        }
        if (request.status() != null) {
            subscription.setStatus(PlanCatalog.normalizeStatus(request.status()));
        } else if (request.ready() != null) {
            subscription.setStatus(request.ready() ? PlanCatalog.STATUS_ACTIVE : PlanCatalog.STATUS_SUSPENDED);
        }
        store.updateSubscription(subscription);
        boolean writable = PlanCatalog.isWritable(subscription, Instant.now());
        store.updateTenantsReadyForOrg(orgId, writable, writable ? Instant.now() : null);
        return toSubscriptionView(store, organization, subscription);
    }

    public boolean isSaasAdmin(Account account) {
        return account != null && "SAAS_ADMIN".equalsIgnoreCase(account.getPlatformRole());
    }

    public boolean isTenantReady(Tenant tenant) {
        return entitlements().isWritable(tenant);
    }

    public boolean hasWorkspaceMembership(String tenantId, String accountId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(accountId)) {
            return false;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return false;
        }
        return store.findMembership(tenantId, accountId).isPresent();
    }

    private AuthSessionView issueSession(TenantStore store, Account account, Tenant tenant, String role) {
        String token = newToken();
        Instant now = Instant.now();
        StoredSession session = new StoredSession();
        session.setTokenHash(hashToken(token));
        session.setAccountId(account.getId());
        session.setTenantId(tenant.getId());
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(properties.getSaas().resolvedSessionDays(), ChronoUnit.DAYS));
        store.saveSession(session);
        return toView(token, account, tenant, role);
    }

    private AuthSessionView toView(String token, Account account, Tenant tenant, String role) {
        TenantStore store = requireStore();
        List<TenantView> tenants = store.listTenantsForAccount(account.getId()).stream()
                .map(item -> {
                    String itemRole = store.findMembership(item.getId(), account.getId())
                            .map(Membership::role).orElse("MEMBER");
                    return toTenantView(store, item, itemRole);
                })
                .toList();
        Organization organization = tenant.getOrgId() == null
                ? null
                : store.findOrganization(tenant.getOrgId()).orElse(null);
        Subscription subscription = organization == null
                ? null
                : store.findSubscriptionByOrg(organization.getId()).orElse(null);
        return new AuthSessionView(
                properties.isAuthEnabled(),
                true,
                token,
                new AccountView(account.getId(), account.getEmail(), account.getDisplayName()),
                toTenantView(store, tenant, role),
                tenants,
                isSaasAdmin(account),
                organization == null ? null : new OrganizationView(
                        organization.getId(),
                        organization.getSlug(),
                        organization.getName(),
                        store.findOrgRole(organization.getId(), account.getId()).orElse(null)),
                !properties.isBillingEnabled() || subscription == null || organization == null
                        ? null
                        : toSubscriptionView(store, organization, subscription),
                properties.editionName(),
                properties.isBillingEnabled()
        );
    }

    private static final String DEFAULT_FIRST_WORKSPACE_NAME = "Default";

    private record ResolvedRegistration(String organizationName, String workspaceName) {
    }

    private ResolvedRegistration resolveRegistrationNames(RegisterRequest request, String email) {
        String organizationName = request == null ? null : StringUtils.trimToNull(request.organizationName());
        String workspaceName = request == null ? null : StringUtils.trimToNull(request.workspaceName());
        String legacyWorkspace = request == null ? null : StringUtils.trimToNull(request.legacyWorkspace());

        if (organizationName == null && legacyWorkspace != null) {
            organizationName = legacyWorkspace;
            workspaceName = null;
        }
        if (organizationName == null) {
            organizationName = displayName(request == null ? null : request.name(), email);
        }
        if (StringUtils.isBlank(organizationName)) {
            throw new IllegalArgumentException("Organization name is required");
        }
        if (workspaceName == null) {
            workspaceName = DEFAULT_FIRST_WORKSPACE_NAME;
        }
        return new ResolvedRegistration(organizationName.trim(), workspaceName.trim());
    }

    private Organization newOrganization(TenantStore store, String organizationName, String email, Instant now) {
        String name = StringUtils.defaultIfBlank(organizationName, displayName(null, email)).trim();
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID().toString());
        organization.setName(name);
        organization.setSlug(uniqueOrgSlug(store, name));
        organization.setCreatedAt(now);
        store.saveOrganization(organization);
        return organization;
    }

    private void provisionTrial(TenantStore store, Organization organization, Instant now) {
        PlanCatalog.Limits limits = PlanCatalog.limits(PlanCatalog.TRIAL);
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID().toString());
        subscription.setOrgId(organization.getId());
        subscription.setStatus(PlanCatalog.STATUS_TRIAL);
        subscription.setCreatedAt(now);
        subscription.setTrialEndsAt(PlanCatalog.trialEndFrom(now));
        PlanCatalog.applyLimits(subscription, limits);
        store.saveSubscription(subscription);
    }

    private void provisionEnterprise(TenantStore store, Organization organization, Instant now) {
        if (store.findSubscriptionByOrg(organization.getId()).isPresent()) {
            return;
        }
        PlanCatalog.Limits limits = PlanCatalog.limits(PlanCatalog.ENTERPRISE);
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID().toString());
        subscription.setOrgId(organization.getId());
        subscription.setStatus(PlanCatalog.STATUS_ACTIVE);
        subscription.setCreatedAt(now);
        PlanCatalog.applyLimits(subscription, limits);
        store.saveSubscription(subscription);
    }

    private Tenant newWorkspace(TenantStore store, Organization organization, String workspaceName,
                                String email, Instant now) {
        String name = StringUtils.defaultIfBlank(workspaceName, displayName(null, email) + " workspace").trim();
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID().toString());
        tenant.setOrgId(organization.getId());
        tenant.setName(name);
        tenant.setSlug(uniqueSlug(store, name));
        tenant.setCreatedAt(now);
        tenant.setReady(true);
        tenant.setEnabledAt(now);
        store.saveTenant(tenant);
        return tenant;
    }

    private String uniqueSlug(TenantStore store, String source) {
        String base = slugify(source);
        String candidate = base;
        int suffix = 2;
        while (store.findTenantBySlug(candidate).isPresent()) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String uniqueOrgSlug(TenantStore store, String source) {
        String base = slugify(source);
        if ("workspace".equals(base)) {
            base = "org";
        }
        String candidate = base;
        int suffix = 2;
        while (store.findOrganizationBySlug(candidate).isPresent()) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    static String slugify(String value) {
        String slug = StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank() || TenantContext.LOCAL_SLUG.equals(slug) || TenantContext.PLATFORM_SLUG.equals(slug)) {
            slug = "workspace";
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-$", "");
        }
        return slug;
    }

    private Tenant selectTenant(List<Tenant> workspaces, String requestedId) {
        if (StringUtils.isNotBlank(requestedId)) {
            return workspaces.stream()
                    .filter(tenant -> requestedId.equals(tenant.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ForbiddenException("You are not a member of that workspace"));
        }
        return workspaces.get(0);
    }

    private Tenant requireCompanyWorkspace(TenantStore store) {
        Instant now = Instant.now();
        String orgName = properties.getSaas().resolvedOrgName();
        Organization organization = store.findOrganizationBySlug(slugify(orgName)).orElse(null);
        if (organization == null) {
            organization = new Organization();
            organization.setId(UUID.randomUUID().toString());
            organization.setName(orgName);
            organization.setSlug(uniqueOrgSlug(store, orgName));
            organization.setCreatedAt(now);
            store.saveOrganization(organization);
        }
        List<Tenant> workspaces = store.listTenantsForOrg(organization.getId());
        if (!workspaces.isEmpty()) {
            return workspaces.get(0);
        }
        return newWorkspace(store, organization, properties.getSaas().resolvedWorkspaceName(), orgName, now);
    }

    private void requireSaasAdmin() {
        if (!properties.isBillingEnabled()) {
            throw new ForbiddenException("Subscription management is only available in SaaS mode");
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
        Account account = requireStore().findAccount(scope.accountId())
                .orElseThrow(() -> new UnauthenticatedException("Sign in to continue"));
        if (!isSaasAdmin(account)) {
            throw new ForbiddenException("Only a SaaS admin can manage subscriptions");
        }
    }

    private Tenant requirePlatformTenant(TenantStore store) {
        Instant now = Instant.now();
        Organization organization = store.findOrganization(TenantContext.PLATFORM_TENANT_ID).orElse(null);
        if (organization == null) {
            organization = new Organization();
            organization.setId(TenantContext.PLATFORM_TENANT_ID);
            organization.setSlug(TenantContext.PLATFORM_SLUG);
            organization.setName("SaaS");
            organization.setCreatedAt(now);
            store.saveOrganization(organization);
        }
        provisionEnterprise(store, organization, now);
        Tenant existing = store.findTenant(TenantContext.PLATFORM_TENANT_ID).orElse(null);
        if (existing != null) {
            if (!existing.isReady()) {
                store.updateTenantReady(existing.getId(), true, now);
                existing.setReady(true);
            }
            if (StringUtils.isBlank(existing.getOrgId())) {
                existing.setOrgId(organization.getId());
            }
            return existing;
        }
        Tenant platform = new Tenant();
        platform.setId(TenantContext.PLATFORM_TENANT_ID);
        platform.setOrgId(organization.getId());
        platform.setSlug(TenantContext.PLATFORM_SLUG);
        platform.setName("SaaS");
        platform.setCreatedAt(now);
        platform.setReady(true);
        platform.setEnabledAt(now);
        store.saveTenant(platform);
        return platform;
    }

    private Organization requireOrganization(TenantStore store, Tenant tenant) {
        if (tenant == null || StringUtils.isBlank(tenant.getOrgId())) {
            throw new ForbiddenException("This workspace is not attached to an organization");
        }
        return store.findOrganization(tenant.getOrgId())
                .orElseThrow(() -> new ForbiddenException("Organization not found"));
    }

    private TenantView toTenantView(TenantStore store, Tenant tenant, String role) {
        boolean writable = entitlements().isWritable(tenant);
        return new TenantView(tenant.getId(), tenant.getSlug(), tenant.getName(), role, writable);
    }

    private SubscriptionView toSubscriptionView(TenantStore store, TenantStore.OrgSubscriptionRecord item) {
        Organization organization = new Organization();
        organization.setId(item.orgId());
        organization.setSlug(item.slug());
        organization.setName(item.name());
        organization.setCreatedAt(item.createdAt());
        Subscription subscription = new Subscription();
        subscription.setOrgId(item.orgId());
        subscription.setPlanCode(item.planCode());
        subscription.setStatus(item.status());
        subscription.setSeatLimit(item.seatLimit());
        subscription.setWorkspaceLimit(item.workspaceLimit());
        subscription.setSpecLimit(item.specLimit());
        subscription.setRunLimitMonth(item.runLimitMonth());
        subscription.setTrialEndsAt(item.trialEndsAt());
        subscription.setCurrentPeriodEnd(item.currentPeriodEnd());
        subscription.setCreatedAt(item.createdAt());
        SubscriptionView view = toSubscriptionView(store, organization, subscription);
        return new SubscriptionView(
                view.orgId(), view.slug(), view.name(), item.ownerEmail(), item.ownerName(),
                view.plan(), view.status(), view.writable(), view.ready(), view.createdAt(),
                view.trialEndsAt(), view.currentPeriodEnd(), view.seatLimit(), view.seatsUsed(),
                view.workspaceLimit(), view.workspacesUsed(), view.specLimit(), view.specsUsed(),
                view.runLimitMonth(), view.runsUsed(), view.yearMonth());
    }

    private SubscriptionView toSubscriptionView(TenantStore store, Organization organization,
                                                Subscription subscription) {
        Instant now = Instant.now();
        boolean writable = PlanCatalog.isWritable(subscription, now);
        String yearMonth = entitlements().currentYearMonth();
        return new SubscriptionView(
                organization.getId(),
                organization.getSlug(),
                organization.getName(),
                null,
                null,
                subscription.getPlanCode(),
                subscription.getStatus(),
                writable,
                writable,
                organization.getCreatedAt() == null ? subscription.getCreatedAt() : organization.getCreatedAt(),
                PlanCatalog.trialEndsAt(subscription),
                subscription.getCurrentPeriodEnd(),
                subscription.getSeatLimit(),
                store.countOrgMembers(organization.getId()),
                subscription.getWorkspaceLimit(),
                store.countTenantsForOrg(organization.getId()),
                subscription.getSpecLimit(),
                store.countSpecsForOrg(organization.getId()),
                subscription.getRunLimitMonth(),
                store.runCount(organization.getId(), yearMonth),
                yearMonth
        );
    }

    private TenantStore requireStore() {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("SaaS workspaces need H2 or database storage");
        }
        return store;
    }

    private EntitlementService entitlements() {
        EntitlementService service = entitlements.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Subscription entitlements are not configured");
        }
        return service;
    }

    private void requireManager() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Member management is only available when login is enabled");
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
        if (!isManager(scope.role())) {
            throw new ForbiddenException("Only owners and admins can manage members");
        }
    }

    private void requireOrgMember() {
        requireAuthenticatedScope();
        Organization organization = requireCurrentOrganization(requireStore());
        if (requireStore().findOrgRole(organization.getId(), actorAccountId()).isEmpty()) {
            throw new ForbiddenException("You are not a member of this organization");
        }
    }

    private void requireOrgAdmin() {
        requireAuthenticatedScope();
        Organization organization = requireCurrentOrganization(requireStore());
        String orgRole = requireStore().findOrgRole(organization.getId(), actorAccountId())
                .orElseThrow(() -> new ForbiddenException("You are not a member of this organization"));
        if (!isManager(orgRole)) {
            throw new ForbiddenException("Only organization owners and admins can manage the organization roster");
        }
    }

    private void requireOrgManagerOrWorkspaceManager() {
        requireAuthenticatedScope();
        TenantStore store = requireStore();
        Organization organization = requireCurrentOrganization(store);
        String orgRole = store.findOrgRole(organization.getId(), actorAccountId()).orElse(null);
        if (isManager(orgRole)) {
            return;
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope != null && isManager(scope.role())) {
            return;
        }
        throw new ForbiddenException("Only organization or workspace owners and admins can list organization members");
    }

    private void requireAuthenticatedScope() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Member management is only available when login is enabled");
        }
        if (StringUtils.isBlank(actorAccountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
    }

    private <T> T runWithWorkspaceManagement(String workspaceId, Supplier<T> action) {
        requireAuthenticatedScope();
        TenantStore store = requireStore();
        Tenant workspace = requireWorkspaceInSessionOrganization(store, workspaceId);
        String accountId = actorAccountId();
        String managementRole = resolveWorkspaceManagementRole(store, workspace, accountId);
        if (managementRole == null) {
            throw new ForbiddenException("Only organization or workspace owners and admins can manage members");
        }
        entitlements().requireWritable(workspace);
        TenantContext.Scope scope = TenantContext.current();
        String email = scope == null ? null : scope.email();
        return TenantContext.callAs(workspaceId, accountId, email, managementRole, action);
    }

    private Tenant requireWorkspaceInSessionOrganization(TenantStore store, String workspaceId) {
        Organization contextOrg = organizationForContextTenant(store);
        Tenant workspace = store.findTenant(workspaceId)
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        if (StringUtils.isBlank(workspace.getOrgId()) || !workspace.getOrgId().equals(contextOrg.getId())) {
            throw new ForbiddenException("That workspace is not in your organization");
        }
        return workspace;
    }

    private String resolveWorkspaceManagementRole(TenantStore store, Tenant workspace, String accountId) {
        Organization organization = requireOrganization(store, workspace);
        String orgRole = store.findOrgRole(organization.getId(), accountId).orElse(null);
        String workspaceRole = store.findMembership(workspace.getId(), accountId).map(Membership::role).orElse(null);
        if ("OWNER".equals(orgRole)) {
            return "OWNER";
        }
        if ("ADMIN".equals(orgRole)) {
            if ("OWNER".equals(workspaceRole) || "ADMIN".equals(workspaceRole)) {
                return workspaceRole;
            }
            return "ADMIN";
        }
        if (isManager(workspaceRole)) {
            return workspaceRole;
        }
        return null;
    }

    private static Organization requireCurrentOrganization(TenantStore store) {
        return organizationForContextTenant(store);
    }

    private static Organization organizationForContextTenant(TenantStore store) {
        String tenantId = TenantContext.tenantId();
        Tenant tenant = store.findTenant(tenantId)
                .orElseThrow(() -> new ForbiddenException("Workspace not found"));
        if (StringUtils.isBlank(tenant.getOrgId())) {
            throw new ForbiddenException("This workspace is not attached to an organization");
        }
        return store.findOrganization(tenant.getOrgId())
                .orElseThrow(() -> new ForbiddenException("Organization not found"));
    }

    private static String actorAccountId() {
        TenantContext.Scope scope = TenantContext.current();
        return scope == null ? null : scope.accountId();
    }

    private void assertCanAssign(String role) {
        if ("OWNER".equals(role) && !"OWNER".equals(actorRole())) {
            throw new ForbiddenException("Only an owner can add or promote owners");
        }
    }

    private void assertCanManageTarget(String targetRole) {
        if ("OWNER".equals(targetRole) && !"OWNER".equals(actorRole())) {
            throw new ForbiddenException("Only an owner can change another owner");
        }
    }

    private static String actorRole() {
        TenantContext.Scope scope = TenantContext.current();
        return scope == null ? null : scope.role();
    }

    private static boolean isManager(String role) {
        return "OWNER".equals(role) || "ADMIN".equals(role);
    }

    private static String normalizeAssignableRole(String role) {
        String value = StringUtils.defaultIfBlank(role, "MEMBER").trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(value)) {
            throw new IllegalArgumentException("Role must be OWNER, ADMIN, or MEMBER");
        }
        return value;
    }

    private static String normalizeOrgRole(String role) {
        return normalizeAssignableRole(role);
    }

    private void assertCanAssignOrgRole(String role) {
        if ("OWNER".equals(role) && !"OWNER".equals(orgActorRole())) {
            throw new ForbiddenException("Only an organization owner can add or promote owners");
        }
    }

    private void assertCanManageOrgTarget(String targetRole) {
        if ("OWNER".equals(targetRole) && !"OWNER".equals(orgActorRole())) {
            throw new ForbiddenException("Only an organization owner can change another owner");
        }
    }

    private String orgActorRole() {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return null;
        }
        Organization organization = requireCurrentOrganization(store);
        return store.findOrgRole(organization.getId(), actorAccountId()).orElse(null);
    }

    private static MemberView toMemberView(TenantStore.MemberRecord member) {
        return new MemberView(member.accountId(), member.email(), member.name(), member.role());
    }

    private static String normalizeEmail(String email) {
        String trimmed = StringUtils.trimToNull(email);
        if (trimmed == null || !trimmed.contains("@")) {
            throw new IllegalArgumentException("A valid email is required");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static void requirePassword(String password) {
        if (StringUtils.isBlank(password) || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    private static String displayName(String name, String email) {
        if (StringUtils.isNotBlank(name)) {
            return name.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /** ADR-001: org roster implies access to every workspace in the org (default workspace role). */
    private static void provisionOrgMemberOnAllWorkspaces(TenantStore store, String orgId,
                                                         String accountId, String orgRole) {
        String defaultRole = defaultWorkspaceRoleForOrgRole(orgRole);
        for (Tenant workspace : store.listTenantsForOrg(orgId)) {
            if (store.findMembership(workspace.getId(), accountId).isEmpty()) {
                store.saveMembership(new Membership(workspace.getId(), accountId, defaultRole));
            }
        }
    }

    private static void provisionAllOrgMembersOnWorkspace(TenantStore store, String orgId,
                                                        String workspaceId, String skipAccountId) {
        for (TenantStore.OrgMemberRecord member : store.listOrgMembers(orgId)) {
            if (member.accountId().equals(skipAccountId)) {
                continue;
            }
            if (store.findMembership(workspaceId, member.accountId()).isEmpty()) {
                store.saveMembership(new Membership(workspaceId, member.accountId(),
                        defaultWorkspaceRoleForOrgRole(member.role())));
            }
        }
    }

    private static String defaultWorkspaceRoleForOrgRole(String orgRole) {
        if ("OWNER".equals(orgRole) || "ADMIN".equals(orgRole)) {
            return "ADMIN";
        }
        return "MEMBER";
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash session token", exception);
        }
    }

    public record ResolvedSession(StoredSession session, Account account, Tenant tenant, String role) {
    }
}
