package com.tx.api.test.generator.studio.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Datacenter edition refuses the local H2 profile and the sample secrets that used to ship in the jar.
 */
@Component
@DependsOn("hostingProfileValidator")
public class DatacenterConfigurationValidator {

    static final String SAMPLE_LDAP_HOST = "ldap.client.com";
    static final String SAMPLE_ADMIN_EMAIL = "saas.admin@local.test";
    static final String SAMPLE_ADMIN_PASSWORD = "admin@local";

    private final StudioProperties properties;
    private final HostingProfileValidator hostingProfile;
    private final StudioDatasourceSettings datasourceSettings;

    public DatacenterConfigurationValidator(
            StudioProperties properties,
            HostingProfileValidator hostingProfile,
            StudioDatasourceSettings datasourceSettings) {
        this.properties = properties;
        this.hostingProfile = hostingProfile;
        this.datasourceSettings = datasourceSettings;
    }

    @PostConstruct
    void validate() {
        check(properties, hostingProfile.hostingProfile(), datasourceSettings.jdbcUrl());
    }

    static void check(StudioProperties properties, HostingProfile profile, String jdbcUrl) {
        if (!properties.isDatacenter()) {
            return;
        }
        if (profile != HostingProfile.DATACENTER) {
            throw new IllegalStateException(
                    "testdata.edition=datacenter refuses hosting profile "
                            + profile.wireValue()
                            + ". Set SPRING_PROFILES_ACTIVE=datacenter and SPRING_DATASOURCE_* outside the jar.");
        }
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        if (url.isBlank() || url.startsWith("jdbc:h2:")) {
            throw new IllegalStateException(
                    "testdata.edition=datacenter requires a non-H2 JDBC database (SPRING_DATASOURCE_URL).");
        }
        if (properties.getH2().isConsole()) {
            throw new IllegalStateException(
                    "testdata.edition=datacenter refuses the H2 console. Leave testdata.h2.console false.");
        }
        if (properties.isLdapAuth()) {
            String ldapUrl = StringUtils.defaultString(properties.getAuth().getLdap().getUrl());
            if (ldapUrl.toLowerCase().contains(SAMPLE_LDAP_HOST)) {
                throw new IllegalStateException(
                        "testdata.auth.ldap.url still uses the sample host "
                                + SAMPLE_LDAP_HOST
                                + ". Set TESTDATA_LDAP_URL in external config.");
            }
            return;
        }
        String email = properties.getSaas().normalizedAdminEmail();
        String password = properties.getSaas().getAdminPassword();
        if (email == null || StringUtils.isBlank(password)) {
            throw new IllegalStateException(
                    "testdata.edition=datacenter with testdata.auth.provider=local requires "
                            + "TESTDATA_SAAS_ADMIN_EMAIL and TESTDATA_SAAS_ADMIN_PASSWORD.");
        }
        if (SAMPLE_ADMIN_EMAIL.equals(email) || SAMPLE_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "testdata.saas.admin-email and testdata.saas.admin-password must not be the sample values "
                            + SAMPLE_ADMIN_EMAIL + " / " + SAMPLE_ADMIN_PASSWORD + ".");
        }
        if (password.length() < 8) {
            throw new IllegalStateException("testdata.saas.admin-password must be at least 8 characters.");
        }
    }
}
