package com.tx.api.test.generator.studio.audit;

import com.tx.api.test.generator.studio.concurrency.OptimisticConcurrency;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;

public final class CaseAudit {

    private CaseAudit() {
    }

    public static void stampCreate(StudioTestCase testCase, Instant now) {
        Instant stored = OptimisticConcurrency.toStorageInstant(now);
        testCase.setCreatedAt(stored);
        testCase.setUpdatedAt(stored);
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            return;
        }
        String label = label(scope);
        testCase.setCreatedByAccountId(scope.accountId());
        testCase.setCreatedByLabel(label);
        testCase.setUpdatedByAccountId(scope.accountId());
        testCase.setUpdatedByLabel(label);
    }

    public static void stampUpdate(StudioTestCase testCase, Instant now) {
        testCase.setUpdatedAt(OptimisticConcurrency.toStorageInstant(now));
        TenantContext.Scope scope = TenantContext.current();
        if (scope == null || StringUtils.isBlank(scope.accountId())) {
            return;
        }
        testCase.setUpdatedByAccountId(scope.accountId());
        testCase.setUpdatedByLabel(label(scope));
    }

    private static String label(TenantContext.Scope scope) {
        return StringUtils.defaultIfBlank(scope.email(), scope.accountId());
    }
}
