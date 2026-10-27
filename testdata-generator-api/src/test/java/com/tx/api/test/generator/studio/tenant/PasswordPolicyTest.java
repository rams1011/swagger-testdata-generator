package com.tx.api.test.generator.studio.tenant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void rejectsShortPasswordsMissingALetterOrDigitAndEmailMatches() {
        assertEquals(PasswordPolicy.RULE, PasswordPolicy.problem("short1", "qa@test"));
        assertEquals(PasswordPolicy.RULE, PasswordPolicy.problem("abcdefghijkl", "qa@test"));
        assertEquals(PasswordPolicy.RULE, PasswordPolicy.problem("123456789012", "qa@test"));
        assertEquals("Password must not be the same as the email address",
                PasswordPolicy.problem("user12@test.com", "USER12@test.com"));
        assertNull(PasswordPolicy.problem("password1234", "qa@example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.requireAcceptable("password1", "qa@example.com"));
    }

    @Test
    void directoryAccountsSkipExpiryAndReuse() {
        Account directory = account(LdapAccounts.EXTERNAL_PASSWORD_MARKER, Instant.now().minus(200, ChronoUnit.DAYS));
        directory.setMustChangePassword(true);
        assertFalse(PasswordPolicy.changeRequired(directory, 90, Instant.now()));
        assertFalse(PasswordPolicy.reuses("password1234", List.of(LdapAccounts.EXTERNAL_PASSWORD_MARKER)));
    }

    @Test
    void localAccountsExpireAndMustChange() {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        Account fresh = account(PasswordHasher.hash("password1234"), now.minus(10, ChronoUnit.DAYS));
        assertFalse(PasswordPolicy.changeRequired(fresh, 90, now));

        Account expired = account(PasswordHasher.hash("password1234"), now.minus(90, ChronoUnit.DAYS));
        assertTrue(PasswordPolicy.changeRequired(expired, 90, now));

        Account flagged = account(PasswordHasher.hash("password1234"), now);
        flagged.setMustChangePassword(true);
        assertTrue(PasswordPolicy.changeRequired(flagged, 0, now));
        assertFalse(PasswordPolicy.changeRequired(fresh, 0, now));
    }

    @Test
    void reuseMatchesAPreviousLocalHash() {
        String hash = PasswordHasher.hash("password1234");
        assertTrue(PasswordPolicy.reuses("password1234", List.of(hash)));
        assertFalse(PasswordPolicy.reuses("password5678", List.of(hash)));
    }

    private static Account account(String hash, Instant changedAt) {
        Account account = new Account();
        account.setEmail("qa@example.com");
        account.setPasswordHash(hash);
        account.setPasswordChangedAt(changedAt);
        return account;
    }
}
