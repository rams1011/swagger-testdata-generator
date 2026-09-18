package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component("tenantBootstrap")
@Order(0)
public class TenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final StudioProperties properties;
    private final ObjectProvider<TenantStore> tenantStores;
    private final AuthService authService;

    public TenantBootstrap(StudioProperties properties, ObjectProvider<TenantStore> tenantStores,
                           AuthService authService) {
        this.properties = properties;
        this.tenantStores = tenantStores;
        this.authService = authService;
    }

    @PostConstruct
    public void start() {
        TenantContext.setSaasEnabled(properties.isSaasEnabled());
        if (properties.isSaasEnabled() && tenantStores.getIfAvailable() == null) {
            throw new IllegalStateException("testdata.saas.enabled=true requires testdata.storage.type=h2 or database");
        }
        if (properties.isSaasEnabled()) {
            String adminEmail = authService.ensureSaasAdmin();
            if (adminEmail != null) {
                log.info("Test Data Studio SaaS mode is on — {} can enable workspaces", adminEmail);
            } else {
                log.info("Test Data Studio SaaS mode is on — workspaces isolate specs, plans, and environments");
            }
        } else {
            log.info("Test Data Studio local mode — all specs belong to the built-in Local workspace");
        }
    }
}
