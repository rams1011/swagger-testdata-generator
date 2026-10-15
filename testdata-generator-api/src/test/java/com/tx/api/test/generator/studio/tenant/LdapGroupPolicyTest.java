package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapGroupPolicyTest {

    @Test
    void allowedGroupRejectsNonMembers() {
        StudioProperties properties = ldapProperties();
        properties.getAuth().getLdap().setAllowedGroup("cn=studio-users,ou=groups,dc=client,dc=com");
        LdapAuthenticator authenticator = new LdapAuthenticator(properties);
        LdapUserProfile outsider = new LdapUserProfile(
                "jsmith@client.com",
                "John Smith",
                "jsmith@client.com",
                List.of("cn=other,ou=groups,dc=client,dc=com"));
        assertThrows(UnauthenticatedException.class, () -> invokeAllowedGroup(authenticator, outsider, properties));
    }

    @Test
    void allowedGroupAcceptsMembers() {
        StudioProperties properties = ldapProperties();
        String allowed = "cn=studio-users,ou=groups,dc=client,dc=com";
        properties.getAuth().getLdap().setAllowedGroup(allowed);
        LdapAuthenticator authenticator = new LdapAuthenticator(properties);
        LdapUserProfile member = new LdapUserProfile(
                "jsmith@client.com",
                "John Smith",
                "jsmith@client.com",
                List.of(allowed));
        invokeAllowedGroup(authenticator, member, properties);
    }

    @Test
    void allowedGroupAcceptsBareCommonName() {
        StudioProperties properties = ldapProperties();
        properties.getAuth().getLdap().setAllowedGroup("studio-users");
        LdapAuthenticator authenticator = new LdapAuthenticator(properties);
        LdapUserProfile member = new LdapUserProfile(
                "jsmith@client.com",
                "John Smith",
                "jsmith@client.com",
                List.of("CN=studio-users,OU=Groups,DC=client,DC=com"));
        invokeAllowedGroup(authenticator, member, properties);
    }

    private static void invokeAllowedGroup(LdapAuthenticator authenticator, LdapUserProfile profile,
                                           StudioProperties properties) {
        try {
            var method = LdapAuthenticator.class.getDeclaredMethod(
                    "enforceAllowedGroup", LdapUserProfile.class, StudioProperties.Ldap.class);
            method.setAccessible(true);
            method.invoke(authenticator, profile, properties.getAuth().getLdap());
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause == null ? exception : cause);
        }
    }

    private static StudioProperties ldapProperties() {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        properties.getAuth().getLdap().setUrl("ldaps://ldap.client.com");
        properties.getAuth().getLdap().setDomain("client.com");
        return properties;
    }
}
