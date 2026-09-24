package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Service
public class EntitlementService {

    private final StudioProperties properties;
    private final ObjectProvider<TenantStore> tenantStores;

    public EntitlementService(StudioProperties properties, ObjectProvider<TenantStore> tenantStores) {
        this.properties = properties;
        this.tenantStores = tenantStores;
    }

    public boolean isWritable(Tenant tenant) {
        if (tenant == null) {
            return false;
        }
        if (!properties.isBillingEnabled() || TenantContext.LOCAL_TENANT_ID.equals(tenant.getId())) {
            return true;
        }
        if (PlanCatalog.isReservedOrg(tenant.getId()) || PlanCatalog.isReservedOrg(tenant.getOrgId())) {
            return true;
        }
        return PlanCatalog.isWritable(subscriptionFor(tenant), Instant.now());
    }

    public void requireWritable(Tenant tenant) {
        if (!isWritable(tenant)) {
            throw new ForbiddenException(readOnlyMessage(subscriptionFor(tenant)));
        }
    }

    public void requireWorkspace(String orgId) {
        if (!properties.isBillingEnabled()) {
            return;
        }
        TenantStore store = requireStore();
        Subscription subscription = requireSubscription(store, orgId);
        requireWritableSubscription(subscription);
        int used = store.countTenantsForOrg(orgId);
        if (used >= subscription.getWorkspaceLimit()) {
            throw new ForbiddenException("This plan allows "
                    + subscription.getWorkspaceLimit() + " workspace"
                    + (subscription.getWorkspaceLimit() == 1 ? "" : "s")
                    + ". Upgrade to add another.");
        }
    }

    public void requireSeat(String orgId, String accountId) {
        if (!properties.isBillingEnabled()) {
            return;
        }
        TenantStore store = requireStore();
        if (store.findOrgRole(orgId, accountId).isPresent()) {
            return;
        }
        Subscription subscription = requireSubscription(store, orgId);
        requireWritableSubscription(subscription);
        int used = store.countOrgMembers(orgId);
        if (used >= subscription.getSeatLimit()) {
            throw new ForbiddenException("This plan allows "
                    + subscription.getSeatLimit() + " seat"
                    + (subscription.getSeatLimit() == 1 ? "" : "s")
                    + ". Upgrade to invite another person.");
        }
    }

    public void requireSpec() {
        Tenant tenant = currentTenant();
        if (tenant == null || !properties.isBillingEnabled() || PlanCatalog.isReservedOrg(tenant.getId())) {
            return;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null || tenant.getOrgId() == null) {
            return;
        }
        Subscription subscription = requireSubscription(store, tenant.getOrgId());
        requireWritableSubscription(subscription);
        int used = store.countSpecsForOrg(tenant.getOrgId());
        if (used >= subscription.getSpecLimit()) {
            throw new ForbiddenException("This plan allows "
                    + subscription.getSpecLimit() + " spec"
                    + (subscription.getSpecLimit() == 1 ? "" : "s")
                    + ". Upgrade to load another.");
        }
    }

    public void requireAndIncrementRun() {
        Tenant tenant = currentTenant();
        if (tenant == null || !properties.isBillingEnabled() || PlanCatalog.isReservedOrg(tenant.getId())) {
            return;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null || tenant.getOrgId() == null) {
            return;
        }
        Subscription subscription = requireSubscription(store, tenant.getOrgId());
        requireWritableSubscription(subscription);
        String yearMonth = currentYearMonth();
        int used = store.runCount(tenant.getOrgId(), yearMonth);
        if (used >= subscription.getRunLimitMonth()) {
            throw new ForbiddenException("This plan allows "
                    + subscription.getRunLimitMonth() + " runs this month. Upgrade to run more.");
        }
        store.incrementRunCount(tenant.getOrgId(), yearMonth);
    }

    public String currentYearMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    public Subscription subscriptionFor(Tenant tenant) {
        if (tenant == null || tenant.getOrgId() == null) {
            return null;
        }
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return null;
        }
        return store.findSubscriptionByOrg(tenant.getOrgId()).orElse(null);
    }

    public static String readOnlyMessage(Subscription subscription) {
        if (subscription == null) {
            return "This organization does not have an active subscription.";
        }
        if (PlanCatalog.STATUS_TRIAL.equals(subscription.getStatus())) {
            return "This trial has ended. Ask a SaaS admin to activate a plan.";
        }
        if (PlanCatalog.STATUS_PAST_DUE.equals(subscription.getStatus())) {
            return "This subscription is past due. Billing must be updated before Studio can be used.";
        }
        if (PlanCatalog.STATUS_CANCELED.equals(subscription.getStatus())) {
            return "This subscription is canceled. Studio is read-only until a plan is activated.";
        }
        if (PlanCatalog.STATUS_SUSPENDED.equals(subscription.getStatus())) {
            return "This organization is suspended. Studio is read-only.";
        }
        return "This organization does not have an active subscription.";
    }

    private void requireWritableSubscription(Subscription subscription) {
        if (!PlanCatalog.isWritable(subscription, Instant.now())) {
            throw new ForbiddenException(readOnlyMessage(subscription));
        }
    }

    private Subscription requireSubscription(TenantStore store, String orgId) {
        return store.findSubscriptionByOrg(orgId)
                .orElseThrow(() -> new ForbiddenException("This organization does not have a subscription"));
    }

    private Tenant currentTenant() {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            return null;
        }
        try {
            return store.findTenant(TenantContext.tenantId()).orElse(null);
        } catch (UnauthenticatedException ignored) {
            return null;
        }
    }

    private TenantStore requireStore() {
        TenantStore store = tenantStores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("SaaS workspaces need H2 or database storage");
        }
        return store;
    }
}
