package com.tx.api.test.generator.studio.audit;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The allowlist lives in server configuration, so a change takes effect at startup. This compares the configuration
 * with the last audited state and writes an audit entry for each environment that differs. The audit entry is written
 * before the state is saved, and a failure stops startup, so a change cannot go live without its entry.
 */
@Component
public class PrivateTargetAllowlistAuditor implements ApplicationRunner {

    private final StudioProperties properties;
    private final StudioStorage storage;
    private final StudioAuditService auditService;

    public PrivateTargetAllowlistAuditor(StudioProperties properties, StudioStorage studioStorage,
                                         StudioAuditService auditService) {
        this.properties = properties;
        this.storage = studioStorage;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    void reconcile() {
        Map<String, List<String>> configured = properties.getRun().getPrivateTargetAllowlist();
        Map<String, String> saved = storage.listPrivateTargetAllowlists();
        Set<String> environmentIds = new LinkedHashSet<>(configured.keySet());
        environmentIds.addAll(saved.keySet());
        for (String environmentId : environmentIds) {
            Set<String> current = new TreeSet<>();
            for (String entry : configured.getOrDefault(environmentId, List.of())) {
                current.add(entry.trim().toLowerCase(Locale.ROOT));
            }
            Set<String> previous = new TreeSet<>();
            for (String entry : StringUtils.defaultString(saved.get(environmentId)).split("\n")) {
                if (!entry.isBlank()) {
                    previous.add(entry);
                }
            }
            if (current.equals(previous)) {
                continue;
            }
            List<String> added = new ArrayList<>(current);
            added.removeAll(previous);
            List<String> removed = new ArrayList<>(previous);
            removed.removeAll(current);
            // An id with no environment (a typo, or a deleted environment) still gets an entry, in the platform tenant.
            Optional<StudioStorage.EnvironmentOwner> owner = storage.findEnvironmentOwner(environmentId);
            auditService.recordAllowlistChanged(
                    owner.map(StudioStorage.EnvironmentOwner::tenantId).orElse(TenantContext.PLATFORM_TENANT_ID),
                    environmentId, owner.map(StudioStorage.EnvironmentOwner::name).orElse(null), added, removed);
            if (current.isEmpty()) {
                storage.deletePrivateTargetAllowlist(environmentId);
            } else {
                storage.savePrivateTargetAllowlist(environmentId, String.join("\n", current));
            }
        }
    }
}
