package com.tx.api.test.generator.studio.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "testdata.storage.engine", havingValue = "jdbc")
public class JdbcTenantStore implements TenantStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveTenant(Tenant tenant) {
        jdbcTemplate.update("""
                        INSERT INTO td_tenant (tenant_id, slug, name, created_at, ready, enabled_at, org_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                tenant.getId(), tenant.getSlug(), tenant.getName(), Timestamp.from(tenant.getCreatedAt()),
                tenant.isReady(), tenant.getEnabledAt() == null ? null : Timestamp.from(tenant.getEnabledAt()),
                tenant.getOrgId());
    }

    @Override
    public Optional<Tenant> findTenant(String tenantId) {
        List<Tenant> tenants = jdbcTemplate.query(
                "SELECT * FROM td_tenant WHERE tenant_id = ?", tenantMapper(), tenantId);
        return tenants.stream().findFirst();
    }

    @Override
    public Optional<Tenant> findTenantBySlug(String slug) {
        List<Tenant> tenants = jdbcTemplate.query(
                "SELECT * FROM td_tenant WHERE LOWER(slug) = LOWER(?)", tenantMapper(), slug);
        return tenants.stream().findFirst();
    }

    @Override
    public List<Tenant> listTenantsForAccount(String accountId) {
        return jdbcTemplate.query("""
                        SELECT t.*
                          FROM td_tenant t
                          JOIN td_membership m ON m.tenant_id = t.tenant_id
                         WHERE m.account_id = ?
                         ORDER BY t.name
                        """,
                tenantMapper(), accountId);
    }

    @Override
    public List<Tenant> listTenantsForOrg(String orgId) {
        return jdbcTemplate.query(
                "SELECT * FROM td_tenant WHERE org_id = ? ORDER BY name",
                tenantMapper(), orgId);
    }

    @Override
    public int countTenantsForOrg(String orgId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM td_tenant WHERE org_id = ?", Integer.class, orgId);
        return count == null ? 0 : count;
    }

    @Override
    public void updateTenantReady(String tenantId, boolean ready, Instant enabledAt) {
        int updated = jdbcTemplate.update(
                "UPDATE td_tenant SET ready = ?, enabled_at = ? WHERE tenant_id = ?",
                ready, enabledAt == null ? null : Timestamp.from(enabledAt), tenantId);
        if (updated == 0) {
            throw new java.util.NoSuchElementException("Workspace not found");
        }
    }

    @Override
    public void updateTenantsReadyForOrg(String orgId, boolean ready, Instant enabledAt) {
        jdbcTemplate.update(
                "UPDATE td_tenant SET ready = ?, enabled_at = ? WHERE org_id = ?",
                ready, enabledAt == null ? null : Timestamp.from(enabledAt), orgId);
    }

    @Override
    public void saveOrganization(Organization organization) {
        jdbcTemplate.update("""
                        INSERT INTO td_organization (org_id, slug, name, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                organization.getId(), organization.getSlug(), organization.getName(),
                Timestamp.from(organization.getCreatedAt()));
    }

    @Override
    public Optional<Organization> findOrganization(String orgId) {
        List<Organization> orgs = jdbcTemplate.query(
                "SELECT * FROM td_organization WHERE org_id = ?", organizationMapper(), orgId);
        return orgs.stream().findFirst();
    }

    @Override
    public Optional<Organization> findOrganizationBySlug(String slug) {
        List<Organization> orgs = jdbcTemplate.query(
                "SELECT * FROM td_organization WHERE LOWER(slug) = LOWER(?)", organizationMapper(), slug);
        return orgs.stream().findFirst();
    }

    @Override
    public void saveSubscription(Subscription subscription) {
        jdbcTemplate.update("""
                        INSERT INTO td_subscription (
                            subscription_id, org_id, plan_code, status,
                            seat_limit, workspace_limit, spec_limit, run_limit_month,
                            trial_ends_at, current_period_end, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                subscription.getId(), subscription.getOrgId(), subscription.getPlanCode(),
                subscription.getStatus(), subscription.getSeatLimit(), subscription.getWorkspaceLimit(),
                subscription.getSpecLimit(), subscription.getRunLimitMonth(),
                timestamp(subscription.getTrialEndsAt()), timestamp(subscription.getCurrentPeriodEnd()),
                Timestamp.from(subscription.getCreatedAt()));
    }

    @Override
    public void updateSubscription(Subscription subscription) {
        int updated = jdbcTemplate.update("""
                        UPDATE td_subscription
                           SET plan_code = ?, status = ?, seat_limit = ?, workspace_limit = ?,
                               spec_limit = ?, run_limit_month = ?, trial_ends_at = ?, current_period_end = ?
                         WHERE org_id = ?
                        """,
                subscription.getPlanCode(), subscription.getStatus(), subscription.getSeatLimit(),
                subscription.getWorkspaceLimit(), subscription.getSpecLimit(), subscription.getRunLimitMonth(),
                timestamp(subscription.getTrialEndsAt()), timestamp(subscription.getCurrentPeriodEnd()),
                subscription.getOrgId());
        if (updated == 0) {
            throw new java.util.NoSuchElementException("Subscription not found");
        }
    }

    @Override
    public Optional<Subscription> findSubscriptionByOrg(String orgId) {
        List<Subscription> subscriptions = jdbcTemplate.query(
                "SELECT * FROM td_subscription WHERE org_id = ?", subscriptionMapper(), orgId);
        return subscriptions.stream().findFirst();
    }

    @Override
    public List<OrgSubscriptionRecord> listOrgSubscriptions() {
        return jdbcTemplate.query("""
                        SELECT o.org_id, o.slug, o.name, o.created_at,
                               s.plan_code, s.status, s.seat_limit, s.workspace_limit,
                               s.spec_limit, s.run_limit_month, s.trial_ends_at, s.current_period_end,
                               (SELECT a.email
                                  FROM td_org_membership m
                                  JOIN td_account a ON a.account_id = m.account_id
                                 WHERE m.org_id = o.org_id AND m.role = 'OWNER'
                                 ORDER BY a.email
                                 FETCH FIRST 1 ROW ONLY) AS owner_email,
                               (SELECT a.display_name
                                  FROM td_org_membership m
                                  JOIN td_account a ON a.account_id = m.account_id
                                 WHERE m.org_id = o.org_id AND m.role = 'OWNER'
                                 ORDER BY a.email
                                 FETCH FIRST 1 ROW ONLY) AS owner_name
                          FROM td_organization o
                          JOIN td_subscription s ON s.org_id = o.org_id
                         WHERE o.org_id <> ?
                           AND o.org_id <> ?
                           AND o.slug <> ?
                         ORDER BY CASE s.status
                                    WHEN 'trial' THEN 0
                                    WHEN 'past_due' THEN 1
                                    WHEN 'suspended' THEN 2
                                    WHEN 'canceled' THEN 3
                                    ELSE 4
                                  END, o.created_at DESC
                        """,
                (rs, rowNum) -> new OrgSubscriptionRecord(
                        rs.getString("org_id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("owner_email"),
                        rs.getString("owner_name"),
                        rs.getString("plan_code"),
                        rs.getString("status"),
                        rs.getInt("seat_limit"),
                        rs.getInt("workspace_limit"),
                        rs.getInt("spec_limit"),
                        rs.getInt("run_limit_month"),
                        instant(rs.getTimestamp("trial_ends_at")),
                        instant(rs.getTimestamp("current_period_end")),
                        instant(rs.getTimestamp("created_at"), Instant.now())),
                TenantContext.LOCAL_TENANT_ID, TenantContext.PLATFORM_TENANT_ID, TenantContext.PLATFORM_SLUG);
    }

    @Override
    public void saveOrgMembership(String orgId, String accountId, String role) {
        jdbcTemplate.update("""
                        INSERT INTO td_org_membership (org_id, account_id, role, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                orgId, accountId, role, Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<String> findOrgRole(String orgId, String accountId) {
        List<String> roles = jdbcTemplate.query(
                "SELECT role FROM td_org_membership WHERE org_id = ? AND account_id = ?",
                (rs, rowNum) -> rs.getString("role"),
                orgId, accountId);
        return roles.stream().findFirst();
    }

    @Override
    public int countOrgMembers(String orgId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM td_org_membership WHERE org_id = ?", Integer.class, orgId);
        return count == null ? 0 : count;
    }

    @Override
    public int countSpecsForOrg(String orgId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM td_spec s
                          JOIN td_tenant t ON t.tenant_id = s.tenant_id
                         WHERE t.org_id = ?
                        """,
                Integer.class, orgId);
        return count == null ? 0 : count;
    }

    @Override
    public int incrementRunCount(String orgId, String yearMonth) {
        int updated = jdbcTemplate.update("""
                        UPDATE td_usage_month
                           SET run_count = run_count + 1
                         WHERE org_id = ? AND year_month = ?
                        """,
                orgId, yearMonth);
        if (updated == 0) {
            try {
                jdbcTemplate.update("""
                                INSERT INTO td_usage_month (org_id, year_month, run_count)
                                VALUES (?, ?, 1)
                                """,
                        orgId, yearMonth);
            } catch (Exception ignored) {
                jdbcTemplate.update("""
                                UPDATE td_usage_month
                                   SET run_count = run_count + 1
                                 WHERE org_id = ? AND year_month = ?
                                """,
                        orgId, yearMonth);
            }
        }
        return runCount(orgId, yearMonth);
    }

    @Override
    public int runCount(String orgId, String yearMonth) {
        List<Integer> counts = jdbcTemplate.query(
                "SELECT run_count FROM td_usage_month WHERE org_id = ? AND year_month = ?",
                (rs, rowNum) -> rs.getInt("run_count"),
                orgId, yearMonth);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    @Override
    public void saveAccount(Account account) {
        jdbcTemplate.update("""
                        INSERT INTO td_account (account_id, email, password_hash, display_name, created_at, platform_role)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                account.getId(), account.getEmail(), account.getPasswordHash(),
                account.getDisplayName(), Timestamp.from(account.getCreatedAt()),
                account.getPlatformRole() == null || account.getPlatformRole().isBlank()
                        ? "USER" : account.getPlatformRole());
    }

    @Override
    public Optional<Account> findAccount(String accountId) {
        List<Account> accounts = jdbcTemplate.query(
                "SELECT * FROM td_account WHERE account_id = ?", accountMapper(), accountId);
        return accounts.stream().findFirst();
    }

    @Override
    public Optional<Account> findAccountByEmail(String email) {
        List<Account> accounts = jdbcTemplate.query(
                "SELECT * FROM td_account WHERE LOWER(email) = LOWER(?)", accountMapper(), email);
        return accounts.stream().findFirst();
    }

    @Override
    public void updatePlatformRole(String accountId, String platformRole) {
        int updated = jdbcTemplate.update(
                "UPDATE td_account SET platform_role = ? WHERE account_id = ?",
                platformRole, accountId);
        if (updated == 0) {
            throw new java.util.NoSuchElementException("Account not found");
        }
    }

    @Override
    public void saveMembership(Membership membership) {
        jdbcTemplate.update("""
                        INSERT INTO td_membership (tenant_id, account_id, role, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                membership.tenantId(), membership.accountId(), membership.role(), Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<Membership> findMembership(String tenantId, String accountId) {
        List<Membership> memberships = jdbcTemplate.query(
                "SELECT tenant_id, account_id, role FROM td_membership WHERE tenant_id = ? AND account_id = ?",
                (rs, rowNum) -> new Membership(rs.getString("tenant_id"), rs.getString("account_id"),
                        rs.getString("role")),
                tenantId, accountId);
        return memberships.stream().findFirst();
    }

    @Override
    public List<MemberRecord> listMembers(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT a.account_id, a.email, a.display_name, m.role, m.created_at
                          FROM td_membership m
                          JOIN td_account a ON a.account_id = m.account_id
                         WHERE m.tenant_id = ?
                         ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, a.email
                        """,
                (rs, rowNum) -> {
                    Timestamp created = rs.getTimestamp("created_at");
                    return new MemberRecord(
                            rs.getString("account_id"),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getString("role"),
                            created == null ? Instant.now() : created.toInstant());
                },
                tenantId);
    }

    @Override
    public void updateMembershipRole(String tenantId, String accountId, String role) {
        int updated = jdbcTemplate.update(
                "UPDATE td_membership SET role = ? WHERE tenant_id = ? AND account_id = ?",
                role, tenantId, accountId);
        if (updated == 0) {
            throw new java.util.NoSuchElementException("Member not found");
        }
    }

    @Override
    public void deleteMembership(String tenantId, String accountId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM td_membership WHERE tenant_id = ? AND account_id = ?",
                tenantId, accountId);
        if (deleted == 0) {
            throw new java.util.NoSuchElementException("Member not found");
        }
    }

    @Override
    public int countOwners(String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM td_membership WHERE tenant_id = ? AND role = 'OWNER'",
                Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    @Override
    public void saveSession(StoredSession session) {
        jdbcTemplate.update("""
                        INSERT INTO td_session (token_hash, account_id, tenant_id, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                session.getTokenHash(), session.getAccountId(), session.getTenantId(),
                Timestamp.from(session.getExpiresAt()), Timestamp.from(session.getCreatedAt()));
    }

    @Override
    public Optional<StoredSession> findSession(String tokenHash) {
        List<StoredSession> sessions = jdbcTemplate.query(
                "SELECT * FROM td_session WHERE token_hash = ?", sessionMapper(), tokenHash);
        return sessions.stream().findFirst();
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbcTemplate.update("DELETE FROM td_session WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteExpiredSessions(Instant now) {
        jdbcTemplate.update("DELETE FROM td_session WHERE expires_at < ?", Timestamp.from(now));
    }

    @Override
    public void switchSessionTenant(String tokenHash, String tenantId) {
        jdbcTemplate.update("UPDATE td_session SET tenant_id = ? WHERE token_hash = ?", tenantId, tokenHash);
    }

    private RowMapper<Tenant> tenantMapper() {
        return (rs, rowNum) -> {
            Tenant tenant = new Tenant();
            tenant.setId(rs.getString("tenant_id"));
            tenant.setOrgId(rs.getString("org_id"));
            tenant.setSlug(rs.getString("slug"));
            tenant.setName(rs.getString("name"));
            Timestamp created = rs.getTimestamp("created_at");
            tenant.setCreatedAt(created == null ? Instant.now() : created.toInstant());
            tenant.setReady(rs.getBoolean("ready"));
            Timestamp enabled = rs.getTimestamp("enabled_at");
            tenant.setEnabledAt(enabled == null ? null : enabled.toInstant());
            return tenant;
        };
    }

    private RowMapper<Organization> organizationMapper() {
        return (rs, rowNum) -> {
            Organization organization = new Organization();
            organization.setId(rs.getString("org_id"));
            organization.setSlug(rs.getString("slug"));
            organization.setName(rs.getString("name"));
            organization.setCreatedAt(instant(rs.getTimestamp("created_at"), Instant.now()));
            return organization;
        };
    }

    private RowMapper<Subscription> subscriptionMapper() {
        return (rs, rowNum) -> {
            Subscription subscription = new Subscription();
            subscription.setId(rs.getString("subscription_id"));
            subscription.setOrgId(rs.getString("org_id"));
            subscription.setPlanCode(rs.getString("plan_code"));
            subscription.setStatus(rs.getString("status"));
            subscription.setSeatLimit(rs.getInt("seat_limit"));
            subscription.setWorkspaceLimit(rs.getInt("workspace_limit"));
            subscription.setSpecLimit(rs.getInt("spec_limit"));
            subscription.setRunLimitMonth(rs.getInt("run_limit_month"));
            subscription.setTrialEndsAt(instant(rs.getTimestamp("trial_ends_at")));
            subscription.setCurrentPeriodEnd(instant(rs.getTimestamp("current_period_end")));
            subscription.setCreatedAt(instant(rs.getTimestamp("created_at"), Instant.now()));
            return subscription;
        };
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(Timestamp value, Instant fallback) {
        return value == null ? fallback : value.toInstant();
    }

    private RowMapper<Account> accountMapper() {
        return (rs, rowNum) -> {
            Account account = new Account();
            account.setId(rs.getString("account_id"));
            account.setEmail(rs.getString("email"));
            account.setPasswordHash(rs.getString("password_hash"));
            account.setDisplayName(rs.getString("display_name"));
            Timestamp created = rs.getTimestamp("created_at");
            account.setCreatedAt(created == null ? Instant.now() : created.toInstant());
            String platformRole = rs.getString("platform_role");
            account.setPlatformRole(platformRole == null || platformRole.isBlank() ? "USER" : platformRole);
            return account;
        };
    }

    private RowMapper<StoredSession> sessionMapper() {
        return (rs, rowNum) -> {
            StoredSession session = new StoredSession();
            session.setTokenHash(rs.getString("token_hash"));
            session.setAccountId(rs.getString("account_id"));
            session.setTenantId(rs.getString("tenant_id"));
            Timestamp expires = rs.getTimestamp("expires_at");
            session.setExpiresAt(expires == null ? Instant.now() : expires.toInstant());
            Timestamp created = rs.getTimestamp("created_at");
            session.setCreatedAt(created == null ? Instant.now() : created.toInstant());
            return session;
        };
    }
}
