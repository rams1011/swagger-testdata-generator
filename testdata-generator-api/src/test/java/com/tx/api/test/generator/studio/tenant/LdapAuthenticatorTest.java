package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldif.LDIFReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapAuthenticatorTest {

    private InMemoryDirectoryServer server;
    private StudioProperties properties;
    private LdapAuthenticator authenticator;

    @BeforeEach
    void startServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=client,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=client,dc=com", "admin");
        server = new InMemoryDirectoryServer(config);
        server.startListening();

        String ldif = """
                dn: dc=client,dc=com
                objectClass: top
                objectClass: domain
                dc: client

                dn: cn=admin,dc=client,dc=com
                objectClass: top
                objectClass: person
                objectClass: organizationalPerson
                objectClass: inetOrgPerson
                cn: admin
                sn: Admin
                userPassword: admin

                dn: ou=people,dc=client,dc=com
                objectClass: top
                objectClass: organizationalUnit
                ou: people

                dn: uid=jsmith,ou=people,dc=client,dc=com
                objectClass: top
                objectClass: person
                objectClass: organizationalPerson
                objectClass: inetOrgPerson
                uid: jsmith
                cn: John Smith
                sn: Smith
                mail: jsmith@client.com
                userPassword: secret
                """;
        server.importFromLDIF(true, new LDIFReader(new java.io.ByteArrayInputStream(ldif.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        properties = ldapProperties(server.getListenPort(), false);
        authenticator = new LdapAuthenticator(properties);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    @Test
    void directBindPathRejectsBadPasswordWithoutManager() {
        assertFalse(properties.getAuth().getLdap().hasManagerCredentials());
        assertThrows(UnauthenticatedException.class, () -> authenticator.authenticate("jsmith", "wrong"));
    }

    @Test
    void managerSearchThenBind() {
        properties.getAuth().getLdap().setManagerDn("cn=admin,dc=client,dc=com");
        properties.getAuth().getLdap().setManagerPassword("admin");
        LdapUserProfile profile = authenticator.authenticate("jsmith@client.com", "secret");
        assertEquals("jsmith@client.com", profile.studioEmail());
        assertEquals("John Smith", profile.displayName());
    }

    private static StudioProperties ldapProperties(int port, boolean allowInsecure) {
        StudioProperties properties = new StudioProperties();
        properties.setEdition("datacenter");
        properties.getAuth().setProvider("ldap");
        StudioProperties.Ldap ldap = properties.getAuth().getLdap();
        ldap.setUrl("ldap://127.0.0.1:" + port);
        ldap.setDomain("client.com");
        ldap.setAllowInsecure(allowInsecure || true);
        ldap.setUserSearchFilter("(mail={0})");
        return properties;
    }
}
