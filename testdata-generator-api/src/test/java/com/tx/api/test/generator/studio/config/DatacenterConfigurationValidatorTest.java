package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatacenterConfigurationValidatorTest {

    @Test
    void refusesLocalProfileForDatacenterEdition() {
        StudioProperties properties = datacenter();
        properties.getSaas().setAdminEmail("ops@example.com");
        properties.getSaas().setAdminPassword("change-me-now");
        assertThrows(IllegalStateException.class, () -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.LOCAL, "jdbc:h2:file:./studio-output/testdata"));
    }

    @Test
    void refusesH2Console() {
        StudioProperties properties = datacenter();
        properties.getSaas().setAdminEmail("ops@example.com");
        properties.getSaas().setAdminPassword("change-me-now");
        properties.getH2().setConsole(true);
        assertThrows(IllegalStateException.class, () -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.DATACENTER, "jdbc:postgresql://db/studio"));
    }

    @Test
    void refusesMissingLocalAdminSecrets() {
        assertThrows(IllegalStateException.class, () -> DatacenterConfigurationValidator.check(
                datacenter(), HostingProfile.DATACENTER, "jdbc:postgresql://db/studio"));
    }

    @Test
    void refusesSampleAdminPassword() {
        StudioProperties properties = datacenter();
        properties.getSaas().setAdminEmail("ops@example.com");
        properties.getSaas().setAdminPassword(DatacenterConfigurationValidator.SAMPLE_ADMIN_PASSWORD);
        assertThrows(IllegalStateException.class, () -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.DATACENTER, "jdbc:postgresql://db/studio"));
    }

    @Test
    void refusesSampleLdapHost() {
        StudioProperties properties = datacenter();
        properties.getAuth().setProvider("ldap");
        properties.getAuth().getLdap().setUrl("ldaps://ldap.client.com:636");
        properties.getAuth().getLdap().setDomain("example.com");
        assertThrows(IllegalStateException.class, () -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.DATACENTER, "jdbc:postgresql://db/studio"));
    }

    @Test
    void acceptsExternalDatacenterConfig() {
        StudioProperties properties = datacenter();
        properties.getSaas().setAdminEmail("ops@example.com");
        properties.getSaas().setAdminPassword("change-me-now");
        assertDoesNotThrow(() -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.DATACENTER, "jdbc:postgresql://db/studio"));
    }

    @Test
    void ignoresLocalEdition() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("local");
        assertDoesNotThrow(() -> DatacenterConfigurationValidator.check(
                properties, HostingProfile.LOCAL, "jdbc:h2:mem:studio"));
    }

    private static StudioProperties datacenter() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        return properties;
    }
}
