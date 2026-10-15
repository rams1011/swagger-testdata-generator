package com.tx.api.test.generator.studio.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapLoginNamesTest {

    @Test
    void normalizesShortUsernameToUpn() {
        assertEquals("jsmith@client.com", LdapLoginNames.normalizePrincipal("jsmith", "client.com"));
    }

    @Test
    void normalizesDomainPrefixUsername() {
        assertEquals("jsmith@client.com", LdapLoginNames.normalizePrincipal("CLIENT\\jsmith", "client.com"));
    }

    @Test
    void rejectsForeignDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> LdapLoginNames.normalizePrincipal("other@example.com", "client.com"));
    }

    @Test
    void domainToBaseDn() {
        assertEquals("DC=client,DC=com", LdapLoginNames.domainToBaseDn("client.com"));
    }
}
