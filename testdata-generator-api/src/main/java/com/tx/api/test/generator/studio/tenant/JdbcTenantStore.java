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
                        INSERT INTO td_tenant (tenant_id, slug, name, created_at, ready, enabled_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                tenant.getId(), tenant.getSlug(), tenant.getName(), Timestamp.from(tenant.getCreatedAt()),
                tenant.isReady(), tenant.getEnabledAt() == null ? null : Timestamp.from(tenant.getEnabledAt()));
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
    public List<SubscriptionRecord> listSubscriptions() {
        return jdbcTemplate.query("""
                        SELECT t.tenant_id, t.slug, t.name, t.created_at, t.ready, t.enabled_at,
                               (SELECT a.email
                                  FROM td_membership m
                                  JOIN td_account a ON a.account_id = m.account_id
                                 WHERE m.tenant_id = t.tenant_id AND m.role = 'OWNER'
                                 ORDER BY a.email
                                 FETCH FIRST 1 ROW ONLY) AS owner_email,
                               (SELECT a.display_name
                                  FROM td_membership m
                                  JOIN td_account a ON a.account_id = m.account_id
                                 WHERE m.tenant_id = t.tenant_id AND m.role = 'OWNER'
                                 ORDER BY a.email
                                 FETCH FIRST 1 ROW ONLY) AS owner_name
                          FROM td_tenant t
                         WHERE t.tenant_id <> ?
                           AND t.slug <> ?
                         ORDER BY t.ready ASC, t.created_at DESC
                        """,
                (rs, rowNum) -> {
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp enabled = rs.getTimestamp("enabled_at");
                    return new SubscriptionRecord(
                            rs.getString("tenant_id"),
                            rs.getString("slug"),
                            rs.getString("name"),
                            rs.getString("owner_email"),
                            rs.getString("owner_name"),
                            rs.getBoolean("ready"),
                            created == null ? Instant.now() : created.toInstant(),
                            enabled == null ? null : enabled.toInstant());
                },
                TenantContext.LOCAL_TENANT_ID, TenantContext.PLATFORM_SLUG);
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
