package com.tx.api.test.generator.studio.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class AuthConfigurationValidator {

    private final StudioProperties properties;

    public AuthConfigurationValidator(StudioProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (!properties.isAuthEnabled() || !properties.isLdapAuth()) {
            return;
        }
        StudioProperties.Ldap ldap = properties.getAuth().getLdap();
        if (StringUtils.isBlank(ldap.getUrl())) {
            throw new IllegalStateException("testdata.auth.ldap.url is required when testdata.auth.provider=ldap");
        }
        if (StringUtils.isBlank(ldap.normalizedDomain())) {
            throw new IllegalStateException("testdata.auth.ldap.domain is required when testdata.auth.provider=ldap");
        }
        String url = ldap.getUrl().trim().toLowerCase();
        if (url.startsWith("ldap://") && !ldap.isAllowInsecure()) {
            throw new IllegalStateException(
                    "testdata.auth.ldap.url uses plain ldap:// — set testdata.auth.ldap.allow-insecure=true for labs or use ldaps://");
        }
        if (ldap.hasManagerCredentials() && StringUtils.isBlank(ldap.getManagerPassword())) {
            throw new IllegalStateException("testdata.auth.ldap.manager-password is required when manager-dn is set");
        }
    }
}
