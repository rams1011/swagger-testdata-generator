package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TenantStore {

    void saveTenant(Tenant tenant);

    Optional<Tenant> findTenant(String tenantId);

    Optional<Tenant> findTenantBySlug(String slug);

    List<Tenant> listTenantsForAccount(String accountId);

    List<Tenant> listTenantsForOrg(String orgId);

    int countTenantsForOrg(String orgId);

    void updateTenantReady(String tenantId, boolean ready, Instant enabledAt);

    void updateTenantsReadyForOrg(String orgId, boolean ready, Instant enabledAt);

    void saveOrganization(Organization organization);

    Optional<Organization> findOrganization(String orgId);

    Optional<Organization> findOrganizationBySlug(String slug);

    void saveSubscription(Subscription subscription);

    void updateSubscription(Subscription subscription);

    Optional<Subscription> findSubscriptionByOrg(String orgId);

    List<OrgSubscriptionRecord> listOrgSubscriptions();

    void saveOrgMembership(String orgId, String accountId, String role);

    Optional<String> findOrgRole(String orgId, String accountId);

    int countOrgMembers(String orgId);

    List<OrgMemberRecord> listOrgMembers(String orgId);

    List<MemberRecord> listOrgMemberDetails(String orgId);

    void updateOrgMembershipRole(String orgId, String accountId, String role);

    void deleteOrgMembership(String orgId, String accountId);

    void deleteWorkspaceMembershipsForOrg(String orgId, String accountId);

    int countOrgOwners(String orgId);

    int countSpecsForOrg(String orgId);

    int incrementRunCount(String orgId, String yearMonth);

    int runCount(String orgId, String yearMonth);

    void saveAccount(Account account);

    default void updateAccountPassword(String accountId, String passwordHash, Instant changedAt, boolean mustChangePassword) {
    }

    default void rememberPasswordHash(String accountId, String passwordHash, Instant retiredAt) {
    }

    default List<String> listPasswordHashes(String accountId, int limit) {
        return List.of();
    }

    default void trimPasswordHistory(String accountId, int keep) {
    }

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

    default void touchSession(String tokenHash, Instant lastSeenAt) {
    }

    default void deleteIdleSessions(Instant lastSeenBefore) {
    }

    default Optional<LoginGuard.State> findLoginGuard(String subject) {
        return Optional.empty();
    }

    default void saveLoginGuard(String subject, LoginGuard.State state) {
    }

    default void deleteLoginGuard(String subject) {
    }

    void saveWorkspaceAccessRequest(String requestId, String tenantId, String accountId,
                                    String message, Instant createdAt);

    Optional<WorkspaceAccessRequestRecord> findPendingWorkspaceAccessRequest(String tenantId, String accountId);

    List<WorkspaceAccessRequestRecord> listPendingWorkspaceAccessRequests(String tenantId);

    void resolveWorkspaceAccessRequest(String requestId, String status);

    List<Organization> listOrganizationsForAccount(String accountId);

    record WorkspaceAccessRequestRecord(
            String requestId,
            String tenantId,
            String accountId,
            String email,
            String name,
            String message,
            Instant createdAt
    ) {
    }

    record MemberRecord(String accountId, String email, String name, String role, Instant createdAt) {
    }

    record OrgMemberRecord(String accountId, String role) {
    }

    record OrgSubscriptionRecord(
            String orgId,
            String slug,
            String name,
            String ownerEmail,
            String ownerName,
            String planCode,
            String status,
            int seatLimit,
            int workspaceLimit,
            int specLimit,
            int runLimitMonth,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            Instant createdAt
    ) {
    }
}
