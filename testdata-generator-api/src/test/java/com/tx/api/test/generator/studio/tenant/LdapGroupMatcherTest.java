package com.tx.api.test.generator.studio.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdapGroupMatcherTest {

    @Test
    void bareNameMatchesFullMemberOf() {
        assertTrue(LdapGroupMatcher.matchesAny(
                List.of("CN=StudioUsers,OU=Groups,DC=client,DC=com"),
                "StudioUsers"));
    }

    @Test
    void bareNameMatchesPartialCnOnlyMemberOf() {
        assertTrue(LdapGroupMatcher.matchesAny(
                List.of("CN=StudioUsers"),
                "StudioUsers"));
    }

    @Test
    void partialCnConfigMatchesFullMemberOf() {
        assertTrue(LdapGroupMatcher.matchesAny(
                List.of("CN=StudioUsers,OU=Groups,DC=client,DC=com"),
                "CN=StudioUsers"));
    }

    @Test
    void rejectsUnrelatedGroup() {
        assertFalse(LdapGroupMatcher.matchesAny(
                List.of("CN=Other,OU=Groups,DC=client,DC=com"),
                "StudioUsers"));
    }
}
