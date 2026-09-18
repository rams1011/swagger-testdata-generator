package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TenantStore {

    void saveTenant(Tenant tenant);

    Optional<Tenant> findTenant(String tenantId);

    Optional<Tenant> findTenantBySlug(String slug);

    List<Tenant> listTenantsForAccount(String accountId);

    List<SubscriptionRecord> listSubscriptions();

    void updateTenantReady(String tenantId, boolean ready, Instant enabledAt);

    void saveAccount(Account account);

    Optional<Account> findAccount(String accountId);

    Optional<Account> findAccountByEmail(String email);

    void updatePlatformRole(String accountId, String platformRole);

    void saveMembership(Membership membership);

    Optional<Membership> findMembership(String tenantId, String accountId);

    List<MemberRecord> listMembers(String tenantId);

    void updateMembershipRole(String tenantId, String accountId, String role);

    void deleteMembership(String tenantId, String accountId);

    int countOwners(String tenantId);

    void saveSession(StoredSession session);

    Optional<StoredSession> findSession(String tokenHash);

    void deleteSession(String tokenHash);

    void deleteExpiredSessions(Instant now);

    void switchSessionTenant(String tokenHash, String tenantId);

    record MemberRecord(String accountId, String email, String name, String role, Instant createdAt) {
    }

    record SubscriptionRecord(
            String tenantId,
            String slug,
            String name,
            String ownerEmail,
            String ownerName,
            boolean ready,
            Instant createdAt,
            Instant enabledAt
    ) {
    }
}
