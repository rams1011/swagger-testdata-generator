package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthConfigurationValidatorTest {

    @Test
    void requiresUrlAndDomainForLdap() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        AuthConfigurationValidator validator = new AuthConfigurationValidator(properties);
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void acceptsMinimalLdapConfiguration() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        properties.getAuth().getLdap().setUrl("ldaps://ldap.client.com:636");
        properties.getAuth().getLdap().setDomain("client.com");
        AuthConfigurationValidator validator = new AuthConfigurationValidator(properties);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsPlainLdapWithoutOptIn() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        properties.getAuth().getLdap().setUrl("ldap://ldap.client.com:389");
        properties.getAuth().getLdap().setDomain("client.com");
        AuthConfigurationValidator validator = new AuthConfigurationValidator(properties);
        assertThrows(IllegalStateException.class, validator::validate);
    }
}
