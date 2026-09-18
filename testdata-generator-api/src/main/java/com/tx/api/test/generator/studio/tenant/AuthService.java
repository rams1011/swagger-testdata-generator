package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AccountView;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.CreateTenantRequest;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.MemberView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.SubscriptionView;
import com.tx.api.test.generator.studio.dto.TenantView;
import com.tx.api.test.generator.studio.dto.UpdateMemberRequest;
import com.tx.api.test.generator.studio.dto.UpdateSubscriptionRequest;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final StudioProperties properties;
    private final ObjectProvider<TenantStore> tenantStores;

    public AuthService(StudioProperties properties, ObjectProvider<TenantStore> tenantStores) {
        this.properties = properties;
        this.tenantStores = tenantStores;
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

        Tenant tenant = newWorkspace(store, request == null ? null : request.workspace(), email, now);
        store.saveMembership(new Membership(tenant.getId(), account.getId(), "OWNER"));
        return issueSession(store, account, tenant, "OWNER");
    }

    public AuthSessionView login(LoginRequest request) {
        TenantStore store = requireStore();
        String email = normalizeEmail(request == null ? null : request.email());
        Account account = store.findAccountByEmail(email)
                .orElseThrow(() -> new UnauthenticatedException("Email or password is incorrect"));
        if (!PasswordHasher.matches(request == null ? null : request.password(), account.getPasswordHash())) {
            throw new UnauthenticatedException("Email or password is incorrect");
        }
        List<Tenant> workspaces = store.listTenantsForAccount(account.getId());
        if (workspaces.isEmpty()) {
            if (isSaasAdmin(account)) {
                Tenant platform = requirePlatformTenant(store);
                store.saveMembership(new Membership(platform.getId(), account.getId(), "OWNER"));
                return issueSession(store, account, platform, "OWNER");
            }
            throw new ForbiddenException("This account has no workspace");
        }
        Tenant tenant = selectTenant(workspaces, request == null ? null : request.tenantId());
        String role = store.findMembership(tenant.getId(), account.getId()).map(Membership::role).orElse("MEMBER");
        return issueSession(store, account, tenant, role);
    }

    public AuthSessionView session(String rawToken) {
        if (!properties.isSaasEnabled()) {
            return localSession();
        }
        if (StringUtils.isBlank(rawToken)) {
            return AuthSessionView.anonymous();
        }
        ResolvedSession resolved = resolve(rawToken).orElse(null);
        if (resolved == null) {
            return AuthSessionView.anonymous();
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
        String name = StringUtils.defaultIfBlank(request == null ? null : request.name(),
                displayName(null, resolved.account().getEmail()) + " workspace").trim();
        String slugSource = StringUtils.defaultIfBlank(request == null ? null : request.slug(), name);
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID().toString());
        tenant.setName(name);
        tenant.setSlug(uniqueSlug(store, slugSource));
        tenant.setCreatedAt(Instant.now());
        tenant.setReady(false);
        store.saveTenant(tenant);
        store.saveMembership(new Membership(tenant.getId(), resolved.account().getId(), "OWNER"));
        store.switchSessionTenant(hashToken(rawToken), tenant.getId());
        return toTenantView(tenant, "OWNER");
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
        String role = store.findMembership(tenant.getId(), account.getId()).map(Membership::role).orElse("MEMBER");
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
        return toTenantView(tenant, null);
    }

    public List<MemberView> listMembers() {
        requireManager();
        return requireStore().listMembers(TenantContext.tenantId()).stream()
                .map(AuthService::toMemberView)
                .toList();
    }

    public MemberView addMember(AddMemberRequest request) {
        requireManager();
        TenantStore store = requireStore();
        String tenantId = TenantContext.tenantId();
        String email = normalizeEmail(request == null ? null : request.email());
        String role = normalizeAssignableRole(request == null ? null : request.role());
        assertCanAssign(role);

        Account account = store.findAccountByEmail(email).orElse(null);
        if (account != null) {
            if (store.findMembership(tenantId, account.getId()).isPresent()) {
                throw new IllegalArgumentException("That person is already a member of this workspace");
            }
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
        store.saveMembership(new Membership(tenantId, account.getId(), role));
        return new MemberView(account.getId(), account.getEmail(), account.getDisplayName(), role);
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
        if (!properties.isSaasEnabled()) {
            return null;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return null;
        }
        Tenant platform = requirePlatformTenant(store);
        String email = properties.getSaas().normalizedAdminEmail();
        if (email == null) {
            log.warn("No testdata.saas.admin-email is set — nobody can enable customer workspaces");
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
            account.setDisplayName("SaaS admin");
            account.setCreatedAt(now);
            account.setPlatformRole("SAAS_ADMIN");
            store.saveAccount(account);
        } else if (!isSaasAdmin(account)) {
            store.updatePlatformRole(account.getId(), "SAAS_ADMIN");
            account.setPlatformRole("SAAS_ADMIN");
        }
        if (store.findMembership(platform.getId(), account.getId()).isEmpty()) {
            store.saveMembership(new Membership(platform.getId(), account.getId(), "OWNER"));
        }
        return email;
    }

    public List<SubscriptionView> listSubscriptions() {
        requireSaasAdmin();
        return requireStore().listSubscriptions().stream()
                .map(item -> new SubscriptionView(
                        item.tenantId(), item.slug(), item.name(),
                        item.ownerEmail(), item.ownerName(), item.ready(),
                        item.createdAt(), item.enabledAt()))
                .toList();
    }

    public SubscriptionView updateSubscription(String tenantId, UpdateSubscriptionRequest request) {
        requireSaasAdmin();
        if (request == null || request.ready() == null) {
            throw new IllegalArgumentException("ready is required");
        }
        TenantStore store = requireStore();
        Tenant tenant = store.findTenant(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Workspace not found"));
        if (isReservedTenant(tenant)) {
            throw new ForbiddenException("The platform workspace cannot be disabled");
        }
        boolean ready = request.ready();
        Instant enabledAt = ready ? Instant.now() : null;
        store.updateTenantReady(tenantId, ready, enabledAt);
        tenant.setReady(ready);
        tenant.setEnabledAt(enabledAt);
        String ownerEmail = null;
        String ownerName = null;
        for (SubscriptionView view : listSubscriptions()) {
            if (tenantId.equals(view.tenantId())) {
                return view;
            }
        }
        return new SubscriptionView(tenant.getId(), tenant.getSlug(), tenant.getName(),
                ownerEmail, ownerName, tenant.isReady(), tenant.getCreatedAt(), tenant.getEnabledAt());
    }

    public boolean isSaasAdmin(Account account) {
        return account != null && "SAAS_ADMIN".equalsIgnoreCase(account.getPlatformRole());
    }

    public boolean isTenantReady(Tenant tenant) {
        if (tenant == null) {
            return false;
        }
        if (!properties.isSaasEnabled() || TenantContext.LOCAL_TENANT_ID.equals(tenant.getId())) {
            return true;
        }
        return tenant.isReady();
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
                    return toTenantView(item, itemRole);
                })
                .toList();
        return new AuthSessionView(
                true,
                true,
                token,
                new AccountView(account.getId(), account.getEmail(), account.getDisplayName()),
                toTenantView(tenant, role),
                tenants,
                isSaasAdmin(account)
        );
    }

    private Tenant newWorkspace(TenantStore store, String workspaceName, String email, Instant now) {
        String name = StringUtils.defaultIfBlank(workspaceName, displayName(null, email) + " workspace").trim();
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID().toString());
        tenant.setName(name);
        tenant.setSlug(uniqueSlug(store, name));
        tenant.setCreatedAt(now);
        tenant.setReady(false);
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

    private void requireSaasAdmin() {
        if (!properties.isSaasEnabled()) {
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
        Tenant existing = store.findTenant(TenantContext.PLATFORM_TENANT_ID).orElse(null);
        if (existing != null) {
            if (!existing.isReady()) {
                store.updateTenantReady(existing.getId(), true, Instant.now());
                existing.setReady(true);
            }
            return existing;
        }
        Instant now = Instant.now();
        Tenant platform = new Tenant();
        platform.setId(TenantContext.PLATFORM_TENANT_ID);
        platform.setSlug(TenantContext.PLATFORM_SLUG);
        platform.setName("SaaS");
        platform.setCreatedAt(now);
        platform.setReady(true);
        platform.setEnabledAt(now);
        store.saveTenant(platform);
        return platform;
    }

    private static boolean isReservedTenant(Tenant tenant) {
        return TenantContext.LOCAL_TENANT_ID.equals(tenant.getId())
                || TenantContext.PLATFORM_TENANT_ID.equals(tenant.getId())
                || TenantContext.PLATFORM_SLUG.equals(tenant.getSlug());
    }

    private static TenantView toTenantView(Tenant tenant, String role) {
        return new TenantView(tenant.getId(), tenant.getSlug(), tenant.getName(), role, tenant.isReady());
    }

    private TenantStore requireStore() {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("SaaS workspaces need H2 or database storage");
        }
        return store;
    }

    private void requireManager() {
        if (!properties.isSaasEnabled()) {
            throw new ForbiddenException("Member management is only available in SaaS mode");
        }
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            throw new UnauthenticatedException("Sign in to continue");
        }
        if (!isManager(scope.role())) {
            throw new ForbiddenException("Only owners and admins can manage members");
        }
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
